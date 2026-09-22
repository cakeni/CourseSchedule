package com.courseschedule.ui

import org.junit.Assert.*
import org.junit.Test

class ScheduleReturnMotionTest {
    @Test fun onlyExplicitReturnSourcesAreAccepted() {
        assertTrue(ScheduleReturnMotion.accepts("SETTINGS"))
        assertTrue(ScheduleReturnMotion.accepts("IMPORT"))
        listOf(null, "", "HOME", "RESUME", "REFRESH", "WEEK", "DETAIL").forEach {
            assertFalse(ScheduleReturnMotion.accepts(it))
        }
    }

    @Test fun twoCardsFinishAt500msAndDenseSchedulesStayWithin560ms() {
        assertEquals(0L, ScheduleReturnMotion.delay(0))
        assertEquals(80L, ScheduleReturnMotion.delay(1))
        assertEquals(140L, ScheduleReturnMotion.delay(2))
        assertEquals(500L, ScheduleReturnMotion.duration(2))
        for (count in 3..100) assertEquals(560L, ScheduleReturnMotion.duration(count))
    }

    @Test fun cardsAreReadableBeforeTheySettle() {
        assertEquals(1f, ScheduleReturnMotion.fraction(160f, ScheduleReturnMotion.FADE_MS), 0f)
        assertTrue(ScheduleReturnMotion.fraction(160f, ScheduleReturnMotion.MOVE_MS) < 0.5f)
        assertEquals(0f, ScheduleReturnMotion.fraction(-80f, ScheduleReturnMotion.FADE_MS), 0f)
        assertEquals(1f, ScheduleReturnMotion.fraction(560f, ScheduleReturnMotion.MOVE_MS), 0f)
    }
}
