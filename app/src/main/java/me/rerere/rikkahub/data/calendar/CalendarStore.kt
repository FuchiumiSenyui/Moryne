package me.rerere.rikkahub.data.calendar

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

import me.rerere.rikkahub.utils.JsonInstant

private val Context.calendarDataStore by preferencesDataStore("calendar")

class CalendarStore(context: Context) {
    private val dataStore: DataStore<Preferences> = context.calendarDataStore

    private companion object {
        val CALENDAR_DATA = stringPreferencesKey("calendar_data")
        val DIARY_SETTINGS = stringPreferencesKey("diary_settings")
        val PUSH_SETTINGS = stringPreferencesKey("push_settings")
    }

    val calendarDataFlow = dataStore.data
        .map { preferences ->
            val json = preferences[CALENDAR_DATA] ?: "{}"
            try {
                JsonInstant.decodeFromString<CalendarData>(json)
            } catch (e: Exception) {
                CalendarData()
            }
        }

    suspend fun saveCalendarData(data: CalendarData) {
        dataStore.edit { preferences ->
            preferences[CALENDAR_DATA] = JsonInstant.encodeToString(data)
        }
    }

    /** 在 DataStore 的互斥写事务中读取并更新，供 AI 工具等后台入口安全修改。 */
    suspend fun updateCalendarData(transform: (CalendarData) -> CalendarData): CalendarData {
        var updated = CalendarData()
        dataStore.edit { preferences ->
            val current = runCatching {
                JsonInstant.decodeFromString<CalendarData>(preferences[CALENDAR_DATA] ?: "{}")
            }.getOrDefault(CalendarData())
            updated = transform(current)
            preferences[CALENDAR_DATA] = JsonInstant.encodeToString(updated)
        }
        return updated
    }

    suspend fun getCalendarData(): CalendarData {
        return calendarDataFlow.first()
    }

    // 日历对话的配置
    val diarySettingsFlow = dataStore.data
        .map { preferences ->
            val json = preferences[DIARY_SETTINGS] ?: return@map DiarySettings.DEFAULT
            try {
                JsonInstant.decodeFromString<DiarySettings>(json)
            } catch (e: Exception) {
                DiarySettings.DEFAULT
            }
        }

    suspend fun saveDiarySettings(settings: DiarySettings) {
        dataStore.edit { preferences ->
            preferences[DIARY_SETTINGS] = JsonInstant.encodeToString(settings)
        }
    }

    suspend fun getDiarySettings(): DiarySettings {
        return diarySettingsFlow.first()
    }

    // 推送配置
    val pushSettingsFlow = dataStore.data
        .map { preferences ->
            val json = preferences[PUSH_SETTINGS] ?: return@map PushSettings.DEFAULT
            try {
                JsonInstant.decodeFromString<PushSettings>(json)
            } catch (e: Exception) {
                PushSettings.DEFAULT
            }
        }

    suspend fun savePushSettings(settings: PushSettings) {
        dataStore.edit { preferences ->
            preferences[PUSH_SETTINGS] = JsonInstant.encodeToString(settings)
        }
    }

    suspend fun getPushSettings(): PushSettings {
        return pushSettingsFlow.first()
    }
}
