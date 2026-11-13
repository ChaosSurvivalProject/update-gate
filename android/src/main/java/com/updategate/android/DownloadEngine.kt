package com.updategate.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/** 客户端可观测结果码（与服务端一致）。 */
enum class ResultCode {
    SUCCESS, VERSION_GATE_BLOCKED, NETWORK_ALL_MIRRORS_FAILED,
    SHA256_MISMATCH, SIGNATURE_INVALID, DISK_FULL, INSTALL_REJECTED, ROLLED_BACK
}

/**
 * 多加速源竞速下载引擎（OkHttp 实现，对齐第 3 节）。
 *
 * <p>策略：</p>
 * <ul>
 *   <li>竞速：并发向各镜像拉取，先完成且 sha256 通过者胜出，其余取消；</li>
 *   <li>续传：Range 断点（part 文件已存在则从断点续传）；</li>
 *   <li>熔断：连续 2 次 4xx/5xx 或 3s 无字节 → 该镜像冷却 60s；</li>
 *   <li>全池失败 → 直连 GitHub 兜底 → 仍失败则指数退避重试 3 次后报
 *       {@link ResultCode#NETWORK_ALL_MIRRORS_FAILED}；</li>
 *   <li>sha256 不匹配：换镜像重下，最多 2 次。</li>
 * </ul>
 */
class DownloadEngine(
    private val http: OkHttpClient,
    private val pool: MirrorPool
) {

    /** 下载结果。 */
    data class Outcome(
        val file: File,
        val mirrorUsed: String,
        val bytes: Long
    )

    /**
     * 竞速下载资产到 destPart。
     * @param asset      资产（含 size / sha256 / downloads）
     * @param originalUrl 原始 GitHub Release URL（用于构造各镜像地址）
     * @param destPart   目标 part 文件
     */
    suspend fun download(asset: UpdateManifest.Asset, originalUrl: String, destPart: File): Outcome =
        withContext(Dispatchers.IO) {
            val candidates = buildCandidates(asset, originalUrl)
            require(candidates.isNotEmpty()) { "没有任何可用的下载地址" }

            pool.preheat(originalUrl, 2)

            var shaMismatchCount = 0
            repeat(4) { attempt -> // 1 主尝试 + 3 次指数退避
                val outcome = race(candidates, asset, destPart)
                if (outcome != null) {
                    // sha256 校验
                    if (Crypto.verifySha256(destPart, asset.sha256)) {
                        return@withContext outcome
                    }
                    destPart.delete()
                    if (++shaMismatchCount > 2) {
                        throw DownloadException(ResultCode.SHA256_MISMATCH, "sha256 校验失败（已重试 2 次）")
                    }
                }
                if (attempt < 3) kotlinx.coroutines.delay((1000L * (1 shl attempt)))
            }
            throw DownloadException(ResultCode.NETWORK_ALL_MIRRORS_FAILED, "全部镜像下载失败")
        }

    private suspend fun race(
        candidates: List<Pair<String, String>>,
        asset: UpdateManifest.Asset,
        destPart: File
    ): Outcome? = coroutineScope {
        val lock = Mutex()
        var winner: Outcome? = null
        val jobs = candidates.map { (id, url) ->
            async {
                try {
                    val f = downloadOne(id, url, asset, destPart)
                    lock.withLock {
                        if (winner == null) winner = Outcome(f, id, asset.size)
                    }
                } catch (_: CancellationException) {
                    // 被胜出者取消，忽略
                } catch (e: Exception) {
                    pool.markFailure(id, isHardError(e))
                }
            }
        }
        jobs.forEach { it.await() }
        winner
    }

    private fun downloadOne(id: String, url: String, asset: UpdateManifest.Asset, destPart: File): File {
        val start = if (destPart.exists()) destPart.length() else 0L
        val reqBuilder = Request.Builder().url(url)
        if (start > 0) reqBuilder.header("Range", "bytes=$start-${asset.size - 1}")
        val req = reqBuilder.build()
        http.newCall(req).execute().use { resp ->
            if (resp.code !in 200..299 && resp.code != 206) {
                pool.markFailure(id, true)
                throw IllegalStateException("镜像返回 ${resp.code}：$id")
            }
            resp.body ?: throw IllegalStateException("空响应：$id")
            RandomAccessFile(destPart, "rw").use { raf ->
                if (start == 0L) raf.setLength(asset.size)
                raf.seek(start)
                resp.body!!.byteStream().use { ins ->
                    val buf = ByteArray(1 shl 16)
                    var n: Int
                    var lastByte = System.nanoTime()
                    while (ins.read(buf).also { n = it } > 0) {
                        raf.write(buf, 0, n)
                        if (System.nanoTime() - lastByte > 3_000_000_000L) {
                            throw IllegalStateException("3s 无字节：$id")
                        }
                        lastByte = System.nanoTime()
                    }
                }
            }
        }
        pool.markSuccess(id)
        return destPart
    }

    private fun buildCandidates(asset: UpdateManifest.Asset, originalUrl: String): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        asset.downloads.forEach { list.add(it.mirror to it.url) }
        pool.orderedCandidates().forEach { id ->
            try { list.add(id to pool.buildUrl(id, originalUrl)) } catch (_: IllegalArgumentException) {}
        }
        return list
    }

    private fun isHardError(e: Exception): Boolean {
        val m = e.message ?: return false
        return m.contains("403") || m.contains("404") || m.contains("500")
                || m.contains("502") || m.contains("503")
    }

    /** 下载失败异常（携带结果码）。 */
    class DownloadException(val code: ResultCode, msg: String) : Exception(msg)
}
