package com.courseschedule.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.widget.ImageView

internal fun courseGlassBackdrop(activity: Activity): ImageView? {
    val source = activity.window.decorView
    if (source.width <= 0 || source.height <= 0) return null
    val width = if (Build.VERSION.SDK_INT >= 31) 240 else 96
    val bitmap = Bitmap.createBitmap(width, (source.height.toFloat() * width / source.width).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    source.draw(Canvas(bitmap).apply { scale(width.toFloat() / source.width, width.toFloat() / source.width) })
    if (Build.VERSION.SDK_INT < 31) blurCourseSnapshot(bitmap)
    return ImageView(activity).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setImageBitmap(bitmap)
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        if (Build.VERSION.SDK_INT >= 31) {
            val radius = 30f * resources.displayMetrics.density
            setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        }
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        alpha = 0f
    }
}

internal fun blurCourseSnapshot(bitmap: Bitmap) {
    val width = bitmap.width
    val height = bitmap.height
    val source = IntArray(width * height)
    val horizontal = IntArray(source.size)
    val result = IntArray(source.size)
    bitmap.getPixels(source, 0, width, 0, 0, width, height)
    repeat(2) {
        for (y in 0 until height) for (x in 0 until width) {
            var red = 0; var green = 0; var blue = 0
            for (offset in -8..8) {
                val pixel = source[y * width + (x + offset).coerceIn(0, width - 1)]
                red += Color.red(pixel); green += Color.green(pixel); blue += Color.blue(pixel)
            }
            horizontal[y * width + x] = Color.rgb(red / 17, green / 17, blue / 17)
        }
        for (y in 0 until height) for (x in 0 until width) {
            var red = 0; var green = 0; var blue = 0
            for (offset in -8..8) {
                val pixel = horizontal[(y + offset).coerceIn(0, height - 1) * width + x]
                red += Color.red(pixel); green += Color.green(pixel); blue += Color.blue(pixel)
            }
            result[y * width + x] = Color.rgb(red / 17, green / 17, blue / 17)
        }
        result.copyInto(source)
    }
    bitmap.setPixels(result, 0, width, 0, 0, width, height)
}
