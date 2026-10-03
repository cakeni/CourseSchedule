package com.courseschedule.ui

import org.junit.Assert.*
import org.junit.Test

class SchedulePageMotionTest {
    @Test fun interruptedOrReversedGestureUsesTheSameBoundedPhase() {
        val positions = listOf(0f, 0.2f, 0.7f, 0.3f, 0.9f, 1f)
        val phases = positions.map(SchedulePageMotion::phase)
        assertEquals(0f, phases.first(), 0f)
        assertEquals(1f, phases.last(), 0f)
        assertTrue(phases.all { it in 0f..1f })
        assertTrue(phases[3] < phases[2])
        assertEquals(SchedulePageMotion.phase(0.3f), phases[3], 0f)
    }

    @Test fun forwardAndReverseHaveMatchingOpacityAndNoOvershoot() {
        for (step in 0..100) {
            val progress = step / 100f
            assertEquals(1f, SchedulePageMotion.phase(progress) + SchedulePageMotion.phase(1f - progress), 0.00001f)
        }
        assertEquals(0f, SchedulePageMotion.phase(-1f), 0f)
        assertEquals(1f, SchedulePageMotion.phase(2f), 0f)
    }

    @Test fun replacementTextNeverDrawsBothContentsAtOnce() {
        for (step in 0..100) {
            val progress = step / 100f
            val outgoing = SchedulePageMotion.outgoingText(progress)
            val incoming = SchedulePageMotion.incomingText(progress)
            assertTrue(outgoing in 0f..1f && incoming in 0f..1f)
            assertEquals(0f, outgoing * incoming, 0f)
        }
        assertEquals(1f, SchedulePageMotion.outgoingText(0f), 0f)
        assertEquals(0f, SchedulePageMotion.incomingText(0f), 0f)
        assertEquals(0f, SchedulePageMotion.outgoingText(1f), 0f)
        assertEquals(1f, SchedulePageMotion.incomingText(1f), 0f)
    }
}
