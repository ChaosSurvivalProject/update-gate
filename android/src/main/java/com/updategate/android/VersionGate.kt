package com.updategate.android

import android.content.Context

/**
 * 版本门禁返回码（与服务端一致）。
 */
enum class GateCode {
    UP_TO_DATE,
    BLOCKED_UPGRADE_REQUIRED,
    BLOCKED_API_TOO_NEW,
    BLOCKED_CLIENT_TOO_OLD,
    UPDATE_REQUIRED,
    UPDATE_OPTIONAL,
    BLOCKED_DOWNGRADE_FORBIDDEN
}

/**
 * 版本门禁判定结果（携带中英双语文案与可选下载地址）。
 */
data class GateDecision(
    val code: GateCode,
    val messageZh: String,
    val messageEn: String,
    val downloadUrl: String?,
    val canSkip: Boolean
)

/**
 * 版本门禁上下文（输入）。
 */
data class GateContext(
    val platform: Platform,
    val current: String,
    val target: String,
    val minSupported: String,
    val mode: UpdateMode,
    val targetApiVersion: String?,
    val serverApiVersion: String?,
    val minClientVersion: String?,
    val clientVersion: String?,
    val targetVersionCode: Int,
    val currentVersionCode: Int
)

enum class Platform { SERVER, CLIENT }
enum class UpdateMode { OPTIONAL, REQUIRED }

/**
 * 版本门禁算法（最高优先级，唯一真相源）。
 * 判定顺序（命中即返回，不得跳过）：
 * 1) target == current → UP_TO_DATE
 * 2) current < minSupported → BLOCKED_UPGRADE_REQUIRED
 * 3) target > current（逐段比 major→minor→patch）
 *    - 服务端：target.apiVersion > 服务端实际 apiVersion → BLOCKED_API_TOO_NEW
 *    - 客户端：target.minClientVersion > 客户端版本 → BLOCKED_CLIENT_TOO_OLD
 *    - 否则 → UPDATE_REQUIRED / UPDATE_OPTIONAL
 * 4) target < current → BLOCKED_DOWNGRADE_FORBIDDEN
 *
 * 硬性约束：禁止自动跨 minor/major 强升；需要升版本时只返回提示文案与下载地址。
 */
object VersionGate {

    fun evaluate(ctx: GateContext, downloadUrl: String?): GateDecision {
        val current = SemanticVersion.parse(ctx.current)
        val target = SemanticVersion.parse(ctx.target)
        val minSup = SemanticVersion.parse(ctx.minSupported)

        if (target == current) {
            return GateDecision(
                GateCode.UP_TO_DATE,
                "已是最新版本：$current",
                "Already up to date: $current",
                null, false
            )
        }

        if (current < minSup) {
            return GateDecision(
                GateCode.BLOCKED_UPGRADE_REQUIRED,
                "当前版本 $current 已不被支持（最低 $minSup），请更新到受支持的版本后再继续。",
                "Current version $current is no longer supported (minimum $minSup). Please update.",
                null, false
            )
        }

        val cmp = target.compareTo(current)
        if (cmp > 0) {
            if (ctx.platform == Platform.SERVER) {
                val tApi = require(ctx.targetApiVersion, "targetApiVersion")
                val sApi = require(ctx.serverApiVersion, "serverApiVersion")
                if (SemanticVersion.parse(tApi) > SemanticVersion.parse(sApi)) {
                    return GateDecision(
                        GateCode.BLOCKED_API_TOO_NEW,
                        "当前应用要求服务端 $tApi，你的服务器为 $sApi，请先升级服务端至 $tApi，不会自动跨版本更新。",
                        "This app requires server $tApi, but yours is $sApi. Please upgrade your server to $tApi first. No automatic cross-version update will be performed.",
                        null, false
                    )
                }
            } else {
                val minCli = require(ctx.minClientVersion, "minClientVersion")
                val cli = require(ctx.clientVersion, "clientVersion")
                if (SemanticVersion.parse(minCli) > SemanticVersion.parse(cli)) {
                    return GateDecision(
                        GateCode.BLOCKED_CLIENT_TOO_OLD,
                        "此更新需要客户端 $minCli，你的客户端为 $cli，请先更新客户端。",
                        "This update requires client $minCli, but yours is $cli. Please update your client first.",
                        null, false
                    )
                }
            }
            val required = ctx.mode == UpdateMode.REQUIRED
            val code = if (required) GateCode.UPDATE_REQUIRED else GateCode.UPDATE_OPTIONAL
            val zh = if (required) "发现强更版本 $target，必须更新后才能继续使用。"
            else "发现可选更新 $target，是否更新？"
            val en = if (required) "Mandatory update $target is available. You must update to continue."
            else "Optional update $target is available. Update now?"
            return GateDecision(code, zh, en, downloadUrl, !required)
        }

        return GateDecision(
            GateCode.BLOCKED_DOWNGRADE_FORBIDDEN,
            "目标版本 $target 低于当前版本 $current，禁止降级。",
            "Target version $target is lower than current $current. Downgrade is forbidden.",
            null, false
        )
    }

    private fun require(v: String?, name: String): String {
        require(!v.isNullOrBlank()) { "门禁判定缺少必要字段：$name" }
        return v
    }
}
