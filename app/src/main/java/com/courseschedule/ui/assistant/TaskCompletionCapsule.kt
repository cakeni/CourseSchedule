package com.courseschedule.ui.assistant

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.courseschedule.R

/** Completion stays inside the checkbox; the task title remains readable throughout feedback. */
internal class TaskCompletionCapsule(context: Context, private val completed: Boolean, icon: RectF) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = ContextCompat.getColor(context, if (completed) R.color.task_success else R.color.reference_blue_accent)
    private val checkInk = ContextCompat.getColor(context, R.color.task_paper)
    private val rect = RectF(icon).apply { inset(width() * .125f, height() * .125f) }
    private val check = Path().apply {
        moveTo(rect.left + rect.width() * .25f, rect.centerY())
        lineTo(rect.left + rect.width() * .42f, rect.top + rect.height() * .67f)
        lineTo(rect.left + rect.width() * .75f, rect.top + rect.height() * .33f)
    }
    private val measure = PathMeasure(check, false)
    private val segment = Path()
    var progress = 0f
        set(value) { field = value; invalidateSelf() }

    override fun draw(canvas: Canvas) {
        val reveal = com.courseschedule.ui.SchedulePageMotion.phase((progress / .35f).coerceIn(0f, 1f))
        val radius = rect.width() * .22f
        paint.color = ink; paint.style = Paint.Style.FILL
        paint.alpha = if (completed) (reveal * 255).toInt() else ((1f - reveal) * 255).toInt()
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.alpha = 255; paint.style = Paint.Style.STROKE; paint.strokeWidth = rect.width() * .075f
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.color = checkInk; paint.strokeWidth = rect.width() * .1f
        paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        segment.reset()
        measure.getSegment(0f, measure.length * (if (completed) reveal else 1f - reveal), segment, true)
        canvas.drawPath(segment, paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
