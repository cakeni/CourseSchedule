package com.courseschedule.ui

import kotlin.math.abs

/** Empty content belongs to the week being dragged, never to an idle entrance timer. */
internal object EmptyWeekMotion {
    data class Frame(val offsetX: Float, val offsetY: Float, val scale: Float, val opacity: Float)

    fun frame(position: Float, density: Float, softSlide: Boolean, animationsEnabled: Boolean): Frame {
        val distance = abs(position).coerceIn(0f, 1f)
        if (!animationsEnabled) return Frame(0f, 0f, 1f,
            if (softSlide || distance < .5f) 1f else 0f)

        // ViewPager already eases the release. Applying another time curve here
        // would finish the reveal before the incoming content is on screen.
        return Frame(
            offsetX = if (softSlide) 0f else position.coerceIn(-1f, 1f) * 16f * density,
            offsetY = 0f,
            scale = 1f,
            // Local paging fades through the center so two copies of the text
            // never overlap. A sliding sheet has its own spatial separation.
            opacity = 1f - SchedulePageMotion.phase(if (softSlide) distance else distance / .5f)
        )
    }
}
