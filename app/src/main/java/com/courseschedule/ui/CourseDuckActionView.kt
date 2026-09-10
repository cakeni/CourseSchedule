package com.courseschedule.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import app.rive.runtime.kotlin.RiveAnimationView
import app.rive.runtime.kotlin.core.Alignment
import app.rive.runtime.kotlin.core.Fit
import com.courseschedule.R

/** Toolbar action backed by one long-lived Rive renderer. */
class CourseDuckActionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val riveView = RiveAnimationView(context).apply {
        layoutParams = centeredLayoutParams()
        isClickable = false
        isFocusable = false
    }
    private var pressed = false

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        clipChildren = false
        clipToPadding = false
        addView(riveView)
        runCatching {
            riveView.setRiveResource(
                R.raw.course_duck,
                artboardName = ARTBOARD_NAME,
                stateMachineName = STATE_MACHINE_NAME,
                autoplay = true,
                fit = Fit.CONTAIN,
                alignment = Alignment.CENTER
            )
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                animate().cancel()
                animate()
                    .scaleX(0.97f)
                    .scaleY(0.97f)
                    .setDuration(75L)
                    .setInterpolator(PRESS_INTERPOLATOR)
                    .start()
                return true
            }

            MotionEvent.ACTION_UP -> {
                val inside = event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()
                releasePress()
                if (inside) performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                releasePress()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    private fun releasePress() {
        if (!pressed) return
        pressed = false
        animate().cancel()
        animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(150L)
            .setInterpolator(RELEASE_INTERPOLATOR)
            .start()
    }

    private fun centeredLayoutParams() = LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
        Gravity.CENTER
    )

    private companion object {
        const val ARTBOARD_NAME = "Artboard"
        const val STATE_MACHINE_NAME = "State Machine 1"
        val PRESS_INTERPOLATOR = PathInterpolator(0.2f, 0f, 0f, 1f)
        val RELEASE_INTERPOLATOR = PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}
