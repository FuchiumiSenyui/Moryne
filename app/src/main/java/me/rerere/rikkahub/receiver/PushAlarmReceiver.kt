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
import me.rerere.rikkahub.data.calendar.PushContentSource
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

        // 固定文案模式在广播里直接做完，不启前台服务。
        //
        // 它不联网、不长跑：读一次 DataStore、挑一条字符串、写日历、发通知，
        // 通常几十毫秒。而「从后台启前台服务」是 Android 12+ 限制最严、
        // 国产 ROM 拦得最多的一道关卡 —— 让一个根本不需要它的路径去闯这道关，
        // 等于白背一整类失败风险（启动被拒、5 秒内没调 startForeground 崩溃）。
        //
        // goAsync() 把广播的存活期从 onReceive 返回延长到 finish()，
        // 系统给约 10 秒，对这条路径绰绰有余。仍设 8 秒超时兜底，
        // 避免 DataStore 异常卡住导致 ANR。
        val pendingResult = goAsync()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope.launch {
            try {
                val pushSettings = calendarStore.getPushSettings()

                // 先续订下一次闹钟，跟前台服务那条路保持一致：
                // 一次性闹钟不续订就只响这一次。放在最前面，保证后面失败也不影响明天。
                if (pushSettings.enabled) {
                    runCatching { pushScheduler.ensureScheduled(pushSettings.pushTimes) }
                        .onFailure { Log.e(TAG, "Failed to re-arm next push alarm", it) }
                }

                if (pushSettings.contentSource == PushContentSource.FIXED_TEXT) {
                    Log.i(TAG, "Fixed-text mode: executing inline, no foreground service")
                    withTimeout(8_000) {
                        pushNotificationManager.executePush(hour, minute)
                    }
                    Log.i(TAG, "Fixed-text push done inline")
                } else {
                    // AI 模式要联网、要跑几十秒，必须有前台服务保命。
                    //
                    // 必须兜住异常：部分 ROM 不认精确闹钟的后台启动豁免，
                    // 会抛 ForegroundServiceStartNotAllowedException。不兜的话
                    // 接收器直接崩，用户侧表现为「到点什么都没有」，无从排查。
                    runCatching {
                        PushGenerationService.start(context, hour, minute)
                    }.onFailure { e ->
                        Log.e(TAG, "Failed to start PushGenerationService for $hour:$minute", e)
                        notifyDeliveryFailed(context, hour, minute, e)
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
