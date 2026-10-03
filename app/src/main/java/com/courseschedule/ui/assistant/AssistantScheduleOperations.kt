package com.courseschedule.ui.assistant

import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.domain.ScheduleRules
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

internal data class AssistantDateSelection(
    val date: String? = null,
    val dayOffset: Int? = null,
    val week: Int? = null,
    val weekOffset: Int? = null,
    val useDisplayedWeek: Boolean = false
)

internal data class AssistantCourseQuery(
    val courseName: String? = null,
    val teacher: String? = null,
    val classroom: String? = null,
    val dayOfWeek: Int? = null,
    val startSection: Int? = null,
    val endSection: Int? = null,
    val whenTo: AssistantDateSelection = AssistantDateSelection(),
    val freeSlots: Boolean = false
)

internal data class AssistantResolvedDate(val week: Int?, val dayOfWeek: Int?)

internal object AssistantScheduleOperations {
    const val MAX_RECORDS = 200

    fun alternativeSections(incoming: List<Course>, unchanged: List<Course>, origins: List<Course?>,
        baseline: List<Course>, index: Int, sectionCount: Int, limit: Int = 3): List<Course> {
        require(incoming.size == origins.size && index in incoming.indices && sectionCount in 1..12 && limit > 0)
        val course = incoming[index]
        val duration = course.endSection - course.startSection + 1
        val occupied = unchanged + incoming.filterIndexed { otherIndex, _ -> otherIndex != index }
        return (1..(sectionCount - duration + 1)).sortedBy { kotlin.math.abs(it - course.startSection) }
            .map { start -> course.copy(startSection = start, endSection = start + duration - 1) }
            .filter { candidate ->
                occupied.none { ScheduleRules.coursesOverlap(candidate, it) } &&
                    newConflicts(incoming.mapIndexed { rowIndex, row -> if (rowIndex == index) candidate else row },
                        unchanged, origins, baseline).isEmpty()
            }.take(limit)
    }

    fun startDate(semester: Semester): LocalDate = Instant.ofEpochMilli(semester.startDate)
        .atZone(ZoneId.systemDefault()).toLocalDate()

    fun weekOf(semester: Semester, date: LocalDate): Int =
        Math.floorDiv(ChronoUnit.DAYS.between(startDate(semester), date), 7L).toInt() + 1

    fun resolve(selection: AssistantDateSelection, semester: Semester, displayedWeek: Int,
        today: LocalDate = LocalDate.now()): AssistantResolvedDate {
        require(listOf(selection.date != null, selection.dayOffset != null, selection.week != null,
            selection.weekOffset != null, selection.useDisplayedWeek).count { it } <= 1) { "日期条件不能混用。" }
        val date = selection.date?.let { LocalDate.parse(it) } ?: selection.dayOffset?.let { today.plusDays(it.toLong()) }
        val week = when {
            date != null -> weekOf(semester, date)
            selection.week != null -> selection.week
            selection.weekOffset != null -> {
                val actual = weekOf(semester, today)
                require(actual in 1..semester.totalWeeks) { "今天不在本学期内，请指定学期内的日期或周次。" }
                actual + selection.weekOffset
            }
            selection.useDisplayedWeek -> displayedWeek
            else -> null
        }
        require(week == null || week in 1..semester.totalWeeks) { "指定日期或周次不在本学期内，请重新选择。" }
        return AssistantResolvedDate(week, date?.dayOfWeek?.value)
    }

    fun query(query: AssistantCourseQuery, courses: List<Course>, semester: Semester, displayedWeek: Int,
        today: LocalDate = LocalDate.now()): List<Course> {
        val date = resolve(query.whenTo, semester, displayedWeek, today)
        require(date.dayOfWeek == null || query.dayOfWeek == null || date.dayOfWeek == query.dayOfWeek) {
            "日期与星期不一致，请重新选择。"
        }
        val day = date.dayOfWeek ?: query.dayOfWeek
        val start = query.startSection ?: 1
        val end = query.endSection ?: if (query.startSection != null) start else 12
        return courses.filter { course ->
            course.semesterId == semester.id &&
                (query.courseName == null || course.courseName.contains(query.courseName, ignoreCase = true)) &&
                (query.teacher == null || course.teacher.contains(query.teacher, ignoreCase = true)) &&
                (query.classroom == null || course.classroom.contains(query.classroom, ignoreCase = true)) &&
                (day == null || course.dayOfWeek == day) &&
                (date.week == null || ScheduleRules.isCourseInWeek(course, date.week)) &&
                course.startSection <= end && course.endSection >= start
        }.sortedWith(compareBy<Course> { it.dayOfWeek }.thenBy { it.startSection }.thenBy { it.id })
    }

    fun freeSections(query: AssistantCourseQuery, courses: List<Course>, semester: Semester, displayedWeek: Int,
        sectionCount: Int, today: LocalDate = LocalDate.now()): List<Int> {
        val date = resolve(query.whenTo, semester, displayedWeek, today)
        require(date.week != null && (date.dayOfWeek != null || query.dayOfWeek != null)) {
            "查空闲时间请指定某一周的某一天或明确日期。"
        }
        require(query.courseName == null && query.teacher == null && query.classroom == null) {
            "空闲时间需要根据当天全部课程计算。"
        }
        val occupied = this.query(query, courses, semester, displayedWeek, today)
            .flatMap { (it.startSection..it.endSection).toList() }.toSet()
        val start = query.startSection ?: 1
        val end = query.endSection ?: if (query.startSection != null) start else sectionCount
        return (start..minOf(end, sectionCount)).filter { it !in occupied }
    }

    /** Keep only overlap cells that already existed, including their original week/day/section. */
    fun newConflicts(incoming: List<Course>, unchanged: List<Course>, origins: List<Course?>,
        baseline: List<Course>): List<Pair<Course, Course>> {
        require(incoming.size == origins.size)
        val byId = baseline.associateBy { it.id }
        fun inherited(first: Course, second: Course, oldFirst: Course?, oldSecond: Course?): Boolean {
            if (oldFirst == null || oldSecond == null || oldFirst.id == oldSecond.id || first.dayOfWeek != oldFirst.dayOfWeek ||
                second.dayOfWeek != oldSecond.dayOfWeek) return false
            val start = maxOf(first.startSection, second.startSection)
            val end = minOf(first.endSection, second.endSection)
            if (start < maxOf(oldFirst.startSection, oldSecond.startSection) ||
                end > minOf(oldFirst.endSection, oldSecond.endSection)) return false
            return (maxOf(first.startWeek, second.startWeek)..minOf(first.endWeek, second.endWeek)).all { week ->
                !ScheduleRules.isCourseInWeek(first, week) || !ScheduleRules.isCourseInWeek(second, week) ||
                    (ScheduleRules.isCourseInWeek(oldFirst, week) && ScheduleRules.isCourseInWeek(oldSecond, week))
            }
        }
        return buildList {
            incoming.forEachIndexed { index, course ->
                unchanged.forEach { other ->
                    if (ScheduleRules.coursesOverlap(course, other) &&
                        !inherited(course, other, origins[index], byId[other.id])) add(course to other)
                }
                incoming.take(index).forEachIndexed { otherIndex, other ->
                    if (ScheduleRules.coursesOverlap(course, other) &&
                        !inherited(course, other, origins[index], origins[otherIndex])) add(course to other)
                }
            }
        }
    }
}
