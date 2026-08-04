package com.courseschedule.domain

import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.utils.SchedulePreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class ReminderTimeCalculatorTest {

    @Test
    fun reminderUsesSemesterDateAndCustomSectionTime() {
        val monday = calendar(2026, Calendar.SEPTEMBER, 7, 0, 0)
        val now = calendar(2026, Calendar.SEPTEMBER, 7, 7, 0)
        val expected = calendar(2026, Calendar.SEPTEMBER, 9, 8, 15)
        val semester = Semester(name = "秋季", startDate = monday, totalWeeks = 20)
        val course = Course(
            courseName = "编译原理",
            dayOfWeek = 3,
            startSection = 1,
            endSection = 2,
            startWeek = 1,
            endWeek = 16,
            reminderMinutes = 15
        )
        val times = SchedulePreferences.DEFAULT_SECTION_TIMES.toMutableList().apply {
            this[0] = "08:30"
        }

        assertEquals(expected, ReminderTimeCalculator.nextReminderTime(course, semester, times, now))
    }

    @Test
    fun evenWeekCourseSkipsOddWeek() {
        val monday = calendar(2026, Calendar.SEPTEMBER, 7, 0, 0)
        val semester = Semester(name = "秋季", startDate = monday, totalWeeks = 20)
        val course = Course(
            courseName = "编译原理",
            dayOfWeek = 1,
            startSection = 1,
            endSection = 2,
            startWeek = 1,
            endWeek = 4,
            weekType = 2,
            reminderMinutes = 10
        )
        val expected = calendar(2026, Calendar.SEPTEMBER, 14, 7, 50)

        assertEquals(
            expected,
            ReminderTimeCalculator.nextReminderTime(course, semester, SchedulePreferences.DEFAULT_SECTION_TIMES, monday)
        )
    }

    private fun calendar(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        return Calendar.getInstance().apply {
            set(year, month, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
