package com.courseschedule.ui

/** A reversible scene change: cards stay in the grid while their content changes. */
internal object SchedulePageMotion {
    const val EXIT_DP = 12f
    const val ENTER_DP = 26f
    const val TEXT_DP = 10f
    const val ENTER_SCALE = 0.95f
    const val EXIT_SCALE = 0.97f

    fun phase(progress: Float): Float {
        val value = progress.coerceIn(0f, 1f)
        return value * value * (3f - 2f * value)
    }

    fun outgoingText(progress: Float): Float = 1f - phase(progress / 0.48f)

    fun incomingText(progress: Float): Float = phase((progress - 0.48f) / 0.52f)
}
