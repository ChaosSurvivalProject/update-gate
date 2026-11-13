package com.updategate.update;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * 版本门禁算法（最高优先级，唯一真相源）。
 *
 * <p>判定顺序（命中即返回，不得跳过）：</p>
 * <ol>
 *   <li>target == current                              → UP_TO_DATE</li>
 *   <li>current &lt; minSupported                      → BLOCKED_UPGRADE_REQUIRED</li>
 *   <li>target &gt; current（逐段比 major→minor→patch）
 *     <ul>
 *       <li>服务端：target.apiVersion &gt; 服务端实际 apiVersion → BLOCKED_API_TOO_NEW</li>
 *       <li>客户端：target.minClientVersion &gt; 客户端版本      → BLOCKED_CLIENT_TOO_OLD</li>
 *       <li>否则                                                  → UPDATE_REQUIRED / UPDATE_OPTIONAL</li>
 *     </ul>
 *   </li>
 *   <li>target &lt; current                            → BLOCKED_DOWNGRADE_FORBIDDEN</li>
 * </ol>
 *
 * <p>硬性约束：</p>
 * <ul>
 *   <li>禁止任何「自动跨 minor/major 强升」代码路径；需要升版本时只返回提示文案与下载地址。</li>
 *   <li>服务端场景：客户端 required 1.21.11、服务端 1.21.10 → 仅提示「请更新服务端至 1.21.11」，
 *       不允许下载任何 jar，也不允许降级到 1.21.10 的包。</li>
 *   <li>支持 preRelease 与 build metadata；preRelease 只在 channel=beta 可见（由调用方过滤）。</li>
 * </ul>
 */
public final class VersionGate {

    private VersionGate() {}

    /**
     * 执行门禁判定。
     *
     * @param ctx       判定上下文（见 {@link GateContext}）
     * @param downloadUrl 命中可更新时附带的下载地址（如为 null，结果不携带 url）
     * @return 判定结果 {@link GateDecision}
     */
    public static GateDecision evaluate(GateContext ctx, @Nullable String downloadUrl) {
        Objects.requireNonNull(ctx, "GateContext 不能为空");
        Objects.requireNonNull(ctx.platform(), "platform 不能为空");
        Objects.requireNonNull(ctx.current(), "current 不能为空");
        Objects.requireNonNull(ctx.target(), "target 不能为空");
        Objects.requireNonNull(ctx.minSupported(), "minSupported 不能为空");
        Objects.requireNonNull(ctx.mode(), "mode 不能为空");

        SemanticVersion current = SemanticVersion.parse(ctx.current());
        SemanticVersion target  = SemanticVersion.parse(ctx.target());
        SemanticVersion minSup  = SemanticVersion.parse(ctx.minSupported());

        // 1) target == current → 已是最新
        if (target.equals(current)) {
            return GateDecision.of(GateCode.UP_TO_DATE,
                    "已是最新版本：" + current,
                    "Already up to date: " + current,
                    null, false);
        }

        // 2) current < minSupported → 版本过旧，必须升级（但不自动跨版本）
        if (current.compareTo(minSup) < 0) {
            return GateDecision.of(GateCode.BLOCKED_UPGRADE_REQUIRED,
                    "当前版本 " + current + " 已不被支持（最低 " + minSup
                            + "），请更新到受支持的版本后再继续。",
                    "Current version " + current + " is no longer supported (minimum "
                            + minSup + "). Please update to a supported version.",
                    null, false);
        }

        int cmp = target.compareTo(current);

        // 3) target > current
        if (cmp > 0) {
            if (ctx.platform() == Platform.SERVER) {
                // 服务端：对比 api-version
                String tApi = require(ctx.targetApiVersion(), "targetApiVersion");
                String sApi = require(ctx.serverApiVersion(), "serverApiVersion");
                int apiCmp = SemanticVersion.parse(tApi).compareTo(SemanticVersion.parse(sApi));
                if (apiCmp > 0) {
                    // 仅提示升级服务端，禁止下载 jar / 禁止降级
                    return GateDecision.of(GateCode.BLOCKED_API_TOO_NEW,
                            "当前插件要求服务端 " + tApi + "，你的服务器为 " + sApi
                                    + "，请先升级服务端至 " + tApi
                                    + "，不会自动跨版本更新。",
                            "This plugin requires server " + tApi + ", but yours is " + sApi
                                    + ". Please upgrade your server to " + tApi
                                    + " first. No automatic cross-version update will be performed.",
                            null, false);
                }
            } else {
                // 客户端：对比 minClientVersion
                String minCli = require(ctx.minClientVersion(), "minClientVersion");
                String cli = require(ctx.clientVersion(), "clientVersion");
                int cliCmp = SemanticVersion.parse(minCli).compareTo(SemanticVersion.parse(cli));
                if (cliCmp > 0) {
                    return GateDecision.of(GateCode.BLOCKED_CLIENT_TOO_OLD,
                            "此更新需要客户端 " + minCli + "，你的客户端为 " + cli
                                    + "，请先更新客户端。",
                            "This update requires client " + minCli + ", but yours is " + cli
                                    + ". Please update your client first.",
                            null, false);
                }
            }
            // 门禁通过：返回可更新（强更 / 可选）
            boolean required = ctx.mode() == UpdateMode.REQUIRED;
            GateCode code = required ? GateCode.UPDATE_REQUIRED : GateCode.UPDATE_OPTIONAL;
            String zh = required
                    ? "发现强更版本 " + target + "，必须更新后才能继续使用。"
                    : "发现可选更新 " + target + "，是否更新？";
            String en = required
                    ? "Mandatory update " + target + " is available. You must update to continue."
                    : "Optional update " + target + " is available. Update now?";
            return GateDecision.of(code, zh, en, downloadUrl, !required);
        }

        // 4) target < current → 禁止降级
        return GateDecision.of(GateCode.BLOCKED_DOWNGRADE_FORBIDDEN,
                "目标版本 " + target + " 低于当前版本 " + current + "，禁止降级。",
                "Target version " + target + " is lower than current " + current
                        + ". Downgrade is forbidden.",
                null, false);
    }

    private static String require(@Nullable String v, String name) {
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("门禁判定缺少必要字段：" + name);
        }
        return v;
    }
}
