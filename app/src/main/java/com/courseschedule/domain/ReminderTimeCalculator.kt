package com.courseschedule.domain

import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import java.util.Calendar

object ReminderTimeCalculator {

    fun nextReminderTime(
        course: Course,
        semester: Semester,
        sectionTimes: List<String>,
        now: Long = System.currentTimeMillis()
    ): Long? {
        if (course.reminderMinutes <= 0 || course.startSection !in sectionTimes.indices.map { it + 1 }) {
            return null
        }
        val (hour, minute) = parseTime(sectionTimes[course.startSection - 1]) ?: return null
        for (week in course.startWeek..minOf(course.endWeek, semester.totalWeeks)) {
            if (!ScheduleRules.isCourseInWeek(course, week)) continue
            val reminder = Calendar.getInstance().apply {
                timeInMillis = semester.startDate
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_MONTH, (week - 1) * 7 + course.dayOfWeek - 1)
                add(Calendar.MINUTE, -course.reminderMinutes)
            }.timeInMillis
            if (reminder > now) return reminder
        }
        return null
    }

    private fun parseTime(value: String): Pair<Int, Int>? {
        val parts = value.split(':')
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }
}
