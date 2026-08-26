package me.rerere.rikkahub.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.PUSH_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.data.calendar.CalendarStore
import me.rerere.rikkahub.service.PushGenerationService
import me.rerere.rikkahub.service.PushNotificationManager
import me.rerere.rikkahub.utils.NotificationUtil
import me.rerere.rikkahub.utils.PushScheduler
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 推送闹钟接收器 - 接收 AlarmManager 触发的推送事件
 * 
 * 启动前台服务执行推送生成，防止后台被杀或网络被限制
 */
class PushAlarmReceiver : BroadcastReceiver(), KoinComponent {
    private val calendarStore: CalendarStore by inject()
    private val pushNotificationManager: PushNotificationManager by inject()
    private val pushScheduler: PushScheduler by inject()

    companion object {
        private const val TAG = "PushAlarmReceiver"
        const val ACTION_PUSH_TRIGGER = "me.rerere.rikkahub.ACTION_PUSH_TRIGGER"
        const val EXTRA_PUSH_TIME_HOUR = "push_time_hour"
        const val EXTRA_PUSH_TIME_MINUTE = "push_time_minute"

        // 20001 推送、20002 前台服务、20003 生成失败，这里用 20004
        private const val DELIVERY_FAILURE_NOTIFICATION_ID = 20004
    }

    /**
     * 闹钟响了但后续没走通时，发一条通知说明情况。
     *
     * 这条通知的意义是把「闹钟响了但后面失败」和「闹钟压根没响」区分开：
     * 前者能看到这条，后者什么都没有。没它的话两种情况在用户侧长得一模一样。
     */
    private fun notifyDeliveryFailed(context: Context, hour: Int, minute: Int, e: Throwable) {
        NotificationUtil.notify(
            context = context,
            channelId = PUSH_NOTIFICATION_CHANNEL_ID,
            notificationId = DELIVERY_FAILURE_NOTIFICATION_ID,
        ) {
            title = "推送没能完成"
            content = "闹钟已触发（${String.format("%02d:%02d", hour, minute)}），" +
                "但后续步骤失败：${e::class.simpleName}。" +
                "打开应用可补发今天这一次。"
            autoCancel = true
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PUSH_TRIGGER) {
            return
        }

        val hour = intent.getIntExtra(EXTRA_PUSH_TIME_HOUR, -1)
        val minute = intent.getIntExtra(EXTRA_PUSH_TIME_MINUTE, -1)

        if (hour == -1 || minute == -1) {
            Log.w(TAG, "Invalid push time in intent")
            return
        }

        Log.i(TAG, "Push alarm triggered at $hour:$minute")

        // 两条路径统一走前台服务：它是唯一能在冷启动时保护进程不被杀的机制。
        //
        // 之前固定文案模式为了规避「从后台启前台服务」的限制改成了 goAsync() 内联，
        // 但实测在国产 ROM 上进程保护不足，冷启动时进程被系统杀掉导致推送丢失。
        // 渊海同样的逻辑走前台服务完全没问题，说明前台服务启动在 setAlarmClock
        // 的豁免窗口内是可靠的。
        //
        // 仍然保留 goAsync fallback：万一 ROM 拒绝前台服务启动，退化到内联执行，
        // 至少不比完全不做强。
        val pendingResult = goAsync()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope.launch {
            try {
                val pushSettings = calendarStore.getPushSettings()

                if (pushSettings.enabled) {
                    runCatching { pushScheduler.ensureScheduled(pushSettings.pushTimes) }
                        .onFailure { Log.e(TAG, "Failed to re-arm next push alarm", it) }
                }

                // 优先走前台服务（有进程保护），被拒时 fallback 到内联
                val serviceStarted = runCatching {
                    PushGenerationService.start(context, hour, minute)
                }.isSuccess

                if (!serviceStarted) {
                    Log.w(TAG, "Foreground service rejected, falling back to inline execution")
                    withTimeout(8_000) {
                        pushNotificationManager.executePush(hour, minute)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Push delivery failed at $hour:$minute", e)
                notifyDeliveryFailed(context, hour, minute, e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
