package com.courseschedule.view

/** A single entrance reaches its final pose and never wraps around. */
internal object CalendarIllustrationMotion {
    const val DURATION_MS = 2800L

    fun progress(elapsedMs: Long): Float =
        elapsedMs.coerceIn(0L, DURATION_MS).toFloat() / DURATION_MS
}
