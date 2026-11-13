package com.updategate.android

import java.io.File

/**
 * bsdiff/bspatch 增量合成（native aar）。
 *
 * <p>仅当本地 versionCode 与 patch.fromVersionCode 完全一致时走补丁，
 * 否则走全量。合成后必须校验 sha256 + 签名指纹。</p>
 *
 * <p>注意：本类通过 JNI 调用原生 bspatch（由独立 native 模块编译为 aar 提供）。
 * 若未集成该 aar，{@link #isAvailable} 返回 false，调用方应自动降级全量包。</p>
 */
object BsDiff {
    private var loaded = false

    @Volatile
    var isAvailable: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("bspatch")
            isAvailable = true
        } catch (_: UnsatisfiedLinkError) {
            isAvailable = false
        }
    }

    /**
     * 用旧 APK + 补丁合成新 APK。
     * @return 合成成功返回 true
     */
    external fun patch(oldApk: String, patchFile: String, newApk: String): Int

    /** 安全封装：失败返回 false（调用方降级全量）。 */
    fun tryPatch(oldApk: File, patch: File, out: File): Boolean {
        if (!isAvailable) return false
        return try {
            patch(oldApk.absolutePath, patch.absolutePath, out.absolutePath) == 0
        } catch (_: Exception) {
            false
        }
    }
}
