package me.rerere.rikkahub.data.calendar

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

// 时间戳统一用 ISO 字符串存储，避免 kotlinx.serialization 无法序列化 LocalDateTime
private fun nowIso(): String = LocalDateTime.now().toString()


// 留言角色
@Serializable
enum class MessageRole {
    USER,       // 使用者（左侧）
    ASSISTANT,  // AI（右侧）
}


// 留言
@Serializable
data class Message(
    val role: MessageRole,
    val content: String,
    val createdAt: String = nowIso(),
    val isPushed: Boolean = false,           // 是否为推送消息
    val scheduledTime: String? = null,       // 原定推送时间（ISO 格式）
    val actualTime: String? = null,          // 实际推送时间（延迟时不同于 scheduledTime）
)


// 日期标注
@Serializable
data class DateAnnotation(
    val id: String = "",
    val title: String = "",
    val note: String = "",
    val isCountdown: Boolean = false, // 是否需要倒计时
)

// 每日数据
@Serializable
data class DayData(
    val date: String, // yyyy-MM-dd
    val messages: List<Message> = emptyList(),
    val annotation: DateAnnotation? = null, // 自定义标注
    /** 新话题起点：从这条消息的 createdAt 起才算当前上下文，之前的只保留可见 */
    val contextStartAt: String? = null,
    /** 当日打卡记录 */
    val checkIns: List<CheckInRecord> = emptyList(),

) {
    companion object {
        fun fromDate(date: LocalDate): DayData = DayData(date = date.toString())
    }

    fun localDate(): LocalDate = LocalDate.parse(date)
}

// 特殊日期（预设）
@Serializable
data class SpecialDate(
    val date: String, // yyyy-MM-dd 完整日期
    val title: String,
    val note: String = "",
    val isCountdown: Boolean = false, // 是否需要倒计时
) {
    companion object {
        /**
         * 预设特殊日期。
         * 故意留空：纪念日属于个人数据，由使用者在日历里自己标注（DateAnnotation），
         * 不硬编码进包里。
         */
        val PRESETS = emptyList<SpecialDate>()

        // 获取需要倒计时的日期
        val COUNTDOWN_DATES = PRESETS.filter { it.isCountdown }

        // 根据日期查找特殊日期（匹配月日）
        fun findByDay(month: Int, day: Int): List<SpecialDate> {
            return PRESETS.filter {
                val date = LocalDate.parse(it.date)
                date.monthValue == month && date.dayOfMonth == day
            }
        }

        // 根据完整日期查找
        fun findByDate(date: LocalDate): SpecialDate? {
            return PRESETS.find { LocalDate.parse(it.date).isEqual(date) }
        }
    }

    fun localDate(): LocalDate = LocalDate.parse(date)

    // 计算距今的天数（正数表示已过去，负数表示还有多少天）
    fun daysSince(date: LocalDate = LocalDate.now()): Long {
        return ChronoUnit.DAYS.between(localDate(), date)
    }

    // 计算周年数
    fun anniversaryCount(date: LocalDate = LocalDate.now()): Int {
        return date.year - localDate().year
    }

    // 获取下一个纪念日日期
    fun nextAnniversary(date: LocalDate = LocalDate.now()): LocalDate {
        val currentYear = date.year
        var nextDate = localDate().withYear(currentYear)
        // 当天就是纪念日时要返回当天，否则 daysUntil 会变成 365、"今天"永远不显示
        if (nextDate.isBefore(date)) {
            nextDate = nextDate.plusYears(1)
        }
        return nextDate
    }

    // 计算到下一个纪念日还有多少天
    fun daysUntilNextAnniversary(date: LocalDate = LocalDate.now()): Long {
        val nextDate = nextAnniversary(date)
        return ChronoUnit.DAYS.between(date, nextDate)
    }
}

// 日历数据存储
@Serializable
data class CalendarData(
    val days: Map<String, DayData> = emptyMap(),
    val customAnnotations: Map<String, DateAnnotation> = emptyMap(), // 自定义标注
    // 纪念日展示模式：key 为纪念日日期(yyyy-MM-dd)，true = 显示倒计时，false = 显示正数日
    val countdownModes: Map<String, Boolean> = emptyMap(),
) {
    /** 该纪念日当前是否显示倒计时；没设过就用预设的 isCountdown */
    fun isCountdownMode(specialDate: SpecialDate): Boolean =
        countdownModes[specialDate.date] ?: specialDate.isCountdown

    /** 切换某个纪念日的展示模式 */
    fun toggleCountdownMode(specialDate: SpecialDate): CalendarData {
        val next = !isCountdownMode(specialDate)
        return copy(countdownModes = countdownModes + (specialDate.date to next))
    }


    fun getDay(date: LocalDate): DayData {
        val key = date.toString()
        return days[key] ?: DayData.fromDate(date)
    }

    fun updateDay(dayData: DayData): CalendarData {
        return copy(days = days + (dayData.date to dayData))
    }

    fun addCustomAnnotation(date: LocalDate, annotation: DateAnnotation): CalendarData {
        val key = date.toString()
        return copy(customAnnotations = customAnnotations + (key to annotation))
    }

    fun removeCustomAnnotation(date: LocalDate): CalendarData {
        val key = date.toString()
        return copy(customAnnotations = customAnnotations - key)
    }

    fun getCustomAnnotation(date: LocalDate): DateAnnotation? {
        val key = date.toString()
        return customAnnotations[key]
    }
}

// 导出数据格式
@Serializable
data class CalendarExport(
    val exportTime: String = nowIso(),
    val data: CalendarData,
)

