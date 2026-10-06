package com.courseschedule.ui.assistant

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.doOnLayout
import com.courseschedule.R
import com.google.android.material.button.MaterialButton

internal class TaskFilterIndicator(private val container: ViewGroup, private val buttons: List<MaterialButton>) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(container.context, R.color.task_filter_selected) }
    private val rect = RectF()
    private val idleInk = ContextCompat.getColor(container.context, R.color.task_secondary)
    private val activeInk = ContextCompat.getColor(container.context, R.color.task_filter_ink)
    private var selected: View? = null
    private var animation: ValueAnimator? = null
    private var selectionGeneration = 0
    private var waitingForLayout = false

    init {
        container.background = LayerDrawable(arrayOf(container.background, this))
        container.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!waitingForLayout && animation?.isRunning != true) move(false)
        }
    }

    fun select(button: View?, animate: Boolean) {
        val changed = selected !== button
        selected = button
        val generation = ++selectionGeneration
        waitingForLayout = true
        container.doOnLayout {
            if (generation == selectionGeneration) {
                waitingForLayout = false
                move(animate && changed)
            }
        }
    }

    fun settle() {
        ++selectionGeneration
        waitingForLayout = false
        move(false)
    }

    private fun move(animate: Boolean) {
        animation?.cancel()
        animation = null
        val button = selected
        if (button == null) {
            rect.setEmpty(); tintText(); invalidateSelf(); return
        }
        val target = RectF(button.left.toFloat(), button.top.toFloat(), button.right.toFloat(), button.bottom.toFloat())
        if (target.isEmpty) return
        if (!animate || rect.isEmpty || !ValueAnimator.areAnimatorsEnabled()) {
            rect.set(target); tintText(); invalidateSelf(); return
        }
        val start = RectF(rect)
        animation = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            interpolator = PathInterpolator(.22f, 1f, .36f, 1f)
            addUpdateListener {
                val p = it.animatedValue as Float
                rect.set(start.left + (target.left - start.left) * p, start.top + (target.top - start.top) * p,
                    start.right + (target.right - start.right) * p, start.bottom + (target.bottom - start.bottom) * p)
                tintText(); invalidateSelf()
            }
            start()
        }
    }

    private fun tintText() {
        buttons.forEach { button ->
            val overlap = (minOf(rect.right, button.right.toFloat()) - maxOf(rect.left, button.left.toFloat())).coerceAtLeast(0f)
            val covered = if (button.width > 0) (overlap / button.width).coerceIn(0f, 1f) else 0f
            button.setTextColor(ColorUtils.blendARGB(idleInk, activeInk, covered))
        }
    }

    override fun draw(canvas: Canvas) {
        if (!rect.isEmpty) canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
