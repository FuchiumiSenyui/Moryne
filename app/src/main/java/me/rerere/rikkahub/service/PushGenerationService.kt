package me.rerere.rikkahub.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.calendar.CalendarStore
import me.rerere.rikkahub.data.calendar.PushContentSource
import me.rerere.rikkahub.utils.PushScheduler
import org.koin.android.ext.android.inject

/**
 * 推送生成前台服务
 * 
 * 在后台生成推送消息时保持前台服务状态，防止系统杀掉进程或中断网络请求
 */
class PushGenerationService : Service() {
    private val pushNotificationManager: PushNotificationManager by inject()
    private val calendarStore: CalendarStore by inject()
    private val pushScheduler: PushScheduler by inject()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // 防止同一时间的推送被并发执行
    private val activePushKeys = mutableSetOf<String>()

    companion object {
        private const val TAG = "PushGenerationService"
        private const val CHANNEL_ID = "push_generation"
        private const val NOTIFICATION_ID = 20002
        
        const val EXTRA_PUSH_TIME_HOUR = "push_time_hour"
        const val EXTRA_PUSH_TIME_MINUTE = "push_time_minute"

        /**
         * 启动推送生成服务
         */
        fun start(context: Context, hour: Int, minute: Int) {
            val intent = Intent(context, PushGenerationService::class.java).apply {
                putExtra(EXTRA_PUSH_TIME_HOUR, hour)
                putExtra(EXTRA_PUSH_TIME_MINUTE, minute)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "PushGenerationService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val hour = intent?.getIntExtra(EXTRA_PUSH_TIME_HOUR, -1) ?: -1
        val minute = intent?.getIntExtra(EXTRA_PUSH_TIME_MINUTE, -1) ?: -1

        if (hour == -1 || minute == -1) {
            Log.w(TAG, "Invalid push time, stopping service")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // 防止同一时间的推送被并发启动
        val pushKey = String.format("%02d:%02d", hour, minute)
        synchronized(activePushKeys) {
            if (!activePushKeys.add(pushKey)) {
                Log.w(TAG, "Push $pushKey already in progress, skipping")
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }

        // 立即启动前台服务，显示正在生成的通知
        // 先用默认文案，协程中读配置后更新
        val notification = buildNotification("我在看着你。")
        startForeground(NOTIFICATION_ID, notification)
        Log.i(TAG, "Started foreground service for push $hour:$minute")

        // 在协程中执行推送生成
        serviceScope.launch {
            try {
                // 读取配置并更新通知文案
                val pushSettings = calendarStore.getPushSettings()

                // 先续订下一次闹钟，再生成。
                // setExactAndAllowWhileIdle 是一次性闹钟，不续订的话响过这一次就永远不再响。
                // 放在生成之前，保证即使生成失败/进程被杀，明天的闹钟也已经排好。
                if (pushSettings.enabled) {
                    runCatching { pushScheduler.rescheduleAll(pushSettings.pushTimes) }
                        .onFailure { Log.e(TAG, "Failed to reschedule next push alarm", it) }
                }

                // 固定文案模式几乎瞬间完成，显示"正在生成"会误导
                if (pushSettings.contentSource == PushContentSource.AI_GENERATED) {
                    val updatedNotification = buildNotification(pushSettings.foregroundNotificationText)
                    val notificationManager = getSystemService(NotificationManager::class.java)
                    notificationManager.notify(NOTIFICATION_ID, updatedNotification)
                }

                Log.i(TAG, "Executing push...")
                pushNotificationManager.executePush(hour, minute)
                Log.i(TAG, "Push executed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to generate push message", e)
            } finally {
                synchronized(activePushKeys) {
                    activePushKeys.remove(pushKey)
                }
                // 生成完成，停止前台服务
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        Log.d(TAG, "PushGenerationService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "推送生成",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "正在生成推送消息"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = Intent(this, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(text)
            .setContentText("")
            .setSmallIcon(R.drawable.small_icon)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
