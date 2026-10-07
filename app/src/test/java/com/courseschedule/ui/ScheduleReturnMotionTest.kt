package com.courseschedule.ui

import org.junit.Assert.*
import org.junit.Test

class ScheduleReturnMotionTest {
    @Test fun onlyExplicitReturnSourcesAreAccepted() {
        assertTrue(ScheduleReturnMotion.accepts("SETTINGS"))
        assertTrue(ScheduleReturnMotion.accepts("IMPORT"))
        assertTrue(ScheduleReturnMotion.accepts("TODO"))
        assertTrue(ScheduleReturnMotion.accepts("ASSISTANT"))
        listOf(null, "", "HOME", "RESUME", "REFRESH", "WEEK", "DETAIL").forEach {
            assertFalse(ScheduleReturnMotion.accepts(it))
        }
    }

    @Test fun staggerFollowsGridPositionAndDenseSchedulesFinishWithin524ms() {
        assertEquals(0L, ScheduleReturnMotion.delay(1, 0))
        assertEquals(9L, ScheduleReturnMotion.delay(2, 0))
        assertEquals(7L, ScheduleReturnMotion.delay(1, 1))
        assertEquals(0L, ScheduleReturnMotion.delay(1, -2))
        assertEquals(84L, ScheduleReturnMotion.delay(7, 11))
        assertEquals(524L, ScheduleReturnMotion.duration(1000L))
    }

    @Test fun cardsAreReadableBeforeTheySettle() {
        assertEquals(1f, ScheduleReturnMotion.fraction(140f, ScheduleReturnMotion.FADE_MS), 0f)
        assertTrue(ScheduleReturnMotion.progress(140f) > 0.8f)
        assertEquals(0f, ScheduleReturnMotion.fraction(-80f, ScheduleReturnMotion.FADE_MS), 0f)
        assertEquals(1f, ScheduleReturnMotion.fraction(560f, ScheduleReturnMotion.MOVE_MS), 0f)
    }

    @Test fun returnSpringNeverOvershootsOrReversesAndFinishesExactly() {
        val samples = (0..440).map { ScheduleReturnMotion.progress(it.toFloat()) }
        assertEquals(0f, samples.first(), 0f)
        assertEquals(1f, samples.last(), 0f)
        assertTrue(samples.all { it in 0f..1f })
        assertTrue(samples.zipWithNext().all { (before, after) -> after >= before })
    }
}
