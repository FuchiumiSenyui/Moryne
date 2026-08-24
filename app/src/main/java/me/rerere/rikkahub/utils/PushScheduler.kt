package me.rerere.rikkahub.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import me.rerere.rikkahub.data.calendar.PushTime
import me.rerere.rikkahub.receiver.PushAlarmReceiver

/**
 * 推送调度器 - 使用 AlarmManager 设置定时推送
 */
class PushScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    companion object {
        private const val TAG = "PushScheduler"
        private const val BASE_REQUEST_CODE = 10000 // 推送闹钟的 request code 起始值
    }

    /**
     * 设置每日推送闹钟
     * 
     * @param pushTime 推送时间
     * @param index 时间索引（用于生成唯一的 request code）
     */
    fun scheduleDailyPush(pushTime: PushTime, index: Int) {
        val requestCode = BASE_REQUEST_CODE + index
        val intent = Intent(context, PushAlarmReceiver::class.java).apply {
            action = PushAlarmReceiver.ACTION_PUSH_TRIGGER
            putExtra(PushAlarmReceiver.EXTRA_PUSH_TIME_HOUR, pushTime.hour)
            putExtra(PushAlarmReceiver.EXTRA_PUSH_TIME_MINUTE, pushTime.minute)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerAtMillis = pushTime.toTodayMillis()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ 需要检查精确闹钟权限
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                Log.i(TAG, "Scheduled daily push at $pushTime (requestCode=$requestCode, triggerAt=$triggerAtMillis)")
            } else {
                Log.w(TAG, "Cannot schedule exact alarm: permission not granted")
            }
        } else {
            // Android 12 以下直接设置
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
            Log.i(TAG, "Scheduled daily push at $pushTime (requestCode=$requestCode)")
        }
    }

    /**
     * 取消推送闹钟
     * 
     * @param index 时间索引
     */
    fun cancelPush(index: Int) {
        val requestCode = BASE_REQUEST_CODE + index
        val intent = Intent(context, PushAlarmReceiver::class.java).apply {
            action = PushAlarmReceiver.ACTION_PUSH_TRIGGER
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )

        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.i(TAG, "Cancelled push alarm (requestCode=$requestCode)")
        }
    }

    /**
     * 重新调度所有推送闹钟
     * 
     * @param pushTimes 推送时间列表
     */
    fun rescheduleAll(pushTimes: List<PushTime>) {
        // 取消所有可能存在的旧闹钟（最多支持 10 个推送时间）
        for (i in 0 until 10) {
            cancelPush(i)
        }

        // 去重：同一时刻只设置一个闹钟
        val uniqueTimes = pushTimes.distinct()
        if (uniqueTimes.size < pushTimes.size) {
            Log.w(TAG, "Removed ${pushTimes.size - uniqueTimes.size} duplicate push times")
        }

        // 设置新的闹钟
        uniqueTimes.forEachIndexed { index, pushTime ->
            scheduleDailyPush(pushTime, index)
        }
        
        Log.i(TAG, "Rescheduled ${uniqueTimes.size} push alarms")
    }

    /**
     * 检查是否有精确闹钟权限（Android 12+）
     */
    fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true // Android 12 以下不需要权限
        }
    }
}
