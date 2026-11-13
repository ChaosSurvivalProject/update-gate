package com.updategate.update;

import org.jetbrains.annotations.Nullable;

/**
 * 版本门禁判定结果。
 *
 * <p>同时携带返回码、中英双语文案、可选下载地址。是否可跳过（canSkip）仅对
 * UPDATE_OPTIONAL 为 true；强更（UPDATE_REQUIRED）不可跳过。</p>
 */
public record GateDecision(
        GateCode code,
        String messageZh,
        String messageEn,
        @Nullable String downloadUrl,
        boolean canSkip
) {
    public static GateDecision of(GateCode code, String zh, String en,
                                  @Nullable String url, boolean canSkip) {
        return new GateDecision(code, zh, en, url, canSkip);
    }
}
