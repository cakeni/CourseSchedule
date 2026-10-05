package com.courseschedule.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.doOnPreDraw
import com.courseschedule.R
import com.courseschedule.data.entity.Course
import com.courseschedule.view.CourseTableView
import kotlin.math.roundToInt

internal class CourseFocusDialog(context: Context, private val content: View, private val course: Course,
    private val source: View, private val sourceBounds: RectF, private val restoring: Boolean = false) : Dialog(context, R.style.Theme_CourseSchedule_CourseFocus) {
    var onCloseRequest: (() -> Boolean)? = null
    private val courseColor = (source as? CourseTableView)?.detailSourceColor(course)
        ?: ContextCompat.getColor(context, R.color.course_focus_divider)
    private val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private var detailColor = context.resources.obtainTypedArray(R.array.course_colors).let { colors ->
        colors.getColor(Math.floorMod(course.colorIndex, colors.length()), Color.BLACK).also { colors.recycle() }
    }
    private val stage = FrameLayout(context)
    private val backdrop = (context as? android.app.Activity)?.let(::courseGlassBackdrop)
    private val layer = CardLayer(context)
    private val ease = PathInterpolator(.18f, 1f, .3f, 1f)
    private var animation: ValueAnimator? = null
    private var progress = 0f
    private var closing = false
    private var finishing = false
    private var afterClose: (() -> Unit)? = null
    private var start: RectF? = null
    private var target = RectF()
    private var dragOffset = 0f
    private val density = context.resources.displayMetrics.density

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.apply {
            WindowCompat.setDecorFitsSystemWindows(this, false)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
        backdrop?.let { stage.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        stage.addView(layer, FrameLayout.LayoutParams(-1, -1))
        stage.addView(content, FrameLayout.LayoutParams(-1, -1).apply {
            setMargins((16 * density).roundToInt(), (12 * density).roundToInt(), (16 * density).roundToInt(), (16 * density).roundToInt())
        })
        content.alpha = 0f
        content.findViewById<View>(R.id.detailCourseColor).background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(courseColor)
        }
        setContentView(stage)
        run {
            var imeAnimating = false
            fun adjustForKeyboard(insets: WindowInsetsCompat) {
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                val bottom = maxOf(insets.getInsets(WindowInsetsCompat.Type.ime()).bottom, bars.bottom)
                if (stage.paddingBottom != bottom || stage.paddingTop != bars.top ||
                    stage.paddingLeft != bars.left || stage.paddingRight != bars.right) {
                    stage.setPadding(bars.left, bars.top, bars.right, bottom)
                }
            }
            ViewCompat.setOnApplyWindowInsetsListener(stage) { _, insets ->
                if (!imeAnimating) adjustForKeyboard(insets)
                insets
            }
            ViewCompat.setWindowInsetsAnimationCallback(stage, object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) imeAnimating = true
                }
                override fun onProgress(insets: WindowInsetsCompat, runningAnimations: MutableList<WindowInsetsAnimationCompat>): WindowInsetsCompat {
                    adjustForKeyboard(insets)
                    return insets
                }
                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                        imeAnimating = false
                        ViewCompat.getRootWindowInsets(stage)?.let(::adjustForKeyboard)
                    }
                }
            })
        }
        content.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            if (progress == 1f && !closing) {
                target.set(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
                applyProgress(progress)
            }
        }
        content.findViewById<View>(R.id.btnCloseCourse).setOnClickListener { cancel() }
        var downY = 0f
        content.findViewById<View>(R.id.detailHero).setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = event.rawY; true }
                MotionEvent.ACTION_MOVE -> { dragOffset = (event.rawY - downY).coerceIn(0f, 140 * density); applyProgress(progress); true }
                MotionEvent.ACTION_UP -> {
                    if (dragOffset > 56 * density) cancel()
                    else { dragOffset = 0f; applyProgress(progress); view.performClick() }
                    true
                }
                MotionEvent.ACTION_CANCEL -> { dragOffset = 0f; applyProgress(progress); true }
                else -> false
            }
        }
        stage.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP && !target.contains(event.x, event.y)) cancel()
            true
        }
        setOnShowListener {
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setWindowAnimations(0)
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                WindowCompat.setDecorFitsSystemWindows(this, false)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
                ViewCompat.requestApplyInsets(stage)
                statusBarColor = ContextCompat.getColor(context, R.color.course_focus_backdrop)
                navigationBarColor = statusBarColor
                WindowInsetsControllerCompat(this, decorView).apply {
                    isAppearanceLightStatusBars = context.resources.getBoolean(R.bool.window_light_system_bars)
                    isAppearanceLightNavigationBars = isAppearanceLightStatusBars
                }
            }
            content.doOnPreDraw {
                val origin = IntArray(2); stage.getLocationOnScreen(origin)
                val position = IntArray(2); source.getLocationOnScreen(position)
                val visible = Rect()
                start = if (!restoring && source.isAttachedToWindow && source.getGlobalVisibleRect(visible)) RectF(sourceBounds).apply {
                    offset(position[0] - origin[0].toFloat(), position[1] - origin[1].toFloat())
                } else null
                target.set(content.left.toFloat(), content.top.toFloat(), content.right.toFloat(), content.bottom.toFloat())
                if (!closing) {
                    if (start == null || !ValueAnimator.areAnimatorsEnabled()) applyProgress(1f)
                    else { applyProgress(0f); animateTo(1f, 440L) {} }
                }
            }
        }
    }

    private fun bounds(p: Float): RectF {
        val from = start ?: target
        return RectF(from.left + (target.left - from.left) * p, from.top + (target.top - from.top) * p,
            from.right + (target.right - from.right) * p, from.bottom + (target.bottom - from.bottom) * p)
            .apply { offset(0f, dragOffset * p) }
    }
    private fun applyProgress(p: Float) {
        progress = p
        backdrop?.alpha = p
        content.alpha = ((p - .32f) / .52f).coerceIn(0f, 1f)
        content.translationY = dragOffset * p
        content.clipBounds = if (p == 1f && dragOffset == 0f) null else bounds(p).let {
            Rect((it.left - target.left).roundToInt(), (it.top - target.top - content.translationY).roundToInt(),
                (it.right - target.left).roundToInt(), (it.bottom - target.top - content.translationY).roundToInt())
        }
        layer.invalidate()
    }
    fun setCourseColor(color: Int) {
        if (detailColor == color) return
        detailColor = color
        layer.invalidate()
    }
    private fun animateTo(to: Float, durationMs: Long, done: () -> Unit) {
        animation?.removeAllListeners(); animation?.cancel()
        animation = ValueAnimator.ofFloat(progress, to).apply {
            duration = durationMs; interpolator = ease
            addUpdateListener { applyProgress(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(anim: Animator) { animation = null; done() } })
            start()
        }
    }
    fun dismissThen(action: () -> Unit) { afterClose = action; dismiss() }
    fun dismissImmediately() { afterClose = null; finish(false) }
    override fun dismiss() { if (finishing) super.dismiss() else close(false) }
    override fun cancel() { if (finishing) super.cancel() else close(true) }
    private fun close(cancel: Boolean) {
        if (closing) return
        if (onCloseRequest?.invoke() == false) {
            dragOffset = 0f
            applyProgress(progress)
            return
        }
        closing = true
        if (!isShowing || start == null || !ValueAnimator.areAnimatorsEnabled()) finish(cancel)
        else animateTo(0f, (320 * progress.coerceAtLeast(.25f)).toLong()) { finish(cancel) }
    }
    private fun finish(cancel: Boolean) {
        animation?.removeAllListeners(); animation?.cancel(); animation = null
        finishing = true
        if (cancel) super.cancel() else super.dismiss()
        finishing = false
        afterClose?.also { afterClose = null; it() }
    }
    private inner class CardLayer(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val matte = ContextCompat.getColor(context, R.color.course_focus_backdrop)
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(ColorUtils.setAlphaComponent(matte, ((if (night) 200 else 210) * progress).roundToInt()))
            val card = bounds(progress).apply { offset(-this@CardLayer.left.toFloat(), -this@CardLayer.top.toFloat()) }
            if (card.isEmpty) return
            val top = if (night) Color.argb(232, 14, 14, 14) else ColorUtils.setAlphaComponent(detailColor, 240)
            val bottom = if (night) Color.argb(240, 0, 0, 0) else
                ColorUtils.setAlphaComponent(ColorUtils.blendARGB(detailColor, Color.WHITE, .24f), 224)
            paint.color = Color.WHITE
            paint.shader = LinearGradient(card.left, card.top, card.left, card.bottom,
                ColorUtils.blendARGB(courseColor, top, progress),
                ColorUtils.blendARGB(courseColor, bottom, progress), Shader.TileMode.CLAMP)
            val radius = (10f + 22f * progress) * density
            canvas.drawRoundRect(card, radius, radius, paint)
            paint.shader = null
            val opacity = (1f - progress / .32f).coerceIn(0f, 1f)
            if (opacity > 0f) {
                canvas.save(); canvas.clipRect(card)
                canvas.translate(card.left - sourceBounds.left, card.top - sourceBounds.top)
                (source as? CourseTableView)?.drawDetailSourceText(canvas, course, sourceBounds, (255 * opacity).roundToInt())
                canvas.restore()
            }
        }
    }
}
