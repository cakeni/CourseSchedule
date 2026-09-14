package com.courseschedule.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulePreferencesTest {

    @Test
    fun sectionEndTimesSupportLegacyStartsAndRejectOverlap() {
        val starts = SchedulePreferences.DEFAULT_SECTION_TIMES

        assertEquals(
            SchedulePreferences.DEFAULT_SECTION_END_TIMES,
            SchedulePreferences.inferSectionEndTimes(starts)
        )
        assertTrue(
            SchedulePreferences.areValidSectionTimes(
                starts,
                SchedulePreferences.DEFAULT_SECTION_END_TIMES
            )
        )
        assertFalse(
            SchedulePreferences.areValidSectionTimes(
                starts,
                SchedulePreferences.DEFAULT_SECTION_END_TIMES.toMutableList().apply {
                    this[0] = "08:55"
                }
            )
        )
    }
}
