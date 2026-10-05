package com.courseschedule.ui.assistant

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.courseschedule.R

internal class TaskCompletionCapsule(private val context: Context, private val completed: Boolean, private val rowHeight: Int) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = ContextCompat.getColor(context, if (completed) R.color.reference_success else R.color.reference_blue_surface)
    private val ink = ContextCompat.getColor(context, if (completed) R.color.reference_success_text else R.color.text_primary)
    private val rect = RectF()
    private val check = Path()
    var progress = 0f
        set(value) { field = value; invalidateSelf() }
    override fun draw(canvas: Canvas) {
        val p = com.courseschedule.ui.SchedulePageMotion.phase((progress / .48f).coerceIn(0f, 1f))
        val height = 42f * density
        val y = rowHeight / 2f
        val right = 44 * density + (bounds.width() - 44 * density) * p
        rect.set(0f, y - height / 2f, right, y + height / 2f)
        paint.style = Paint.Style.FILL; paint.color = fill
        canvas.drawRoundRect(rect, height / 2f, height / 2f, paint)
        val x = 24 * density + (bounds.width() / 2f - 54 * density) * p
        paint.color = ink; paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.2f * density
        paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        check.reset(); check.moveTo(x - 6 * density, y); check.lineTo(x - 1 * density, y + 5 * density); check.lineTo(x + 8 * density, y - 5 * density)
        canvas.drawPath(check, paint)
        paint.style = Paint.Style.FILL; paint.textSize = 13f * context.resources.displayMetrics.scaledDensity
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.alpha = (((p - .25f) / .5f).coerceIn(0f, 1f) * 255).toInt()
        val label = if (completed) "已完成" else "已重新打开"
        val baseline = y - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
        canvas.drawText(label, x + 17 * density, baseline, paint)
        paint.alpha = 255
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
