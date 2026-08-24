package me.rerere.rikkahub.data.calendar

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.rikkahub.data.ai.tools.buildCalendarTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 推送消息生成器
 *
 * 与 DiaryChatGenerator 同一套模式，但用推送专用的提示词。
 * 只读日历：写入动作会绕过推送防重，导致同一条推送重复进日历。
 */
class PushMessageGenerator(
    private val providerManager: ProviderManager,
    private val json: Json,
    private val calendarStore: CalendarStore,
) {
    /**
     * 生成推送消息
     * 
     * @param settings 全局设置
     * @param pushSettings 推送配置
     * @param date 推送日期
     * @param scheduledTime 原定推送时间
     * @param actualTime 实际推送时间（第二阶段支持延迟）
     * @param fallbackModelId 当推送未指定模型时使用的模型
     */
    fun generatePushMessage(
        settings: Settings,
        pushSettings: PushSettings,
        date: LocalDate,
        scheduledTime: LocalDateTime,
        actualTime: LocalDateTime = scheduledTime,
        fallbackModelId: kotlin.uuid.Uuid?,
    ): Flow<String> = flow {
        // 推送复用日历对话的模型配置
        val diarySettings = calendarStore.getDiarySettings()
        val modelId = diarySettings.resolvedModelId() ?: fallbackModelId
            ?: error("未选择模型")
        val model = settings.providers.findModelById(modelId)
            ?: error("模型不存在")
        val provider = model.findProvider(settings.providers)
            ?: error("模型对应的供应商不存在")

        val providerHandler = providerManager.getProviderByType(provider)

        // 获取当天数据
        val calendarData = calendarStore.getCalendarData()
        val dayData = calendarData.getDay(date)

        var messages = buildMessages(pushSettings, dayData, scheduledTime, actualTime)
        var reply = ""

        // 推送场景强制只读：写入动作会绕过推送防重，导致同一条推送重复进日历
        val tools = buildCalendarTools(json, calendarStore, readOnly = true)

        repeat(MAX_TOOL_STEPS) {
            providerHandler.streamText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(model = model, tools = tools),
            ).collect { chunk ->
                messages = messages.handleMessageChunk(chunk)
                val text = sanitize(messages.lastOrNull()?.toText() ?: "")
                if (text.isNotBlank() && text != reply) {
                    reply = text
                    emit(reply)
                }
            }

            val calls = messages.lastOrNull()?.getTools()?.filter { !it.isExecuted }.orEmpty()
            if (calls.isEmpty()) return@flow

            val executed = calls.map { call ->
                runCatching {
                    val definition = tools.find { it.name == call.toolName }
                        ?: error("Tool ${call.toolName} not found")
                    val arguments = json.parseToJsonElement(call.input.ifBlank { "{}" })
                    call.copy(output = definition.execute(arguments))
                }.getOrElse { error ->
                    call.copy(output = listOf(UIMessagePart.Text("{\"error\":${json.encodeToString(error.message ?: "Tool execution failed")}}")))
                }
            }
            val last = messages.last()
            messages = messages.dropLast(1) + last.copy(
                parts = last.parts.map { part ->
                    if (part is UIMessagePart.Tool) {
                        executed.find { it.toolCallId == part.toolCallId } ?: part
                    } else part
                }
            )
        }
        error("推送工具调用超过 $MAX_TOOL_STEPS 步")
    }.flowOn(Dispatchers.IO)

    private fun buildMessages(
        pushSettings: PushSettings,
        dayData: DayData,
        scheduledTime: LocalDateTime,
        actualTime: LocalDateTime,
    ): List<UIMessage> {
        val result = mutableListOf<UIMessage>()

        val systemPrompt = buildString {
            if (pushSettings.pushPrompt.isNotBlank()) {
                appendLine(pushSettings.pushPrompt)
                appendLine()
            }
            
            appendLine(buildDayContext(dayData, scheduledTime, actualTime))
        }.trim()

        result.add(UIMessage.system(systemPrompt))

        // 当天的历史消息作为上下文
        val inContext = dayData.contextStartAt?.let { start ->
            dayData.messages.filter { it.createdAt >= start }
        } ?: dayData.messages
        val history = inContext.takeLast(20) // 限制历史条数

        history.forEach { msg ->
            when (msg.role) {
                MessageRole.USER -> result.add(UIMessage.user(msg.content))
                MessageRole.ASSISTANT -> result.add(UIMessage.assistant(msg.content))
            }
        }

        // 推送是主动发起的，不需要用户消息触发
        if (history.isEmpty() || history.last().role == MessageRole.ASSISTANT) {
            result.add(UIMessage.user("[定时推送触发，主动留言。]"))
        }

        return result
    }

    private fun buildDayContext(
        dayData: DayData,
        scheduledTime: LocalDateTime,
        actualTime: LocalDateTime,
    ): String = buildString {
        val date = runCatching { dayData.localDate() }.getOrElse { LocalDate.now() }
        val now = LocalDateTime.now()
        val weekday = now.dayOfWeek.getDisplayName(
            java.time.format.TextStyle.FULL,
            java.util.Locale.CHINESE
        )
        
        appendLine("## 今日状态")
        appendLine(
            "当前时间：${now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}（$weekday）"
        )
        
        // 第二阶段：如果有延迟，告知延迟信息
        // 注意：第一阶段 actualTime 总是等于 now，scheduledTime 是设定的时间
        // 只有当 actualTime 明显晚于 scheduledTime（超过 1 分钟）时才说明是延迟推送
        val delayMinutes = java.time.Duration.between(scheduledTime, actualTime).toMinutes()
        if (delayMinutes > 1) {
            val scheduledStr = scheduledTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            val actualStr = actualTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            appendLine("这条推送原计划在 $scheduledStr 发送，因检测到用户当时在线活跃，延迟至 $actualStr 发送。")
        }
        
        if (date != now.toLocalDate()) {
            appendLine("正在查看的是过去的这一天：$date")
        }

        dayData.annotation?.let { annotation ->
            if (annotation.title.isNotBlank()) {
                appendLine("今日标注：${annotation.title}${
                    if (annotation.note.isNotBlank()) "（${annotation.note}）" else ""
                }")
            }
        }
    }

    companion object {
        private const val MAX_TOOL_STEPS = 8
        
        /** 成对出现的思考标签 */
        private val THINK_BLOCK = Regex(
            "<(think|thinking|reasoning|monologue)>[\\s\\S]*?</\\1>",
            RegexOption.IGNORE_CASE
        )

        /** 流式过程中只有开标签、还没等到闭标签的情况 */
        private val THINK_OPEN_TAIL = Regex(
            "<(think|thinking|reasoning|monologue)>[\\s\\S]*$",
            RegexOption.IGNORE_CASE
        )

        /**
         * 推送不展示思考过程：把成对的思考块删掉，
         * 未闭合的开标签直接从标签处截断（否则流式时会闪一段思考再消失）。
         */
        internal fun sanitize(raw: String): String =
            raw.replace(THINK_BLOCK, "")
                .replace(THINK_OPEN_TAIL, "")
                .trim()
    }
}
