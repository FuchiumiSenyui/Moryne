package me.rerere.rikkahub.ui.pages.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.data.calendar.CalendarData
import me.rerere.rikkahub.data.calendar.SpecialDate
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun CalendarView(
    currentMonth: YearMonth,
    selectedDate: LocalDate,
    calendarData: CalendarData,
    onSelectDate: (LocalDate) -> Unit,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 月份导航栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "<",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.clickable { onPrevMonth() },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = currentMonth.format(DateTimeFormatter.ofPattern("yyyy年MM月")),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = ">",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.clickable { onNextMonth() },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 星期标题
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            DayOfWeek.values().forEach { dayOfWeek ->
                Text(
                    text = dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 日期网格
        val daysInMonth = currentMonth.lengthOfMonth()
        val firstDayOfMonth = currentMonth.atDay(1).dayOfWeek
        val startPadding = firstDayOfMonth.ordinal

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            var dayCounter = 1
            var weekCounter = 0

            while (dayCounter <= daysInMonth) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    for (dayInWeek in 0..6) {
                        val isPadding = weekCounter == 0 && dayInWeek < startPadding
                        val isTrailing = !isPadding && dayCounter > daysInMonth
                        val isCurrentDay = !isPadding && !isTrailing && dayCounter == LocalDate.now().dayOfMonth && currentMonth == YearMonth.from(LocalDate.now())

                        if (isPadding || isTrailing) {
                            Box(modifier = Modifier.weight(1f).aspectRatio(1f))
                        } else {
                            val date = currentMonth.atDay(dayCounter)
                            val isSelected = date == selectedDate

                            // 获取所有标注（预设 + 自定义）
                            val specialDates = SpecialDate.findByDay(date.monthValue, date.dayOfMonth)
                            val customAnnotation = calendarData.getCustomAnnotation(date)
                            val hasAnnotation = specialDates.isNotEmpty() || customAnnotation != null
                            val hasCountdownDate = specialDates.any { it.isCountdown }

                            DateCell(
                                modifier = Modifier.weight(1f),
                                day = dayCounter,
                                isSelected = isSelected,
                                isCurrentDay = isCurrentDay,
                                hasAnnotation = hasAnnotation,
                                hasCountdownDate = hasCountdownDate,
                                onSelect = { onSelectDate(date) },
                            )

                            dayCounter++
                        }
                    }
                }
                weekCounter++
            }
        }
    }
}

@Composable
private fun DateCell(
    modifier: Modifier = Modifier,
    day: Int,
    isSelected: Boolean,
    isCurrentDay: Boolean,
    hasAnnotation: Boolean,
    hasCountdownDate: Boolean,
    onSelect: () -> Unit,
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)

            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onSelect)
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 日期数字
            Text(
                text = day.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 14.sp,
                color = when {
                    isSelected -> MaterialTheme.colorScheme.onPrimary
                    isCurrentDay -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            // 特殊日期标记
            if (hasAnnotation) {
                Spacer(modifier = Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(
                            if (hasCountdownDate) {
                                // 倒计时日期用紫色
                                Color(0xFF9B8AC9)
                            } else {
                                // 普通标注用次要色
                                MaterialTheme.colorScheme.secondary
                            }
                        ),
                )
            }
        }

        // 选中背景
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
            )
        }
    }
}

