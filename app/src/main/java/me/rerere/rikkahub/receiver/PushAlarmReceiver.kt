package me.rerere.rikkahub.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import me.rerere.rikkahub.service.PushGenerationService

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

        // 启动前台服务执行推送生成（保护 API 调用不被系统杀掉）
        PushGenerationService.start(context, hour, minute)
    }
}
