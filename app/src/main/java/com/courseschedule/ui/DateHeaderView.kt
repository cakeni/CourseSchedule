package com.courseschedule.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.text.TextUtils
import android.util.AttributeSet
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.withSave
import androidx.core.view.ViewCompat
import com.courseschedule.R
import kotlin.math.roundToInt

/** Only changed digits roll; both rows share one clock and keep the toolbar's text styles. */
class DateHeaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val dateRow = DigitRow(context, R.style.CourseScheduleToolbarTitle)
    private val summaryRow = DigitRow(context, R.style.CourseScheduleToolbarSubtitle)
    private val interpolator = PathInterpolator(0.2f, 0f, 0.1f, 1f)
    private var animator: ValueAnimator? = null

    val dateText: String get() = dateRow.text.toString()
    val summaryText: String get() = summaryRow.text.toString()

    init {
        orientation = VERTICAL
        addView(dateRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(summaryRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        ViewCompat.setAccessibilityHeading(this, true)
    }

    fun setDate(date: String, summary: String, animate: Boolean, forward: Boolean = true) {
        // LiveData and the pager can report the same week in one frame. Do not replay it.
        if (date == dateText && summary == summaryText) return
        val shouldAnimate = animate && dateText.isNotEmpty() && isAttachedToWindow &&
            isLaidOut && isShown && ValueAnimator.areAnimatorsEnabled()
        cancelAnimator()
        contentDescription = "$date，$summary"
        val distance = resources.displayMetrics.density * 12f * (if (forward) 1f else -1f)
        dateRow.setDigits(date, shouldAnimate, distance)
        summaryRow.setDigits(summary, shouldAnimate, distance * 0.6f)
        if (!dateRow.motion.isMoving && !summaryRow.motion.isMoving) return

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 340L
            interpolator = this@DateHeaderView.interpolator
            addUpdateListener {
                val progress = it.animatedValue as Float
                dateRow.advance(progress)
                summaryRow.advance(progress)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    settle()
                }
            })
        }
        animator?.start()
    }

    private fun cancelAnimator() {
        animator?.apply {
            removeAllListeners()
            removeAllUpdateListeners()
            cancel()
        }
        animator = null
    }

    private fun settle() {
        dateRow.advance(1f)
        summaryRow.advance(1f)
    }

    override fun onDetachedFromWindow() {
        cancelAnimator()
        settle()
        super.onDetachedFromWindow()
    }

    internal class DigitRow(context: Context, appearance: Int) : AppCompatTextView(context) {
        val motion = DateDigitMotion()

        init {
            setTextAppearance(appearance)
            minLines = 1
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            textDirection = TEXT_DIRECTION_LTR
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun setDigits(value: String, animate: Boolean, distance: Float) {
            // Measure once per text change, never during an animation frame.
            val widths = FloatArray(value.length)
            paint.getTextWidths(value, widths)
            motion.setText(value, widths, animate, distance)
            text = value
            invalidate()
        }

        fun advance(progress: Float) {
            if (!motion.isMoving) return
            motion.advance(progress)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            // Preserve native ellipsis for unusually large fonts / narrow windows.
            if ((layout?.getEllipsisCount(0) ?: 0) > 0) {
                super.onDraw(canvas)
                return
            }
            val oldColor = paint.color
            paint.color = currentTextColor
            val textAlpha = paint.alpha
            canvas.withSave {
                clipRect(compoundPaddingLeft, 0, width - compoundPaddingRight, height)
                for (glyph in motion.glyphs) {
                    paint.alpha = (textAlpha * glyph.alpha(motion.progress)).roundToInt()
                    if (paint.alpha == 0) continue
                    drawText(
                        glyph.text,
                        compoundPaddingLeft + glyph.x(motion.progress),
                        baseline + glyph.y(motion.progress),
                        paint
                    )
                }
            }
            paint.color = oldColor
        }
    }
}
