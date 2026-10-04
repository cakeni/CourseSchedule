package com.courseschedule.ui

import kotlin.math.abs

/** A whole week stays together; drag progress remains directly reversible. */
internal object WeekSlideMotion {
    data class Frame(val offsetX: Float, val scale: Float, val opacity: Float)

    fun frame(position: Float, gridWidth: Float, density: Float, animationsEnabled: Boolean): Frame {
        val distance = SchedulePageMotion.phase(abs(position))
        return Frame(
            offsetX = position * (gridWidth + if (animationsEnabled) 12f * density else 0f),
            scale = if (animationsEnabled) 1f - 0.018f * distance else 1f,
            opacity = if (animationsEnabled) 1f - 0.12f * distance else 1f
        )
    }
}
