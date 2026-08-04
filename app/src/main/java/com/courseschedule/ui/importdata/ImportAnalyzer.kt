package com.courseschedule.ui.importdata

import com.courseschedule.data.entity.Course
import com.courseschedule.domain.ScheduleRules

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

        parsed.forEachIndexed { index, original ->
            val course = original.copy(
                id = 0,
                semesterId = semesterId,
                colorIndex = Math.floorMod(original.colorIndex.takeIf { it != 0 } ?: index, 16),
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
