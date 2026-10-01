package com.courseschedule.ui.importdata

import com.courseschedule.data.entity.Course
import com.courseschedule.domain.ScheduleRules
import com.courseschedule.domain.AiCourseColors

data class ImportAnalysis(
    val accepted: List<Course>,
    val conflicts: List<Course>,
    val duplicates: List<Course>,
    val invalid: List<Course>
)

object ImportAnalyzer {
    fun analyze(
        parsed: List<Course>,
        existing: List<Course>,
        semesterId: Long,
        totalWeeks: Int
    ): ImportAnalysis {
        val accepted = mutableListOf<Course>()
        val conflicts = mutableListOf<Course>()
        val duplicates = mutableListOf<Course>()
        val invalid = mutableListOf<Course>()
        val seen = mutableListOf<Course>()
        val aiColors = mutableMapOf<String, Int>()
        existing.filter { it.semesterId == semesterId && AiCourseColors.isAiCourse(it) }
            .sortedBy { it.id }.forEach {
                aiColors.putIfAbsent(it.courseName.trim(), Math.floorMod(it.colorIndex, 16))
            }

        parsed.forEachIndexed { index, original ->
            val course = original.copy(
                id = 0,
                semesterId = semesterId,
                colorIndex = if (AiCourseColors.isAiCourse(original)) {
                    aiColors.getOrPut(original.courseName.trim()) { Math.floorMod(original.colorIndex, 16) }
                } else Math.floorMod(original.colorIndex.takeIf { it != 0 } ?: index, 16),
                createTime = System.currentTimeMillis()
            )
            when {
                !ScheduleRules.isValidCourse(course, totalWeeks) -> invalid += course
                (existing + seen).any { ScheduleRules.isDuplicate(it, course) } -> duplicates += course
                (existing + accepted + conflicts).any { ScheduleRules.coursesOverlap(it, course) } -> {
                    conflicts += course
                    seen += course
                }
                else -> {
                    accepted += course
                    seen += course
                }
            }
        }
        return ImportAnalysis(accepted, conflicts, duplicates, invalid)
    }
}
