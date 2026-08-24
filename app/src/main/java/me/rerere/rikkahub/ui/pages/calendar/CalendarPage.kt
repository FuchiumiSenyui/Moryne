package me.rerere.rikkahub.ui.pages.calendar

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*


import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Message02

import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalSettings

import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter


@Composable
fun CalendarPage(vm: CalendarVM = koinViewModel()) {
    val calendarData by vm.calendarData.collectAsStateWithLifecycle()
    val selectedDate by vm.selectedDate.collectAsStateWithLifecycle()
    val replying by vm.replying.collectAsStateWithLifecycle()
    val streamingReply by vm.streamingReply.collectAsStateWithLifecycle()
    val replyError by vm.replyError.collectAsStateWithLifecycle()
    val diarySettings by vm.diarySettings.collectAsStateWithLifecycle()

    var currentMonth by remember { mutableStateOf(YearMonth.from(LocalDate.now())) }
    var showDetailSheet by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showDiaryDialog by remember { mutableStateOf(false) }
    var showPushDialog by remember { mutableStateOf(false) }
    // 0 = 日历（日历网格 + 当天对话），1 = 纪念日（倒计时卡片）
    var selectedTab by remember { mutableIntStateOf(0) }


    val scope = rememberCoroutineScope()
    val context = LocalContext.current


    Scaffold(
        topBar = {
            TopAppBar(

                title = {
                    // 栏高用普通 TopAppBar（矮），但标题字号手动指回大标题栏的尺寸
                    Text(
                        text = "日历",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                },

                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
                actions = {
                    TextButton(onClick = { showPushDialog = true }) {
                        Text("推送")
                    }
                    TextButton(onClick = { showDiaryDialog = true }) {
                        Text("对话")
                    }
                    IconButton(onClick = { showExportDialog = true }) {

                        Icon(
                            imageVector = HugeIcons.Message02,
                            contentDescription = "导出",
                        )

                    }
                },
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = CustomColors.topBarColors.containerColor,
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("日历") },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("纪念日") },
                )
            }

            if (selectedTab == 1) {
                // 纪念日栏：只放倒计时卡片
                // 注意：getCountdownData() 读的是 StateFlow 的 value，不是 Compose State，
                // 直接调用不会在 toggle 后重组（点了没反应的 bug）。用 remember(calendarData)
                // 把它绑到已 collect 的 calendarData 上，数据一变就重算。
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item {
                        val countdownData = remember(calendarData) { vm.getCountdownData() }
                        CountdownCards(
                            countdownData = countdownData,
                            onToggleMode = { vm.toggleCountdownMode(it) },
                        )
                    }
                }
            } else {

            LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 当前日期时间
            item {
                NowCard()
            }

            // 日历视图
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CustomColors.cardColorsOnSurfaceContainer,
                ) {
                    Box(
                        modifier = Modifier.padding(16.dp),
                    ) {
                        CalendarView(

                            currentMonth = currentMonth,
                            selectedDate = selectedDate,
                            calendarData = calendarData,
                            onSelectDate = { date ->
                                vm.selectDate(date)
                                showDetailSheet = true
                            },
                            onPrevMonth = {
                                currentMonth = currentMonth.minusMonths(1)
                            },
                            onNextMonth = {
                                currentMonth = currentMonth.plusMonths(1)
                            },
                        )
                    }
                }
            }

            // 当天对话（网格下方）
            item {
                DayChatSection(
                    date = selectedDate,
                    dayData = calendarData.getDay(selectedDate),
                    replying = replying,
                    streamingReply = streamingReply,
                    replyError = replyError,
                    diaryEnabled = diarySettings.enabled,
                    onSend = { vm.sendDiaryMessage(it) },
                    onRetry = { vm.requestDiaryReply() },
                    onDelete = { vm.deleteMessage(it) },
                    onEdit = { msg, text -> vm.editMessage(msg, text) },
                    onEditAndResend = { msg, text -> vm.editMessageAndResend(msg, text) },

                    onRegenerate = { vm.regenerateMessage(it) },
                    onNewTopic = { vm.startNewTopic() },
                )
            }

            // 说明文字
            item {
                Text(
                    text = "点击日期查看和添加标注；下方是当天的对话",

                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        }
        }
    }

    // 日期详情面板
    if (showDetailSheet) {
        DayDetailSheet(
            date = selectedDate,
            annotations = vm.getDateAnnotations(selectedDate),
            customAnnotation = calendarData.getCustomAnnotation(selectedDate),
            onAddAnnotation = { title, note -> vm.addCustomAnnotation(title, note) },
            onRemoveAnnotation = { vm.removeCustomAnnotation() },
            onDismiss = { showDetailSheet = false },
        )
    }

    // 导出对话框
    if (showExportDialog) {
        ExportDialog(
            onExportJson = {
                scope.launch {
                    exportToFile(context, vm.exportToJson(), "calendar_export.json", "application/json")
                }

                showExportDialog = false
            },
            onExportMarkdown = {
                scope.launch {
                    exportToFile(context, vm.exportToMarkdown(), "calendar_export.md", "text/markdown")
                }

                showExportDialog = false
            },
            onDismiss = { showExportDialog = false },
        )
    }

    // 日历对话设置
    if (showDiaryDialog) {
        DiarySettingsDialog(
            settings = diarySettings,
            onSave = { vm.updateDiarySettings(it) },
            onDismiss = { showDiaryDialog = false },
        )
    }

    // 推送设置
    if (showPushDialog) {
        val pushSettings by vm.pushSettings.collectAsStateWithLifecycle()
        PushSettingsDialog(
            settings = pushSettings,
            onSave = { vm.updatePushSettings(it) },
            onDismiss = { showPushDialog = false },
        )
    }
}

@Composable
private fun DiarySettingsDialog(
    settings: me.rerere.rikkahub.data.calendar.DiarySettings,
    onSave: (me.rerere.rikkahub.data.calendar.DiarySettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val appSettings = LocalSettings.current
    var enabled by remember { mutableStateOf(settings.enabled) }
    var prompt by remember { mutableStateOf(settings.systemPrompt) }
    var modelId by remember { mutableStateOf(settings.resolvedModelId()) }


    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("日历对话") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("留言时让 AI 回复")
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("模型")
                    ModelSelector(
                        modelId = modelId,
                        providers = appSettings.providers,
                        type = ModelType.CHAT,
                        allowClear = true,
                        onSelect = { model ->
                            // allowClear 时回传的是空 Model，用 modelId 是否为空判断清除
                            modelId = if (model.modelId.isBlank()) null else model.id
                        },

                    )
                }
                Text(
                    text = "留空则跟随聊天页当前使用的模型",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(

                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("提示词") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 320.dp),
                    minLines = 6,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    settings.copy(
                        enabled = enabled,
                        systemPrompt = prompt,
                        modelId = modelId?.toString(),
                    )
                )

                onDismiss()
            }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}


@Composable
private fun NowCard() {
    // 当前日期时间，纯数字文本，精确到秒，每秒刷新
    var now by remember { mutableStateOf(java.time.LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1_000L)
            now = java.time.LocalDateTime.now()
        }
    }
    val nowText = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) +
        " " + now.dayOfWeek.getDisplayName(
            java.time.format.TextStyle.SHORT,
            java.util.Locale.CHINESE
        )


    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = nowText,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}


@Composable
private fun CountdownCards(
    countdownData: List<CountdownInfo>,
    onToggleMode: (String) -> Unit,
) {

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "重要纪念日",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        
        if (countdownData.isEmpty()) {
            Text(
                text = "暂无倒计时",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            countdownData.forEach { countdown ->
                CountdownCard(
                    countdown = countdown,
                    onToggleMode = { onToggleMode(countdown.date) },
                )
            }

        }
    }
}

@Composable
private fun CountdownCard(
    countdown: CountdownInfo,
    onToggleMode: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = countdown.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (countdown.note.isNotBlank()) {
                    Text(
                        text = countdown.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 明确的切换按钮，不再靠"整张卡片可点"这种看不出来的交互
                AssistChip(
                    onClick = onToggleMode,
                    label = {
                        Text(
                            text = if (countdown.isCountdown) "倒计时 · 点这里切换" else "正数日 · 点这里切换",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            
            Column(
                horizontalAlignment = Alignment.End,
            ) {
                val mainText = when {
                    countdown.isToday -> "今天"
                    countdown.isCountdown -> "${countdown.daysUntil} 天"
                    else -> "${countdown.daysSince} 天"
                }
                Text(
                    text = mainText,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color(0xFF9B8AC9), // 冷紫色
                )
                // 副标题不要写死"在一起"，生日之类的条目套不上；正数日统一显示起算日期
                Text(
                    text = if (countdown.isCountdown) {
                        "距第 ${countdown.anniversary} 周年"
                    } else {
                        "自 ${countdown.date} 起"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )


            }
        }
    }
}

@Composable
private fun ExportDialog(
    onExportJson: () -> Unit,
    onExportMarkdown: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出") },
        text = { Text("选择导出格式") },
        confirmButton = {
            TextButton(onClick = onExportJson) {
                Text("JSON")
            }
        },
        dismissButton = {
            TextButton(onClick = onExportMarkdown) {
                Text("Markdown")
            }
        },
    )
}

private fun exportToFile(context: Context, content: String, fileName: String, mimeType: String) {
    val uri = createFileUri(context, fileName)

    uri?.let {
        context.contentResolver.openOutputStream(it)?.use { outputStream ->
            outputStream.write(content.toByteArray())
        }
        
        // 分享/保存文件
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        context.startActivity(Intent.createChooser(intent, "保存文件"))
    }
}

private fun createFileUri(context: Context, fileName: String): Uri? {
    // 简单实现，实际应用中需要使用 FileProvider
    return try {
        val file = java.io.File(context.externalCacheDir, fileName)
        file.writeText("") // 创建空文件
        Uri.fromFile(file)
    } catch (e: Exception) {
        null
    }
}
