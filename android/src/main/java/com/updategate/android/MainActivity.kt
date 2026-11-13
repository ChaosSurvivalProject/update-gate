package com.updategate.android

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 主界面：触发检查并展示更新对话框。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>强更（UPDATE_OPTIONAL=false）不可跳过；</li>
 *   <li>可选更新用户可「稍后再说」，同一版本 24h 内不重复弹窗；</li>
 *   <li>安装必须经用户确认，禁止静默安装；Android 8+ 引导「未知来源」授权。</li>
 * </ul>
 */
class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences
    private var pendingApk: java.io.File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("ug_ui", MODE_PRIVATE)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    UpdateScreen()
                }
            }
        }
    }

    @Composable
    private fun UpdateScreen() {
        var outcome by remember { mutableStateOf<UpdateManager.CheckOutcome?>(null) }
        var message by remember { mutableStateOf(getString(R.string.update_checking)) }

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { onInstallPermissionResult() }

        val installLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { /* 安装结果由系统处理，无需静默处理 */ }

        LaunchedEffect(Unit) {
            outcome = runCheck()
            outcome?.let { message = it.decision.messageZh }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = message, style = MaterialTheme.typography.bodyLarge)
        }

        val d = outcome?.decision
        val ch = outcome?.channel
        if (d != null && ch != null
            && (d.code == GateCode.UPDATE_OPTIONAL || d.code == GateCode.UPDATE_REQUIRED)) {
            // 24h 内已选择稍后且为可选 → 不弹窗
            val skipUntil = prefs.getLong("skip_${d.code}", 0)
            if (d.canSkip && skipUntil > System.currentTimeMillis()) {
                return@UpdateScreen
            }
            AlertDialog(
                onDismissRequest = { if (d.canSkip) dismiss(d) },
                title = { Text(getString(R.string.app_name)) },
                text = { Text(d.messageZh) },
                confirmButton = {
                    Button(onClick = {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val out = (application as UpdateApplication).updateManager
                                .downloadAndStage(ch)
                            withContext(Dispatchers.Main) {
                                out.file?.let { apk ->
                                    pendingApk = apk
                                    if (Installer.canInstall(this@MainActivity)) {
                                        installLauncher.launch(Installer.buildInstallIntent(this@MainActivity, apk))
                                    } else {
                                        Installer.requestInstallPermission(this@MainActivity, permissionLauncher)
                                    }
                                }
                            }
                        }
                    }) { Text(getString(R.string.update_install)) }
                },
                dismissButton = if (d.canSkip) {
                    {
                        Button(onClick = { dismiss(d) }) {
                            Text(getString(R.string.update_remind_later))
                        }
                    }
                } else null
            )
        }
    }

    private fun dismiss(d: GateDecision) {
        if (d.canSkip) {
            prefs.edit().putLong("skip_${d.code}", System.currentTimeMillis() + TimeUnit.HOURS.toMillis(24)).apply()
        }
    }

    private fun onInstallPermissionResult() {
        pendingApk?.let { apk ->
            if (Installer.canInstall(this)) {
                startActivity(Installer.buildInstallIntent(this, apk))
            }
        }
    }

    private suspend fun runCheck(): UpdateManager.CheckOutcome? = withContext(Dispatchers.IO) {
        runCatching { (application as UpdateApplication).updateManager.check() }.getOrNull()
    }
}
