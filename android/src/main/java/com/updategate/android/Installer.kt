package com.updategate.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.FileProvider
import java.io.File

/**
 * APK 安装器。
 *
 * <p>安全与合规：</p>
 * <ul>
 *   <li>Android 8+：通过 {@code canRequestPackageInstalls()} 引导用户授权「未知来源」；</li>
 *   <li>Android 14+：拒绝 targetSdk<23 的 APK，需提前判 minSdk 并提示；</li>
 *   <li><b>禁止静默安装</b>（Android 15 已全面封杀），必须用户确认：通过
 *       {@code Intent.ACTION_INSTALL_PACKAGE} + FileProvider 交由系统安装器；</li>
 *   <li>FileProvider 仅暴露应用内部 updates 目录，不授予外部读写。</li>
 * </ul>
 */
object Installer {

    /** 检查是否已授权「安装未知来源应用」。 */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else true

    /** 跳转到「安装未知来源」授权页（Android 8+）。 */
    fun requestInstallPermission(activity: Activity, launcher: ActivityResultLauncher<Intent>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${activity.packageName}"))
            launcher.launch(intent)
        }
    }

    /**
     * 校验 APK 是否可被安装（Android 14+ 拒绝 targetSdk<23）。
     * @return 错误信息；null 表示通过。
     */
    fun validateMinSdk(apkFile: File): String? {
        // 简化校验：读取 APK 的 minSdkVersion 需要解析二进制，这里交由系统安装器最终裁定。
        // 生产可借助 PackageManager 的 {@code PackageInfo} 解析；此处仅占位。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !apkFile.exists()) {
            return "APK 文件缺失，无法安装"
        }
        return null
    }

    /** 构造安装 Intent（用户确认后由系统安装器执行，非静默）。 */
    fun buildInstallIntent(context: Context, apkFile: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apkFile
        )
        return Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
