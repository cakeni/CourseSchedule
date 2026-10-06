package com.courseschedule.ui.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView
import android.widget.ImageView
import android.animation.ValueAnimator
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.courseschedule.R
import com.courseschedule.ui.MainActivity
import com.courseschedule.ui.playNavigationMotion
import com.courseschedule.utils.SchedulePreferences
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.google.android.material.R as MaterialR

@RunWith(AndroidJUnit4::class)
class SettingsThemeAndPermissionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = SchedulePreferences(context)
    private val suffix = InstrumentationRegistry.getArguments().getString("proofSuffix", "light")
    private val folder by lazy { File(context.getExternalFilesDir(null), "settings-theme-permission-proof").apply { mkdirs() } }
    // Resolve these IDs at runtime so the frame check can also reproduce the defect in the previous APK.
    private fun id(name: String) = context.resources.getIdentifier(name, "id", context.packageName)

    private fun fixture(block: () -> Unit) {
        val previous = preferences.snapshot()
        val previousOverride = preferences.darkModeOverride
        val previousMode = AppCompatDelegate.getDefaultNightMode()
        val dark = InstrumentationRegistry.getArguments().getString("themeMode", "light") == "dark"
        preferences.darkModeOverride = dark
        instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO) }
        try { block() } finally {
            preferences.applySnapshot(previous)
            preferences.darkModeOverride = previousOverride
            instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(previousMode) }
        }
    }

    private fun awaitSettings(): SettingsActivity {
        val ready = CountDownLatch(1)
        val deadline = SystemClock.uptimeMillis() + 6000
        var activity: SettingsActivity? = null
        instrumentation.runOnMainSync {
            fun poll() {
                activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<SettingsActivity>().firstOrNull { it.window.decorView.hasWindowFocus() }
                if (activity != null) ready.countDown()
                else if (SystemClock.uptimeMillis() < deadline) Handler(Looper.getMainLooper()).postDelayed({ poll() }, 30)
            }
            poll()
        }
        assertTrue("Settings did not gain focus", ready.await(7, TimeUnit.SECONDS))
        return activity!!
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(180)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        folder.resolve("$name-$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun capsuleRemainsUnderSettingsOnEveryThemeSwitchFrame() = fixture {
        // Keep a real timetable behind settings: its theme update must not move this page's capsule.
        ActivityScenario.launch(MainActivity::class.java).use { timetable ->
            SystemClock.sleep(700)
            lateinit var backgroundNavigation: BottomNavigationView
            timetable.onActivity { activity -> backgroundNavigation = activity.findViewById(id("bottomNavigation")) }
            onView(withId(id("nav_settings"))).perform(click())
            val activity = awaitSettings()
            SystemClock.sleep(850)
            val frames = mutableListOf<List<Float>>()
            val failures = mutableListOf<String>()
            val bitmap = Bitmap.createBitmap(activity.resources.displayMetrics.widthPixels,
                (80 * activity.resources.displayMetrics.density).toInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val pixels = IntArray(bitmap.width * bitmap.height)
            fun sample() {
                val nav = activity.findViewById<BottomNavigationView>(id("bottomNavigation"))
                if (nav.width == 0 || nav.height == 0) return
                val icon = nav.findViewById<View>(id("nav_settings")).findViewById<View>(id("navigation_bar_item_icon_view"))
                val iconBounds = Rect(0, 0, icon.width, icon.height)
                nav.offsetDescendantRectToMyCoords(icon, iconBounds)
                bitmap.eraseColor(Color.TRANSPARENT)
                nav.draw(canvas)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                var left = bitmap.width
                var right = -1
                val band = (4 * nav.resources.displayMetrics.density).toInt()
                for (y in (iconBounds.centerY() - band)..(iconBounds.centerY() + band)) for (x in 0 until nav.width) {
                    val color = pixels[y * bitmap.width + x]
                    if (Color.alpha(color) > 100 && Color.blue(color) > Color.red(color) + 45 && Color.blue(color) > Color.green(color) + 30) {
                        left = minOf(left, x)
                        right = maxOf(right, x)
                    }
                }
                val center = (left + right) / 2f
                val drift = kotlin.math.abs(center - iconBounds.exactCenterX()) / nav.resources.displayMetrics.density
                frames += listOf(center, iconBounds.exactCenterX(), drift)
                if (right < left) failures += "The selected capsule disappeared"
                else if (drift > 1f) failures += "Capsule moved ${drift}dp away from settings"
                if (nav.selectedItemId != id("nav_settings")) failures += "Theme restoration selected another tab"
            }
            val listener = ViewTreeObserver.OnDrawListener { sample() }
            instrumentation.runOnMainSync { activity.window.decorView.viewTreeObserver.addOnDrawListener(listener) }
            try {
                repeat(3) {
                    // A background timetable can restore its own home selection during a palette update.
                    // Exercise that real navigation feedback before rebuilding the foreground settings bar.
                    instrumentation.runOnMainSync { backgroundNavigation.findViewById<View>(id("nav_home")).playNavigationMotion() }
                    SystemClock.sleep(450)
                    val finished = CountDownLatch(1)
                    instrumentation.runOnMainSync {
                        activity.findViewById<View>(id("rowDarkMode")).performClick()
                        val deadline = SystemClock.uptimeMillis() + 750
                        val callback = object : Choreographer.FrameCallback {
                            override fun doFrame(frameTimeNanos: Long) {
                                sample()
                                if (SystemClock.uptimeMillis() >= deadline) finished.countDown()
                                else Choreographer.getInstance().postFrameCallback(this)
                            }
                        }
                        Choreographer.getInstance().postFrameCallback(callback)
                    }
                    assertTrue("Theme switch did not complete", finished.await(4, TimeUnit.SECONDS))
                }
                folder.resolve("capsule-frames-$suffix.json").writeText(Gson().toJson(mapOf("frames" to frames, "failures" to failures)))
                assertTrue("Actual navigation frames must be sampled", frames.size > 12)
                assertTrue(failures.take(8).joinToString(), failures.isEmpty())
                screenshot("theme-settled")
            } finally {
                instrumentation.runOnMainSync { activity.window.decorView.viewTreeObserver.removeOnDrawListener(listener) }
                bitmap.recycle()
                instrumentation.runOnMainSync { activity.finish() }
            }
        }
    }

    @Test fun themeSwitchDuringIconFeedbackCapturesRestingSettingsGlyph() = fixture {
        assumeTrue(ValueAnimator.areAnimatorsEnabled())
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            SystemClock.sleep(850)
            val finished = CountDownLatch(1)
            var startedSpinning = false
            var continuedSpinning = false
            var glyphChanged = false
            fun pixels(icon: ImageView): List<Int> {
                val bitmap = Bitmap.createBitmap(icon.width, icon.height, Bitmap.Config.ARGB_8888)
                icon.draw(Canvas(bitmap))
                val colors = IntArray(icon.width * icon.height)
                bitmap.getPixels(colors, 0, icon.width, 0, 0, icon.width, icon.height)
                bitmap.recycle()
                return colors.toList()
            }
            scenario.onActivity { activity ->
                val item = activity.findViewById<View>(id("nav_settings"))
                val icon = item.findViewById<ImageView>(id("navigation_bar_item_icon_view"))
                val resting = pixels(icon)
                item.performClick()
                Handler(Looper.getMainLooper()).postDelayed({
                    startedSpinning = (icon.drawable as? Animatable)?.isRunning == true
                    activity.findViewById<View>(id("rowDarkMode")).performClick()
                    continuedSpinning = (icon.drawable as? Animatable)?.isRunning == true
                    glyphChanged = resting != pixels(icon)
                    finished.countDown()
                }, 100)
            }
            assertTrue("Early theme toggle did not run", finished.await(4, TimeUnit.SECONDS))
            folder.resolve("gear-snapshot-$suffix.json").writeText(Gson().toJson(mapOf(
                "feedbackWasRunning" to startedSpinning, "snapshotStillAnimating" to continuedSpinning,
                "snapshotGlyphChanged" to glyphChanged)))
            assertTrue("Test must start the real gear feedback", startedSpinning)
            assertFalse("Theme snapshot must not capture a moving gear", continuedSpinning)
            assertFalse("Captured settings icon must match its resting silhouette", glyphChanged)
            SystemClock.sleep(700)
        }
    }

    @Test fun explicitPermissionButtonOpensTheChooserAndStaysOutsideDiagnostics() = fixture {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            SystemClock.sleep(800)
            fun showButton() {
                scenario.onActivity { activity ->
                    val scroll = activity.findViewById<NestedScrollView>(R.id.settingsScroll)
                    val button = activity.findViewById<View>(R.id.buttonReminderPermissions)
                    val scrollPosition = IntArray(2).also(scroll::getLocationOnScreen)
                    val buttonPosition = IntArray(2).also(button::getLocationOnScreen)
                    // Center the reading area above the floating navigation, as a visible touch target.
                    scroll.scrollBy(0, buttonPosition[1] - scrollPosition[1] - scroll.height / 3)
                }
                instrumentation.waitForIdleSync()
            }
            showButton()
            onView(withId(R.id.buttonReminderPermissions)).check(matches(isDisplayed()))
            scenario.onActivity { activity ->
                val button = activity.findViewById<MaterialButton>(R.id.buttonReminderPermissions)
                assertEquals(context.getString(R.string.reminder_permissions_action), button.text.toString())
                assertNotNull("The permission button must have an action icon", button.icon)
                assertTrue(button.isClickable && button.isEnabled && button.isFocusable)
                assertTrue(button.height >= 48 * activity.resources.displayMetrics.density)
                assertFalse("Status details should read as text", activity.findViewById<TextView>(R.id.tvReminderStatus).isClickable)
            }
            screenshot("permission-entry")
            onView(withId(R.id.buttonReminderPermissions)).perform(click())
            onView(withText(R.string.reminder_settings_title)).check(matches(isDisplayed()))
            onView(withText(R.string.reminder_notification_settings)).check(matches(isDisplayed()))
            onView(withText(R.string.reminder_exact_settings)).check(matches(isDisplayed()))
            screenshot("permission-chooser")
            onView(withText(R.string.cancel)).perform(click())
            scenario.onActivity { activity -> activity.findViewById<View>(R.id.reminderDiagnostics).visibility = View.GONE }
            showButton()
            onView(withId(R.id.buttonReminderPermissions)).perform(click())
            onView(withText(R.string.reminder_notification_settings)).check(matches(isDisplayed()))
            onView(withText(R.string.cancel)).perform(click())
        }
    }
}
