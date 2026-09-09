package com.courseschedule.ui

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.view.ViewGroup
import androidx.core.view.doOnLayout
import androidx.core.view.drawToBitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.courseschedule.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DateHeaderViewTest {

    @Test
    fun onlyChangedDigitsMoveAndUnchangedPrefixIsPixelIdentical() {
        assumeTrue(ValueAnimator.areAnimatorsEnabled())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            isolateHeader(scenario)
            scenario.onActivity { activity ->
                val header = activity.findViewById<DateHeaderView>(R.id.dateHeader)
                header.setDate("2026/10/12", "第10周 · 周一", animate = true)
                assertSettled(header)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val header = activity.findViewById<DateHeaderView>(R.id.dateHeader)
                val row = rows(header).first()
                val before = row.drawToBitmap()
                val prefixWidth = row.motion.glyphs.single { it.slot == DateDigitMotion.Slot(4, 1) }.fromX.toInt()
                header.setDate("2026/10/19", "第11周 · 周一", animate = true)
                row.advance(0.5f)
                val after = row.drawToBitmap()
                assertTrue("Unchanged year/month must not move or fade", Bitmap.createBitmap(
                    before, 0, 0, prefixWidth, before.height
                ).sameAs(Bitmap.createBitmap(after, 0, 0, prefixWidth, after.height)))
                assertEquals(setOf(DateDigitMotion.Slot(4, 0)),
                    row.motion.glyphs.filter { it.fromY != it.toY }.map { it.slot }.toSet())
                assertEquals(setOf(DateDigitMotion.Slot(1, 0)),
                    rows(header)[1].motion.glyphs.filter { it.fromY != it.toY }.map { it.slot }.toSet())

                val state = row.motion.glyphs to row.motion.progress
                header.setDate("2026/10/19", "第11周 · 周一", animate = false)
                assertEquals(state, row.motion.glyphs to row.motion.progress)
            }
            Thread.sleep(450L)
            scenario.onActivity { activity ->
                val header = activity.findViewById<DateHeaderView>(R.id.dateHeader)
                assertEquals("2026/10/19，第11周 · 周一", header.contentDescription)
                assertSettled(header)
                header.setDate("2026/12/28", "第18周 · 周一", animate = false)
                assertSettled(header)
            }
        }
    }

    @Test
    fun rapidRetargetReverseAndDetachKeepTheLatestTextAndNeverBlank() {
        assumeTrue(ValueAnimator.areAnimatorsEnabled())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            isolateHeader(scenario)
            scenario.onActivity { activity ->
                val header = activity.findViewById<DateHeaderView>(R.id.dateHeader)
                header.setDate("2026/10/12", "第5周 · 周一", animate = false)
                header.setDate("2026/10/19", "第6周 · 周一", animate = true)
            }
            Thread.sleep(80L)
            scenario.onActivity { activity ->
                val header = activity.findViewById<DateHeaderView>(R.id.dateHeader)
                assertOpacity(header)
                header.setDate("2026/10/26", "第7周 · 周一", animate = true)
                assertOpacity(header)
                assertEquals("2026/10/26", header.dateText)
            }
            Thread.sleep(60L)
            scenario.onActivity { activity ->
                val header = activity.findViewById<DateHeaderView>(R.id.dateHeader)
                header.setDate("2026/10/12", "第5周 · 周一", animate = true, forward = false)
                assertOpacity(header)
            }
            Thread.sleep(450L)
            scenario.onActivity { activity ->
                val header = activity.findViewById<DateHeaderView>(R.id.dateHeader)
                assertEquals("2026/10/12", header.dateText)
                assertEquals("第5周 · 周一", header.summaryText)
                assertSettled(header)
                header.setDate("2026/11/2", "第8周 · 周一", animate = true)
                val parent = header.parent as ViewGroup
                val index = parent.indexOfChild(header)
                val params = header.layoutParams
                parent.removeView(header)
                assertSettled(header)
                assertEquals("2026/11/2", header.dateText)
                parent.addView(header, index, params)
            }
        }
    }

    private fun isolateHeader(scenario: ActivityScenario<MainActivity>) {
        val laidOut = CountDownLatch(1)
        scenario.onActivity { activity ->
            // Keep Room/semester observers from replacing the test strings asynchronously.
            val original = activity.findViewById<DateHeaderView>(R.id.dateHeader)
            val parent = original.parent as ViewGroup
            val index = parent.indexOfChild(original)
            val params = original.layoutParams
            parent.removeView(original)
            val header = DateHeaderView(activity).apply {
                id = R.id.dateHeader
                doOnLayout { laidOut.countDown() }
            }
            parent.addView(header, index, params)
        }
        assertTrue("Date header was not laid out", laidOut.await(5L, TimeUnit.SECONDS))
    }

    private fun rows(header: DateHeaderView) = (0..1).map { header.getChildAt(it) as DateHeaderView.DigitRow }

    private fun assertOpacity(header: DateHeaderView) {
        rows(header).forEach { row ->
            row.motion.glyphs.groupBy { it.slot }.values.forEach { glyphs ->
                assertEquals(1f, glyphs.sumOf { it.alpha(row.motion.progress).toDouble() }.toFloat(), 0.001f)
            }
        }
    }

    private fun assertSettled(header: DateHeaderView) {
        rows(header).forEach { row ->
            assertFalse(row.motion.isMoving)
            assertEquals(row.text.toString(), row.motion.glyphs.joinToString("") { it.text })
            row.motion.glyphs.forEach {
                assertEquals(0f, it.y(row.motion.progress), 0f)
                assertEquals(1f, it.alpha(row.motion.progress), 0f)
            }
        }
    }
}
