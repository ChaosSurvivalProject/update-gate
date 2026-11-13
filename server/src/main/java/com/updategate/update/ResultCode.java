package com.updategate.update;

/**
 * 可观测上报结果码枚举（对齐第 7 节）。
 *
 * <p>上报字段：instanceId(匿名 UUID)、from→to、result_code、mirror_used、
 * duration_ms、bytes、fail_reason；不上报 IP 与账号信息。</p>
 */
public enum ResultCode {
    SUCCESS,
    VERSION_GATE_BLOCKED,
    NETWORK_ALL_MIRRORS_FAILED,
    SHA256_MISMATCH,
    SIGNATURE_INVALID,
    DISK_FULL,
    INSTALL_REJECTED,
    ROLLED_BACK
}
