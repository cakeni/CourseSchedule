package com.courseschedule.ui.settings

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.doOnPreDraw
import com.courseschedule.R
import com.google.android.material.materialswitch.MaterialSwitch
import kotlin.math.roundToInt

/** One cached old frame fades away while the same live switch keeps moving above it. */
internal class ThemeSwitchTransition {
    private var bitmap: Bitmap? = null
    private var cover: BitmapDrawable? = null
    private var animation: ValueAnimator? = null
    private var cleanup: (() -> Unit)? = null
    private var oldStatus = 0
    private var oldNavigation = 0

    fun capture(root: ViewGroup, control: MaterialSwitch, window: Window) {
        finish()
        if (!ValueAnimator.areAnimatorsEnabled() || root.width == 0 || root.height == 0) return
        // A bounded texture, captured once. No blur, layout or bitmap creation in animation frames.
        val scale = minOf(1f, 1080f / root.width)
        val frame = Bitmap.createBitmap((root.width * scale).roundToInt(),
            (root.height * scale).roundToInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame).apply { scale(scale, scale) }
        val visibility = control.visibility
        control.visibility = View.INVISIBLE
        try { root.draw(canvas) } finally { control.visibility = visibility }
        bitmap = frame
        oldStatus = window.statusBarColor
        oldNavigation = window.navigationBarColor
    }

    fun reveal(root: ViewGroup, control: MaterialSwitch, window: Window, onFinished: () -> Unit, onProgress: (Float) -> Unit) {
        val frame = bitmap ?: run { onProgress(1f); onFinished(); return }
        val overlay = BitmapDrawable(root.resources, frame).apply {
            bounds = Rect(0, 0, root.width.takeIf { it > 0 } ?: frame.width,
                root.height.takeIf { it > 0 } ?: frame.height)
        }
        cover = overlay
        root.overlay.add(overlay)
        val newStatus = window.statusBarColor
        val newNavigation = window.navigationBarColor
        window.statusBarColor = oldStatus
        window.navigationBarColor = oldNavigation
        onProgress(0f)
        root.doOnPreDraw {
            if (bitmap !== frame) return@doOnPreDraw
            overlay.setBounds(0, 0, root.width, root.height)
            val parent = control.parent as ViewGroup
            val index = parent.indexOfChild(control)
            val params = control.layoutParams
            val spacer = View(root.context).apply {
                minimumWidth = control.width
                minimumHeight = control.height
            }
            val switchHost = window.decorView as ViewGroup
            val origin = IntArray(2).also(switchHost::getLocationInWindow)
            val position = IntArray(2).also(control::getLocationInWindow)
            parent.removeView(control)
            parent.addView(spacer, index, params)
            switchHost.overlay.add(control)
            val left = position[0] - origin[0]
            val top = position[1] - origin[1]
            control.layout(left, top, left + control.width, top + control.height)
            cleanup = {
                switchHost.overlay.remove(control)
                parent.removeView(spacer)
                parent.addView(control, index, params)
                root.overlay.remove(overlay)
                window.statusBarColor = newStatus
                window.navigationBarColor = newNavigation
                onProgress(1f)
            }
            animation = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 320L
                interpolator = PathInterpolator(.22f, 0f, .2f, 1f)
                addUpdateListener {
                    val progress = it.animatedValue as Float
                    overlay.alpha = (255 * (1f - progress)).roundToInt()
                    window.statusBarColor = ColorUtils.blendARGB(oldStatus, newStatus, progress)
                    window.navigationBarColor = ColorUtils.blendARGB(oldNavigation, newNavigation, progress)
                    onProgress(progress)
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { finish(); onFinished() }
                })
                start()
            }
        }
        // Also removes a pending cover when the activity leaves before the first layout.
        if (cleanup == null) cleanup = {
            root.overlay.remove(overlay)
            window.statusBarColor = newStatus
            window.navigationBarColor = newNavigation
            onProgress(1f)
        }
    }

    fun finish() {
        animation?.removeAllListeners()
        animation?.cancel()
        animation = null
        cleanup?.invoke()
        cleanup = null
        cover = null
        // Dropping ownership lets RenderThread release its last frame safely.
        bitmap = null
    }
}

/** The reference's diagonal handoff, using the existing sun and moon vectors. */
internal class ThemeSwitchIcon(context: android.content.Context, dark: Boolean) : Drawable() {
    private val sun = ContextCompat.getDrawable(context, R.drawable.ic_theme_sun)!!.mutate()
    private val moon = ContextCompat.getDrawable(context, R.drawable.ic_theme_moon)!!.mutate()
    private val wipe = Path()
    private var animation: ValueAnimator? = null
    private var tint: ColorStateList? = null
    internal var progress = if (dark) 1f else 0f
        private set

    fun animateTo(dark: Boolean) {
        animation?.cancel()
        val target = if (dark) 1f else 0f
        if (!ValueAnimator.areAnimatorsEnabled()) { progress = target; invalidateSelf(); return }
        animation = ValueAnimator.ofFloat(progress, target).apply {
            duration = 320L
            interpolator = PathInterpolator(.22f, 0f, .2f, 1f)
            addUpdateListener { progress = it.animatedValue as Float; invalidateSelf() }
            start()
        }
    }

    fun settle(dark: Boolean) {
        animation?.cancel()
        animation = null
        progress = if (dark) 1f else 0f
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        canvas.clipRect(0f, 0f, 24f, 24f)
        val edge = 24f * (1f - 2f * progress)
        wipe.reset()
        wipe.moveTo(48f, -24f)
        wipe.lineTo(edge - 24f, -24f)
        wipe.lineTo(edge + 48f, 48f)
        wipe.lineTo(48f, 48f)
        wipe.close()
        sun.setBounds(0, 0, 24, 24)
        moon.setBounds(0, 0, 24, 24)
        canvas.save()
        canvas.clipOutPath(wipe)
        canvas.translate(-4f * progress, 4f * progress)
        sun.draw(canvas)
        canvas.restore()
        canvas.clipPath(wipe)
        canvas.translate(4f * (1f - progress), -4f * (1f - progress))
        moon.draw(canvas)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { sun.alpha = alpha; moon.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { sun.colorFilter = filter; moon.colorFilter = filter; invalidateSelf() }
    override fun setTint(color: Int) { sun.setTint(color); moon.setTint(color); invalidateSelf() }
    override fun setTintList(tint: ColorStateList?) {
        this.tint = tint
        tint?.let { setTint(it.getColorForState(state, it.defaultColor)) }
    }
    override fun onStateChange(state: IntArray): Boolean {
        val colors = tint ?: return false
        setTint(colors.getColorForState(state, colors.defaultColor))
        return true
    }
    override fun isStateful() = tint?.isStateful == true
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = sun.intrinsicWidth
    override fun getIntrinsicHeight() = sun.intrinsicHeight
}
