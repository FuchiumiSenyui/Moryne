package me.rerere.rikkahub.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import me.rerere.rikkahub.service.PushNotificationManager
import me.rerere.rikkahub.utils.PushScheduler
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 推送兜底 worker：周期性检查「今天该推的有没有漏」，漏了就补。
 *
 * 为什么需要它。
 * 定时推送靠 AlarmManager 的一次性闹钟，而闹钟是最容易被系统抹掉的东西：
 * 强停、ROM 清理后台、深度休眠都可能让它不响，而且不响时**没有任何回调**
 * 通知应用「你的闹钟没了」—— 表现就是到点静悄悄，无从察觉。
 * 原来唯一的补救是「使用者主动打开应用时补发」，那要求人先想起来去看。
 *
 * WorkManager 的性质刚好相反：任务落盘由系统调度，进程被杀、设备重启都不会丢，
 * 国产 ROM 对它的容忍度也明显高于自建闹钟。代价是**不保证准时**
 * （最小周期 15 分钟，且系统会按电量和 Doze 窗口挪动），所以它不能取代闹钟，
 * 只能兜底：闹钟准时响就走闹钟那条路，闹钟丢了就靠这里在一刻钟内补上。
 *
 * 判据完全复用现成的那套（isPushed + scheduledTime），所以不新增落盘、
 * 不跟防重打架、也不会和闹钟路径重复推送。
 */
class PushCatchUpWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val pushNotificationManager: PushNotificationManager by inject()
    private val pushScheduler: PushScheduler by inject()

    companion object {
        private const val TAG = "PushCatchUpWorker"
        const val UNIQUE_NAME = "push_catch_up"
    }

    override suspend fun doWork(): Result {
        return try {
            // 顺手补排闹钟。覆盖式，不取消 —— 取消会作废正在投递路上的广播。
            runCatching {
                val settings = pushNotificationManager.currentPushSettings()
                if (settings.enabled) {
                    pushScheduler.ensureScheduled(settings.pushTimes)
                }
            }.onFailure { Log.e(TAG, "Failed to re-arm alarms from worker", it) }

            val missed = pushNotificationManager.findMissedPushToday()
            if (missed == null) {
                Log.d(TAG, "No missed push")
                return Result.success()
            }

            Log.i(TAG, "Missed push found at $missed, delivering from worker")
            // 直接在 worker 里执行，不启前台服务：worker 本身已经是系统认可的
            // 后台执行环境，再去闯「后台启前台服务」那道关只会白增失败点。
            pushNotificationManager.executePush(
                scheduledHour = missed.hour,
                scheduledMinute = missed.minute,
                isCatchUp = true,
            )
            Result.success()
        } catch (e: Exception) {
            // 不重试：推送错过的时效性很短，反复重试只会在 AI 模式下白烧 API。
            // 下一个周期会再检查一次，那时如果还漏着就会再补。
            Log.e(TAG, "Catch-up worker failed", e)
            Result.success()
        }
    }
}
