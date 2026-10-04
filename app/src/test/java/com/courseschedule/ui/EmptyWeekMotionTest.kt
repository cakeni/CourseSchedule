package com.courseschedule.ui

import org.junit.Assert.*
import org.junit.Test

class EmptyWeekMotionTest {
    @Test fun arrivalEndsAtTheExactRestingStateInBothStyles() {
        listOf(false, true).forEach { sliding ->
            val frame = EmptyWeekMotion.frame(0f, 3f, sliding, true)
            assertEquals(EmptyWeekMotion.Frame(0f, 0f, 1f, 1f), frame)
            assertEquals(0f, EmptyWeekMotion.frame(1f, 3f, sliding, true).opacity, 0f)
        }
    }

    @Test fun reversingTheDragRetracesItsGeometryWithoutAnIndependentClock() {
        listOf(false, true).forEach { sliding ->
            listOf(.1f, .35f, .8f, .35f, .1f).forEach { distance ->
                val forward = EmptyWeekMotion.frame(distance, 2f, sliding, true)
                val backward = EmptyWeekMotion.frame(-distance, 2f, sliding, true)
                assertEquals(-forward.offsetX, backward.offsetX, 0f)
                assertEquals(forward.offsetY, backward.offsetY, 0f)
                assertEquals(forward.scale, backward.scale, 0f)
                assertEquals(forward.opacity, backward.opacity, 0f)
                assertTrue(forward.scale in .86f..1f)
                assertTrue(forward.opacity in 0f..1f)
            }
        }
    }

    @Test fun localReplacementNeverDrawsTwoTextCopiesAtOnce() {
        for (step in 0..100) {
            val progress = step / 100f
            val outgoing = EmptyWeekMotion.frame(-progress, 1f, false, true)
            val incoming = EmptyWeekMotion.frame(1f - progress, 1f, false, true)
            assertTrue(outgoing.opacity == 0f || incoming.opacity == 0f)
        }
    }

    @Test fun reducedMotionRemovesInsetScaleLiftAndFading() {
        listOf(false, true).forEach { sliding ->
            val frame = EmptyWeekMotion.frame(.25f, 3f, sliding, false)
            assertEquals(EmptyWeekMotion.Frame(0f, 0f, 1f, 1f), frame)
        }
    }
}
