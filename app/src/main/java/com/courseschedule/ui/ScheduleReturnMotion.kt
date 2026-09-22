package com.courseschedule.ui

internal object ScheduleReturnMotion {
    const val HEADER_MS = 120f
    const val FADE_MS = 160f
    const val MOVE_MS = 420f
    const val OFFSET_DP = 18f
    const val INITIAL_SCALE = 0.96f

    fun delay(index: Int): Long = (index * 80L).coerceIn(0L, 140L)
    fun duration(count: Int): Long = MOVE_MS.toLong() + delay((count - 1).coerceAtLeast(0))
    fun fraction(elapsed: Float, duration: Float): Float = (elapsed / duration).coerceIn(0f, 1f)
    fun accepts(source: String?): Boolean =
        source == ScheduleReturnSource.SETTINGS.name || source == ScheduleReturnSource.IMPORT.name
}
