package com.updategate.android

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * 前台生命周期节流观察者：应用回到前台时按 6h 节流触发检查。
 */
class ForegroundThrottleObserver(
    private val manager: UpdateManager,
    private val intervalHours: Long = 6
) : DefaultLifecycleObserver {

    @Volatile
    private var lastCheckMs = 0L

    override fun onStart(owner: LifecycleOwner) {
        val now = System.currentTimeMillis()
        if (now - lastCheckMs >= TimeUnit.HOURS.toMillis(intervalHours)) {
            lastCheckMs = now
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { manager.check() }
            }
        }
    }
}
