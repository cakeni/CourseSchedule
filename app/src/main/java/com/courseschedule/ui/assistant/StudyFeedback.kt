package com.courseschedule.ui.assistant

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View

internal object StudyFeedback {
    private val lastTicks = java.util.WeakHashMap<View, Long>()

    fun clockTick(view: View, minute: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (now - (lastTicks[view] ?: 0L) < if (minute) 40 else 60) return
        lastTicks[view] = now
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) {
            if (minute) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.SEGMENT_TICK
        } else HapticFeedbackConstants.CLOCK_TICK)
    }

    fun completed(view: View) {
        if (Build.VERSION.SDK_INT >= 34) view.performHapticFeedback(HapticFeedbackConstants.TOGGLE_ON)
        else if (Build.VERSION.SDK_INT >= 30) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }
}
