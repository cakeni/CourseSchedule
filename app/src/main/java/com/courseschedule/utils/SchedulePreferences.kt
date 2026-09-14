package com.courseschedule.utils

import android.content.Context
import com.courseschedule.data.backup.SettingsSnapshot
import java.util.Locale

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

    val sectionTimes: List<String>
        get() {
            val stored = prefs.getString(KEY_SECTION_TIMES, null)
                ?.split(',')
                .orEmpty()
            return stored.takeIf(::areValidStartTimes) ?: DEFAULT_SECTION_TIMES
        }

    val sectionEndTimes: List<String>
        get() {
            val starts = sectionTimes
            val stored = prefs.getString(KEY_SECTION_END_TIMES, null)
                ?.split(',')
                .orEmpty()
            return stored.takeIf { areValidSectionTimes(starts, it) }
                ?: inferSectionEndTimes(starts)
        }

    fun setSectionTimes(startTimes: List<String>, endTimes: List<String>) {
        require(areValidSectionTimes(startTimes, endTimes))
        prefs.edit()
            .putString(KEY_SECTION_TIMES, startTimes.joinToString(","))
            .putString(KEY_SECTION_END_TIMES, endTimes.joinToString(","))
            .apply()
    }

    fun snapshot() = SettingsSnapshot(
        showWeekend = showWeekend,
        showTime = showTime,
        showInactiveCourses = showInactiveCourses,
        sectionHeightDp = sectionHeightDp,
        reminderEnabled = reminderEnabled,
        defaultReminderMinutes = defaultReminderMinutes,
        sectionTimes = sectionTimes,
        sectionEndTimes = sectionEndTimes
    )

    fun applySnapshot(snapshot: SettingsSnapshot) {
        val times = snapshot.sectionTimes.takeIf(::areValidStartTimes) ?: DEFAULT_SECTION_TIMES
        val endTimes = snapshot.sectionEndTimes.takeIf { areValidSectionTimes(times, it) }
            ?: inferSectionEndTimes(times)
        prefs.edit()
            .putBoolean(KEY_SHOW_WEEKEND, snapshot.showWeekend)
            .putBoolean(KEY_SHOW_TIME, snapshot.showTime)
            .putBoolean(KEY_SHOW_INACTIVE_COURSES, snapshot.showInactiveCourses)
            .putInt(KEY_SECTION_HEIGHT, snapshot.sectionHeightDp.coerceIn(56, 104))
            .putBoolean(KEY_REMINDER_ENABLED, snapshot.reminderEnabled)
            .putInt(KEY_DEFAULT_REMINDER, snapshot.defaultReminderMinutes)
            .putString(KEY_SECTION_TIMES, times.joinToString(","))
            .putString(KEY_SECTION_END_TIMES, endTimes.joinToString(","))
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
        private const val KEY_SECTION_END_TIMES = "section_end_times"
        private const val DEFAULT_SECTION_DURATION_MINUTES = 45

        val DEFAULT_SECTION_TIMES = listOf(
            "08:00", "08:50", "09:50", "10:40",
            "11:30", "14:30", "15:20", "16:20",
            "17:10", "19:00", "19:50", "20:40"
        )

        val DEFAULT_SECTION_END_TIMES = listOf(
            "08:45", "09:35", "10:35", "11:25",
            "12:15", "15:15", "16:05", "17:05",
            "17:55", "19:45", "20:35", "21:25"
        )

        fun isValidTime(value: String): Boolean {
            return Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d$").matches(value)
        }

        fun areValidSectionTimes(startTimes: List<String>, endTimes: List<String>): Boolean {
            if (!areValidStartTimes(startTimes) || endTimes.size != SECTION_COUNT ||
                !endTimes.all(::isValidTime)
            ) return false

            val starts = startTimes.map(::timeToMinutes)
            val ends = endTimes.map(::timeToMinutes)
            return starts.zip(ends).all { (start, end) -> start < end } &&
                ends.dropLast(1).zip(starts.drop(1)).all { (end, nextStart) -> end <= nextStart }
        }

        fun inferSectionEndTimes(startTimes: List<String>): List<String> {
            if (!areValidStartTimes(startTimes)) return DEFAULT_SECTION_END_TIMES
            val starts = startTimes.map(::timeToMinutes)
            return starts.mapIndexed { index, start ->
                val end = minOf(
                    start + DEFAULT_SECTION_DURATION_MINUTES,
                    starts.getOrElse(index + 1) { 23 * 60 + 59 }
                )
                "%02d:%02d".format(Locale.ROOT, end / 60, end % 60)
            }
        }

        private fun areValidStartTimes(times: List<String>): Boolean {
            return times.size == SECTION_COUNT && times.all(::isValidTime) &&
                times.map(::timeToMinutes).zipWithNext().all { (first, second) -> first < second }
        }

        private fun timeToMinutes(value: String): Int {
            val (hour, minute) = value.split(':').map(String::toInt)
            return hour * 60 + minute
        }
    }
}
