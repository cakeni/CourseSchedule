package com.courseschedule.ui

import com.courseschedule.data.entity.Course
import org.junit.Assert.*
import org.junit.Test

class CourseContinuityTest {
    private fun lesson(id: Long, day: Int = 1, name: String = "大学英语", teacher: String = "王老师") =
        Course(id = id, courseName = name, teacher = teacher, classroom = "教一 302", dayOfWeek = day,
            startSection = 1, endSection = 2, startWeek = 1, endWeek = 20, colorIndex = 5)

    @Test fun identicalIdsTakePriorityOverRepeatedNames() {
        val first = lesson(1)
        val second = lesson(2, day = 3)
        val pairs = CourseContinuity.match(listOf(first, second), listOf(second, first))
        assertEquals(listOf(1L to 1L, 2L to 2L), pairs.map { it.from.id to it.to.id })
    }

    @Test fun uniqueOddEvenLessonConnectsAcrossDifferentIdsAndPositions() {
        val pairs = CourseContinuity.match(listOf(lesson(1)), listOf(lesson(2, day = 4)))
        assertEquals(1, pairs.size)
        assertEquals(1, pairs.single().from.dayOfWeek)
        assertEquals(4, pairs.single().to.dayOfWeek)
    }

    @Test fun ambiguousOccurrencesAndDifferentTeachersAreNeverMerged() {
        assertTrue(CourseContinuity.match(listOf(lesson(1), lesson(2, day = 3)),
            listOf(lesson(3, day = 4))).isEmpty())
        assertTrue(CourseContinuity.match(listOf(lesson(1)), listOf(lesson(2, teacher = "李老师"))).isEmpty())
        assertTrue(CourseContinuity.match(listOf(lesson(1)), listOf(lesson(2, name = "大学物理"))).isEmpty())
    }

    @Test fun roomAndColorChangesDoNotBreakLessonIdentity() {
        val original = lesson(1)
        val changed = lesson(2, day = 4).copy(classroom = "教二 104", colorIndex = 9)
        assertEquals(listOf(CourseContinuityPair(original, changed)), CourseContinuity.match(listOf(original), listOf(changed)))
    }

    @Test fun repeatedLessonsConnectByTheirSlotsBeforeMatchingRemainingUniqueOccurrence() {
        val first = lesson(1)
        val second = lesson(2, day = 3)
        val replacement = lesson(3).copy(colorIndex = 2)
        val moved = lesson(4, day = 5)
        assertEquals(listOf(1L to 3L, 2L to 4L), CourseContinuity.match(listOf(first, second),
            listOf(replacement, moved)).map { it.from.id to it.to.id })
    }
}
