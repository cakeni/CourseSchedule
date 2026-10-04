package com.courseschedule.ui

import com.courseschedule.domain.WeekMotionStyle
import org.junit.Assert.*
import org.junit.Test

class WeekSlideMotionTest {
    @Test fun aLandedWeekHasItsExactOriginalGeometryAndOpacity() {
        val frame = WeekSlideMotion.frame(0f, 312f, 1f, true)
        assertEquals(0f, frame.offsetX, 0f)
        assertEquals(1f, frame.scale, 0f)
        assertEquals(1f, frame.opacity, 0f)
    }

    @Test fun reverseAndInterruptedDragsKeepAConsistentSpaceBetweenWeeks() {
        for (progress in listOf(0f, .2f, .7f, .35f, .9f, 1f)) {
            val from = WeekSlideMotion.frame(-progress, 312f, 1f, true)
            val to = WeekSlideMotion.frame(1f - progress, 312f, 1f, true)
            assertEquals(324f, to.offsetX - from.offsetX, .0001f)
            assertTrue(from.scale in .98f..1f && to.scale in .98f..1f)
            assertTrue(from.opacity in .87f..1f && to.opacity in .87f..1f)
            val reverse = WeekSlideMotion.frame(progress, 312f, 1f, true)
            assertEquals(-from.offsetX, reverse.offsetX, 0f)
            assertEquals(from.scale, reverse.scale, 0f)
        }
    }

    @Test fun reducedMotionRemovesDepthAndGapsButKeepsDirectSwipeNavigation() {
        val frame = WeekSlideMotion.frame(-.5f, 312f, 1f, false)
        assertEquals(-156f, frame.offsetX, 0f)
        assertEquals(1f, frame.scale, 0f)
        assertEquals(1f, frame.opacity, 0f)
    }

    @Test fun absentOrUnknownSettingsUseSoftPagingAndBothChoicesRoundTrip() {
        assertEquals(WeekMotionStyle.SOFT_SLIDE, WeekMotionStyle.fromStoredValue(null))
        assertEquals(WeekMotionStyle.SOFT_SLIDE, WeekMotionStyle.fromStoredValue("future_effect"))
        WeekMotionStyle.entries.forEach { assertEquals(it, WeekMotionStyle.fromStoredValue(it.storedValue)) }
    }
}
