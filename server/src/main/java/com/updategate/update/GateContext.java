package com.updategate.update;

import org.jetbrains.annotations.Nullable;

/**
 * 版本门禁判定上下文（输入）。
 *
 * <p>字段设计严格对齐第 2 节「版本门禁算法」输入：current / target / minSupported /
 * minClientVersion / platform，并补充门禁判定所需的实际运行态信息。</p>
 *
 * @param platform        运行平台（SERVER / CLIENT）
 * @param current         当前版本（如插件 1.4.1、客户端 2.3.0）
 * @param target          目标版本（来自 manifest.channels[].target.version）
 * @param minSupported    仍被支持的最低版本（manifest.channels[].minSupported.version）
 * @param mode            渠道模式（OPTIONAL / REQUIRED），决定返回 UPDATE_OPTIONAL 还是 UPDATE_REQUIRED
 * @param targetApiVersion 目标要求的服务端 api-version（如 1.21.11），服务端场景使用
 * @param serverApiVersion 服务端实际 api-version（如 1.21.10），服务端场景使用
 * @param minClientVersion 目标要求的最低客户端版本（manifest.channels[].minClientVersion），客户端场景使用
 * @param clientVersion   当前客户端版本（如 2.3.0），客户端场景使用
 * @param targetVersionCode 目标 versionCode（Android 使用）
 * @param currentVersionCode 当前 versionCode（Android 使用）
 */
public record GateContext(
        Platform platform,
        String current,
        String target,
        String minSupported,
        UpdateMode mode,
        @Nullable String targetApiVersion,
        @Nullable String serverApiVersion,
        @Nullable String minClientVersion,
        @Nullable String clientVersion,
        int targetVersionCode,
        int currentVersionCode
) {}
