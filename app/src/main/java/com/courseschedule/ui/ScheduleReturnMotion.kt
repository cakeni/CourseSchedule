package com.courseschedule.ui

import kotlin.math.exp

internal object ScheduleReturnMotion {
    const val FADE_MS = 140f
    const val MOVE_MS = 440f
    const val OFFSET_DP = 16f
    const val INITIAL_SCALE = 0.975f

    // Follow the card's place in the visible grid, rather than its database order.
    fun delay(day: Int, row: Int): Long =
        ((day - 1).coerceAtLeast(0) * 9L + row.coerceAtLeast(0) * 7L).coerceAtMost(84L)

    fun duration(lastDelay: Long): Long = MOVE_MS.toLong() + lastDelay.coerceIn(0L, 84L)
    fun fraction(elapsed: Float, duration: Float): Float = (elapsed / duration).coerceIn(0f, 1f)
    fun progress(elapsed: Float): Float {
        if (elapsed >= MOVE_MS) return 1f
        val time = elapsed.coerceAtLeast(0f) / 1000f
        return 1f - (1f + 24f * time) * exp(-24f * time)
    }

    fun accepts(source: String?): Boolean =
        source == ScheduleReturnSource.SETTINGS.name || source == ScheduleReturnSource.IMPORT.name ||
            source == ScheduleReturnSource.TODO.name || source == ScheduleReturnSource.ASSISTANT.name
}
