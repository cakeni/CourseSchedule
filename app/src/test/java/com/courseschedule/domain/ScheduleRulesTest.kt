package com.courseschedule.domain

import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleRulesTest {

    @Test
    fun oddAndEvenCoursesAtSameTimeDoNotConflict() {
        val odd = course(weekType = 1)
        val even = course(weekType = 2)

        assertFalse(ScheduleRules.coursesOverlap(odd, even))
    }

    @Test
    fun weeklyCourseConflictsWithOddCourse() {
        val weekly = course(weekType = 0)
        val odd = course(weekType = 1)

        assertTrue(ScheduleRules.coursesOverlap(weekly, odd))
    }

    @Test
    fun overlapRequiresAnEligibleWeekInsideIntersection() {
        val first = course(startWeek = 2, endWeek = 2, weekType = 1)
        val second = course(startWeek = 2, endWeek = 2, weekType = 0)

        assertFalse(ScheduleRules.coursesOverlap(first, second))
    }

    @Test
    fun semesterStatusDistinguishesBeforeActiveAndAfter() {
        val week = 7L * 24 * 60 * 60 * 1000
        val semester = Semester(name = "测试学期", startDate = 10 * week, totalWeeks = 2)

        assertEquals(SemesterPhase.BEFORE, ScheduleRules.semesterWeekStatus(semester, 10 * week - 1).phase)
        assertEquals(2, ScheduleRules.semesterWeekStatus(semester, 11 * week).week)
        assertEquals(SemesterPhase.AFTER, ScheduleRules.semesterWeekStatus(semester, 12 * week).phase)
    }

    @Test
    fun sameSlotKeepsCurrentOrNearestUpcomingCourseOnly() {
        val ethics = course(name = "计算机伦理", startWeek = 5, endWeek = 5)
        val project = course(name = "IT项目管理", startWeek = 10, endWeek = 10)

        assertEquals(listOf(ethics), ScheduleRules.selectCoursesForWeek(listOf(project, ethics), 3, true))
        assertEquals(listOf(ethics), ScheduleRules.selectCoursesForWeek(listOf(project, ethics), 5, true))
        assertEquals(listOf(project), ScheduleRules.selectCoursesForWeek(listOf(project, ethics), 6, true))
        assertTrue(ScheduleRules.selectCoursesForWeek(listOf(project, ethics), 11, true).isEmpty())
        assertTrue(ScheduleRules.selectCoursesForWeek(listOf(project, ethics), 3, false).isEmpty())
    }

    private fun course(
        name: String = "高等数学",
        startWeek: Int = 1,
        endWeek: Int = 16,
        weekType: Int = 0
    ) = Course(
        courseName = name,
        dayOfWeek = 1,
        startSection = 1,
        endSection = 2,
        startWeek = startWeek,
        endWeek = endWeek,
        weekType = weekType,
        semesterId = 1
    )
}
