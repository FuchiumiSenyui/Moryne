package me.rerere.rikkahub.data.calendar

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 日历对话的配置。
 *
 * 与聊天页的助手设置完全隔离：独立提示词、独立模型选择。
 * 提示词内容由使用者自行填写，这里只负责存取。
 */
@Serializable
data class DiarySettings(
    /** 总开关：关闭后留言区退化为纯本地记录，不发起任何网络请求 */
    val enabled: Boolean = true,

    /** 日历对话的系统提示词 */
    val systemPrompt: String = DEFAULT_PROMPT,

    /** 使用的模型 id；为空则复用聊天页当前选中的模型 */
    val modelId: String? = null,

    /** 单次请求携带的历史留言条数上限（同一天内） */
    val historyLimit: Int = 20,

    /** 是否在与聊天页对话时自动注入极简日历摘要 */
    val injectSummary: Boolean = true,
) {
    fun resolvedModelId(): Uuid? = modelId?.takeIf { it.isNotBlank() }?.let {
        runCatching { Uuid.parse(it) }.getOrNull()
    }

    companion object {
        /** 默认提示词只是个能用的起点，建议在设置页整段换成自己想要的角色和语气。 */
        const val DEFAULT_PROMPT = """你是这本日历里的对话对象，陪使用者记录每一天。

怎么回：
读当天已经写下的内容，针对具体的事说话，不要泛泛而谈。
不复述对方的原话，不写成总结报告。
对方状态不好的时候先接住，再说别的。

格式：
只回一段话，两到四句。不分点，不加标题，不写动作描写。"""

        val DEFAULT = DiarySettings()
    }
}
