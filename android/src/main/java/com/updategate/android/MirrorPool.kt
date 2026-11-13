package com.updategate.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 多加速源代理池（内置，优先级可配，全部走 HTTPS，绝不携带 Cookie/Token）。
 * 与服务端 MirrorPool 对齐：gh-proxy.com 置顶，gh-jasonzeng.dev 末位（需运行时探活）。
 *
 * 安全红线：所有请求均为公开资源，绝不附加任何凭证；
 * 元数据（api.github.com）禁止走代理池，只用直连或 gh-proxy.com。
 */
class MirrorPool(private val http: OkHttpClient) {

    data class Entry(
        val id: String,
        val base: String,
        var baseWeight: Int,
        var healthWeight: Double = 1.0,
        var cooldownUntil: Long = 0L,
        var consecutiveFailures: Int = 0,
        var lastTtfbMs: Long = -1
    ) {
        fun isCoolingDown(now: Long) = cooldownUntil > now
        fun effectiveWeight(now: Long): Double = if (isCoolingDown(now)) 0.0 else baseWeight * healthWeight
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    init {
        // 优先级顺序即插入顺序（gh-proxy 置顶，gh-jasonzeng 末位）
        val list = DEFAULT_MIRRORS.entries.toList()
        var w = list.size
        for ((id, base) in list) {
            entries[id] = Entry(id, base, w--)
        }
    }

    /** gh-proxy.com 是唯一获准代理 api.github.com 的镜像。 */
    fun isApiAllowed(id: String) = id == DIRECT || id == API_PROXY

    /** 预热：并发 HEAD 取 TTFB 最优前 n 个作为主力。 */
    suspend fun preheat(probeUrl: String, topN: Int = 2) = withContext(Dispatchers.IO) {
        val snapshot = entries.values.toList()
        snapshot.forEach { e ->
            if (e.id == DIRECT || e.id == JSLIVER) return@forEach // 直连/仅raw不探针
            val url = buildUrl(e.id, probeUrl)
            val t0 = System.nanoTime()
            try {
                val req = Request.Builder().url(url).head().build()
                http.newCall(req).execute().use { resp ->
                    val ttfb = (System.nanoTime() - t0) / 1_000_000
                    e.lastTtfbMs = ttfb
                    e.healthWeight = clamp(1.0 - ttfb / 2000.0, 0.2, 1.0)
                    e.consecutiveFailures = 0
                }
            } catch (_: Exception) {
                e.healthWeight = 0.1
                e.consecutiveFailures++
            }
        }
        snapshot.filter { it.lastTtfbMs >= 0 }.sortedBy { it.lastTtfbMs }
            .take(topN).forEach { it.healthWeight = it.healthWeight.coerceAtLeast(1.0) }
    }

    /** 按 effectiveWeight 降序返回可用镜像 id（剔除冷却中）。 */
    fun orderedCandidates(): List<String> {
        val now = System.currentTimeMillis()
        return entries.values.filter { !it.isCoolingDown(now) }
            .sortedByDescending { it.effectiveWeight(now) }
            .map { it.id }
    }

    /** 失败熔断：连续 2 次 4xx/5xx 或 3s 无字节 → 冷却 60s。 */
    fun markFailure(id: String, hard: Boolean) {
        val e = entries[id] ?: return
        e.consecutiveFailures++
        if (hard || e.consecutiveFailures >= 2) {
            e.cooldownUntil = System.currentTimeMillis() + 60_000
            e.healthWeight = minOf(e.healthWeight, 0.3)
        }
    }

    fun markSuccess(id: String) {
        entries[id]?.let { it.consecutiveFailures = 0 }
    }

    /** 构造某镜像下的实际下载 URL。 */
    fun buildUrl(id: String, originalUrl: String): String {
        val e = entries[id] ?: throw IllegalArgumentException("未知镜像：$id")
        if (e.id == DIRECT) return originalUrl
        val path = stripHost(originalUrl)
        return if (e.id == JSLIVER) toJsdelivr(originalUrl)
        else (if (e.base.endsWith("/")) e.base else e.base + "/") + path
    }

    private fun stripHost(url: String): String {
        val idx = url.indexOf("://")
        if (idx < 0) return url
        val slash = url.indexOf('/', idx + 3)
        return if (slash < 0) "" else url.substring(slash + 1)
    }

    private fun toJsdelivr(url: String): String {
        val marker = "raw.githubusercontent.com/"
        val i = url.indexOf(marker)
        if (i < 0) return url
        val rest = url.substring(i + marker.length)
        val parts = rest.split("/", limit = 4)
        if (parts.size < 4) return url
        return "https://cdn.jsdelivr.net/gh/${parts[0]}/${parts[1]}@${parts[2]}/${parts[3]}"
    }

    companion object {
        const val DIRECT = "direct"
        const val API_PROXY = "gh-proxy"
        const val JSLIVER = "jsdelivr"

        /** 内置镜像：id → base（代理前缀）。 */
        val DEFAULT_MIRRORS = linkedMapOf(
            "gh-proxy" to "https://gh-proxy.com/",
            "ghfast" to "https://ghfast.top/",
            "ghproxy-net" to "https://ghproxy.net/",
            "mirror-ghproxy" to "https://mirror.ghproxy.com/",
            "hub-gitmirror" to "https://hub.gitmirror.com/",
            "moeyy" to "https://moeyy.cn/gh-proxy/",
            "gh-llkk" to "https://gh.llkk.cc/",
            "gh-jasonzeng" to "https://gh.jasonzeng.dev/",
            DIRECT to "https://github.com/",
            JSLIVER to "https://cdn.jsdelivr.net/gh/"
        )
    }
}

private fun clamp(v: Double, lo: Double, hi: Double) = v.coerceIn(lo, hi)
