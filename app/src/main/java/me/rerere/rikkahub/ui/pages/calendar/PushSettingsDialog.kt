package me.rerere.rikkahub.ui.pages.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.calendar.FixedTextPickMode
import me.rerere.rikkahub.data.calendar.NotificationContentMode
import me.rerere.rikkahub.data.calendar.PushContentSource
import me.rerere.rikkahub.data.calendar.PushSettings
import me.rerere.rikkahub.data.calendar.PushTime
import me.rerere.rikkahub.utils.NotificationUtil
import me.rerere.rikkahub.utils.PushScheduler
import me.rerere.rikkahub.service.PushGenerationService
import java.time.LocalTime

/**
 * 推送配置对话框
 */
@Composable
fun PushSettingsDialog(
    settings: PushSettings,
    onSave: (PushSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pushScheduler = remember { PushScheduler(context) }

    var enabled by remember { mutableStateOf(settings.enabled) }
    var pushPrompt by remember { mutableStateOf(settings.pushPrompt) }
    var contentSource by remember { mutableStateOf(settings.contentSource) }
    var fixedTexts by remember { mutableStateOf(settings.fixedTexts) }
    var fixedTextPickMode by remember { mutableStateOf(settings.fixedTextPickMode) }
    var notificationTitle by remember { mutableStateOf(settings.notificationTitle) }
    var pushTimes by remember { mutableStateOf(settings.pushTimes) }
    var foregroundNotificationText by remember { mutableStateOf(settings.foregroundNotificationText) }
    var notificationContentMode by remember { mutableStateOf(settings.notificationContentMode) }
    var notificationPlaceholder by remember { mutableStateOf(settings.notificationPlaceholder) }
    var showPermissionGuide by remember { mutableStateOf(false) }
    var showTimePickerDialog by remember { mutableStateOf(false) }
    var editingTimeIndex by remember { mutableIntStateOf(-1) }

    // 检查权限状态
    val hasNotification = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
        NotificationUtil.hasNotificationPermission(context)
    val hasAlarm = pushScheduler.canScheduleExactAlarms()
    val hasAllPermissions = hasNotification && hasAlarm

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("推送配置") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 推送总开关
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("启用推送")
                    Switch(
                        checked = enabled,
                        onCheckedChange = { newValue ->
                            if (newValue && !hasAllPermissions) {
                                // 开启时检查权限
                                showPermissionGuide = true
                            } else {
                                enabled = newValue
                            }
                        }
                    )
                }

                if (!hasAllPermissions && enabled) {
                    Text(
                        text = "⚠️ 权限未完全授予，推送可能无法正常工作",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { showPermissionGuide = true }) {
                        Text("查看权限设置")
                    }
                }

                Divider()

                // 推送时间配置
                Text(
                    text = "推送时间",
                    style = MaterialTheme.typography.titleSmall,
                )

                pushTimes.forEachIndexed { index, time ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(time.toString())
                        Row {
                            TextButton(onClick = {
                                editingTimeIndex = index
                                showTimePickerDialog = true
                            }) {
                                Text("修改")
                            }
                            if (pushTimes.size > 1) {
                                TextButton(onClick = {
                                    pushTimes = pushTimes.filterIndexed { i, _ -> i != index }
                                }) {
                                    Text("删除")
                                }
                            }
                        }
                    }
                }

                TextButton(onClick = {
                    editingTimeIndex = -1
                    showTimePickerDialog = true
                }) {
                    Text("+ 添加推送时间")
                }

                Divider()

                // 推送内容来源：模型生成 or 固定文案
                Text("推送内容来源", style = MaterialTheme.typography.titleSmall)
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { contentSource = PushContentSource.AI_GENERATED },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = contentSource == PushContentSource.AI_GENERATED,
                            onClick = { contentSource = PushContentSource.AI_GENERATED },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text("每次让模型生成")
                            Text(
                                text = "内容每次都不一样，会产生 API 费用",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { contentSource = PushContentSource.FIXED_TEXT },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = contentSource == PushContentSource.FIXED_TEXT,
                            onClick = { contentSource = PushContentSource.FIXED_TEXT },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text("用我写好的固定文案")
                            Text(
                                text = "完全不联网，不产生任何费用",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Divider()

                // 固定文案编辑区（只在固定文案模式下出现）
                if (contentSource == PushContentSource.FIXED_TEXT) {
                    Text("固定文案", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "每次推送会从这些文案里挑一条",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    fixedTexts.forEachIndexed { index, text ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                        ) {
                            OutlinedTextField(
                                value = text,
                                onValueChange = { newValue ->
                                    fixedTexts = fixedTexts.toMutableList().also { it[index] = newValue }
                                },
                                label = { Text("第 ${index + 1} 条") },
                                modifier = Modifier.weight(1f),
                                minLines = 1,
                                maxLines = 4,
                            )
                            IconButton(onClick = {
                                fixedTexts = fixedTexts.filterIndexed { i, _ -> i != index }
                            }) {
                                Text("删除")
                            }
                        }
                    }

                    TextButton(onClick = { fixedTexts = fixedTexts + "" }) {
                        Text("+ 添加一条文案")
                    }

                    if (fixedTexts.none { it.isNotBlank() }) {
                        Text(
                            text = "还没有任何文案。推送时间到了会发一条提醒你来填。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    // 挑选方式
                    Text("挑选方式", style = MaterialTheme.typography.titleSmall)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { fixedTextPickMode = FixedTextPickMode.RANDOM },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = fixedTextPickMode == FixedTextPickMode.RANDOM,
                            onClick = { fixedTextPickMode = FixedTextPickMode.RANDOM },
                        )
                        Text("随机挑一条", modifier = Modifier.weight(1f))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { fixedTextPickMode = FixedTextPickMode.SEQUENTIAL },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = fixedTextPickMode == FixedTextPickMode.SEQUENTIAL,
                            onClick = { fixedTextPickMode = FixedTextPickMode.SEQUENTIAL },
                        )
                        Text("按顺序轮流", modifier = Modifier.weight(1f))
                    }

                    Divider()
                }

                // 通知标题
                OutlinedTextField(
                    value = notificationTitle,
                    onValueChange = { notificationTitle = it },
                    label = { Text("通知标题") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                // 推送提示词（只有模型生成模式才用得上）
                if (contentSource == PushContentSource.AI_GENERATED) {
                    OutlinedTextField(
                        value = pushPrompt,
                        onValueChange = { pushPrompt = it },
                        label = { Text("推送提示词") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp, max = 400.dp),
                        minLines = 8,
                    )

                    Text(
                        text = "提示词告诉模型这是推送场景，独立于日历对话提示词",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Divider()
                }

                // 前台服务通知文案（固定文案模式几乎瞬间完成，用不到）
                if (contentSource == PushContentSource.AI_GENERATED) {
                    OutlinedTextField(
                        value = foregroundNotificationText,
                        onValueChange = { foregroundNotificationText = it },
                        label = { Text("生成过程通知文案") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    Text(
                        text = "推送生成时前台服务显示的文字",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Divider()
                }

                // 推送完成通知内容配置
                Text(
                    text = "推送完成通知内容",
                    style = MaterialTheme.typography.titleSmall,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.RadioButton(
                        selected = notificationContentMode == NotificationContentMode.PLACEHOLDER,
                        onClick = { notificationContentMode = NotificationContentMode.PLACEHOLDER }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("占位符（不显示消息内容）")
                }

                if (notificationContentMode == NotificationContentMode.PLACEHOLDER) {
                    OutlinedTextField(
                        value = notificationPlaceholder,
                        onValueChange = { notificationPlaceholder = it },
                        label = { Text("占位符文案") },
                        modifier = Modifier.fillMaxWidth().padding(start = 32.dp),
                        singleLine = true,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.RadioButton(
                        selected = notificationContentMode == NotificationContentMode.MESSAGE_CONTENT,
                        onClick = { notificationContentMode = NotificationContentMode.MESSAGE_CONTENT }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("显示消息内容（前 80 字）")
                }

                Divider()

                // 调试按钮
                Button(
                    onClick = {
                        // 立即触发推送（调试用），使用前台服务保护
                        val now = java.time.LocalTime.now()
                        PushGenerationService.start(context, now.hour, now.minute)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("立即触发推送（调试）")
                }

                Text(
                    text = "点击后立即触发推送，用于测试提示词效果",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val newSettings = settings.copy(
                        enabled = enabled,
                        pushPrompt = pushPrompt,
                        contentSource = contentSource,
                        fixedTexts = fixedTexts.filter { it.isNotBlank() },
                        fixedTextPickMode = fixedTextPickMode,
                        notificationTitle = notificationTitle,
                        pushTimes = pushTimes,
                        foregroundNotificationText = foregroundNotificationText,
                        notificationContentMode = notificationContentMode,
                        notificationPlaceholder = notificationPlaceholder,
                    )
                    onSave(newSettings)
                    
                    // 更新推送调度
                    if (enabled && hasAllPermissions) {
                        pushScheduler.rescheduleAll(pushTimes)
                    } else {
                        // 关闭推送，取消所有闹钟
                        pushScheduler.rescheduleAll(emptyList())
                    }
                    
                    onDismiss()
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )

    // 权限引导对话框
    if (showPermissionGuide) {
        PushPermissionGuide(
            onAllGranted = {
                showPermissionGuide = false
                enabled = true
            },
            onDismiss = {
                showPermissionGuide = false
            },
        )
    }

    // 时间选择器对话框
    if (showTimePickerDialog) {
        TimePickerDialog(
            initialTime = if (editingTimeIndex >= 0) pushTimes[editingTimeIndex] else PushTime(8, 0),
            onConfirm = { hour, minute ->
                val newTime = PushTime(hour, minute)
                pushTimes = if (editingTimeIndex >= 0) {
                    pushTimes.toMutableList().apply { set(editingTimeIndex, newTime) }
                } else {
                    pushTimes + newTime
                }
                showTimePickerDialog = false
            },
            onDismiss = {
                showTimePickerDialog = false
            },
        )
    }
}

@Composable
private fun TimePickerDialog(
    initialTime: PushTime,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var hour by remember { mutableIntStateOf(initialTime.hour) }
    var minute by remember { mutableIntStateOf(initialTime.minute) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置推送时间") },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 小时选择
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = { hour = (hour + 1) % 24 }) {
                        Text("▲")
                    }
                    Text(
                        text = String.format("%02d", hour),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    IconButton(onClick = { hour = (hour - 1 + 24) % 24 }) {
                        Text("▼")
                    }
                }

                Text(
                    text = " : ",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                // 分钟选择
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = { minute = (minute + 1) % 60 }) {
                        Text("▲")
                    }
                    Text(
                        text = String.format("%02d", minute),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    IconButton(onClick = { minute = (minute - 1 + 60) % 60 }) {
                        Text("▼")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(hour, minute) }) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
