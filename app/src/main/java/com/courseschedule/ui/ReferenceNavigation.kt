package com.courseschedule.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import com.courseschedule.R
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.R as MaterialR

internal class ReferenceNavigation(private val bar: BottomNavigationView) : Drawable() {
    companion object {
        private var lastPosition: Float? = null
        private var generation = 0
        fun install(bar: BottomNavigationView) {
            if (bar.getTag(R.id.reference_navigation) != null) return
            val capsule = ReferenceNavigation(bar)
            bar.setTag(R.id.reference_navigation, capsule)
            bar.background = LayerDrawable(arrayOf(bar.background, capsule))
            bar.doOnLayout { capsule.move() }
            bar.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) { capsule.animation?.cancel() }
            })
        }
        fun select(bar: BottomNavigationView, animate: Boolean = true) {
            bar.doOnLayout { (bar.getTag(R.id.reference_navigation) as? ReferenceNavigation)?.move(animate) }
        }
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val color = ContextCompat.getColor(bar.context, R.color.reference_capsule)
    private val top = ContextCompat.getColor(bar.context, R.color.reference_capsule_top)
    private val edge = ContextCompat.getColor(bar.context, R.color.reference_capsule_edge)
    private var position = lastPosition ?: .125f
    private var centerY = 0f
    private var stretch = 0f
    private var target = Float.NaN
    private var owner = 0
    private var animation: ValueAnimator? = null
    private var gradient: Shader? = null

    private fun move(animate: Boolean = true) {
        val icon = bar.findViewById<View>(bar.selectedItemId)?.findViewById<View>(MaterialR.id.navigation_bar_item_icon_view) ?: return
        val bounds = Rect(0, 0, icon.width, icon.height)
        bar.offsetDescendantRectToMyCoords(icon, bounds)
        centerY = bounds.exactCenterY()
        val next = bounds.exactCenterX() / bar.width
        if (animate && target == next && animation?.isRunning == true && owner == generation) return
        animation?.cancel()
        target = next
        position = lastPosition ?: next
        owner = ++generation
        val currentOwner = owner
        val density = bar.resources.displayMetrics.density
        gradient = LinearGradient(0f, centerY - 17 * density, 0f, centerY + 17 * density,
            intArrayOf(top, color, color), floatArrayOf(0f, .35f, 1f), Shader.TileMode.CLAMP)
        if (!animate || !ValueAnimator.areAnimatorsEnabled() || kotlin.math.abs(position - next) < .001f) {
            position = next; lastPosition = next; stretch = 0f; invalidateSelf(); return
        }
        val start = position
        animation = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 340L
            interpolator = PathInterpolator(.22f, 1f, .36f, 1f)
            addUpdateListener {
                val progress = it.animatedValue as Float
                position = start + (next - start) * progress
                stretch = kotlin.math.sin(progress * Math.PI).toFloat() * 7f
                if (currentOwner == generation) lastPosition = position
                invalidateSelf()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(anim: Animator) { stretch = 0f; if (animation === anim) animation = null; invalidateSelf() }
            })
            start()
        }
    }
    override fun draw(canvas: Canvas) {
        if (centerY <= 0f) return
        val dp = bar.resources.displayMetrics.density
        val x = bounds.left + bounds.width() * position
        rect.set(x - (26f + stretch) * dp, centerY - 16f * dp, x + (26f + stretch) * dp, centerY + 16f * dp)
        paint.style = Paint.Style.FILL; paint.shader = gradient
        canvas.drawRoundRect(rect, 18f * dp, 18f * dp, paint)
        paint.shader = null; paint.color = edge; paint.style = Paint.Style.STROKE; paint.strokeWidth = .6f * dp
        canvas.drawRoundRect(rect, 18f * dp, 18f * dp, paint)
        paint.style = Paint.Style.FILL
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
