package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.calendar.CalendarData
import me.rerere.rikkahub.data.calendar.CalendarStore
import me.rerere.rikkahub.data.calendar.DateAnnotation
import me.rerere.rikkahub.data.calendar.Message
import me.rerere.rikkahub.data.calendar.MessageRole
import java.time.LocalDate
import java.util.UUID

/**
 * @param readOnly 只暴露 read 动作。推送生成必须用只读模式：
 *   写入动作会绕过推送防重（写进去的留言没有 isPushed/scheduledTime 标记），导致同一条推送进两遍。
 */
fun buildCalendarTools(
    json: Json,
    calendarStore: CalendarStore,
    readOnly: Boolean = false,
): List<Tool> = listOf(
    Tool(
        name = "calendar_tool",
        description = if (readOnly) """
            Read the user's calendar. Dates must use ISO yyyy-MM-dd.
            Actions:
            - read: read one date (`date`) or an inclusive range (`start_date`, `end_date`). The response also includes `current_datetime` and `current_weekday`, which are the real current time; treat all returned day content as past records.
            This is a read-only view: you cannot modify the calendar here. Do not try to write your message into the calendar; your reply is delivered automatically.
        """.trimIndent() else """
            Read and directly manage the user's calendar. Dates must use ISO yyyy-MM-dd.
            Actions:
            - read: read one date (`date`) or an inclusive range (`start_date`, `end_date`). The response also includes `current_datetime` and `current_weekday`, which are the real current time; treat all returned day content as past records.
            - add_message: add a message on a date (`role`: `user` = written by the user, default; `assistant` = written by you), using `content`.
            - upsert_annotation: create or replace an annotation on a date using `title`, optional `note`, and `is_countdown`.
            - delete_annotation: remove the annotation on a date.
            You are authorized to read and modify any past, current, or future date without confirmation.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put(
                        "action",
                        stringSchema(
                            "Operation",
                            if (readOnly) listOf("read")
                            else listOf("read", "add_message", "upsert_annotation", "delete_annotation")
                        )
                    )
                    put("date", stringSchema("Target date, yyyy-MM-dd"))
                    put("start_date", stringSchema("Range start, yyyy-MM-dd"))
                    put("end_date", stringSchema("Range end, yyyy-MM-dd"))
                    put("content", stringSchema("Message content"))
                    put("role", stringSchema("Who the message is written as", listOf("user", "assistant")))
                    put("title", stringSchema("Annotation title"))
                    put("note", stringSchema("Annotation note"))
                    put("is_countdown", booleanSchema("true=countdown, false=positive days"))
                },
                required = listOf("action")
            )
        },
        execute = { element ->
            val p = element.jsonObject
            val action = p.text("action") ?: error("action is required")
            require(!readOnly || action == "read") {
                "calendar_tool is read-only here; '$action' is not allowed"
            }
            val result = when (action) {
                "read" -> {
                    val data = calendarStore.getCalendarData()
                    val single = p.text("date")?.let(::parseDate)
                    val start = p.text("start_date")?.let(::parseDate)
                    val end = p.text("end_date")?.let(::parseDate)
                    val dates = when {
                        single != null -> listOf(single)
                        start != null && end != null -> {
                            require(!end.isBefore(start)) { "end_date must not be before start_date" }
                            generateSequence(start) { d -> d.plusDays(1).takeIf { !it.isAfter(end) } }.toList()
                        }
                        else -> error("read requires date or both start_date and end_date")
                    }
                    buildJsonObject {
                        val now = java.time.LocalDateTime.now()
                        put(
                            "current_datetime",
                            now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                        )
                        put("current_weekday", now.dayOfWeek.toString())
                        put(
                            "note",
                            "current_datetime is the real time right now. Any content in the returned days happened in the past, not now."
                        )
                        put(
                            "days",
                            json.encodeToJsonElement(
                                kotlinx.serialization.builtins.ListSerializer(me.rerere.rikkahub.data.calendar.DayData.serializer()),
                                dates.map(data::getDay)
                            )
                        )
                    }
                }

                "add_message" -> update(calendarStore, p) { data, date ->
                    val content = p.text("content")?.trim().orEmpty()
                    require(content.isNotEmpty()) { "content is required" }
                    val role = when (p.text("role") ?: "user") {
                        "user" -> MessageRole.USER
                        "assistant" -> MessageRole.ASSISTANT
                        else -> error("role must be user or assistant")
                    }
                    val day = data.getDay(date)
                    data.updateDay(day.copy(messages = day.messages + Message(role, content)))
                }

                "upsert_annotation" -> update(calendarStore, p) { data, date ->
                    val title = p.text("title")?.trim().orEmpty()
                    require(title.isNotEmpty()) { "title is required" }
                    val old = data.getCustomAnnotation(date)
                    data.addCustomAnnotation(date, DateAnnotation(
                        id = old?.id ?: UUID.randomUUID().toString(),
                        title = title,
                        note = p.text("note").orEmpty(),
                        isCountdown = p.bool("is_countdown") ?: old?.isCountdown ?: false,
                    ))
                }

                "delete_annotation" -> update(calendarStore, p) { data, date -> data.removeCustomAnnotation(date) }

                else -> error("unknown action: $action")
            }
            listOf(UIMessagePart.Text(result.toString()))
        }
    )
)

private suspend fun update(
    store: CalendarStore,
    p: kotlinx.serialization.json.JsonObject,
    transform: (CalendarData, LocalDate) -> CalendarData,
) = buildJsonObject {
    val date = parseDate(p.text("date") ?: error("date is required"))
    store.updateCalendarData { transform(it, date) }
    put("success", true)
    put("date", date.toString())
}

private fun parseDate(value: String): LocalDate =
    runCatching { LocalDate.parse(value) }.getOrElse { error("Invalid ISO date: $value") }

private fun kotlinx.serialization.json.JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull
private fun kotlinx.serialization.json.JsonObject.bool(key: String) = this[key]?.jsonPrimitive?.booleanOrNull

private fun stringSchema(description: String, values: List<String> = emptyList()) = buildJsonObject {
    put("type", "string")
    put("description", description)
    if (values.isNotEmpty()) {
        put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
    }
}

private fun booleanSchema(description: String) = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}
