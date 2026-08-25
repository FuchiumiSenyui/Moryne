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

    // 电池优化豁免状态。系统层面算「可选」，但国产 ROM 上它比精确闹钟权限
    // 更能决定推送活不活：没豁免进程会被冻结，闹钟到点也叫不醒。
    val isBatteryExempt = pushScheduler.isIgnoringBatteryOptimizations()

    // 只有必需权限决定是否自动关闭。电池优化不列入条件 —— 它无法用一次点击
    // 保证拿到（部分 ROM 的开关藏在自家电池管理里），拿它当门槛会让弹窗关不掉。
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

                // 省电限制。放在这里跟前两项同级，因为国产 ROM 上它才是
                // 推送不来的头号原因，而它以前只是一句灰色小字。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PermissionItem(
                        title = "关闭省电限制",
                        description = "不关的话系统会冻结应用，定时任务到点也叫不醒它",
                        isGranted = isBatteryExempt,
                        onGrant = {
                            // 先试直接申请豁免的弹窗，被 ROM 屏蔽时退回电池优化列表页
                            val direct = Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}")
                            )
                            val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            runCatching { context.startActivity(direct) }
                                .recoverCatching { context.startActivity(fallback) }
                        },
                    )

                    Text(
                        text = "小米 / 华为 / OPPO / vivo 还需要在系统设置里单独开「自启动」" +
                            "（有的叫「后台运行」），并在最近任务里给应用加锁，" +
                            "否则清理后台会把定时任务一起清掉。这一步没法由应用代劳。",
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
