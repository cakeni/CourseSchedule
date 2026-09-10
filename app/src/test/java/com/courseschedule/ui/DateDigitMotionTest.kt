package com.courseschedule.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DateDigitMotionTest {
    @Test
    fun onlyChangedPlacesRollWithinTheirDateFields() {
        val cases = listOf(
            Triple("2026/10/12", "2026/10/19", setOf(4 to 0)),
            Triple("2026/10/5", "2026/10/12", setOf(4 to 0, 4 to 1)),
            Triple("2026/9/28", "2026/10/5", setOf(2 to 0, 2 to 1, 4 to 0, 4 to 1)),
            Triple("2026/12/28", "2027/1/4", setOf(0 to 0, 2 to 0, 2 to 1, 4 to 0, 4 to 1)),
            Triple("第10周 · 周一", "第11周 · 周一", setOf(1 to 0)),
            Triple("第19周 · 周一", "第20周 · 周一", setOf(1 to 0, 1 to 1))
        )
        for ((before, after, expected) in cases) {
            val motion = DateDigitMotion()
            motion.set(before, animate = false)
            motion.set(after)
            val rolling = motion.glyphs.filter { it.fromY != it.toY }
                .map { it.slot.run to it.slot.place }.toSet()
            assertEquals("$before -> $after", expected, rolling)
            motion.glyphs.filter { !it.isDigit }.forEach { assertNoRoll(it) }
            motion.advance(1f)
            assertEquals(after, motion.glyphs.joinToString("") { it.text })
            assertFalse(motion.isMoving)
        }
    }

    @Test
    fun addedDigitsMoveTheSuffixWithoutAnimatingUnchangedDigits() {
        val motion = DateDigitMotion()
        motion.set("2026/9/5", animate = false)
        motion.set("2026/10/5")
        val lastSlash = motion.glyphs.single { it.slot.run == 3 }
        val day = motion.glyphs.single { it.slot.run == 4 }
        for (glyph in listOf(lastSlash, day)) {
            assertNoRoll(glyph)
            assertEquals(10f, glyph.toX - glyph.fromX, 0.001f)
        }
        motion.glyphs.filter { it.slot.run <= 1 }.forEach {
            assertNoRoll(it)
            assertEquals(it.fromX, it.toX, 0f)
        }
        motion.advance(1f)
        motion.set("2026/9/5", distance = -12f)
        assertTrue(motion.glyphs.filter { it.toAlpha == 0f }.all { it.toY == -12f })
        assertTrue(motion.glyphs.filter { it.fromAlpha == 0f }.all { it.fromY == 12f })
    }

    @Test
    fun rapidRetargetAndReverseContinueFromTheExactVisibleState() {
        val motion = DateDigitMotion()
        motion.set("2026/10/12", animate = false)
        motion.set("2026/10/19")
        for ((text, distance) in listOf("2026/10/26" to 12f, "2026/10/12" to -12f)) {
            motion.advance(0.35f)
            val before = snapshot(motion)
            motion.set(text, distance = distance)
            assertEquals(before, snapshot(motion))
            for (fraction in listOf(0f, 0.3f, 0.7f)) {
                motion.advance(fraction)
                motion.glyphs.groupBy { it.slot }.values.forEach { glyphs ->
                    assertEquals(1f, glyphs.sumOf { it.alpha(fraction).toDouble() }.toFloat(), 0.001f)
                }
            }
        }
        motion.advance(1f)
        assertEquals("2026/10/12", motion.glyphs.joinToString("") { it.text })
        assertEquals(motion.glyphs.size, motion.glyphs.distinctBy { it.slot }.size)
        motion.glyphs.forEach { assertNoRoll(it) }
        assertFalse(motion.isMoving)
    }

    @Test
    fun firstDisplayDisabledMotionAndWeekdayOnlyChangesStayStill() {
        val motion = DateDigitMotion()
        motion.set("第6周 · 周一")
        assertFalse(motion.isMoving)
        motion.set("第6周 · 周二")
        assertFalse(motion.isMoving)
        assertEquals("第6周 · 周二", motion.glyphs.joinToString("") { it.text })
        motion.set("第7周 · 周二")
        assertTrue(motion.isMoving)
        motion.set("第10周 · 周二", animate = false)
        assertFalse(motion.isMoving)
        motion.glyphs.forEach { assertNoRoll(it) }
    }

    private fun DateDigitMotion.set(text: String, animate: Boolean = true, distance: Float = 12f) =
        setText(text, FloatArray(text.length) { 10f }, animate, distance)

    private fun snapshot(motion: DateDigitMotion) = motion.glyphs
        .filter { it.alpha(motion.progress) > 0f }
        .associate { (it.slot to it.text) to listOf(it.x(motion.progress), it.y(motion.progress), it.alpha(motion.progress)) }

    private fun assertNoRoll(glyph: DateDigitMotion.Glyph) {
        assertEquals(0f, glyph.fromY, 0f)
        assertEquals(0f, glyph.toY, 0f)
        assertEquals(1f, glyph.fromAlpha, 0f)
        assertEquals(1f, glyph.toAlpha, 0f)
    }
}
