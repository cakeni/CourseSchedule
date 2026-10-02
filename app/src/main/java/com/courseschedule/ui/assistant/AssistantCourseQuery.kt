package com.courseschedule.ui.assistant

import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.domain.ScheduleRules
import java.time.LocalDate

internal data class AssistantCourseQuery(val courseName: String? = null, val teacher: String? = null,
    val classroom: String? = null, val dayOfWeek: Int? = null, val week: Int? = null, val date: String? = null,
    val weekScope: String? = null) {
    fun find(courses: List<Course>, semester: Semester, displayedWeek: Int): List<Course> {
        val parsedDate = date?.let { LocalDate.parse(it) }
        val targetWeek = parsedDate?.let { requireNotNull(AssistantCalendar.week(semester, it)) { "指定日期不在本学期内。" } }
            ?: weekScope?.let { AssistantCalendar.scopedWeeks(it, semester, displayedWeek).singleOrNull() } ?: week
        val targetDay = parsedDate?.dayOfWeek?.value ?: dayOfWeek
        return courses.filter { course -> course.semesterId == semester.id &&
            (courseName == null || course.courseName.contains(courseName, ignoreCase = true)) &&
            (teacher == null || course.teacher.contains(teacher, ignoreCase = true)) &&
            (classroom == null || course.classroom.contains(classroom, ignoreCase = true)) &&
            (targetDay == null || course.dayOfWeek == targetDay) &&
            (targetWeek == null || ScheduleRules.isCourseInWeek(course, targetWeek)) }
    }

    companion object {
        // Short, unambiguous queries can be answered without a model or configured API.
        fun quick(text: String, today: LocalDate = LocalDate.now()): AssistantCourseQuery? {
            val normalized = text.trim().trimEnd('？', '?', '。').replace(" ", "")
            return when (normalized) {
                "今天有哪些课", "今天有什么课", "今天的课", "查今天的课" -> AssistantCourseQuery(date = today.toString())
                "明天有哪些课", "明天有什么课", "明天的课", "查明天的课" -> AssistantCourseQuery(date = today.plusDays(1).toString())
                "本周有哪些课", "这周有哪些课", "本周的课" -> AssistantCourseQuery(weekScope = "this_week")
                "下周有哪些课", "下周的课" -> AssistantCourseQuery(weekScope = "next_week")
                else -> Regex("(?:查)?周([一二三四五六日天])(?:有哪些课|有什么课|的课)").matchEntire(normalized)?.let {
                    AssistantCourseQuery(dayOfWeek = "一二三四五六日天".indexOf(it.groupValues[1]).let { day -> if (day == 7) 7 else day + 1 },
                        weekScope = "displayed_week") }
            }
        }
    }
}
