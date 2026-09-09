package com.courseschedule.ui

/** Glyph matching for the short date / week labels, independent of Android rendering. */
internal class DateDigitMotion {
    data class Slot(val run: Int, val place: Int)

    data class Glyph(
        val slot: Slot,
        val text: String,
        val fromX: Float,
        val toX: Float = fromX,
        val fromY: Float = 0f,
        val toY: Float = 0f,
        val fromAlpha: Float = 1f,
        val toAlpha: Float = 1f
    ) {
        val isDigit: Boolean get() = slot.place >= 0
        fun x(progress: Float) = fromX + (toX - fromX) * progress
        fun y(progress: Float) = fromY + (toY - fromY) * progress
        fun alpha(progress: Float) = fromAlpha + (toAlpha - fromAlpha) * progress
    }

    var glyphs: List<Glyph> = emptyList()
        private set
    var progress = 1f
        private set
    var isMoving = false
        private set
    private var target: List<Glyph> = emptyList()

    fun setText(text: String, widths: FloatArray, animate: Boolean, distance: Float) {
        require(widths.size == text.length)
        val next = layout(text, widths)
        if (!animate || glyphs.isEmpty()) {
            target = next
            finish()
            return
        }

        // Retarget from what is on screen, including any still-fading digits.
        val visible = glyphs.filter { it.alpha(progress) > 0f }.map {
            Glyph(it.slot, it.text, it.x(progress), fromY = it.y(progress), fromAlpha = it.alpha(progress))
        }
        val remaining = visible.toMutableList()
        val result = mutableListOf<Glyph>()
        val nextBySlot = next.associateBy { it.slot }
        for (glyph in next) {
            val sameSlot = visible.filter { it.slot == glyph.slot }
            val matching = sameSlot.filter { it.text == glyph.text }.maxByOrNull { it.fromAlpha }
            if (matching != null) {
                remaining.remove(matching)
                result += matching.copy(toX = glyph.toX, toY = 0f, toAlpha = 1f)
            } else {
                val startX = sameSlot.maxByOrNull { it.fromAlpha }?.fromX ?: glyph.fromX
                result += glyph.copy(
                    fromX = startX,
                    fromY = if (glyph.isDigit) -distance else 0f,
                    fromAlpha = if (glyph.isDigit) 0f else 1f
                )
            }
        }
        for (glyph in remaining) {
            // Punctuation and weekday words never roll or fade, even if the weekday changes.
            if (glyph.isDigit) result += glyph.copy(
                toX = nextBySlot[glyph.slot]?.toX ?: glyph.fromX,
                toY = distance,
                toAlpha = 0f
            )
        }
        target = next
        glyphs = result
        progress = 0f
        isMoving = result.any {
            it.fromX != it.toX || it.fromY != it.toY || it.fromAlpha != it.toAlpha
        }
        if (!isMoving) finish()
    }

    fun advance(fraction: Float) {
        progress = fraction.coerceIn(0f, 1f)
        if (progress == 1f) finish()
    }

    private fun finish() {
        glyphs = target
        progress = 1f
        isMoving = false
    }

    private fun layout(text: String, widths: FloatArray): List<Glyph> {
        val result = mutableListOf<Glyph>()
        var start = 0
        var run = 0
        var x = 0f
        while (start < text.length) {
            val digit = text[start] in '0'..'9'
            var end = start + 1
            while (end < text.length && (text[end] in '0'..'9') == digit) end++
            if (digit) {
                // Match units/tens within each field, not raw string indices across slashes.
                for (index in start until end) {
                    result += Glyph(Slot(run, end - index - 1), text[index].toString(), x)
                    x += widths[index]
                }
            } else {
                result += Glyph(Slot(run, -1), text.substring(start, end), x)
                for (index in start until end) x += widths[index]
            }
            start = end
            run++
        }
        return result
    }
}
