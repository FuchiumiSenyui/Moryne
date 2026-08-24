package me.rerere.rikkahub.ui.pages.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.calendar.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

class CalendarVM(
    private val calendarStore: CalendarStore,
    private val settingsStore: SettingsStore,
    private val diaryChatGenerator: DiaryChatGenerator,
) : ViewModel() {

    private val _calendarData = MutableStateFlow(CalendarData())
    val calendarData = _calendarData.asStateFlow()

    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate = _selectedDate.asStateFlow()

    // 日历对话配置
    val diarySettings = calendarStore.diarySettingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, DiarySettings.DEFAULT)

    // 推送配置
    val pushSettings = calendarStore.pushSettingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, PushSettings.DEFAULT)

    /** 正在生成回复 */
    private val _replying = MutableStateFlow(false)
    val replying = _replying.asStateFlow()

    /** 生成中的流式文本，未落盘 */
    private val _streamingReply = MutableStateFlow("")
    val streamingReply = _streamingReply.asStateFlow()

    /** 生成失败提示 */
    private val _replyError = MutableStateFlow<String?>(null)
    val replyError = _replyError.asStateFlow()

    private var replyJob: Job? = null

    init {
        viewModelScope.launch {
            calendarStore.calendarDataFlow.collect { data ->
                _calendarData.value = data
            }
        }
    }

    fun selectDate(date: LocalDate) {
        _selectedDate.value = date
    }

    /** 写入一条留言并落盘，返回写入后的当天数据 */
    private suspend fun appendMessage(
        date: LocalDate,
        role: MessageRole,
        content: String,
    ): DayData {
        val dayData = _calendarData.value.getDay(date)
        val updatedDay = dayData.copy(
            messages = dayData.messages + Message(role = role, content = content)
        )
        val newData = _calendarData.value.updateDay(updatedDay)
        _calendarData.value = newData
        calendarStore.saveCalendarData(newData)
        return updatedDay
    }

    fun updateDiarySettings(settings: DiarySettings) {
        viewModelScope.launch {
            calendarStore.saveDiarySettings(settings)
        }
    }

    fun updatePushSettings(settings: PushSettings) {
        viewModelScope.launch {
            calendarStore.savePushSettings(settings)
        }
    }

    fun dismissReplyError() {
        _replyError.value = null
    }

    /**
     * 发送一条留言，随后由日历对话回复。
     * 开关关闭时只记录，不请求。
     */
    fun sendDiaryMessage(content: String) {
        val text = content.trim()
        if (text.isEmpty() || _replying.value) return
        val date = _selectedDate.value
        if (date != LocalDate.now()) return

        replyJob?.cancel()
        replyJob = viewModelScope.launch {
            _replyError.value = null
            appendMessage(date, MessageRole.USER, text)

            val diary = calendarStore.getDiarySettings()
            if (!diary.enabled) return@launch

            requestReply(date, diary)
        }
    }

    /** 不发新内容，直接让日历对话说话（或重试） */
    fun requestDiaryReply() {
        if (_replying.value) return
        val date = _selectedDate.value
        if (date != LocalDate.now()) return
        replyJob?.cancel()
        replyJob = viewModelScope.launch {
            _replyError.value = null
            val diary = calendarStore.getDiarySettings()
            if (!diary.enabled) {
                _replyError.value = "日历对话未启用"
                return@launch
            }
            requestReply(date, diary)
        }
    }

    private suspend fun requestReply(date: LocalDate, diary: DiarySettings) {
        _replying.value = true
        _streamingReply.value = ""
        try {
            val settings = settingsStore.settingsFlow.first()
            var finalReply = ""
            diaryChatGenerator.reply(
                settings = settings,
                diarySettings = diary,
                dayData = _calendarData.value.getDay(date),
                fallbackModelId = settings.chatModelId,
            ).collect { text ->
                finalReply = text
                _streamingReply.value = text
            }
            if (finalReply.isNotBlank()) {
                appendMessage(date, MessageRole.ASSISTANT, finalReply.trim())
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _replyError.value = e.message ?: "生成失败"
        } finally {
            _streamingReply.value = ""
            _replying.value = false
        }
    }

    fun cancelDiaryReply() {
        replyJob?.cancel()
        replyJob = null
        _streamingReply.value = ""
        _replying.value = false
    }

    /** 删除某条留言 */
    fun deleteMessage(message: Message) {
        if (_selectedDate.value != LocalDate.now()) return
        viewModelScope.launch {
            val date = _selectedDate.value
            val dayData = _calendarData.value.getDay(date)
            val updatedDay = dayData.copy(
                messages = dayData.messages.filterNot {
                    it.createdAt == message.createdAt && it.content == message.content
                }
            )
            val newData = _calendarData.value.updateDay(updatedDay)
            _calendarData.value = newData
            calendarStore.saveCalendarData(newData)
        }
    }

    /** 开启新话题：之后的对话不再带之前的上下文，旧记录仍然可见 */
    fun startNewTopic() {
        if (_selectedDate.value != LocalDate.now()) return
        viewModelScope.launch {
            val date = _selectedDate.value
            val dayData = _calendarData.value.getDay(date)
            val marker = java.time.LocalDateTime.now().toString()
            val updatedDay = dayData.copy(contextStartAt = marker)
            val newData = _calendarData.value.updateDay(updatedDay)
            _calendarData.value = newData
            calendarStore.saveCalendarData(newData)
        }
    }

    /** 编辑某条留言的内容（用旧值定位） */

    fun editMessage(message: Message, newContent: String) {
        val text = newContent.trim()
        if (text.isEmpty() || _selectedDate.value != LocalDate.now()) return
        viewModelScope.launch {
            val date = _selectedDate.value
            val dayData = _calendarData.value.getDay(date)
            val updatedDay = dayData.copy(
                messages = dayData.messages.map {
                    if (it.createdAt == message.createdAt && it.content == message.content) {
                        it.copy(content = text)
                    } else {
                        it
                    }
                }
            )
            val newData = _calendarData.value.updateDay(updatedDay)
            _calendarData.value = newData
            calendarStore.saveCalendarData(newData)
        }
    }

    /**
     * 编辑自己的留言后当成重新发一次：
     * 把这条之后的所有消息截掉，再让日历对话针对新内容重新回复。
     */
    fun editMessageAndResend(message: Message, newContent: String) {
        val text = newContent.trim()
        if (text.isEmpty() || _replying.value || _selectedDate.value != LocalDate.now()) return
        replyJob?.cancel()
        replyJob = viewModelScope.launch {
            _replyError.value = null
            val date = _selectedDate.value
            val dayData = _calendarData.value.getDay(date)
            val index = dayData.messages.indexOfFirst {
                it.createdAt == message.createdAt && it.content == message.content
            }
            if (index < 0) return@launch

            // 保留这条之前的消息 + 这条（内容换成新的），之后的全部丢掉
            val kept = dayData.messages.take(index) +
                dayData.messages[index].copy(content = text)
            val updatedDay = dayData.copy(messages = kept)
            val newData = _calendarData.value.updateDay(updatedDay)
            _calendarData.value = newData
            calendarStore.saveCalendarData(newData)

            val diary = calendarStore.getDiarySettings()
            if (!diary.enabled) {
                _replyError.value = "日历对话未启用"
                return@launch
            }
            requestReply(date, diary)
        }
    }

    /** 重新生成某条 AI 的回复：删掉它，再让日历对话重新说 */

    fun regenerateMessage(message: Message) {
        if (_replying.value || _selectedDate.value != LocalDate.now()) return
        replyJob?.cancel()
        replyJob = viewModelScope.launch {
            _replyError.value = null
            val date = _selectedDate.value
            val dayData = _calendarData.value.getDay(date)
            val updatedDay = dayData.copy(
                messages = dayData.messages.filterNot {
                    it.createdAt == message.createdAt && it.content == message.content
                }
            )
            val newData = _calendarData.value.updateDay(updatedDay)
            _calendarData.value = newData
            calendarStore.saveCalendarData(newData)

            val diary = calendarStore.getDiarySettings()
            if (!diary.enabled) {
                _replyError.value = "日历对话未启用"
                return@launch
            }
            requestReply(date, diary)
        }
    }

    // 添加自定义标注
    fun addCustomAnnotation(title: String, note: String) {

        viewModelScope.launch {
            val date = _selectedDate.value
            val annotation = DateAnnotation(
                id = UUID.randomUUID().toString(),
                title = title,
                note = note,
                isCountdown = false,
            )
            val newData = _calendarData.value.addCustomAnnotation(date, annotation)
            _calendarData.value = newData
            calendarStore.saveCalendarData(newData)
        }
    }

    // 删除自定义标注
    fun removeCustomAnnotation() {
        viewModelScope.launch {
            val date = _selectedDate.value
            val newData = _calendarData.value.removeCustomAnnotation(date)
            _calendarData.value = newData
            calendarStore.saveCalendarData(newData)
        }
    }

    // 获取日期的所有标注（预设 + 自定义）
    fun getDateAnnotations(date: LocalDate): List<Any> {
        val annotations = mutableListOf<Any>()
        
        // 添加预设特殊日期
        val specialDates = SpecialDate.findByDay(date.monthValue, date.dayOfMonth)
        annotations.addAll(specialDates)
        
        // 添加自定义标注
        val customAnnotation = _calendarData.value.getCustomAnnotation(date)
        if (customAnnotation != null) {
            annotations.add(customAnnotation)
        }
        
        return annotations
    }

    // 获取纪念日数据（所有预设纪念日，可逐条切正数日/倒计时）
    fun getCountdownData(): List<CountdownInfo> {
        val data = _calendarData.value
        return SpecialDate.PRESETS.map { specialDate ->
            val daysUntil = specialDate.daysUntilNextAnniversary()
            val nextDate = specialDate.nextAnniversary()
            // 周年数按「下一个纪念日」所在年份算，否则今年月日还没到时会多算一年
            val anniversary = specialDate.anniversaryCount(nextDate)

            CountdownInfo(
                date = specialDate.date,
                title = specialDate.title,
                note = specialDate.note,
                daysUntil = daysUntil,
                daysSince = specialDate.daysSince(),
                anniversary = anniversary,
                nextDate = nextDate,
                isToday = daysUntil == 0L,
                isCountdown = data.isCountdownMode(specialDate),
            )
        }
    }

    /** 切换某个纪念日的展示模式（正数日 / 倒计时），落盘 */
    fun toggleCountdownMode(date: String) {
        viewModelScope.launch {
            val specialDate = SpecialDate.PRESETS.find { it.date == date } ?: return@launch
            val newData = _calendarData.value.toggleCountdownMode(specialDate)
            _calendarData.value = newData
            calendarStore.saveCalendarData(newData)
        }
    }


    fun exportToJson(): String {
        val export = CalendarExport(data = _calendarData.value)
        return JsonInstant.encodeToString(export)
    }

    fun exportToMarkdown(): String {
        val data = _calendarData.value
        val sb = StringBuilder()
        sb.append("# 日历记录\n\n")
        sb.append("导出时间: ${LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"))}\n\n")
        
        // 添加倒计时信息
        val countdowns = getCountdownData()
        if (countdowns.isNotEmpty()) {
            sb.append("## 重要纪念日\n\n")
            countdowns.forEach {
                if (it.isCountdown) {
                    sb.append("- ${it.title}: 还有 ${it.daysUntil} 天（第 ${it.anniversary} 周年）\n")
                } else {
                    sb.append("- ${it.title}: 已经第 ${it.daysSince} 天\n")
                }
            }

            sb.append("\n")
        }
        
        sb.append("## 每日记录\n\n")
        
        val sortedDays = data.days.values.sortedBy { it.localDate() }
        sortedDays.forEach { dayData ->
            val date = dayData.localDate()
            sb.append("### ${date.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"))}\n\n")
            
            // 标注信息
            val annotations = getDateAnnotations(date)
            if (annotations.isNotEmpty()) {
                sb.append("**标注:**\n")
                annotations.forEach { annotation ->
                    when (annotation) {
                        is SpecialDate -> sb.append("- ${annotation.title}: ${annotation.note}\n")
                        is DateAnnotation -> sb.append("- ${annotation.title}: ${annotation.note}\n")
                    }
                }
                sb.append("\n")
            }
            
            // 留言
            if (dayData.messages.isNotEmpty()) {
                sb.append("**留言:**\n\n")
                dayData.messages.forEach { msg ->
                    val roleName = if (msg.role == MessageRole.USER) "我" else "AI"
                    sb.append("- $roleName: ${msg.content}\n")
                }
                sb.append("\n")
            }
        }
        
        return sb.toString()
    }
}

// 纪念日信息
data class CountdownInfo(
    val date: String,          // yyyy-MM-dd，用于切换模式
    val title: String,
    val note: String,
    val daysUntil: Long,       // 距下一个周年还有多少天
    val daysSince: Long,       // 从当天起已过多少天（正数日）
    val anniversary: Int,
    val nextDate: LocalDate,
    val isToday: Boolean,
    val isCountdown: Boolean,  // true = 显示倒计时，false = 显示正数日
)

