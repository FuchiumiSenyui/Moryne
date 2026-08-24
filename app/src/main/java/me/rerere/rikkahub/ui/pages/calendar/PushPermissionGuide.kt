package me.rerere.rikkahub.ui.pages.calendar

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.utils.NotificationUtil
import me.rerere.rikkahub.utils.PushScheduler
import org.koin.androidx.compose.koinViewModel

/**
 * 推送权限引导组件
 * 
 * 引导用户开启：
 * 1. 通知权限（Android 13+）
 * 2. 精确闹钟权限（Android 12+，需要跳转系统设置）
 * 3. 电池优化豁免（可选，引导到设置）
 */
@Composable
fun PushPermissionGuide(
    onAllGranted: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val pushScheduler = remember { PushScheduler(context) }

    // 通知权限状态
    val hasNotificationPermission = NotificationUtil.hasNotificationPermission(context)

    // 精确闹钟权限状态（Android 12+）
    val canScheduleExactAlarms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        pushScheduler.canScheduleExactAlarms()
    } else {
        true // Android 12 以下不需要
    }

    // 如果所有必需权限都已授予，自动关闭
    LaunchedEffect(hasNotificationPermission, canScheduleExactAlarms) {
        if (hasNotificationPermission && canScheduleExactAlarms) {
            onAllGranted()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("推送功能需要权限") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "为了让推送能准时到达，需要开启以下权限：",
                    style = MaterialTheme.typography.bodyMedium,
                )

                // 通知权限
                PermissionItem(
                    title = "通知权限",
                    description = "用于在后台时发送推送通知",
                    isGranted = hasNotificationPermission,
                    onGrant = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            // 跳转到通知设置页面
                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            }
                            context.startActivity(intent)
                        }
                    },
                )

                // 精确闹钟权限
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PermissionItem(
                        title = "精确闹钟权限",
                        description = "确保推送准时触发（需要在系统设置中手动开启）",
                        isGranted = canScheduleExactAlarms,
                        onGrant = {
                            // 跳转到精确闹钟设置页面
                            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        },
                    )
                }

                // 电池优化提示（可选）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Text(
                        text = "提示：如果推送不准时，可能需要在系统设置中关闭电池优化（可选）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (hasNotificationPermission && canScheduleExactAlarms) {
                        onAllGranted()
                    } else {
                        onDismiss()
                    }
                }
            ) {
                Text(if (hasNotificationPermission && canScheduleExactAlarms) "完成" else "稍后设置")
            }
        },
    )
}

@Composable
private fun PermissionItem(
    title: String,
    description: String,
    isGranted: Boolean,
    onGrant: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        
        Spacer(modifier = Modifier.width(8.dp))
        
        if (isGranted) {
            Text(
                text = "✓",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium,
            )
        } else {
            TextButton(onClick = onGrant) {
                Text("开启")
            }
        }
    }
}
