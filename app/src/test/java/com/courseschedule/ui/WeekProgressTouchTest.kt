package com.courseschedule.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WeekProgressTouchTest {

    @Test
    fun mapsProgressPositionToNearestWeekAndClampsEdges() {
        assertEquals(1, weekAtProgressPosition(-10f, 200, 20))
        assertEquals(1, weekAtProgressPosition(0f, 200, 20))
        assertEquals(4, weekAtProgressPosition(40f, 200, 20))
        assertEquals(20, weekAtProgressPosition(200f, 200, 20))
        assertEquals(20, weekAtProgressPosition(300f, 200, 20))
    }

    @Test
    fun handlesUnavailableTrackAndSingleWeekSemester() {
        assertEquals(1, weekAtProgressPosition(50f, 0, 20))
        assertEquals(1, weekAtProgressPosition(50f, 200, 1))
    }
}
