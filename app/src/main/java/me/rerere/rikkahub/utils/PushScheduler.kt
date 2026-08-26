package me.rerere.rikkahub.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.calendar.PushTime
import me.rerere.rikkahub.receiver.PushAlarmReceiver

/**
 * 推送调度器 - 使用 AlarmManager 设置定时推送
 */
class PushScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    companion object {
        private const val TAG = "PushScheduler"
        private const val BASE_REQUEST_CODE = 10000 // 推送闹钟的 request code 起始值
        private const val MAX_SLOTS = 10 // 最多支持 10 个推送时间

        // setAlarmClock 的 showIntent 用的 request code，跟闹钟本身的错开避免互相覆盖
        private const val SHOW_INTENT_REQUEST_CODE = 10100
    }

    /**
     * 构造闹钟的 PendingIntent。
     *
     * @param flags 额外的 flag（FLAG_UPDATE_CURRENT 用于排闹钟，FLAG_NO_CREATE 用于查询/取消）
     */
    private fun buildPendingIntent(
        index: Int,
        pushTime: PushTime?,
        flags: Int,
    ): PendingIntent? {
        val intent = Intent(context, PushAlarmReceiver::class.java).apply {
            action = PushAlarmReceiver.ACTION_PUSH_TRIGGER
            if (pushTime != null) {
                putExtra(PushAlarmReceiver.EXTRA_PUSH_TIME_HOUR, pushTime.hour)
                putExtra(PushAlarmReceiver.EXTRA_PUSH_TIME_MINUTE, pushTime.minute)
            }
        }
        return PendingIntent.getBroadcast(
            context,
            BASE_REQUEST_CODE + index,
            intent,
            flags or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 设置每日推送闹钟。
     *
     * @param allowToday 是否允许排在今天。
     *   true（补排/续订）：只要今天这个时刻还没到就排今天，不留安全边距。
     *   false（用户手动保存设置）：留 2 分钟边距，避免「设置的时间≈当前时间」立即触发。
     * @return 是否成功排上
     */
    fun scheduleDailyPush(pushTime: PushTime, index: Int, allowToday: Boolean = true): Boolean {
        if (!canScheduleExactAlarms()) {
            Log.w(TAG, "Cannot schedule exact alarm: permission not granted (pushTime=$pushTime)")
            return false
        }

        val pendingIntent = buildPendingIntent(
            index = index,
            pushTime = pushTime,
            flags = PendingIntent.FLAG_UPDATE_CURRENT,
        ) ?: return false

        val triggerAtMillis = pushTime.nextTriggerMillis(allowToday = allowToday)

        // 用 setAlarmClock 而不是 setExactAndAllowWhileIdle。
        //
        // setExactAndAllowWhileIdle 名字里有 AllowWhileIdle，但它在 Doze 下仍会被限流
        // （官方限制大约每 9 分钟一次），而且国产 ROM 深度休眠时经常直接不理它 ——
        // 「锁屏放着不动、到点什么都没有」就是这么来的。
        //
        // setAlarmClock 被系统当成「用户设的闹钟」，是优先级最高的一档：Doze 下照样准时响，
        // ROM 也不敢拦，因为拦了用户的起床闹钟就要出事。代价是状态栏会出现一个闹钟图标、
        // 系统「下一个闹钟」里能看到时间。对一个用户自己设定的定时推送来说这个代价可以接受，
        // 某种意义上还是个「推送已排好」的正向指示。
        //
        // showIntent 是点状态栏闹钟图标时打开的界面，指向应用本身。
        val showIntent = PendingIntent.getActivity(
            context,
            SHOW_INTENT_REQUEST_CODE + index,
            Intent(context, RouteActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent),
            pendingIntent
        )
        Log.i(TAG, "Scheduled push at $pushTime (index=$index, triggerAt=$triggerAtMillis)")
        return true
    }

    /**
     * 取消推送闹钟。
     *
     * ⚠️ 只调 alarmManager.cancel()，**绝不调 pendingIntent.cancel()**。
     * 后者会把 PendingIntent 本身作废；如果此刻正有一条广播在投递路上
     * （闹钟刚响、进程刚被唤醒的那个窗口），会把这条广播直接丢掉，
     * 表现为「闹钟响了但接收器没被调用」。
     */
    fun cancelPush(index: Int) {
        val pendingIntent = buildPendingIntent(
            index = index,
            pushTime = null,
            flags = PendingIntent.FLAG_NO_CREATE,
        ) ?: return

        alarmManager.cancel(pendingIntent)
        Log.i(TAG, "Cancelled push alarm (index=$index)")
    }

    /**
     * 重新调度所有推送闹钟 —— 用于「用户在设置里点了保存」。
     *
     * 会先取消全部再重排，所以只应该在用户主动改配置时调用。
     * 进程启动时的兜底补排走 [ensureScheduled]，那条路绝不能取消。
     *
     * @param allowToday 见 [scheduleDailyPush]；用户手动保存时传 false
     */
    fun rescheduleAll(pushTimes: List<PushTime>, allowToday: Boolean = false) {
        // 去重：同一时刻只设置一个闹钟
        val uniqueTimes = pushTimes.distinct()
        if (uniqueTimes.size < pushTimes.size) {
            Log.w(TAG, "Removed ${pushTimes.size - uniqueTimes.size} duplicate push times")
        }

        // 权限拿不到时不要清空已有闹钟：清了也排不回来，等于把推送彻底弄死。
        // 关闭推送（传空列表）是明确意图，照常执行。
        if (uniqueTimes.isNotEmpty() && !canScheduleExactAlarms()) {
            Log.e(TAG, "Exact alarm permission missing, keeping existing alarms untouched")
            return
        }

        for (i in 0 until MAX_SLOTS) {
            cancelPush(i)
        }

        uniqueTimes.forEachIndexed { index, pushTime ->
            scheduleDailyPush(pushTime, index, allowToday = allowToday)
        }

        Log.i(TAG, "Rescheduled ${uniqueTimes.size} push alarms")
    }

    /**
     * 兜底补排：把所有配置中的槽位「覆盖式」排一遍，**不取消任何东西**。
     *
     * 进程启动时和推送响完续订时调用。一次性闹钟可能因强停 / ROM 清理而丢失，需要补。
     *
     * 为什么不先查「是否已排」再补缺失的：`FLAG_NO_CREATE` 返回非 null 只能证明
     * PendingIntent 这个对象还在，不能证明闹钟还挂着（一次性闹钟响过之后对象可能仍存在），
     * 拿它当「已排」的判据会漏补。
     *
     * 为什么覆盖式是安全的：AlarmManager 的 set 系列（含 `setAlarmClock`）对同一个
     * PendingIntent 是**替换**语义，不会变成两个；重算出来的时间跟原本排的是同一个时刻，
     * 覆盖等于没变。
     *
     * 为什么绝不能取消：闹钟响起唤醒进程的那一刻，广播还在投递路上，
     * `pendingIntent.cancel()` 会把它丢掉 —— 「闹钟把进程唤醒，进程反手杀掉这个闹钟」，
     * 这正是清后台后推送彻底不触发的原因。
     *
     * @return 排上的槽位数
     */
    fun ensureScheduled(pushTimes: List<PushTime>): Int {
        val uniqueTimes = pushTimes.distinct()
        if (uniqueTimes.isEmpty()) return 0

        if (!canScheduleExactAlarms()) {
            Log.w(TAG, "ensureScheduled skipped: exact alarm permission not granted")
            return 0
        }

        var armed = 0
        uniqueTimes.forEachIndexed { index, pushTime ->
            if (scheduleDailyPush(pushTime, index, allowToday = true)) armed++
        }

        Log.i(TAG, "ensureScheduled: armed $armed of ${uniqueTimes.size} alarm(s)")
        return armed
    }

    /**
     * 检查是否有精确闹钟权限（Android 12+）
     */
    fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true // Android 12 以下不需要权限
        }
    }

    /**
     * 数一下有几个槽位「看起来」还挂着闹钟。
     *
     * ⚠️ 这只是个粗略指示，不是可靠判据：`FLAG_NO_CREATE` 返回非 null 只证明
     * PendingIntent 对象还在，不证明 AlarmManager 里那条闹钟还在
     * （一次性闹钟响过之后对象通常仍存在）。所以它只用来给使用者看个大概，
     * 排闹钟的逻辑绝不能拿它当判断依据 —— 见 [ensureScheduled] 的注释。
     *
     * 反过来说，返回 0 是**可靠**的坏消息：连对象都没了，闹钟肯定不在。
     * 强停 / ROM 清理正是这种情况。
     */
    fun countLiveSlots(): Int {
        var count = 0
        for (i in 0 until MAX_SLOTS) {
            val pi = buildPendingIntent(
                index = i,
                pushTime = null,
                flags = PendingIntent.FLAG_NO_CREATE,
            )
            if (pi != null) count++
        }
        return count
    }

    /**
     * 应用是否已被豁免电池优化。
     *
     * 国产 ROM 上这一条比精确闹钟权限更能决定推送活不活：没豁免的话
     * 进程被冻结，闹钟即便排上了也可能延迟很久甚至不触发。
     */
    fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 系统「下一个闹钟」里登记的是谁、什么时候。
     *
     * setAlarmClock 排的闹钟会登记在这里，状态栏图标也由它驱动。
     * 用来回答一个之前只能靠肉眼看状态栏、而且看不准的问题：闹钟到底排上了没有。
     *
     * 注意全系统只有**一个**「下一个闹钟」槽位：如果用户自己的时钟应用设了更早的闹钟，
     * 这里返回的就是那一个，包名不是本应用。所以包名不匹配**不等于**我们的闹钟没排上，
     * 只能说明它不是最近的那一个 —— 判断时必须结合 [describeNextAlarm] 的文案，
     * 不能直接当成失败。
     */
    fun nextSystemAlarm(): AlarmManager.AlarmClockInfo? =
        runCatching { alarmManager.nextAlarmClock }.getOrNull()

    /**
     * 把闹钟状态说成人话，直接显示给使用者。
     *
     * 这里刻意区分「我们的闹钟是最近的一个」「有别的应用闹钟更近」「一个都没有」，
     * 因为第二种情况完全正常，却最容易被误读成推送坏了。
     */
    fun describeNextAlarm(): String {
        val info = nextSystemAlarm()
            ?: return "系统里没有任何已登记的闹钟"

        val whenText = runCatching {
            java.time.Instant.ofEpochMilli(info.triggerTime)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        }.getOrElse { "时间未知" }

        val ownerPackage = runCatching { info.showIntent?.creatorPackage }.getOrNull()
        return if (ownerPackage == context.packageName) {
            "下一次推送：$whenText"
        } else {
            // 系统只保留最近的那一个，别的应用闹钟更近时会占据这个位置
            "系统最近的闹钟是其他应用的（$whenText），本应用的推送闹钟排在它之后"
        }
    }
}
