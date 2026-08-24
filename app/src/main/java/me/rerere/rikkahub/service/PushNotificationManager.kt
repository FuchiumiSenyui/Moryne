package me.rerere.rikkahub.service

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.calendar.CalendarStore
import me.rerere.rikkahub.data.calendar.Message
import me.rerere.rikkahub.data.calendar.MessageRole
import me.rerere.rikkahub.data.calendar.FixedTextPickMode
import me.rerere.rikkahub.data.calendar.NotificationContentMode
import me.rerere.rikkahub.data.calendar.PushContentSource
import me.rerere.rikkahub.data.calendar.PushMessageGenerator
import me.rerere.rikkahub.data.calendar.PushSettings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.NotificationUtil
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 推送通知管理器
 * 
 * 监听推送触发事件，生成推送消息并插入日历，发送通知
 */
class PushNotificationManager(
    private val application: Application,
    private val calendarStore: CalendarStore,
    private val settingsStore: SettingsStore,
    private val pushMessageGenerator: PushMessageGenerator,
) {
    companion object {
        private const val TAG = "PushNotificationManager"
        private const val PUSH_NOTIFICATION_ID = 20001
        private const val PUSH_FAILURE_NOTIFICATION_ID = 20003 // 20002 是前台服务通知
        private const val DUPLICATE_THRESHOLD_SECONDS = 30L // 30 秒内相同时间的推送视为重复
    }

    // 去重：记录最近执行的推送时间（格式："HH:mm"）和执行时刻
    private val recentExecutions = mutableMapOf<String, Long>()
    
    // 互斥锁：确保 executePush 不会并发执行
    private val executeMutex = Mutex()
    
    // 防止重复启动监听器
    @Volatile
    private var isStarted = false

    fun start() {
        if (isStarted) {
            Log.w(TAG, "PushNotificationManager already started, skipping")
            return
        }
        isStarted = true
        Log.i(TAG, "PushNotificationManager started (event bus listener removed, push via foreground service only)")
    }

    /**
     * 执行推送（公开方法，供 PushAlarmReceiver 直接调用）
     */
    suspend fun executePush(scheduledHour: Int, scheduledMinute: Int) {
        // 互斥锁：同一时间只能有一个 executePush 在运行
        executeMutex.withLock {
            doExecutePush(scheduledHour, scheduledMinute)
        }
    }
    
    private suspend fun doExecutePush(scheduledHour: Int, scheduledMinute: Int) {
        val pushKey = String.format("%02d:%02d", scheduledHour, scheduledMinute)
        val now = System.currentTimeMillis()
        
        // 防重：检查是否在短时间内重复触发相同的推送
        synchronized(recentExecutions) {
            val lastExecution = recentExecutions[pushKey]
            if (lastExecution != null && (now - lastExecution) < DUPLICATE_THRESHOLD_SECONDS * 1000) {
                Log.w(TAG, "Duplicate push detected for $pushKey, skipping (last: ${now - lastExecution}ms ago)")
                return
            }
            recentExecutions[pushKey] = now
            
            // 清理超过阈值的旧记录
            recentExecutions.entries.removeIf { (now - it.value) > DUPLICATE_THRESHOLD_SECONDS * 1000 }
        }
        
        Log.i(TAG, "Executing push: $scheduledHour:$scheduledMinute")

        try {
            val pushSettings = calendarStore.getPushSettings()
            if (!pushSettings.enabled) {
                Log.i(TAG, "Push is disabled, skipping")
                return
            }

            val settings = settingsStore.settingsFlowRaw.first()
            val date = LocalDate.now()
            val scheduledTime = LocalDateTime.of(date, LocalTime.of(scheduledHour, scheduledMinute))
            val actualTime = LocalDateTime.now()

            // 防重：检查当天是否已有相同 scheduledTime 的推送消息
            val calendarData = calendarStore.getCalendarData()
            val dayData = calendarData.getDay(date)
            val alreadyPushed = dayData.messages.any { msg ->
                msg.isPushed && msg.scheduledTime == scheduledTime.toString()
            }
            if (alreadyPushed) {
                Log.w(TAG, "Push already exists for $scheduledTime, skipping")
                return
            }

            val generatedContent: String
            if (pushSettings.contentSource == PushContentSource.FIXED_TEXT) {
                // 固定文案模式：完全不联网、不产生任何 API 费用
                val picked = pickFixedText(pushSettings)
                if (picked == null) {
                    Log.w(TAG, "Fixed text mode but no texts configured")
                    sendFailureNotification(reason = "固定文案列表是空的，先去推送设置里加几条")
                    return
                }
                generatedContent = picked
                Log.i(TAG, "Using fixed text push (no API call)")
            } else {
                // 获取当前使用的模型（作为 fallback）
                val fallbackModelId = settings.chatModelId

                var content = ""
                var generationError: Throwable? = null
                pushMessageGenerator.generatePushMessage(
                    settings = settings,
                    pushSettings = pushSettings,
                    date = date,
                    scheduledTime = scheduledTime,
                    actualTime = actualTime,
                    fallbackModelId = fallbackModelId,
                ).catch { e ->
                    Log.e(TAG, "Failed to generate push message", e)
                    generationError = e
                }.collect { chunk ->
                    content = chunk
                }

                // 失败必须让使用者看见：API 已经计费，静默退出等于钱白花且无从排查
                if (content.isBlank()) {
                    Log.w(TAG, "Generated push message is empty", generationError)
                    sendFailureNotification(
                        reason = generationError?.message?.takeIf { it.isNotBlank() }
                            ?: "模型没有返回内容"
                    )
                    return
                }
                generatedContent = content
            }

            // 插入推送消息到日历（事务内去重，防止并发插入）
            val pushMessage = Message(
                role = MessageRole.ASSISTANT,
                content = generatedContent,
                createdAt = actualTime.toString(),
                isPushed = true,
                scheduledTime = scheduledTime.toString(),
                actualTime = actualTime.toString(),
            )

            var inserted = false
            calendarStore.updateCalendarData { calendarData ->
                val dayData = calendarData.getDay(date)
                // 事务内再次检查：是否已有相同 scheduledTime 的推送
                val alreadyExists = dayData.messages.any { msg ->
                    msg.isPushed && msg.scheduledTime == scheduledTime.toString()
                }
                if (alreadyExists) {
                    Log.w(TAG, "Push already exists in transaction for $scheduledTime, skipping insert")
                    return@updateCalendarData calendarData
                }
                inserted = true
                val updatedDay = dayData.copy(
                    messages = dayData.messages + pushMessage
                )
                calendarData.updateDay(updatedDay)
            }

            if (!inserted) {
                Log.w(TAG, "Push was not inserted (duplicate detected in transaction)")
                return
            }

            Log.i(TAG, "Push message inserted to calendar: ${generatedContent.take(50)}...")

            // 发送通知
            sendNotification(pushSettings, generatedContent, date)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle push triggered", e)
            sendFailureNotification(reason = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName.orEmpty())
        }
    }

    /**
     * 固定文案模式下挑一条文案。不联网、不调模型。
     *
     * 顺序模式会把游标往后推一格并落盘，所以每次推送拿到的是下一条。
     * 返回 null 表示使用者还没配任何文案。
     */
    private suspend fun pickFixedText(pushSettings: PushSettings): String? {
        val texts = pushSettings.fixedTexts.filter { it.isNotBlank() }
        if (texts.isEmpty()) return null

        return when (pushSettings.fixedTextPickMode) {
            FixedTextPickMode.RANDOM -> texts.random()
            FixedTextPickMode.SEQUENTIAL -> {
                // 游标可能因为使用者删过文案而越界，取模兜底
                val index = pushSettings.fixedTextCursor.mod(texts.size)
                val picked = texts[index]
                val nextCursor = (index + 1).mod(texts.size)
                runCatching {
                    calendarStore.savePushSettings(
                        calendarStore.getPushSettings().copy(fixedTextCursor = nextCursor)
                    )
                }.onFailure { Log.e(TAG, "Failed to advance fixed text cursor", it) }
                picked
            }
        }
    }

    /**
     * 推送失败时发一条可见通知。
     * 生成失败往往已经发生过计费，静默吞掉会让人以为"根本没触发"，无从排查。
     */
    private fun sendFailureNotification(reason: String) {
        val intent = Intent(application, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            application,
            PUSH_FAILURE_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        NotificationUtil.notify(
            context = application,
            channelId = me.rerere.rikkahub.PUSH_NOTIFICATION_CHANNEL_ID,
            notificationId = PUSH_FAILURE_NOTIFICATION_ID,
        ) {
            this.title = "推送生成失败"
            this.content = reason.take(120)
            autoCancel = true
            contentIntent = pendingIntent
        }
        Log.i(TAG, "Push failure notification sent: $reason")
    }

    private fun sendNotification(pushSettings: PushSettings, messageContent: String, date: LocalDate) {
        // 根据配置决定通知显示的内容
        val notificationContent = when (pushSettings.notificationContentMode) {
            NotificationContentMode.PLACEHOLDER -> 
                pushSettings.notificationPlaceholder
            NotificationContentMode.MESSAGE_CONTENT -> 
                messageContent.take(80).let { if (messageContent.length > 80) "$it..." else it }
        }
        
        // 创建点击通知后跳转到日历页的 Intent
        val intent = Intent(application, RouteActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("navigate_to", "calendar")
            putExtra("calendar_date", date.toString())
        }

        val pendingIntent = PendingIntent.getActivity(
            application,
            PUSH_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val success = NotificationUtil.notify(
            context = application,
            channelId = me.rerere.rikkahub.PUSH_NOTIFICATION_CHANNEL_ID,
            notificationId = PUSH_NOTIFICATION_ID,
        ) {
            this.title = pushSettings.notificationTitle
            this.content = notificationContent
            autoCancel = true
            contentIntent = pendingIntent
        }

        if (success) {
            Log.i(TAG, "Push notification sent")
        } else {
            Log.w(TAG, "Failed to send push notification (permission denied?)")
        }
    }
}
