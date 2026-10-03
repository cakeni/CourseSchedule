package com.courseschedule.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.pressBack
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.courseschedule.R
import com.courseschedule.ui.importdata.ImportActivity
import com.google.android.material.card.MaterialCardView
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ControlPressFeedbackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val folder = File(context.getExternalFilesDir(null), "icon-feedback-proof").apply { mkdirs() }
    private val suffix = InstrumentationRegistry.getArguments().getString("proofSuffix", "light")

    private fun settle(ms: Long = 350L) {
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).postDelayed({ done.countDown() }, ms)
        assertTrue(done.await(3, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }

    private fun touch(view: View, action: Int, down: Long) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, view.width / 2f, view.height / 2f, 0)
        try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
    }

    private fun shot(name: String): Bitmap = instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
        File(folder, "$name-$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun toolbarPressHasNoGrayHaloAndPopupReleaseRestoresTheButton() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settle(900L)
            lateinit var button: View
            val bounds = Rect()
            var density = 1f
            scenario.onActivity { activity ->
                button = activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
                    .menu.findItem(R.id.action_course_assistant).actionView!!
                button.getGlobalVisibleRect(bounds)
                density = activity.resources.displayMetrics.density
            }
            val normal = shot("toolbar-normal")
            val down = SystemClock.uptimeMillis()
            scenario.onActivity { touch(button, MotionEvent.ACTION_DOWN, down) }
            settle(180L)
            val pressed = shot("toolbar-pressed")
            val inner = Rect(bounds).apply { inset((11 * density).toInt(), (11 * density).toInt()) }
            var changedOutsideIcon = 0
            for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) {
                if (!inner.contains(x, y) && normal.getPixel(x, y) != pressed.getPixel(x, y)) changedOutsideIcon++
            }
            assertEquals("Touch must never paint a gray halo outside the glyph", 0, changedOutsideIcon)
            scenario.onActivity {
                assertTrue(button.isPressed)
                assertEquals(.94f, button.scaleX, .002f)
                touch(button, MotionEvent.ACTION_UP, down)
            }
            settle(420L)
            onView(withId(R.id.assistantEntry)).check(matches(isDisplayed()))
            scenario.onActivity { assertFalse(button.isPressed); assertEquals(1f, button.scaleX, .001f) }
            shot("toolbar-popup").recycle()
            File(folder, "toolbar-press-$suffix.json").writeText(Gson().toJson(mapOf("changedPixelsOutsideIcon" to changedOutsideIcon, "releasedScale" to 1f)))
            onView(withId(R.id.assistantEntry)).perform(pressBack())
            normal.recycle(); pressed.recycle()
        }
    }

    @Test fun importCardRetainsItsColorOnPressAndCanceledGestureRestoresScale() {
        ActivityScenario.launch(ImportActivity::class.java).use { scenario ->
            settle(1000L)
            lateinit var card: MaterialCardView
            var color = 0
            var restored = 1f
            val down = SystemClock.uptimeMillis()
            scenario.onActivity { activity ->
                card = activity.findViewById(R.id.cardImportSchool)
                color = card.cardBackgroundColor.defaultColor
                assertEquals(Color.TRANSPARENT, card.rippleColor.getColorForState(intArrayOf(android.R.attr.state_pressed, android.R.attr.state_enabled), -1))
                touch(card, MotionEvent.ACTION_DOWN, down)
            }
            settle(180L)
            scenario.onActivity {
                assertEquals(color, card.cardBackgroundColor.defaultColor)
                assertTrue(card.scaleX < 1f)
                touch(card, MotionEvent.ACTION_CANCEL, down)
            }
            settle()
            scenario.onActivity {
                restored = card.scaleX
                assertFalse(card.isPressed)
                assertEquals(1f, restored, .001f)
                assertEquals(Color.TRANSPARENT, card.rippleColor.getColorForState(intArrayOf(android.R.attr.state_selected, android.R.attr.state_pressed), -1))
            }
            shot("import-release").recycle()
            File(folder, "card-press-$suffix.json").writeText(Gson().toJson(mapOf("background" to color, "releasedScale" to restored)))
        }
    }
}
