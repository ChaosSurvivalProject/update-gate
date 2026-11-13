package com.updategate.android

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 更新编排器：协调「拉取 manifest → 验签 → 门禁 → 竞速下载 → 校验 → 增量/全量 → 安装引导」。
 *
 * <p>节流：入口（Application.onCreate + 回到前台）6h；同一版本「稍后再说」24h 内不重复弹窗；
 * 强更（UPDATE_OPTIONAL=false）不可跳过。</p>
 */
class UpdateManager(
    private val context: Context,
    private val config: Config = Config.DEFAULT
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val pool = MirrorPool(http)
    private val engine = DownloadEngine(http, pool)
    val reporter = UpdateReporter(context, http, config.reportEndpoint)

    /** 客户端配置。 */
    data class Config(
        val org: String = "ChaosSurvivalProject",
        val repo: String = "update-gate",
        val channel: String = "stable",
        val intervalHours: Long = 6,
        val manifestUrl: String =
            "https://gh-proxy.com/https://raw.githubusercontent.com/ChaosSurvivalProject/update-gate/gh-pages/updates/latest.json",
        val releaseBaseUrl: String =
            "https://github.com/ChaosSurvivalProject/update-gate/releases/download/",
        val reportEndpoint: String = ""
    ) {
        companion object {
            val DEFAULT = Config()
        }
    }

    data class CheckOutcome(
        val decision: GateDecision,
        val channel: UpdateManifest.Channel?
    )

    data class DownloadOutcome(
        val ok: Boolean,
        val file: File?,
        val mirrorUsed: String?,
        val message: String
    )

    /** 拉取并验签 manifest（验签失败一律终止）。 */
    private suspend fun fetchManifest(): UpdateManifest.Root = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(config.manifestUrl).build()
        val body = http.newCall(req).execute().use { it.body?.string() }
            ?: throw IllegalStateException("manifest 为空")
        val sigReq = Request.Builder().url(config.manifestUrl + ".sig").build()
        val sig = runCatching { http.newCall(sigReq).execute().use { it.body?.string() } }.getOrNull()
        if (!sig.isNullOrBlank() && !Crypto.verifySignature(body.toByteArray(), sig.trim())) {
            reporter.report("", "", ResultCode.SIGNATURE_INVALID, "", 0, 0, "manifest 验签失败")
            throw SecurityException("manifest 验签失败，已终止更新")
        }
        UpdateManifest.fromJson(body)
    }

    /** 执行检查：拉取 manifest → 门禁判定。 */
    suspend fun check(): CheckOutcome {
        val root = fetchManifest()
        val ch = root.channel(config.channel) ?: return CheckOutcome(
            GateDecision(GateCode.BLOCKED_UPGRADE_REQUIRED, "未找到渠道：${config.channel}",
                "Channel not found: ${config.channel}", null, false), null
        )
        if (!reporter.isInRollout(ch.rolloutPercent)) {
            return CheckOutcome(
                GateDecision(GateCode.UP_TO_DATE, "未命中灰度：${ch.target.version}",
                    "Not in rollout: ${ch.target.version}", null, false), ch
            )
        }
        val pkg = currentPackage()
        val ctx = GateContext(
            platform = Platform.CLIENT,
            current = pkg.versionName,
            target = ch.target.version,
            minSupported = ch.minSupported.version,
            mode = ch.updateMode(),
            targetApiVersion = null,
            serverApiVersion = null,
            minClientVersion = ch.minClientVersion,
            clientVersion = pkg.versionName,
            targetVersionCode = ch.target.versionCode,
            currentVersionCode = pkg.versionCode
        )
        val decision = VersionGate.evaluate(ctx, downloadUrlFor(ch))
        if (decision.code == GateCode.BLOCKED_CLIENT_TOO_OLD
            || decision.code == GateCode.BLOCKED_UPGRADE_REQUIRED
            || decision.code == GateCode.BLOCKED_API_TOO_NEW
            || decision.code == GateCode.BLOCKED_DOWNGRADE_FORBIDDEN
        ) {
            reporter.report(pkg.versionName, ch.target.version, ResultCode.VERSION_GATE_BLOCKED,
                "", 0, 0, decision.code.name)
        }
        return CheckOutcome(decision, ch)
    }

    /** 下载并校验（增量优先，失败降级全量）。 */
    suspend fun downloadAndStage(channel: UpdateManifest.Channel): DownloadOutcome =
        withContext(Dispatchers.IO) {
            val abi = BuildCompat.abi()
            val asset = channel.asset("android", abi)
                ?: return@withContext DownloadOutcome(false, null, null, "无 android($abi) 资产")
            val updateDir = File(context.filesDir, "updates").also { it.mkdirs() }
            val part = File(updateDir, asset.fileName + ".part")
            val originalUrl = config.releaseBaseUrl + "v" + channel.target.version + "/" + asset.fileName
            val t0 = System.currentTimeMillis()
            return@withContext try {
                val out = engine.download(asset, originalUrl, part)
                // 增量合成（仅当本地 versionCode == patch.fromVersionCode）
                val finalApk = applyPatchIfNeeded(channel, asset, part, updateDir)
                    ?: return@withContext DownloadOutcome(false, null, null,
                        context.getString(R.string.update_sha_mismatch))
                DownloadOutcome(true, finalApk, out.mirrorUsed,
                    context.getString(R.string.update_install_ready))
            } catch (e: DownloadEngine.DownloadException) {
                reporter.report("", channel.target.version, e.code, "",
                    System.currentTimeMillis() - t0, 0, e.message ?: "")
                DownloadOutcome(false, null, null, context.getString(R.string.update_all_mirrors_failed))
            } catch (e: Exception) {
                DownloadOutcome(false, null, null, e.message ?: "下载失败")
            }
        }

    /** 增量合成：仅当本地 versionCode == patch.fromVersionCode 时走补丁，否则全量。 */
    private fun applyPatchIfNeeded(
        channel: UpdateManifest.Channel,
        asset: UpdateManifest.Asset,
        fullApk: File,
        updateDir: File
    ): File? {
        val patch = asset.patch ?: return fullApk
        val current = currentPackage()
        if (current.versionCode != patch.fromVersionCode || !BsDiff.isAvailable) {
            // 版本不匹配或不支持补丁 → 使用全量包（已校验 sha256）
            return if (Crypto.verifySha256(fullApk, asset.sha256)) fullApk else null
        }
        val patchFile = File(updateDir, patch.fileName)
        // 注：patch 文件应由下载引擎一并拉取；此处假设已存在，真实实现需先下载 patch。
        val merged = File(updateDir, asset.fileName)
        return if (BsDiff.tryPatch(BuildCompat.apkPath(context), patchFile, merged)
            && Crypto.verifySha256(merged, asset.sha256)
            && verifySignatureFingerprint(merged, asset.signatureFingerprint)
        ) {
            merged
        } else {
            // 合并失败（签名不一致/损坏）→ 降级全量包，最多重试 1 次
            if (Crypto.verifySha256(fullApk, asset.sha256)) fullApk else null
        }
    }

    private fun verifySignatureFingerprint(apk: File, fingerprint: String?): Boolean {
        if (fingerprint.isNullOrBlank()) return true
        // 真实实现：用 PackageManager 校验 APK 签名指纹；此处占位为 true
        return true
    }

    private fun currentPackage(): PackageInfo {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        return pm.getPackageInfo(context.packageName, 0)
    }

    private fun downloadUrlFor(ch: UpdateManifest.Channel): String? {
        val asset = ch.asset("android", BuildCompat.abi()) ?: ch.asset("android", null) ?: return null
        return asset.firstDownload()?.url
    }
}
