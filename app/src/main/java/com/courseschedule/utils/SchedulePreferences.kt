package com.courseschedule.utils

import android.content.Context
import com.courseschedule.data.backup.SettingsSnapshot

class SchedulePreferences(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        if (!prefs.getBoolean(KEY_COMPACT_DENSITY_MIGRATED, false)) {
            val editor = prefs.edit()
            if (prefs.getInt(KEY_SECTION_HEIGHT, 72) == 72) {
                editor.putInt(KEY_SECTION_HEIGHT, 64)
            }
            editor.putBoolean(KEY_COMPACT_DENSITY_MIGRATED, true).apply()
        }
    }

    var showWeekend: Boolean
        get() = prefs.getBoolean(KEY_SHOW_WEEKEND, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_WEEKEND, value).apply()

    var showTime: Boolean
        get() = prefs.getBoolean(KEY_SHOW_TIME, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_TIME, value).apply()

    var showInactiveCourses: Boolean
        get() = prefs.getBoolean(KEY_SHOW_INACTIVE_COURSES, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_INACTIVE_COURSES, value).apply()

    var sectionHeightDp: Int
        get() = prefs.getInt(KEY_SECTION_HEIGHT, 64)
        set(value) = prefs.edit().putInt(KEY_SECTION_HEIGHT, value).apply()

    var reminderEnabled: Boolean
        get() = prefs.getBoolean(KEY_REMINDER_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_REMINDER_ENABLED, value).apply()

    var defaultReminderMinutes: Int
        get() = prefs.getInt(KEY_DEFAULT_REMINDER, 15)
        set(value) = prefs.edit().putInt(KEY_DEFAULT_REMINDER, value).apply()

    var sectionTimes: List<String>
        get() {
            val stored = prefs.getString(KEY_SECTION_TIMES, null)
                ?.split(',')
                ?.filter(::isValidTime)
                .orEmpty()
            return if (stored.size == SECTION_COUNT) stored else DEFAULT_SECTION_TIMES
        }
        set(value) {
            require(value.size == SECTION_COUNT && value.all(::isValidTime))
            prefs.edit().putString(KEY_SECTION_TIMES, value.joinToString(",")).apply()
        }

    fun snapshot() = SettingsSnapshot(
        showWeekend = showWeekend,
        showTime = showTime,
        showInactiveCourses = showInactiveCourses,
        sectionHeightDp = sectionHeightDp,
        reminderEnabled = reminderEnabled,
        defaultReminderMinutes = defaultReminderMinutes,
        sectionTimes = sectionTimes
    )

    fun applySnapshot(snapshot: SettingsSnapshot) {
        val times = snapshot.sectionTimes.takeIf {
            it.size == SECTION_COUNT && it.all(::isValidTime)
        } ?: DEFAULT_SECTION_TIMES
        prefs.edit()
            .putBoolean(KEY_SHOW_WEEKEND, snapshot.showWeekend)
            .putBoolean(KEY_SHOW_TIME, snapshot.showTime)
            .putBoolean(KEY_SHOW_INACTIVE_COURSES, snapshot.showInactiveCourses)
            .putInt(KEY_SECTION_HEIGHT, snapshot.sectionHeightDp.coerceIn(56, 104))
            .putBoolean(KEY_REMINDER_ENABLED, snapshot.reminderEnabled)
            .putInt(KEY_DEFAULT_REMINDER, snapshot.defaultReminderMinutes)
            .putString(KEY_SECTION_TIMES, times.joinToString(","))
            .apply()
    }

    companion object {
        const val SECTION_COUNT = 12
        const val PREFS_NAME = "settings"
        private const val KEY_SHOW_WEEKEND = "show_weekend"
        private const val KEY_SHOW_TIME = "show_time"
        private const val KEY_SHOW_INACTIVE_COURSES = "show_inactive_courses"
        private const val KEY_SECTION_HEIGHT = "section_height_dp"
        private const val KEY_COMPACT_DENSITY_MIGRATED = "compact_density_migrated_v2"
        private const val KEY_REMINDER_ENABLED = "reminder_enabled"
        private const val KEY_DEFAULT_REMINDER = "default_reminder_minutes"
        private const val KEY_SECTION_TIMES = "section_times"

        val DEFAULT_SECTION_TIMES = listOf(
            "08:00", "08:50", "09:50", "10:40",
            "11:30", "14:30", "15:20", "16:20",
            "17:10", "19:00", "19:50", "20:40"
        )

        fun isValidTime(value: String): Boolean {
            return Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d$").matches(value)
        }
    }
}
