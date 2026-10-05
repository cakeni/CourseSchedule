package com.courseschedule.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.courseschedule.R

/** A calendar medallion drawn at the view's own resolution. */
class ReferenceCalendarView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val light = ContextCompat.getColor(context, R.color.reference_blue_light)
    private val deep = ContextCompat.getColor(context, R.color.reference_blue_deep)
    private val accent = ContextCompat.getColor(context, R.color.reference_blue_accent)
    private var outside: Shader? = null
    private var inside: Shader? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val size = minOf(w, h).toFloat()
        outside = LinearGradient(size * .2f, size * .1f, size * .85f, size,
            intArrayOf(Color.WHITE, light, deep), floatArrayOf(0f, .25f, 1f), Shader.TileMode.CLAMP)
        inside = RadialGradient(size * .38f, size * .28f, size * .66f,
            intArrayOf(light, deep, accent), floatArrayOf(0f, .65f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        val save = canvas.save()
        canvas.translate((width - size) / 2f, (height - size) / 2f)
        val center = size / 2f
        paint.color = 0x126B8C98
        rect.set(size * .1f, size * .78f, size * .9f, size * .95f)
        canvas.drawOval(rect, paint)
        paint.color = Color.WHITE
        paint.shader = outside
        canvas.drawCircle(center, size * .46f, size * .41f, paint)
        paint.shader = inside
        canvas.drawCircle(center, size * .46f, size * .33f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * .015f
        paint.color = 0x70FFFFFF
        canvas.drawCircle(center, size * .46f, size * .385f, paint)
        paint.style = Paint.Style.FILL
        paint.color = 0xF5FFFFFF.toInt()
        rect.set(size * .32f, size * .32f, size * .68f, size * .64f)
        canvas.drawRoundRect(rect, size * .055f, size * .055f, paint)
        paint.strokeWidth = size * .04f
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(size * .4f, size * .27f, size * .4f, size * .37f, paint)
        canvas.drawLine(size * .6f, size * .27f, size * .6f, size * .37f, paint)
        paint.color = deep
        paint.strokeWidth = size * .019f
        canvas.drawLine(size * .38f, size * .43f, size * .62f, size * .43f, paint)
        paint.color = accent
        for (x in listOf(.41f, .5f, .59f)) for (y in listOf(.51f, .57f))
            canvas.drawCircle(size * x, size * y, size * .018f, paint)
        canvas.restoreToCount(save)
    }
}
