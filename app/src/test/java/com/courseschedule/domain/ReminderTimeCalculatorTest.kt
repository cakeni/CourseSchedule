package com.courseschedule.domain

import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.utils.SchedulePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class ReminderTimeCalculatorTest {

    private fun course(reminderMinutes: Int = 15) = Course(courseName = "测试课", dayOfWeek = 1,
        startSection = 1, endSection = 2, startWeek = 1, endWeek = 2, reminderMinutes = reminderMinutes)

    @Test fun enablingDuringReminderWindowCatchesUpBeforeClass() {
        val monday = calendar(2026, Calendar.SEPTEMBER, 7, 0, 0)
        val now = calendar(2026, Calendar.SEPTEMBER, 7, 7, 50)
        val next = ReminderTimeCalculator.nextOccurrence(course(), Semester(name = "秋季", startDate = monday),
            listOf("08:00"), now)
        assertEquals(now, next?.reminderTime)
        assertEquals(calendar(2026, Calendar.SEPTEMBER, 7, 8, 0), next?.classStart)
    }

    @Test fun alreadyDeliveredClassDoesNotRepeatWhenAppResumes() {
        val monday = calendar(2026, Calendar.SEPTEMBER, 7, 0, 0)
        val next = ReminderTimeCalculator.nextOccurrence(course(), Semester(name = "秋季", startDate = monday),
            listOf("08:00"), calendar(2026, Calendar.SEPTEMBER, 7, 7, 50),
            calendar(2026, Calendar.SEPTEMBER, 7, 8, 0))
        assertEquals(calendar(2026, Calendar.SEPTEMBER, 14, 7, 45), next?.reminderTime)
    }

    @Test fun expiredFinalClassIsNotDelivered() {
        val monday = calendar(2026, Calendar.SEPTEMBER, 7, 0, 0)
        assertNull(ReminderTimeCalculator.nextOccurrence(course().copy(endWeek = 1),
            Semester(name = "秋季", startDate = monday), listOf("08:00"),
            calendar(2026, Calendar.SEPTEMBER, 7, 8, 0)))
    }

    @Test fun crossMidnightReminderUsesPreviousDay() {
        val monday = calendar(2026, Calendar.SEPTEMBER, 7, 0, 0)
        assertEquals(calendar(2026, Calendar.SEPTEMBER, 6, 23, 50),
            ReminderTimeCalculator.nextReminderTime(course(30), Semester(name = "秋季", startDate = monday),
                listOf("00:20"), calendar(2026, Calendar.SEPTEMBER, 6, 20, 0)))
    }

    @Test fun disabledAndInvalidSectionTimesAreNotScheduled() {
        val semester = Semester(name = "秋季", startDate = calendar(2026, Calendar.SEPTEMBER, 7, 0, 0))
        assertNull(ReminderTimeCalculator.nextReminderTime(course(-1), semester, listOf("08:00")))
        assertNull(ReminderTimeCalculator.nextReminderTime(course(), semester, listOf("25:00")))
    }

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
