package com.updategate.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 版本门禁矩阵测试（验收 9.2）。
 *
 * <p>覆盖：低/等/高 minor、高 patch、apiVersion 过高、versionCode 回退、
 * minClientVersion 不足 —— 每个用例断言返回码与文案。</p>
 */
class VersionGateTest {

    // ---- 核心比较 ----
    @Test
    void semanticVersion_compareMinorPatch() {
        // 高 patch：1.21.4 < 1.21.11
        assertTrue(SemanticVersion.parse("1.21.11").compareTo(SemanticVersion.parse("1.21.4")) > 0);
        // 低 minor：1.20.0 < 1.21.0
        assertTrue(SemanticVersion.parse("1.21.0").compareTo(SemanticVersion.parse("1.20.99")) > 0);
        // 等 minor：1.21.4 == 1.21.4
        assertEquals(0, SemanticVersion.parse("1.21.4").compareTo(SemanticVersion.parse("1.21.4")));
        // preRelease 仅在 beta 可见；优先级低于同核心正式版
        assertTrue(SemanticVersion.parse("1.4.2").compareTo(SemanticVersion.parse("1.4.2-beta.1")) > 0);
    }

    // ---- 1) UP_TO_DATE ----
    @Test
    void gate_upToDate_whenTargetEqualsCurrent() {
        GateDecision d = VersionGate.evaluate(ctx("1.4.2", "1.4.2", "1.0.0", UpdateMode.OPTIONAL,
                "1.21.11", "1.21.11", null, null, 0, 0), null);
        assertEquals(GateCode.UP_TO_DATE, d.code());
        assertTrue(d.messageZh().contains("已是最新"));
    }

    // ---- 2) BLOCKED_UPGRADE_REQUIRED（versionCode 过低 / 低 minor）----
    @Test
    void gate_blockedUpgradeRequired_whenCurrentBelowMinSupported() {
        GateDecision d = VersionGate.evaluate(ctx("0.9.0", "1.4.2", "1.0.0", UpdateMode.OPTIONAL,
                "1.21.11", "1.21.11", null, null, 0, 0), null);
        assertEquals(GateCode.BLOCKED_UPGRADE_REQUIRED, d.code());
        assertTrue(d.messageZh().contains("已不被支持"));
        assertFalse(d.canSkip());
    }

    // ---- 3) 服务端 apiVersion 过高：仅提示升级服务端，禁止下载/降级 ----
    @Test
    void gate_blockedApiTooNew_whenServerApiTooOld() {
        GateDecision d = VersionGate.evaluate(ctx("1.4.1", "1.4.2", "1.0.0", UpdateMode.OPTIONAL,
                "1.21.11", "1.21.10", null, null, 0, 0), "https://example.com/X.jar");
        assertEquals(GateCode.BLOCKED_API_TOO_NEW, d.code());
        // 文案必须提示升级服务端，且不得附下载地址
        assertTrue(d.messageZh().contains("请先升级服务端至 1.21.11"));
        assertTrue(d.messageZh().contains("1.21.10"));
        assertNull(d.downloadUrl());
        assertFalse(d.canSkip());
    }

    // ---- 3) 客户端 minClientVersion 不足 ----
    @Test
    void gate_blockedClientTooOld_whenMinClientNotMet() {
        GateDecision d = VersionGate.evaluate(ctx("2.2.0", "2.3.0", "2.0.0", UpdateMode.REQUIRED,
                null, null, "2.3.0", "2.2.0", 142, 140), null);
        assertEquals(GateCode.BLOCKED_CLIENT_TOO_OLD, d.code());
        assertTrue(d.messageZh().contains("2.3.0"));
        assertFalse(d.canSkip());
    }

    // ---- 3) UPDATE_OPTIONAL（门禁通过，渠道可选）----
    @Test
    void gate_updateOptional_whenGatePassedAndOptional() {
        GateDecision d = VersionGate.evaluate(ctx("1.4.0", "1.4.2", "1.0.0", UpdateMode.OPTIONAL,
                "1.21.11", "1.21.11", null, null, 0, 0), "https://example.com/X.jar");
        assertEquals(GateCode.UPDATE_OPTIONAL, d.code());
        assertEquals("https://example.com/X.jar", d.downloadUrl());
        assertTrue(d.canSkip());
    }

    // ---- 3) UPDATE_REQUIRED（门禁通过，强更不可跳过）----
    @Test
    void gate_updateRequired_whenGatePassedAndRequired() {
        GateDecision d = VersionGate.evaluate(ctx("1.4.0", "1.4.2", "1.0.0", UpdateMode.REQUIRED,
                "1.21.11", "1.21.11", null, null, 0, 0), "https://example.com/X.jar");
        assertEquals(GateCode.UPDATE_REQUIRED, d.code());
        assertFalse(d.canSkip());
    }

    // ---- 4) BLOCKED_DOWNGRADE_FORBIDDEN（含 versionCode 回退场景）----
    @Test
    void gate_blockedDowngrade_whenTargetBelowCurrent() {
        GateDecision d = VersionGate.evaluate(ctx("1.5.0", "1.4.2", "1.0.0", UpdateMode.OPTIONAL,
                "1.21.11", "1.21.11", null, null, 0, 0), null);
        assertEquals(GateCode.BLOCKED_DOWNGRADE_FORBIDDEN, d.code());
        assertTrue(d.messageZh().contains("禁止降级"));
    }

    @Test
    void gate_blockedDowngrade_onVersionCodeRollback() {
        // 客户端：当前 versionCode 150，目标 142（回退）→ target<current → 禁止降级
        GateDecision d = VersionGate.evaluate(ctx("1.5.0", "1.4.2", "1.0.0", UpdateMode.OPTIONAL,
                null, null, "2.3.0", "2.5.0", 142, 150), null);
        assertEquals(GateCode.BLOCKED_DOWNGRADE_FORBIDDEN, d.code());
    }

    // 工具：构造服务端/客户端通用 GateContext
    private static GateContext ctx(String current, String target, String minSupported,
                                   UpdateMode mode, String targetApi, String serverApi,
                                   String minClient, String clientVer,
                                   int targetCode, int currentCode) {
        return new GateContext(
                serverApi != null ? Platform.SERVER : Platform.CLIENT,
                current, target, minSupported, mode,
                targetApi, serverApi, minClient, clientVer, targetCode, currentCode);
    }
}
