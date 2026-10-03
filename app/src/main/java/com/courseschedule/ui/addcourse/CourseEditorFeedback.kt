package com.courseschedule.ui.addcourse

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View

/** Native touch feedback respects the device's touch-feedback setting. */
internal class CourseEditorFeedback(private val host: View) {
    private var lastWheelTick: Long? = null

    fun selection() {
        host.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34)
            HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
    }

    fun wheelTick() {
        val now = SystemClock.uptimeMillis()
        val interval = if (Build.VERSION.SDK_INT >= 34) 90L else 140L
        if (lastWheelTick?.let { now - it < interval } == true) return
        lastWheelTick = now
        host.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34)
            HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
    }

    fun completed() {
        host.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30)
            HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
    }
}
