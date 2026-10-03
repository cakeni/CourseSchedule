package com.courseschedule.ui

import android.Manifest
import android.animation.ValueAnimator
import android.os.Build
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WeekPagerMotionTest {
    @Test fun weekdayCellsAnimateInBothDirectionsAndSettleWithoutOvershoot() {
        assumeTrue(ValueAnimator.areAnimatorsEnabled())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(
                instrumentation.targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS
            )
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var pager: ViewPager2
            lateinit var listener: ViewTreeObserver.OnPreDrawListener
            val ready = CountDownLatch(1)
            val failures = mutableListOf<String>()
            var movingFrames = 0
            var settledFrames = 0
            var animatedFrames = 0
            var waveCount = 0
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
                            }
                        }
                    }
                    true
                }
                pager.viewTreeObserver.addOnPreDrawListener(listener)
            }
            try {
                assertTrue("Week pages must load", ready.await(15, TimeUnit.SECONDS))
                scenario.onActivity { pager.setCurrentItem(0, false) }
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
                    assertTrue("The circled cells must visibly animate", animatedFrames > 0)
                    assertEquals("Every week change must animate once", 3, waveCount)
                    assertEquals("Stagger follows both paging directions", setOf(true, false), staggerDirections)
                    val holder = (pager.getChildAt(0) as RecyclerView)
                        .findViewHolderForAdapterPosition(pager.currentItem)!!
                    holder.itemView.findViewById<ViewGroup>(R.id.weekDayHeader).children.forEach {
                        assertEquals(0f, it.translationY, 0f)
                        assertEquals(1f, it.alpha, 0f)
                    }
                    assertTrue(failures.take(5).joinToString("\n"), failures.isEmpty())
                }
            } finally {
                scenario.onActivity { pager.viewTreeObserver.removeOnPreDrawListener(listener) }
            }
        }
    }
}
