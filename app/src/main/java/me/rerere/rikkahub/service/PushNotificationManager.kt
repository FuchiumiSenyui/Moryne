package me.rerere.rikkahub.service

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import me.rerere.rikkahub.data.calendar.PushTime
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

    // 补做闸门："yyyy-MM-dd@HH:mm"，每个时刻每天只补一次，防止生成失败后每次开 App 都重试
    private val catchUpAttempted = mutableSetOf<String>()

    // 防止重复启动监听器
    @Volatile
    private var isStarted = false

    fun start() {
        if (isStarted) {
            Log.w(TAG, "PushNotificationManager already started, skipping")
            return
        }
        isStarted = true
        Log.i(TAG, "PushNotificationManager started (push via foreground service only)")
        observeForegroundForCatchUp()
    }

    /**
     * 进入前台时补做今天错过的推送。
     *
     * 为什么挂在前台生命周期上、而不是直接在 Application.onCreate 里补：
     * Android 12+ 禁止从后台启动前台服务，而 `Application.onCreate` 期间进程仍算后台，
     * 在那儿调 startForegroundService 会被系统拒掉（抛
     * ForegroundServiceStartNotAllowedException）。ON_START 时已经确定在前台，可以合法启动。
     *
     * 重复保护由 executeMutex 和日历里的 isPushed/scheduledTime 判据兜着。
     */
    private fun observeForegroundForCatchUp() {
        val owner = ProcessLifecycleOwner.get()
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(lifecycleOwner: LifecycleOwner) {
                owner.lifecycleScope.launch {
                    runCatching {
                        val missed = findMissedPushToday() ?: return@runCatching

                        // 每个时刻每天只补一次。生成失败时日历里不会留记录，
                        // 没这道闸的话每开一次 App 就重试一次，白烧 API。
                        val attemptKey = "${LocalDate.now()}@$missed"
                        synchronized(catchUpAttempted) {
                            if (!catchUpAttempted.add(attemptKey)) {
                                Log.i(TAG, "Catch-up for $attemptKey already attempted, skipping")
                                return@runCatching
                            }
                        }

                        Log.i(TAG, "Foreground: found missed push today at $missed, catching up")
                        PushGenerationService.start(
                            context = application,
                            hour = missed.hour,
                            minute = missed.minute,
                            isCatchUp = true,
                        )
                    }.onFailure {
                        Log.e(TAG, "Catch-up on foreground failed", it)
                    }
                }
            }
        })
    }

    /**
     * 找出今天「已经过点但没推成」的推送时刻，用于补做。
     *
     * 判据复用防重那一套：日历里有 isPushed && scheduledTime == 该时刻 就算推过了。
     * 所以不需要额外落盘记录，也不会跟防重打架。
     *
     * 只返回**最近错过的那一个**：配了多个推送时间时，一次性全补会让她一开 App
     * 就收到好几条。
     *
     * @return 最近错过的时刻，没有则 null
     */
    suspend fun findMissedPushToday(): PushTime? {
        val pushSettings = calendarStore.getPushSettings()
        if (!pushSettings.enabled) return null

        val now = LocalDateTime.now()
        val date = LocalDate.now()
        val dayData = calendarStore.getCalendarData().getDay(date)

        return pushSettings.pushTimes
            .distinct()
            .filter { it.toTodayDateTime().isBefore(now) } // 今天已经过点的
            .filter { pushTime ->
                val scheduled = pushTime.toTodayDateTime().toString()
                dayData.messages.none { it.isPushed && it.scheduledTime == scheduled }
            }
            .maxByOrNull { it.toTodayDateTime() } // 最近的那一个
    }

    /**
     * 执行推送（公开方法，供 PushGenerationService 调用）
     *
     * @param isCatchUp 是否是补做（闹钟丢失后启动时补发），影响告知模型的延迟原因
     */
    suspend fun executePush(scheduledHour: Int, scheduledMinute: Int, isCatchUp: Boolean = false) {
        // 互斥锁：同一时间只能有一个 executePush 在运行
        executeMutex.withLock {
            doExecutePush(scheduledHour, scheduledMinute, isCatchUp)
        }
    }

    private suspend fun doExecutePush(
        scheduledHour: Int,
        scheduledMinute: Int,
        isCatchUp: Boolean = false,
    ) {
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
                // 只有 AI 模式才需要全局设置。这行原来在分支之前无条件执行，
                // 但 settingsFlowRaw 读的是整份应用配置（含所有供应商），是个很大的 JSON；
                // 闹钟冷启动时它慢一点或抛一次异常，就会把根本不需要它的
                // 固定文案推送一起弄死。
                val settings = settingsStore.settingsFlowRaw.first()

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
                    isCatchUp = isCatchUp,
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
