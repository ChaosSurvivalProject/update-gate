package com.updategate.update;

/**
 * 版本门禁返回码。
 *
 * <p>门禁算法（{@link VersionGate}）严格按第 2 节顺序判定，命中即返回，不得跳过。</p>
 */
public enum GateCode {

    /** target == current：已是最新。 */
    UP_TO_DATE,

    /** current < minSupported：版本过旧，必须升级（但不会自动跨版本）。 */
    BLOCKED_UPGRADE_REQUIRED,

    /** 服务端：target.apiVersion 高于服务端实际 apiVersion。 */
    BLOCKED_API_TOO_NEW,

    /** 客户端：target.minClientVersion 高于当前客户端版本。 */
    BLOCKED_CLIENT_TOO_OLD,

    /** target > current 且门禁通过，渠道为强更。 */
    UPDATE_REQUIRED,

    /** target > current 且门禁通过，渠道为可选。 */
    UPDATE_OPTIONAL,

    /** target < current：禁止降级。 */
    BLOCKED_DOWNGRADE_FORBIDDEN
}
