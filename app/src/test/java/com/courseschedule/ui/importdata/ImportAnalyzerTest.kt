package com.courseschedule.ui.importdata

import com.courseschedule.data.entity.Course
import com.courseschedule.domain.AiCourseColors
import org.junit.Assert.assertEquals
import org.junit.Test

class ImportAnalyzerTest {

    @Test
    fun separatesAcceptedConflictingDuplicateAndInvalidCourses() {
        val existing = course("高数", day = 1, startSection = 1)
        val duplicate = existing.copy(id = 0)
        val conflict = course("线代", day = 1, startSection = 2)
        val accepted = course("英语", day = 2, startSection = 1)
        val invalid = course("", day = 3, startSection = 1)

        val analysis = ImportAnalyzer.analyze(
            listOf(duplicate, conflict, accepted, invalid),
            listOf(existing),
            semesterId = 1,
            totalWeeks = 20
        )

        assertEquals(listOf("英语"), analysis.accepted.map { it.courseName })
        assertEquals(listOf("线代"), analysis.conflicts.map { it.courseName })
        assertEquals(1, analysis.duplicates.size)
        assertEquals(1, analysis.invalid.size)
    }

    private fun course(name: String, day: Int, startSection: Int) = Course(
        courseName = name,
        dayOfWeek = day,
        startSection = startSection,
        endSection = startSection + 1,
        startWeek = 1,
        endWeek = 16,
        semesterId = 1
    )

    @Test
    fun aiImportKeepsZeroColorAndReusesExistingCourseColorWithoutChangingOrdinaryImport() {
        val ai = course("P", 1, 1).copy(note = AiCourseColors.DEEPSEEK_NOTE, colorIndex = 0)
        val later = ai.copy(dayOfWeek = 3, classroom = "另一间教室", teacher = "另一位教师")
        val initial = ImportAnalyzer.analyze(listOf(ai, later), emptyList(), 1, 20)
        assertEquals(listOf(0, 0), initial.accepted.map { it.colorIndex })

        val earlier = ai.copy(id = 3, dayOfWeek = 2, colorIndex = 11)
        val differentSemester = ai.copy(id = 1, semesterId = 2, colorIndex = 6)
        val appended = ImportAnalyzer.analyze(listOf(later), listOf(differentSemester, earlier), 1, 20)
        assertEquals(11, appended.accepted.single().colorIndex)

        val ordinary = ImportAnalyzer.analyze(listOf(ai.copy(note = "", colorIndex = 4), later.copy(note = "", colorIndex = 4)),
            emptyList(), 1, 20)
        assertEquals(listOf(4, 4), ordinary.accepted.map { it.colorIndex })
    }
}
