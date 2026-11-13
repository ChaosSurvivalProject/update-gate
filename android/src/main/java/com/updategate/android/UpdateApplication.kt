package com.updategate.android

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 应用入口。
 * - onCreate 触发首检；
 * - 通过 ProcessLifecycleOwner 监听「回到前台」，6h 节流；
 * - 注册 WorkManager 周期性更新（约束 NetworkType.CONNECTED）。
 */
class UpdateApplication : Application() {

    lateinit var updateManager: UpdateManager
        private set

    override fun onCreate() {
        super.onCreate()
        updateManager = UpdateManager(this, UpdateManager.Config.DEFAULT)

        // 周期性后台检查（WorkManager）
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresMetered(false) // 非计量网络可配；如需允许流量下载可改为 true
            .build()
        val work = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "update-check", ExistingPeriodicWorkPolicy.UPDATE, work
        )

        // 回到前台节流检查（6h）
        ProcessLifecycleOwner.get().lifecycle.addObserver(ForegroundThrottleObserver(updateManager))
    }
}
