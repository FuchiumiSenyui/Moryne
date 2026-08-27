package me.rerere.rikkahub.data.calendar

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 打卡项目定义
 */
@Serializable
data class CheckInItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
)

/**
 * 单日单项打卡记录
 */
@Serializable
data class CheckInRecord(
    val itemId: String,
    val completed: Boolean = false,
    val note: String = "",
)

/**
 * 打卡项目配置（全局，不按天变化）
 */
@Serializable
data class CheckInSettings(
    val items: List<CheckInItem> = DEFAULT_ITEMS,
) {
    companion object {
        val DEFAULT_ITEMS = listOf(
            CheckInItem(id = "default_vocab", name = "单词"),
            CheckInItem(id = "default_grammar", name = "语法"),
            CheckInItem(id = "default_sentence", name = "造句"),
        )
        val DEFAULT = CheckInSettings()
    }
}
