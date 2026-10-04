package com.courseschedule.view

import org.junit.Assert.*
import org.junit.Test

class CalendarIllustrationMotionTest {
    @Test fun theEntranceFinishesAndNeverWrapsOrWaitsForAnotherCycle() {
        assertEquals(0f, CalendarIllustrationMotion.progress(0L), 0f)
        assertEquals(.5f, CalendarIllustrationMotion.progress(1400L), 0f)
        listOf(2800L, 6400L, 10000L, Long.MAX_VALUE).forEach {
            assertEquals(1f, CalendarIllustrationMotion.progress(it), 0f)
        }
    }

    @Test fun eachEntranceStartsImmediatelyAndAdvancesMonotonicallyToTheFinalPose() {
        assertEquals(0f, CalendarIllustrationMotion.progress(-100L), 0f)
        assertTrue(CalendarIllustrationMotion.progress(16L) > 0f)
        var previous = 0f
        for (time in 0L..3000L step 16L) {
            val progress = CalendarIllustrationMotion.progress(time)
            assertTrue(progress >= previous && progress in 0f..1f)
            previous = progress
        }
    }
}
