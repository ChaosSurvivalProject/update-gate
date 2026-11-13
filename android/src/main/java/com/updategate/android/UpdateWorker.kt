package com.updategate.android

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WorkManager 周期性更新 Worker。
 * 执行检查与下载（不自动安装——安装必须由用户确认，禁止静默安装）。
 */
class UpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val manager = (applicationContext as UpdateApplication).updateManager
        val outcome = runCatching { manager.check() }.getOrNull() ?: return@withContext Result.retry()
        val decision = outcome.decision
        // 仅强更或可选更新时下载并暂存，等待用户在前台确认安装
        if (decision.code == GateCode.UPDATE_REQUIRED || decision.code == GateCode.UPDATE_OPTIONAL) {
            outcome.channel?.let { ch ->
                manager.downloadAndStage(ch)
            }
        }
        Result.success()
    }
}
