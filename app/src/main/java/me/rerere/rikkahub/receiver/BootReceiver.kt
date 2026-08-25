package me.rerere.rikkahub.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.calendar.CalendarStore
import me.rerere.rikkahub.utils.PushScheduler
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 开机启动接收器 - 系统启动后重新设置推送闹钟
 */
class BootReceiver : BroadcastReceiver(), KoinComponent {
    private val calendarStore: CalendarStore by inject()
    private val pushScheduler: PushScheduler by inject()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        Log.i(TAG, "System boot completed, re-arming push alarms")

        scope.launch {
            try {
                val pushSettings = calendarStore.getPushSettings()
                if (!pushSettings.enabled) {
                    Log.i(TAG, "Push is disabled, no alarms scheduled")
                    return@launch
                }

                // 开机后系统已清空所有闹钟，覆盖式排一遍即可，不必取消。
                // ensureScheduled 内部 allowToday=true：今天还没到的时刻排今天，不顺延。
                pushScheduler.ensureScheduled(pushSettings.pushTimes)
                Log.i(TAG, "Push alarms re-armed after boot")

                // 关机期间错过的那次不在这里补：Android 14+ 限制哪些前台服务类型
                // 可以从 BOOT_COMPLETED 启动，specialUse 不一定被允许。
                // 交给「App 进入前台时补做」那条路（PushNotificationManager）。
            } catch (e: Exception) {
                Log.e(TAG, "Failed to re-arm push alarms after boot", e)
            }
        }
    }
}
