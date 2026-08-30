package com.courseschedule.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Small layered duck mascot used as the current-week action.
 *
 * The motion intentionally stays quiet while idle: breathing, a delayed head
 * follow and an occasional blink. Tapping it plays a short "inspect" motion
 * where the magnifier leads and the head/body follow with different timing.
 */
class DuckMascotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.1f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private var idlePhase = 0f
    private var inspectProgress = 0f
    private var pressed = false

    private val idleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 3200L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            idlePhase = it.animatedValue as Float
            invalidate()
        }
    }

    private val inspectAnimator = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
        duration = 620L
        interpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)
        addUpdateListener {
            inspectProgress = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!idleAnimator.isStarted) idleAnimator.start()
    }

    override fun onDetachedFromWindow() {
        idleAnimator.cancel()
        inspectAnimator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                animate().cancel()
                animate()
                    .scaleX(0.92f)
                    .scaleY(0.92f)
                    .setDuration(80L)
                    .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
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
        super.performClick()
        playInspectMotion()
        return true
    }

    fun playInspectMotion() {
        inspectAnimator.cancel()
        inspectAnimator.start()
    }

    private fun releasePress() {
        if (!pressed) return
        pressed = false
        animate().cancel()
        animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(210L)
            .setInterpolator(PathInterpolator(0.22f, 1f, 0.36f, 1f))
            .start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val sx = width / 48f
        val sy = height / 48f
        canvas.save()
        canvas.scale(sx, sy)

        val breathe = sin(idlePhase * 2f * PI).toFloat()
        val delayedFollow = sin((idlePhase - 0.05f) * 2f * PI).toFloat()
        val inspect = inspectProgress
        val bodyScaleY = 1f + breathe * 0.012f - inspect * 0.025f

        canvas.save()
        canvas.scale(1f, bodyScaleY, 24f, 31f)
        drawBody(canvas)
        canvas.restore()

        val headX = delayedFollow * 0.22f + inspect * 0.55f
        val headY = breathe * 0.18f + inspect * 1.3f
        val headRotation = delayedFollow * 0.45f + inspect * 4.2f
        canvas.save()
        canvas.translate(headX, headY)
        canvas.rotate(headRotation, 24f, 20f)
        drawHead(canvas)
        canvas.restore()

        drawMagnifier(canvas, inspect)

        canvas.restore()
    }

    private fun drawBody(canvas: Canvas) {
        fill.color = DUCK_YELLOW
        canvas.drawOval(RectF(14.2f, 24.2f, 35.4f, 40.2f), fill)

        fill.color = DUCK_SHADOW
        canvas.drawOval(RectF(17.0f, 34.1f, 32.7f, 39.5f), fill)

        fill.color = DUCK_YELLOW
        canvas.drawOval(RectF(10.8f, 27.0f, 18.8f, 35.0f), fill)

        fill.color = DUCK_ORANGE
        canvas.drawOval(RectF(17.0f, 39.0f, 23.1f, 41.4f), fill)
        canvas.drawOval(RectF(27.1f, 39.0f, 33.2f, 41.4f), fill)
    }

    private fun drawHead(canvas: Canvas) {
        fill.color = DUCK_YELLOW
        canvas.drawOval(RectF(14.0f, 9.0f, 35.4f, 29.0f), fill)

        fill.color = CAP_BLUE
        canvas.drawArc(RectF(14.2f, 6.8f, 35.2f, 18.0f), 186f, 168f, true, fill)
        canvas.drawRoundRect(RectF(13.2f, 13.0f, 36.0f, 15.4f), 1.2f, 1.2f, fill)

        fill.color = DUCK_ORANGE
        val beak = Path().apply {
            moveTo(33.0f, 19.0f)
            lineTo(40.0f, 21.3f)
            lineTo(33.0f, 23.1f)
            close()
        }
        canvas.drawPath(beak, fill)

        val blink = blinkAmount(idlePhase)
        stroke.color = EYE_COLOR
        stroke.strokeWidth = if (blink > 0.55f) 1.6f else 2.0f
        if (blink > 0.55f) {
            canvas.drawLine(24.8f, 18.4f, 28.0f, 18.5f, stroke)
        } else {
            fill.color = EYE_COLOR
            canvas.drawCircle(26.5f, 18.3f, 1.15f, fill)
        }
    }

    private fun drawMagnifier(canvas: Canvas, inspect: Float) {
        val lead = inspect * inspect * (3f - 2f * inspect)
        val x = 33.1f - lead * 2.2f
        val y = 28.1f + lead * 2.5f
        val rotation = -10f + lead * 13f

        canvas.save()
        canvas.rotate(rotation, x, y)

        stroke.color = MAGNIFIER_BLUE
        stroke.strokeWidth = 2.25f
        canvas.drawCircle(x, y, 4.35f, stroke)
        canvas.drawLine(x - 3.1f, y + 3.2f, x - 7.0f, y + 7.1f, stroke)

        fill.color = DUCK_YELLOW
        canvas.drawCircle(x - 6.8f, y + 7.1f, 2.25f, fill)
        canvas.restore()
    }

    private fun blinkAmount(phase: Float): Float {
        val center = 0.72f
        val distance = abs(phase - center).coerceAtMost(abs(phase + 1f - center))
        return (1f - distance / 0.035f).coerceIn(0f, 1f)
    }

    private fun dp(value: Float): Float = value * density

    private companion object {
        val DUCK_YELLOW = Color.rgb(252, 205, 75)
        val DUCK_SHADOW = Color.rgb(239, 177, 52)
        val DUCK_ORANGE = Color.rgb(240, 144, 43)
        val CAP_BLUE = Color.rgb(54, 101, 137)
        val MAGNIFIER_BLUE = Color.rgb(58, 104, 139)
        val EYE_COLOR = Color.rgb(39, 49, 58)
    }
}
