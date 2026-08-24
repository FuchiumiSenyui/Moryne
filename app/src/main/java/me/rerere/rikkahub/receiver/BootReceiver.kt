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

        Log.i(TAG, "System boot completed, rescheduling push alarms")

        scope.launch {
            try {
                val pushSettings = calendarStore.getPushSettings()
                if (pushSettings.enabled) {
                    pushScheduler.rescheduleAll(pushSettings.pushTimes)
                    Log.i(TAG, "Push alarms rescheduled successfully")
                } else {
                    Log.i(TAG, "Push is disabled, no alarms scheduled")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reschedule push alarms", e)
            }
        }
    }
}
