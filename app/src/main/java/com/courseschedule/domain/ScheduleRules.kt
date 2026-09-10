package com.courseschedule.domain

import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester

enum class SemesterPhase {
    BEFORE,
    ACTIVE,
    AFTER
}

data class SemesterWeekStatus(
    val phase: SemesterPhase,
    val week: Int
)

object ScheduleRules {

    private const val WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000

    fun semesterWeekStatus(
        semester: Semester,
        now: Long = System.currentTimeMillis()
    ): SemesterWeekStatus {
        val rawWeek = Math.floorDiv(now - semester.startDate, WEEK_MILLIS).toInt() + 1
        return when {
            rawWeek < 1 -> SemesterWeekStatus(SemesterPhase.BEFORE, 1)
            rawWeek > semester.totalWeeks -> SemesterWeekStatus(
                SemesterPhase.AFTER,
                semester.totalWeeks
            )
            else -> SemesterWeekStatus(SemesterPhase.ACTIVE, rawWeek)
        }
    }

    fun isCourseInWeek(course: Course, week: Int): Boolean {
        if (week !in course.startWeek..course.endWeek) return false
        return when (course.weekType) {
            1 -> week % 2 == 1
            2 -> week % 2 == 0
            else -> true
        }
    }

    fun selectCoursesForWeek(
        courses: List<Course>,
        week: Int,
        showInactiveCourses: Boolean
    ): List<Course> = courses
        .groupBy { Triple(it.dayOfWeek, it.startSection, it.endSection) }
        .values
        .mapNotNull { sameSlot ->
            sameSlot.mapNotNull { course ->
                val nextWeek = (maxOf(week, course.startWeek)..course.endWeek)
                    .firstOrNull { isCourseInWeek(course, it) }
                if (nextWeek == null || (!showInactiveCourses && nextWeek != week)) {
                    null
                } else {
                    course to nextWeek
                }
            }.minByOrNull { it.second }?.first
        }

    fun coursesOverlap(first: Course, second: Course): Boolean {
        if (first.semesterId != second.semesterId || first.dayOfWeek != second.dayOfWeek) {
            return false
        }
        if (first.endSection < second.startSection || second.endSection < first.startSection) {
            return false
        }
        val firstWeek = maxOf(first.startWeek, second.startWeek)
        val lastWeek = minOf(first.endWeek, second.endWeek)
        if (firstWeek > lastWeek) return false
        return (firstWeek..lastWeek).any { week ->
            isCourseInWeek(first, week) && isCourseInWeek(second, week)
        }
    }

    fun isDuplicate(first: Course, second: Course): Boolean {
        return first.courseName.trim().equals(second.courseName.trim(), ignoreCase = true) &&
            first.teacher.trim().equals(second.teacher.trim(), ignoreCase = true) &&
            first.classroom.trim().equals(second.classroom.trim(), ignoreCase = true) &&
            first.dayOfWeek == second.dayOfWeek &&
            first.startSection == second.startSection &&
            first.endSection == second.endSection &&
            first.startWeek == second.startWeek &&
            first.endWeek == second.endWeek &&
            first.weekType == second.weekType
    }

    fun isValidCourse(course: Course, totalWeeks: Int): Boolean {
        return course.courseName.isNotBlank() &&
            course.dayOfWeek in 1..7 &&
            course.startSection in 1..12 &&
            course.endSection in course.startSection..12 &&
            course.startWeek in 1..totalWeeks &&
            course.endWeek in course.startWeek..totalWeeks &&
            course.weekType in 0..2
    }
}
