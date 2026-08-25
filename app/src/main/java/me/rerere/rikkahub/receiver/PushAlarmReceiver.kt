package me.rerere.rikkahub.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import me.rerere.rikkahub.PUSH_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.service.PushGenerationService
import me.rerere.rikkahub.utils.NotificationUtil

/**
 * 推送闹钟接收器 - 接收 AlarmManager 触发的推送事件
 * 
 * 启动前台服务执行推送生成，防止后台被杀或网络被限制
 */
class PushAlarmReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "PushAlarmReceiver"
        const val ACTION_PUSH_TRIGGER = "me.rerere.rikkahub.ACTION_PUSH_TRIGGER"
        const val EXTRA_PUSH_TIME_HOUR = "push_time_hour"
        const val EXTRA_PUSH_TIME_MINUTE = "push_time_minute"

        // 20001 推送、20002 前台服务、20003 生成失败，这里用 20004
        private const val DELIVERY_FAILURE_NOTIFICATION_ID = 20004
    }

    /**
     * 闹钟响了但前台服务起不来时，发一条通知说明情况。
     *
     * 这条通知的意义是把「系统拒绝」和「闹钟压根没响」区分开：
     * 前者能看到这条，后者什么都没有。没它的话两种情况在用户侧长得一模一样。
     */
    private fun notifyDeliveryFailed(context: Context, hour: Int, minute: Int, e: Throwable) {
        NotificationUtil.notify(
            context = context,
            channelId = PUSH_NOTIFICATION_CHANNEL_ID,
            notificationId = DELIVERY_FAILURE_NOTIFICATION_ID,
        ) {
            title = "推送没能启动"
            content = "闹钟已触发（${String.format("%02d:%02d", hour, minute)}），" +
                "但系统拒绝启动后台任务：${e::class.simpleName}。" +
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

        Log.i(TAG, "Push alarm triggered at $hour:$minute, starting foreground service")

        // 启动前台服务执行推送生成（保护 API 调用不被系统杀掉）。
        //
        // 必须兜住异常：Android 12+ 禁止从后台启动前台服务，精确闹钟本来有豁免，
        // 但部分 ROM 不认这个豁免，会抛 ForegroundServiceStartNotAllowedException。
        // 不兜的话广播接收器直接崩掉，用户侧表现为「到点什么都没有」，
        // 连一条错误提示都看不到，完全无从排查。
        runCatching {
            PushGenerationService.start(context, hour, minute)
        }.onFailure { e ->
            Log.e(TAG, "Failed to start PushGenerationService for $hour:$minute", e)
            notifyDeliveryFailed(context, hour, minute, e)
        }
    }
}
