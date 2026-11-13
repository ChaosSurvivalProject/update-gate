package com.updategate.android

import android.content.Context
import android.os.Build

/**
 * 构建兼容工具：ABI 识别与当前 APK 路径获取。
 */
object BuildCompat {
    /** 当前设备的 ABI（与 manifest assets.abi 对应）。 */
    fun abi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    /** 当前已安装 APK 的路径（用于 bsdiff 增量合成的输入）。 */
    fun apkPath(context: Context): String =
        context.applicationInfo.sourceDir ?: context.packageResourcePath ?: ""
}
