package com.courseschedule.ui.assistant

import com.courseschedule.data.entity.Semester
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

internal object AssistantCalendar {
    fun startDate(semester: Semester): LocalDate = Instant.ofEpochMilli(semester.startDate)
        .atZone(ZoneId.systemDefault()).toLocalDate()

    fun week(semester: Semester, date: LocalDate): Int? {
        val days = ChronoUnit.DAYS.between(startDate(semester), date)
        if (days < 0 || days >= semester.totalWeeks * 7L) return null
        return (days / 7).toInt() + 1
    }

    fun scopedWeeks(scope: String, semester: Semester, displayedWeek: Int, today: LocalDate = LocalDate.now()): List<Int> = when (scope) {
        "all" -> (1..semester.totalWeeks).toList()
        "displayed_week" -> listOf(displayedWeek.also { require(it in 1..semester.totalWeeks) })
        "this_week", "next_week" -> listOf(requireNotNull(week(semester,
            if (scope == "next_week") today.plusDays(7) else today)) { "指定日期不在本学期内，请明确课程日期或周次。" })
        else -> throw IllegalArgumentException("不支持的周次范围，请明确周次。")
    }
}
