package me.rerere.rikkahub.ui.pages.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Add01

import me.rerere.rikkahub.data.calendar.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun DayDetailSheet(
    date: LocalDate,
    annotations: List<Any>,
    customAnnotation: DateAnnotation?,
    onAddAnnotation: (String, String) -> Unit,
    onRemoveAnnotation: () -> Unit,
    onDismiss: () -> Unit,
) {

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                // 头部
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = date.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日", Locale.getDefault())),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = date.dayOfWeek.getDisplayName(
                                TextStyle.FULL,
                                Locale.getDefault()
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Text(
                            text = "✕",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 内容区域
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // 标注区域
                    item {
                        AnnotationSection(
                            annotations = annotations,
                            customAnnotation = customAnnotation,
                            onAddAnnotation = onAddAnnotation,
                            onRemoveAnnotation = onRemoveAnnotation,
                        )
                    }

                    // 对话在日历网格下方显示，这里只放日期标注
                }
            }
        }
    }
}

@Composable
private fun AnnotationSection(
    annotations: List<Any>,
    customAnnotation: DateAnnotation?,
    onAddAnnotation: (String, String) -> Unit,
    onRemoveAnnotation: () -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }
    var newNote by remember { mutableStateOf("") }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "日期标注",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        // 标注列表
        if (annotations.isEmpty() && customAnnotation == null) {
            Text(
                text = "暂无标注",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 预设特殊日期
        annotations.forEach { annotation ->
            when (annotation) {
                is SpecialDate -> {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(4.dp)
                                        .clip(MaterialTheme.shapes.extraSmall)
                                        .background(
                                            if (annotation.isCountdown) {
                                                Color(0xFF9B8AC9)
                                            } else {
                                                MaterialTheme.colorScheme.secondary
                                            }
                                        ),
                                )
                                Text(
                                    text = annotation.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                if (annotation.isCountdown) {
                                    Text(
                                        text = "⏳",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            if (annotation.note.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = annotation.note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        // 自定义标注
        customAnnotation?.let {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(4.dp)
                                    .clip(MaterialTheme.shapes.extraSmall)
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                            Text(
                                text = it.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        IconButton(
                            onClick = onRemoveAnnotation,
                        ) {
                            Icon(
                                imageVector = HugeIcons.Delete01,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (it.note.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = it.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // 添加标注按钮
        if (customAnnotation == null) {
            TextButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = HugeIcons.Add01,
                    contentDescription = "添加标注",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("添加标注")
            }
        }
    }

    // 添加标注对话框
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("添加标注") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { newTitle = it },
                        label = { Text("标题") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newNote,
                        onValueChange = { newNote = it },
                        label = { Text("备注") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newTitle.isNotBlank()) {
                            onAddAnnotation(newTitle, newNote)
                            showAddDialog = false
                            newTitle = ""
                            newNote = ""
                        }
                    },
                    enabled = newTitle.isNotBlank(),
                ) {
                    Text("添加")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}

