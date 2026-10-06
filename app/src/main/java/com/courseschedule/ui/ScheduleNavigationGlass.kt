package com.courseschedule.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import androidx.annotation.RequiresApi
import androidx.constraintlayout.widget.ConstraintLayout
import com.courseschedule.R
import kotlin.math.ceil

/** Refresh the glass only when page content changes, never from its own draw. */
class ScheduleRootLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ConstraintLayout(context, attrs) {
    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        val glass = findViewById<ScheduleNavigationGlass>(R.id.navigationGlass)
        if (glass?.usesSource(child) == true) glass.invalidate()
    }
}

/** A bounded, live blur behind the navigation; icons and labels remain separate. */
class ScheduleNavigationGlass @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val overscan = ceil(24f * density).toInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val mask = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val veil = Paint()
    private val bitmapBounds = Rect()
    private var sources = emptyList<View>()
    private var bitmap: Bitmap? = null
    private var gpu: GpuBlur? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    internal fun bind(vararg sources: View) {
        this.sources = sources.toList()
        invalidate()
    }

    internal fun usesSource(view: View) = sources.any { it === view }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val fade = (36f * density / h.coerceAtLeast(1)).coerceIn(.01f, .99f)
        mask.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.TRANSPARENT, Color.BLACK, Color.BLACK),
            floatArrayOf(0f, fade, 1f), Shader.TileMode.CLAMP)
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val surface = if (night) Color.rgb(17, 17, 17) else Color.rgb(246, 248, 252)
        veil.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(surface and 0xffffff, withAlpha(surface, 168), withAlpha(surface, 220), withAlpha(surface, 240)),
            floatArrayOf(0f, fade, (fade + .28f).coerceAtMost(.99f), 1f), Shader.TileMode.CLAMP)
        bitmap?.recycle()
        bitmap = null
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        if (Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated) {
            val renderer = gpu ?: GpuBlur(18f * density).also { gpu = it }
            renderer.draw(canvas, width, height, overscan, ::drawBackdrop)
        } else {
            // Older Android versions blur a small strip, not a full-screen snapshot.
            val sampleWidth = (width + 2 * overscan).coerceAtMost(180)
            val scale = sampleWidth.toFloat() / (width + 2 * overscan)
            val sampleHeight = ceil((height + 2 * overscan) * scale).toInt().coerceAtLeast(1)
            val snapshot = bitmap?.takeIf { it.width == sampleWidth && it.height == sampleHeight }
                ?: Bitmap.createBitmap(sampleWidth, sampleHeight, Bitmap.Config.ARGB_8888).also {
                    bitmap?.recycle()
                    bitmap = it
                }
            val recording = Canvas(snapshot)
            snapshot.eraseColor(Color.TRANSPARENT)
            recording.scale(scale, scale)
            drawBackdrop(recording, overscan)
            blurCourseSnapshot(snapshot)
            bitmapBounds.set(-overscan, -overscan, width + overscan, height + overscan)
            canvas.drawBitmap(snapshot, null, bitmapBounds, paint)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), mask)
        canvas.restoreToCount(layer)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), veil)
    }

    private fun drawBackdrop(canvas: Canvas, padding: Int) {
        val root = parent as? View ?: return
        val count = canvas.save()
        canvas.translate((padding - left).toFloat(), (padding - top).toFloat())
        root.background?.draw(canvas)
        sources.forEach { source ->
            if (source.visibility != VISIBLE) return@forEach
            val sourceCount = canvas.save()
            canvas.translate(source.left.toFloat(), source.top.toFloat())
            canvas.concat(source.matrix)
            canvas.clipRect(0f, 0f, source.width.toFloat(), source.height.toFloat())
            // Direct draws need the same scroll offset as Android's display list.
            canvas.translate(-source.scrollX.toFloat(), -source.scrollY.toFloat())
            source.draw(canvas)
            canvas.restoreToCount(sourceCount)
        }
        canvas.restoreToCount(count)
    }

    override fun onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= 31) gpu?.release()
        gpu = null
        bitmap?.recycle()
        bitmap = null
        super.onDetachedFromWindow()
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0xffffff) or (alpha shl 24)

    @RequiresApi(31)
    private class GpuBlur(radius: Float) {
        private val node = RenderNode("Timetable navigation blur").apply {
            setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        }

        fun draw(canvas: Canvas, width: Int, height: Int, padding: Int, record: (Canvas, Int) -> Unit) {
            node.setPosition(-padding, -padding, width + padding, height + padding)
            val recording = node.beginRecording(width + 2 * padding, height + 2 * padding)
            try { record(recording, padding) } finally { node.endRecording() }
            canvas.drawRenderNode(node)
        }

        fun release() = node.discardDisplayList()
    }
}
