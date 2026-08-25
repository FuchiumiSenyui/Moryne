package me.rerere.rikkahub.data.calendar

import kotlinx.serialization.Serializable

/**
 * 推送来源：决定推送内容是模型生成的，还是使用者自己写死的固定文案。
 */
@Serializable
enum class PushContentSource {
    /** 调模型生成（走 pushPrompt，会产生 API 费用） */
    AI_GENERATED,

    /** 从固定文案列表里挑一条，完全不联网、不花钱 */
    FIXED_TEXT,
}

/**
 * 固定文案的挑选方式。
 */
@Serializable
enum class FixedTextPickMode {
    /** 随机挑一条 */
    RANDOM,

    /** 按列表顺序轮流（每次推送往后走一条，到底回头） */
    SEQUENTIAL,
}

/**
 * 推送配置
 */
@Serializable
data class PushSettings(
    /** 推送总开关 */
    val enabled: Boolean = false,

    /** 推送内容来源：模型生成 or 固定文案 */
    val contentSource: PushContentSource = PushContentSource.AI_GENERATED,

    /** 推送提示词（仅 AI_GENERATED 模式使用，独立于日历对话提示词） */
    val pushPrompt: String = DEFAULT_PUSH_PROMPT,

    /** 固定文案列表（仅 FIXED_TEXT 模式使用） */
    val fixedTexts: List<String> = emptyList(),

    /** 固定文案的挑选方式 */
    val fixedTextPickMode: FixedTextPickMode = FixedTextPickMode.RANDOM,

    /** 顺序模式下的游标，指向下一条要用的文案下标 */
    val fixedTextCursor: Int = 0,

    /** 通知标题（可自定义） */
    val notificationTitle: String = "新的留言",

    /** 推送时间列表 */
    val pushTimes: List<PushTime> = listOf(PushTime(hour = 8, minute = 0)),

    /** 前台服务通知文案（生成过程中显示，仅 AI_GENERATED 模式会出现） */
    val foregroundNotificationText: String = "正在生成推送消息",

    /** 推送完成通知内容模式 */
    val notificationContentMode: NotificationContentMode = NotificationContentMode.PLACEHOLDER,

    /** 占位符模式的自定义文案 */
    val notificationPlaceholder: String = "来看看",
) {
    companion object {
        /** 默认推送提示词只是个能用的起点，建议在设置页整段替换。 */
        const val DEFAULT_PUSH_PROMPT = """现在是定时推送时刻，你主动找使用者说话，不是等对方汇报。

怎么说：
读一下当天已有的记录，针对具体的事说，不要泛泛地问好。
可以是提醒、追问进度、关心状态，或者只是说一句想说的话。
不做空洞承诺，不写成客服话术。

格式：
只回一段话，两到四句。不分点，不加标题，不写动作描写。"""

        val DEFAULT = PushSettings()
    }
}

/**
 * 推送时间
 */
@Serializable
data class PushTime(
    val hour: Int,    // 0-23
    val minute: Int,  // 0-59
) {
    /** 今天这个时刻的 LocalDateTime */
    fun toTodayDateTime(): java.time.LocalDateTime =
        java.time.LocalDateTime.of(java.time.LocalDate.now(), java.time.LocalTime.of(hour, minute))

    /**
     * 下一次触发的时间戳（毫秒）。
     *
     * @param allowToday
     *   true（兜底补排 / 响完续订）：只要今天这个时刻还没过就排今天，不留边距。
     *   false（用户在设置里手动保存）：留 2 分钟安全边距，避免设置的时间≈当前时间导致立即触发。
     *
     *   边距只该用在「用户刚手动设了个时间」这一种场合。用在每次进程启动的补排上会反向咬人：
     *   在推送前 2 分钟内启动过一次 App，今天这一次就被顺延到明天了。
     */
    fun nextTriggerMillis(allowToday: Boolean = true): Long {
        val now = java.time.LocalDateTime.now()
        var target = toTodayDateTime()

        val threshold = if (allowToday) now else now.plusMinutes(2)
        if (!target.isAfter(threshold)) {
            target = target.plusDays(1)
        }

        return target.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    override fun toString(): String = String.format("%02d:%02d", hour, minute)
}

/**
 * 通知内容显示模式
 */
@Serializable
enum class NotificationContentMode {
    /** 占位符（显示自定义文案，不透露消息内容） */
    PLACEHOLDER,

    /** 消息内容（显示实际生成的消息摘要） */
    MESSAGE_CONTENT,
}
