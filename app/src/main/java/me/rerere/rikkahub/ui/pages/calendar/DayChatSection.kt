package me.rerere.rikkahub.ui.pages.calendar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Message02
import me.rerere.rikkahub.data.calendar.DayData
import me.rerere.rikkahub.data.calendar.Message
import me.rerere.rikkahub.data.calendar.MessageRole
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")


/**
 * 日历网格下方的当天对话区。
 * 按天分，切到别的日期就是另一个独立的小房间。
 */
@Composable
fun DayChatSection(
    date: LocalDate,
    dayData: DayData,
    replying: Boolean,
    streamingReply: String,
    replyError: String?,
    diaryEnabled: Boolean,
    aiName: String,
    userName: String,
    onSend: (String) -> Unit,
    onRetry: () -> Unit,
    onDelete: (Message) -> Unit,
    onEdit: (Message, String) -> Unit,
    onEditAndResend: (Message, String) -> Unit,
    onRegenerate: (Message) -> Unit,
    onNewTopic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isToday = date == LocalDate.now()
    var input by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Message?>(null) }
    var deleting by remember { mutableStateOf<Message?>(null) }
    // 编辑自己的留言并确认重发：Pair(原消息, 新内容)
    var resendConfirm by remember { mutableStateOf<Pair<Message, String>?>(null) }


    Card(
        modifier = modifier
            .fillMaxWidth()
            .imePadding(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "对话",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = date.format(
                            DateTimeFormatter.ofPattern("MM月dd日", Locale.getDefault())
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!diaryEnabled) {
                    Text(
                        text = "日历对话未启用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onNewTopic, enabled = isToday && !replying) {
                    Text("新话题")
                }
            }

            if (dayData.messages.isEmpty() && !replying) {
                Text(
                    text = "这一天还没有对话",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val firstInContext = dayData.contextStartAt?.let { start ->
                dayData.messages.firstOrNull { it.createdAt >= start }?.createdAt
            }
            dayData.messages.forEach { message ->
                if (firstInContext != null && firstInContext == message.createdAt) {
                    TopicDivider()
                }

                ChatBubble(
                    message = message,
                    aiName = aiName,
                    userName = userName,
                    actionsEnabled = isToday,
                    onDelete = { deleting = message },
                    onEdit = { editing = message },
                    onRegenerate = { onRegenerate(message) },
                )

            }

            // 新话题之后还没有新消息时，分割线画在末尾
            if (dayData.contextStartAt != null &&
                dayData.messages.none { it.createdAt >= dayData.contextStartAt }
            ) {
                TopicDivider()
            }

            if (replying) {
                ChatBubble(
                    message = Message(
                        role = MessageRole.ASSISTANT,
                        content = streamingReply.ifBlank { "…" },
                    ),
                    aiName = aiName,
                    userName = userName,
                    actionsEnabled = false,
                    onDelete = {},
                    onEdit = {},
                    onRegenerate = {},
                )
            }

            if (replyError != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = replyError,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRetry, enabled = isToday) {
                        Text("重试")
                    }
                }
            }

            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(if (isToday) "说点什么" else "只有当天可以聊天") },
                minLines = 1,
                maxLines = 5,
                enabled = isToday && !replying,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (replying) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(12.dp)
                                .size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(
                            onClick = {
                                val text = input
                                if (text.isNotBlank()) {
                                    onSend(text)
                                    input = ""
                                }
                            },
                            enabled = isToday && input.isNotBlank(),
                        ) {
                            Icon(
                                imageVector = HugeIcons.Message02,
                                contentDescription = "发送",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                    unfocusedLabelColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                ),
            )
        }
    }

    // 编辑消息
    editing?.let { target ->
        var text by remember(target) { mutableStateOf(target.content) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("编辑内容") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 10,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // 改自己的话等于重新发一次，要先确认（会丢后面的对话）；
                        // 改 AI 的话只是修措辞，直接存。
                        if (target.role == MessageRole.USER) {
                            resendConfirm = target to text
                        } else {
                            onEdit(target, text)
                        }
                        editing = null
                    },
                    enabled = text.isNotBlank(),
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) {
                    Text("取消")
                }
            },
        )
    }

    // 删除消息确认
    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除这条消息？") },
            text = { Text("删掉之后就找不回来了。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target)
                    deleting = null
                }) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text("取消")
                }
            },
        )
    }

    // 编辑自己的留言 → 重新发一次的确认
    resendConfirm?.let { (target, newText) ->
        AlertDialog(
            onDismissRequest = { resendConfirm = null },
            title = { Text("重新发一次？") },
            text = { Text("保存后这条之后的对话会被删掉，AI 会针对新内容重新回复。") },
            confirmButton = {
                TextButton(onClick = {
                    onEditAndResend(target, newText)
                    resendConfirm = null
                }) {
                    Text("重新发送")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    // 只改文字，不重发
                    onEdit(target, newText)
                    resendConfirm = null
                }) {
                    Text("只改文字")
                }
            },
        )
    }
}


@Composable
private fun TopicDivider() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = "新话题",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ChatBubble(
    message: Message,
    aiName: String,
    userName: String,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onRegenerate: () -> Unit,
    actionsEnabled: Boolean = true,
) {
    val isUser = message.role == MessageRole.USER
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .clickable(enabled = actionsEnabled) { expanded = !expanded },
                shape = if (isUser) {
                    RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 0.dp,
                        bottomStart = 12.dp,
                        bottomEnd = 12.dp
                    )
                } else {
                    RoundedCornerShape(
                        topStart = 0.dp,
                        topEnd = 12.dp,
                        bottomStart = 12.dp,
                        bottomEnd = 12.dp
                    )
                },
                color = if (isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (isUser) userName else aiName,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isUser) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color(0xFF9B8AC9)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        // createdAt 存的是 ISO 字符串，脏数据解析不出来就不显示时间
                        val timeText = remember(message.createdAt) {
                            runCatching {
                                LocalDateTime.parse(message.createdAt).format(timeFormatter)
                            }.getOrNull()
                        }
                        if (timeText != null) {
                            Text(
                                text = timeText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))

                    SelectionContainer {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(visible = expanded && actionsEnabled) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = {
                    expanded = false
                    onEdit()
                }) {
                    Text("编辑")
                }
                TextButton(onClick = {
                    expanded = false
                    onDelete()
                }) {
                    Text("删除")
                }
                if (!isUser) {
                    TextButton(onClick = {
                        expanded = false
                        onRegenerate()
                    }) {
                        Text("重新生成")
                    }
                }
            }
        }
    }
}
