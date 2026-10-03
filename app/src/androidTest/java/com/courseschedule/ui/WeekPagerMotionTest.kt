package com.courseschedule.ui

import android.Manifest
import android.animation.ValueAnimator
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.core.view.children
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.swipeLeft
import androidx.test.espresso.action.ViewActions.swipeRight
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import com.courseschedule.R
import com.courseschedule.domain.WeekMotionStyle
import com.courseschedule.utils.SchedulePreferences
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.File

@RunWith(AndroidJUnit4::class)
class WeekPagerMotionTest {
    @Test fun weekdayCellsAnimateInBothDirectionsAndSettleWithoutOvershoot() {
        verifyWeekdayCells(WeekMotionStyle.CONTINUITY)
    }

    @Test fun softSlideAlsoAnimatesTheMonthAndWeekdayCellsAfterEveryWeekChange() {
        verifyWeekdayCells(WeekMotionStyle.SOFT_SLIDE)
    }

    @Test fun disabledAnimatorsLeaveTheMonthAndWeekdayCellsSettledInBothStyles() {
        WeekMotionStyle.entries.forEach { verifyWeekdayCells(it, expectMotion = false) }
    }

    private fun verifyWeekdayCells(style: WeekMotionStyle, expectMotion: Boolean = true) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Window attachment initializes ValueAnimator's process-wide scale;
        // before launch, read the configured system value rather than its cache.
        val scale = Settings.Global.getFloat(instrumentation.targetContext.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        assumeTrue((scale > 0f) == expectMotion)
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(
                instrumentation.targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS
            )
        }
        val preferences = SchedulePreferences(instrumentation.targetContext)
        val previousStyle = preferences.weekMotionStyle
        preferences.weekMotionStyle = style
        try {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var pager: ViewPager2
            lateinit var listener: ViewTreeObserver.OnPreDrawListener
            val ready = CountDownLatch(1)
            val failures = mutableListOf<String>()
            var movingFrames = 0
            var settledFrames = 0
            var animatedFrames = 0
            var waveCount = 0
            var monthWaves = 0
            var monthMovingFrames = 0
            var previousMonthOffset: Float? = null
            var forward = true
            var previousOffsets: List<Float>? = null
            val staggerDirections = mutableSetOf<Boolean>()
            var checking = false
            scenario.onActivity { activity ->
                pager = activity.findViewById(R.id.weekPager)
                listener = ViewTreeObserver.OnPreDrawListener {
                    val holder = (pager.getChildAt(0) as RecyclerView)
                        .findViewHolderForAdapterPosition(pager.currentItem)
                    val header = holder?.itemView?.findViewById<View>(R.id.weekDayHeader)
                    if (pager.visibility == View.VISIBLE && header?.isLaidOut == true &&
                        (pager.adapter?.itemCount ?: 0) > 1
                    ) {
                        ready.countDown()
                        if (checking) {
                            val origin = IntArray(2).also { pager.getLocationOnScreen(it) }
                            val row = IntArray(2).also { header.getLocationOnScreen(it) }
                            val opacity = header.alpha * holder.itemView.alpha
                            if (row[1] != origin[1] || opacity != 1f) {
                                failures += "Date row's frame moved: y=${row[1]}, expected=${origin[1]}, alpha=$opacity"
                            }
                            if (pager.scrollState == ViewPager2.SCROLL_STATE_IDLE) {
                                settledFrames++
                                if (row[0] != origin[0]) {
                                    failures += "Date row moved again after landing: x=${row[0]}, expected=${origin[0]}"
                                }
                                val labels = (header as ViewGroup).children.filter { it.visibility != View.GONE }.toList()
                                val offsets = labels.map { it.translationY }
                                val active = offsets.any { it > 0.05f }
                                val monthOffset = header.findViewById<View>(R.id.tvMonthLabel).translationY
                                if (monthOffset > 0.05f) {
                                    monthMovingFrames++
                                    if (previousMonthOffset == null) monthWaves++
                                }
                                if (monthOffset > 0.05f || previousMonthOffset != null) previousMonthOffset = monthOffset
                                if (active) {
                                    animatedFrames++
                                    if (previousOffsets == null) waveCount++
                                    val difference = offsets.last() - offsets.first()
                                    if ((forward && difference > 0.1f) || (!forward && difference < -0.1f)) {
                                        staggerDirections += forward
                                    }
                                }
                                labels.forEach { label ->
                                    if (label.translationX != 0f || label.translationY < -0.01f ||
                                        label.alpha < 0.7f || label.alpha > 1f
                                    ) failures += "A date cell bounced sideways, overshot, or became unreadable"
                                }
                                previousOffsets?.zip(offsets)?.forEach { (before, after) ->
                                    if (after > before + 0.01f) failures += "A date cell reversed while settling"
                                }
                                if (active || previousOffsets != null) previousOffsets = offsets
                            } else {
                                movingFrames++
                                previousOffsets = null
                                previousMonthOffset = null
                            }
                        }
                    }
                    true
                }
                pager.viewTreeObserver.addOnPreDrawListener(listener)
            }
            try {
                assertTrue("Week pages must load", ready.await(15, TimeUnit.SECONDS))
                scenario.onActivity {
                    assertEquals("The activity must honor the configured animation scale", expectMotion,
                        ValueAnimator.areAnimatorsEnabled())
                    pager.setCurrentItem(0, false)
                }
                instrumentation.waitForIdleSync()
                Thread.sleep(450L)
                scenario.onActivity { checking = true }
                onView(withId(R.id.weekPager)).perform(swipeLeft())
                Thread.sleep(450L)
                scenario.onActivity {
                    assertEquals(1, pager.currentItem)
                    forward = false
                }
                onView(withId(R.id.weekPager)).perform(swipeRight())
                Thread.sleep(450L)
                scenario.onActivity {
                    assertEquals(0, pager.currentItem)
                    forward = true
                }
                onView(withId(R.id.btnNextWeek)).perform(click())
                Thread.sleep(450L)
                scenario.onActivity {
                    assertEquals(1, pager.currentItem)
                    assertTrue("Observe actual paging frames", movingFrames > 0)
                    assertTrue("Observe frames after landing", settledFrames > 0)
                    if (expectMotion) {
                        assertTrue("The circled cells must visibly animate", animatedFrames > 0)
                        assertEquals("Every week change must animate once", 3, waveCount)
                        assertEquals("The month must visibly animate after all three week changes", 3, monthWaves)
                        assertTrue("Observe rendered month motion", monthMovingFrames > 0)
                        assertEquals("Stagger follows both paging directions", setOf(true, false), staggerDirections)
                    } else {
                        assertEquals("Reduced motion must keep the date labels settled", 0, animatedFrames)
                        assertEquals("Reduced motion must keep the month settled", 0, monthWaves)
                    }
                    val holder = (pager.getChildAt(0) as RecyclerView)
                        .findViewHolderForAdapterPosition(pager.currentItem)!!
                    holder.itemView.findViewById<ViewGroup>(R.id.weekDayHeader).children.forEach {
                        assertEquals(0f, it.translationY, 0f)
                        assertEquals(1f, it.alpha, 0f)
                    }
                    assertTrue(failures.take(5).joinToString("\n"), failures.isEmpty())
                    val suffix = InstrumentationRegistry.getArguments().getString("proofSuffix", "light")
                    val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "weekday-motion-proof").apply { mkdirs() }
                    File(folder, "${style.storedValue}-$suffix.json").writeText(Gson().toJson(mapOf(
                        "monthWaves" to monthWaves, "monthMovingFrames" to monthMovingFrames,
                        "weekdayWaves" to waveCount, "pagingFrames" to movingFrames,
                        "directions" to staggerDirections, "failures" to failures)))
                }
            } finally {
                scenario.onActivity { pager.viewTreeObserver.removeOnPreDrawListener(listener) }
            }
        }
        } finally { preferences.weekMotionStyle = previousStyle }
    }
}
