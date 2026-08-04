package com.courseschedule.ui.importdata

import com.courseschedule.data.entity.Course
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
}
