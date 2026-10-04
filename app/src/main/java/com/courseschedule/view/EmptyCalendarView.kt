package com.courseschedule.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.courseschedule.R

/** An explicit, single entrance. Visibility can resume it, but cannot start a new one. */
class EmptyCalendarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val illustration = LottieDrawable().apply { callback = this@EmptyCalendarView }
    private val visibleBounds = Rect()
    private var selected = false
    private var stageHost: View? = null
    private var stageReady = false
    private var startedAt = -1L
    private var elapsedBeforePause = 0L

    internal val isMotionRunning: Boolean get() = startedAt >= 0L
    internal val motionProgress: Float get() = CalendarIllustrationMotion.progress(elapsed())
    internal var entranceCount: Int = 0
        private set

    private val tick = object : Runnable {
        override fun run() {
            if (!canAnimate()) {
                pauseMotion()
                return
            }
            if (elapsed() >= CalendarIllustrationMotion.DURATION_MS) finishMotion()
            invalidateScene()
            if (startedAt >= 0L) postOnAnimation(this)
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        LottieCompositionFactory.fromRawRes(context, R.raw.empty_calendar).addListener { composition ->
            illustration.setComposition(composition)
            illustration.setBounds(0, 0, width, height)
            resumeMotion()
            invalidateScene()
        }
    }

    fun setPageSelected(value: Boolean) {
        if (selected == value) return
        pauseMotion()
        selected = value
        if (value) {
            entranceCount++
            elapsedBeforePause = 0L
            if (ValueAnimator.areAnimatorsEnabled()) resumeMotion() else finishMotion()
        }
        invalidateScene()
    }

    /** The swipe stage draws this view even while its normal parent is invisible. */
    fun setStageHost(host: View?, ready: Boolean = false) {
        if (stageHost === host && stageReady == ready) return
        stageHost = host
        stageReady = ready
        if (canAnimate()) resumeMotion() else pauseMotion()
    }

    fun resetForReuse() {
        selected = false
        stageHost = null
        stageReady = false
        prepareForEntry()
        invalidate()
    }

    /** Show the first pose while an uncommitted page is being revealed. */
    fun prepareForEntry() {
        if (selected) return
        pauseMotion()
        elapsedBeforePause = if (ValueAnimator.areAnimatorsEnabled()) 0L else CalendarIllustrationMotion.DURATION_MS
        invalidateScene()
    }

    private fun canAnimate(): Boolean {
        if (!selected || illustration.composition == null || !isAttachedToWindow ||
            windowVisibility != VISIBLE || !ValueAnimator.areAnimatorsEnabled()) return false
        val host = stageHost
        return if (host != null) stageReady && host.isShown && host.getGlobalVisibleRect(visibleBounds)
            else isShown && getGlobalVisibleRect(visibleBounds)
    }

    private fun elapsed(): Long = elapsedBeforePause +
        if (startedAt >= 0L) SystemClock.uptimeMillis() - startedAt else 0L

    private fun resumeMotion() {
        if (startedAt >= 0L || elapsedBeforePause >= CalendarIllustrationMotion.DURATION_MS || !canAnimate()) return
        startedAt = SystemClock.uptimeMillis()
        postOnAnimation(tick)
    }

    private fun pauseMotion() {
        if (startedAt >= 0L) elapsedBeforePause = elapsed().coerceAtMost(CalendarIllustrationMotion.DURATION_MS)
        startedAt = -1L
        removeCallbacks(tick)
    }

    private fun finishMotion() {
        startedAt = -1L
        elapsedBeforePause = CalendarIllustrationMotion.DURATION_MS
        removeCallbacks(tick)
    }

    private fun invalidateScene() {
        invalidate()
        stageHost?.postInvalidateOnAnimation()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (canAnimate()) resumeMotion() else pauseMotion()
        if (isVisible) invalidate()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            resumeMotion()
            invalidateScene()
        } else pauseMotion()
    }

    override fun onDetachedFromWindow() {
        pauseMotion()
        stageHost = null
        stageReady = false
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        illustration.setBounds(0, 0, w, h)
    }

    override fun verifyDrawable(who: Drawable): Boolean = who === illustration || super.verifyDrawable(who)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!ValueAnimator.areAnimatorsEnabled()) finishMotion()
        else if (canAnimate()) resumeMotion() else pauseMotion()
        val progress = motionProgress
        if (illustration.progress != progress) illustration.progress = progress
        illustration.draw(canvas)
    }
}
