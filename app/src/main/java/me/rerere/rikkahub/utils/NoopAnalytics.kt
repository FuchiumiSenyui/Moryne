package me.rerere.rikkahub.utils

import android.os.Bundle
import android.util.Log

/**
 * 空实现的埋点。
 *
 * 这个分支移除了 Firebase（Analytics + Crashlytics）：
 * - 不需要给使用者做数据统计
 * - 上游 CI 靠 secrets 注入 google-services.json，这个仓库没有那份 secret
 * - 顺带避免把 Google 的采集塞进使用者的 App
 *
 * 保留一个同名的 logEvent 接口，调用方不用改。
 */
class NoopAnalytics {
    fun logEvent(name: String, params: Bundle?) {
        if (BuildConfigDebug) Log.d(TAG, "analytics event ignored: $name")
    }

    private companion object {
        const val TAG = "NoopAnalytics"
        val BuildConfigDebug = me.rerere.rikkahub.BuildConfig.DEBUG
    }
}
