package com.courseschedule.ui.assistant

import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.domain.ScheduleRules

internal object AssistantChangeRules {
    const val MAX_OPERATIONS = 20
    const val MAX_RECORDS = 80

    fun weeks(course: Course) = (course.startWeek..course.endWeek).filter { ScheduleRules.isCourseInWeek(course, it) }

    fun sameArrangement(first: Course, second: Course) = first.semesterId == second.semesterId &&
        first.dayOfWeek == second.dayOfWeek && first.startSection == second.startSection &&
        first.endSection == second.endSection && weeks(first) == weeks(second)

    // Existing overlaps may stay, but their overlapping sections/weeks must not grow.
    private fun preservesOverlap(before: Course, after: Course, oldNeighbor: Course, neighbor: Course): Boolean {
        if (!sameArrangement(oldNeighbor, neighbor) || !ScheduleRules.coursesOverlap(before, oldNeighbor) ||
            before.dayOfWeek != after.dayOfWeek) return false
        val oldStart = maxOf(before.startSection, oldNeighbor.startSection)
        val oldEnd = minOf(before.endSection, oldNeighbor.endSection)
        val newStart = maxOf(after.startSection, neighbor.startSection)
        val newEnd = minOf(after.endSection, neighbor.endSection)
        return newStart >= oldStart && newEnd <= oldEnd && weeks(after).filter { ScheduleRules.isCourseInWeek(neighbor, it) }
            .all { ScheduleRules.isCourseInWeek(before, it) && ScheduleRules.isCourseInWeek(oldNeighbor, it) }
    }

    fun validate(incoming: List<Course>, existing: List<Course>, semester: Semester,
        originals: Map<Course, Course> = emptyMap(), baseline: List<Course> = existing) {
        require(incoming.size <= MAX_RECORDS) { "方案展开后超过 $MAX_RECORDS 项课程安排，请分批操作。" }
        val neighbors = baseline.associateBy { it.id }
        val accepted = mutableListOf<Course>()
        incoming.forEach { course ->
            require(ScheduleRules.isValidCourse(course, semester.totalWeeks) && course.semesterId == semester.id &&
                course.reminderMinutes in -1..1440) { "课程数据无效，本次未更改。" }
            require((existing + accepted).none { ScheduleRules.isDuplicate(it, course) }) {
                "发现重复课程：${course.courseName}。本次未更改。"
            }
            val before = originals[course]
            val conflicts = (existing + accepted).filter { neighbor -> ScheduleRules.coursesOverlap(course, neighbor) &&
                (before == null || (originals[neighbor] ?: neighbors[neighbor.id])?.let {
                    preservesOverlap(before, course, it, neighbor) } != true) }
            require(conflicts.isEmpty()) {
                "发现时间冲突：${course.courseName}。本次未更改，请换个时间。"
            }
            accepted += course
        }
    }

    fun conflictNeighbors(before: List<Course>, existing: List<Course>): List<Course> = existing.filter { neighbor ->
        before.none { it.id == neighbor.id } && before.any { ScheduleRules.coursesOverlap(it, neighbor) }
    }
}
