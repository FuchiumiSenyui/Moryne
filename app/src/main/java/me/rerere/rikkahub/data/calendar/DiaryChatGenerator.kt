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
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.rikkahub.data.ai.tools.buildCalendarTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import java.time.LocalDate

/**
 * 日历对话的生成器。
 *
 * 与聊天页的生成链路隔离：
 * - 不走助手的 system prompt / 世界书
 * - 不带独白与思考过程
 * - 只做一件事：读当天的留言上下文，回一段简短批注
 */
class DiaryChatGenerator(
    private val providerManager: ProviderManager,
    private val json: Json,
    private val calendarStore: CalendarStore,
) {
    /**
     * 针对某一天生成回复，流式输出累积文本。
     *
     * @param dayData 当天数据（含历史留言）
     * @param fallbackModelId 当 DiarySettings 未指定模型时使用的模型（一般是聊天页当前模型）
     */
    fun reply(
        settings: Settings,
        diarySettings: DiarySettings,
        dayData: DayData,
        fallbackModelId: kotlin.uuid.Uuid?,
        checkInSettings: CheckInSettings = CheckInSettings.DEFAULT,
    ): Flow<String> = flow {
        val modelId = diarySettings.resolvedModelId() ?: fallbackModelId
        ?: error("未选择模型")
        val model = settings.providers.findModelById(modelId)
            ?: error("模型不存在")
        val provider = model.findProvider(settings.providers)
            ?: error("模型对应的供应商不存在")

        val providerHandler = providerManager.getProviderByType(provider)

        var messages = buildMessages(diarySettings, dayData, checkInSettings)
        var reply = ""

        val tools = buildCalendarTools(json, calendarStore)
        val streamChunkHandler = StreamChunkHandler(model)
        repeat(MAX_TOOL_STEPS) {
            providerHandler.streamText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(model = model, tools = tools),
            ).collect { chunk ->
                messages = streamChunkHandler.handle(messages, chunk)
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
        error("日历工具调用超过 $MAX_TOOL_STEPS 步")
    }.flowOn(Dispatchers.IO)

    private fun buildMessages(
        diarySettings: DiarySettings,
        dayData: DayData,
        checkInSettings: CheckInSettings,
    ): List<UIMessage> {
        val result = mutableListOf<UIMessage>()

        val systemPrompt = buildString {
            if (diarySettings.systemPrompt.isNotBlank()) {
                appendLine(diarySettings.systemPrompt)
                appendLine()
            }
            appendLine(buildDayContext(dayData, checkInSettings))
        }.trim()

        result.add(UIMessage.system(systemPrompt))

        // 同一天内的往来留言作为多轮历史
        // 「新话题」之后的消息才进上下文
        val inContext = dayData.contextStartAt?.let { start ->
            dayData.messages.filter { it.createdAt >= start }
        } ?: dayData.messages
        val history = inContext.takeLast(diarySettings.historyLimit)

        history.forEach { msg ->
            when (msg.role) {
                MessageRole.USER -> result.add(UIMessage.user(msg.content))
                MessageRole.ASSISTANT -> result.add(UIMessage.assistant(msg.content))
            }
        }

        // 兜底：当天还没有用户留言时（主动留言场景），给一句触发指令
        if (history.none { it.role == MessageRole.USER }) {
            result.add(UIMessage.user("[今天还没有新的记录。主动留一句话。]"))
        }

        return result
    }

    /** 当天的客观状态，作为上下文交给模型 */
    private fun buildDayContext(dayData: DayData, checkInSettings: CheckInSettings): String = buildString {
        val date = runCatching { dayData.localDate() }.getOrElse { LocalDate.now() }
        val now = java.time.LocalDateTime.now()
        val weekday = now.dayOfWeek.getDisplayName(
            java.time.format.TextStyle.FULL,
            java.util.Locale.CHINESE
        )
        appendLine("## 今日日历状态")
        appendLine(
            "当前时间：${now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}（$weekday）"
        )
        if (date != now.toLocalDate()) {
            appendLine("正在翻看的是过去的这一天：$date")
        }

        dayData.annotation?.let { annotation ->
            if (annotation.title.isNotBlank()) {
                appendLine("今日标注：${annotation.title}${
                    if (annotation.note.isNotBlank()) "（${annotation.note}）" else ""
                }")
            }
        }

        // 打卡状态
        if (checkInSettings.items.isNotEmpty()) {
            appendLine()
            appendLine("## 每日项目")
            checkInSettings.items.forEach { item ->
                val record = dayData.checkIns.find { it.itemId == item.id }
                val status = if (record?.completed == true) "已完成" else "未完成"
                val note = record?.note?.takeIf { it.isNotBlank() }
                val noteText = if (note != null) "（备注：$note）" else ""
                appendLine("- ${item.name}：$status$noteText")
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
         * 日历不展示思考过程：把成对的思考块删掉，
         * 未闭合的开标签直接从标签处截断（否则流式时会闪一段思考再消失）。
         */
        internal fun sanitize(raw: String): String =
            raw.replace(THINK_BLOCK, "")
                .replace(THINK_OPEN_TAIL, "")
                .trim()
    }
}


