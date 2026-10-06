package com.courseschedule.ui.settings

import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.doOnPreDraw
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.courseschedule.R
import com.courseschedule.utils.ReminderManager
import com.courseschedule.utils.SchedulePreferences
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SettingsEntranceMotionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = SchedulePreferences(context)
    private val suffix = InstrumentationRegistry.getArguments().getString("proofSuffix", "light")
    private val folder by lazy { File(context.getExternalFilesDir(null), "settings-motion-proof").apply { mkdirs() } }

    private fun fixture(block: () -> Unit) {
        val previous = preferences.snapshot()
        val previousOverride = preferences.darkModeOverride
        val previousTheme = AppCompatDelegate.getDefaultNightMode()
        val dark = InstrumentationRegistry.getArguments().getString("themeMode", "light") == "dark"
        preferences.reminderEnabled = false
        preferences.darkModeOverride = dark
        instrumentation.runOnMainSync {
            AppCompatDelegate.setDefaultNightMode(if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        }
        try { block() } finally {
            preferences.applySnapshot(previous)
            preferences.darkModeOverride = previousOverride
            instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(previousTheme) }
            runBlocking { ReminderManager(context).restoreReminders() }
        }
    }

    private fun cards(activity: SettingsActivity): List<MaterialCardView> {
        val content = activity.findViewById<ViewGroup>(R.id.settingsContent)
        return (0 until content.childCount).map(content::getChildAt).filterIsInstance<MaterialCardView>()
    }

    private fun panelHeight(card: MaterialCardView): Int {
        val outline = Outline().also { card.outlineProvider.getOutline(card, it) }
        val rect = Rect()
        if (!outline.getRect(rect)) {
            // Background bounds can still be unset before an offscreen card's first draw.
            assertSame(ViewOutlineProvider.BACKGROUND, card.outlineProvider)
            return card.height
        }
        return rect.height()
    }

    private fun settled(activity: SettingsActivity) {
        cards(activity).forEach { card ->
            assertEquals("A section must restore its full outline", card.height, panelHeight(card))
            assertEquals("A section must restore its content", 1f, card.getChildAt(0).alpha, .001f)
            assertEquals(1f, card.scaleX, .001f)
            assertEquals(1f, card.scaleY, .001f)
            assertEquals(0f, card.translationY, .001f)
        }
    }

    private fun screenshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
            folder.resolve("$name-$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun visibleSectionsUnfoldWithoutMovingHeadingsOrNavigationOnRepeatedEntry() = fixture {
        ActivityScenario.launch(com.courseschedule.ui.MainActivity::class.java).use {
            SystemClock.sleep(800)
            instrumentation.waitForIdleSync()
        }
        val pages = mutableListOf<MutableList<Frame>>()
        val completed = mutableListOf<CountDownLatch>()
        val failures = mutableListOf<String>()
        val samplers = mutableListOf<Choreographer.FrameCallback>()
        val listeners = mutableListOf<Pair<View, ViewTreeObserver.OnDrawListener>>()
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (stage == Stage.CREATED && activity is SettingsActivity) {
                val content = activity.findViewById<ViewGroup>(R.id.settingsContent)
                val appearance = activity.findViewById<View>(R.id.rowDarkMode).parent as MaterialCardView
                val semester = activity.findViewById<MaterialCardView>(R.id.cardSemester)
                val tracked = listOf(appearance, semester)
                val fixed = listOf(activity.findViewById<View>(R.id.toolbar), activity.findViewById<View>(R.id.bottomNavigation)) +
                    (0..content.indexOfChild(semester)).map(content::getChildAt)
                var geometry: List<List<Int>>? = null
                val samples = mutableListOf<Frame>()
                val done = CountDownLatch(1)
                pages += samples
                completed += done
                fun sample() {
                    val positions = fixed.map { listOf(it.left, it.top, it.width, it.height) }
                    if (geometry == null) geometry = positions
                    if (positions != geometry) failures += "A heading, panel slot or navigation moved"
                    if (fixed.any { it.scaleX != 1f || it.scaleY != 1f || it.translationY != 0f || it.alpha != 1f })
                        failures += "The page, heading or panel was scaled, moved or faded"
                    samples += Frame(tracked.map(::panelHeight), tracked.map { it.height }, tracked.map { it.getChildAt(0).alpha })
                }
                var started = false
                val listener = ViewTreeObserver.OnDrawListener {
                    if (!started) {
                        started = true
                        sample()
                        val startTime = SystemClock.uptimeMillis()
                        val sampler = object : Choreographer.FrameCallback {
                            override fun doFrame(frameTimeNanos: Long) {
                                if (appearance.isAttachedToWindow) {
                                    sample()
                                    val last = samples.last()
                                    if (last.height == last.fullHeight && last.alpha.all { it == 1f }) {
                                        done.countDown()
                                        return
                                    }
                                }
                                if (SystemClock.uptimeMillis() - startTime < 5000) Choreographer.getInstance().postFrameCallback(this)
                            }
                        }
                        samplers += sampler
                        Choreographer.getInstance().postFrameCallback(sampler)
                    }
                }
                appearance.viewTreeObserver.addOnDrawListener(listener)
                listeners += appearance to listener
            }
        }
        instrumentation.runOnMainSync { monitor.addLifecycleCallback(callback) }
        try {
            repeat(2) { pass ->
                ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                    assertTrue("Sections did not finish unfolding", completed[pass].await(6, TimeUnit.SECONDS))
                    SystemClock.sleep(500)
                    scenario.onActivity(::settled)
                    if (pass == 0) screenshot("settled")
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                monitor.removeLifecycleCallback(callback)
                samplers.forEach { Choreographer.getInstance().removeFrameCallback(it) }
                listeners.forEach { (view, listener) -> if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(listener) }
            }
        }
        folder.resolve("frames-$suffix.json").writeText(Gson().toJson(mapOf("pages" to pages, "failures" to failures)))
        pages.forEach { frames ->
            assertTrue(frames.isNotEmpty())
            assertEquals(frames.last().fullHeight, frames.last().height)
            assertEquals(listOf(1f, 1f), frames.last().alpha)
            frames.zipWithNext().forEach { (before, after) ->
                assertTrue("Panel outlines must expand continuously", after.height.zip(before.height).all { (now, prior) -> now >= prior })
                assertTrue("Content must not flash", after.alpha.zip(before.alpha).all { (now, prior) -> now + .001f >= prior })
            }
            if (ValueAnimator.areAnimatorsEnabled()) {
                assertTrue("Settings must start as short vertical panels", frames.first().height.zip(frames.first().fullHeight).all { (initial, full) -> initial < full })
                assertTrue("Controls must follow the expanding panel", frames.any { it.alpha.any { alpha -> alpha > .01f && alpha < .99f } })
                assertTrue("Top section must lead the next section", frames.all { it.alpha[0] + .001f >= it.alpha[1] })
            } else assertTrue(frames.all { it.height == it.fullHeight && it.alpha.all { alpha -> alpha == 1f } })
        }
        assertTrue(failures.take(8).joinToString(), failures.isEmpty())
    }

    @Test fun earlyControlsAndBackgroundRestorePanelsWithoutLosingReminderDisabledState() = fixture {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            val before = preferences.showWeekend
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.rowShowWeekend).performClick()
                assertEquals(!before, preferences.showWeekend)
                if (activity.findViewById<View>(R.id.cardSemester).width > 0) settled(activity)
                assertEquals(.45f, activity.findViewById<View>(R.id.spinnerDefaultReminder).alpha, .001f)
                assertEquals(.55f, activity.findViewById<View>(R.id.cardSectionTimes).alpha, .001f)
                repeat(3) { activity.findViewById<View>(R.id.rowReminder).performClick() }
            }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.onActivity { activity ->
                settled(activity)
                assertEquals(1f, activity.findViewById<View>(R.id.spinnerDefaultReminder).alpha, .001f)
                assertEquals(1f, activity.findViewById<View>(R.id.cardSectionTimes).alpha, .001f)
            }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                settled(activity)
                activity.findViewById<View>(R.id.nav_import).performClick()
                settled(activity)
            }
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<com.courseschedule.ui.importdata.ImportActivity>().forEach { it.finish() }
        }
        instrumentation.waitForIdleSync()
        ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_FOCUS_REMINDERS, true)).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                settled(activity)
                assertTrue("Reminder shortcut must present the reminder control", activity.findViewById<View>(R.id.rowReminder).getGlobalVisibleRect(Rect()))
            }
        }
    }

    @Test fun themeSwitchRestoresScrollAndDoesNotReplaySectionEntrance() = fixture {
        ActivityScenario.launch(com.courseschedule.ui.MainActivity::class.java).use { schedule ->
        SystemClock.sleep(800)
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            SystemClock.sleep(1000)
            lateinit var current: SettingsActivity
            lateinit var control: MaterialSwitch
            var scrollPosition = 0
            scenario.onActivity { activity ->
                current = activity
                control = activity.findViewById(R.id.switchDarkMode)
                val scroll = activity.findViewById<NestedScrollView>(R.id.settingsScroll)
                scroll.scrollTo(0, (120f * activity.resources.displayMetrics.density).toInt())
                scrollPosition = scroll.scrollY
                assertTrue(scrollPosition > 0)
            }
            instrumentation.waitForIdleSync()
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            var recreations = 0
            val callback = ActivityLifecycleCallback { activity, stage ->
                if (stage == Stage.CREATED && activity is SettingsActivity) recreations++
            }
            val frames = mutableListOf<ThemeFrame>()
            instrumentation.runOnMainSync { monitor.addLifecycleCallback(callback) }
            try {
                repeat(2) { pass ->
                    val ready = CountDownLatch(1)
                    val target = !control.isChecked
                    val previousGlobalTheme = AppCompatDelegate.getDefaultNightMode()
                    val start = SystemClock.uptimeMillis()
                    val positions = IntArray(2)
                    instrumentation.runOnMainSync { control.getLocationInWindow(positions) }
                    val drawListener = ViewTreeObserver.OnDrawListener {
                        val icon = control.thumbIconDrawable as ThemeSwitchIcon
                        val position = IntArray(2).also(control::getLocationInWindow)
                        frames += ThemeFrame(pass, SystemClock.uptimeMillis() - start, icon.progress, position.toList(),
                            cards(current).all { it.getChildAt(0).alpha == 1f }, AppCompatDelegate.getDefaultNightMode())
                    }
                    instrumentation.runOnMainSync {
                        current.window.decorView.viewTreeObserver.addOnDrawListener(drawListener)
                        current.findViewById<View>(R.id.rowDarkMode).performClick()
                        val sampler = object : Choreographer.FrameCallback {
                            override fun doFrame(frameTimeNanos: Long) {
                                val now = SystemClock.uptimeMillis()
                                val dark = current.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                                if (now - start >= 650 && dark == target) { ready.countDown(); return }
                                if (now - start < 5000) Choreographer.getInstance().postFrameCallback(this)
                            }
                        }
                        Choreographer.getInstance().postFrameCallback(sampler)
                    }
                    assertTrue("Theme switch did not complete", ready.await(6, TimeUnit.SECONDS))
                    instrumentation.runOnMainSync {
                        current.window.decorView.viewTreeObserver.removeOnDrawListener(drawListener)
                    }
                    scenario.onActivity { activity ->
                        assertSame("Changing colors must retain the activity and window", current, activity)
                        assertSame("The moving switch must survive the palette update", control, activity.findViewById(R.id.switchDarkMode))
                        settled(current)
                        assertEquals("Theme must retain the same scroll position", scrollPosition, current.findViewById<NestedScrollView>(R.id.settingsScroll).scrollY)
                        val dark = current.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                        assertEquals(target, dark)
                        assertEquals(preferences.darkModeOverride, dark)
                        assertEquals(if (target) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.getDefaultNightMode())
                        assertEquals(if (target) 1f else 0f, (control.thumbIconDrawable as ThemeSwitchIcon).progress, 0f)
                    }
                    val passFrames = frames.filter { it.pass == pass }
                    folder.resolve("theme-frames-$suffix.json").writeText(Gson().toJson(mapOf("expectedPosition" to positions.toList(), "frames" to frames)))
                    assertTrue("Palette changes must not replay section entrances", passFrames.all { it.sectionsReady })
                    assertTrue("The switch must stay in the same position: expected ${positions.toList()}, drawn ${passFrames.map { it.position }.distinct()}", passFrames.all { it.position == positions.toList() })
                    if (ValueAnimator.areAnimatorsEnabled())
                        assertTrue("Sun and moon must hand off continuously", passFrames.any { it.progress in .05f.. .95f })
                    assertTrue("Background pages must update after the visible handoff", passFrames.filter { it.progress in .05f.. .95f }.all { it.globalNightMode == previousGlobalTheme })
                    screenshot("theme-$pass")
                }
                assertEquals("Theme switching must not recreate settings", 0, recreations)
                folder.resolve("theme-scroll-$suffix.json").writeText(Gson().toJson(mapOf("scrollY" to scrollPosition, "recreations" to recreations, "frames" to frames)))
            } finally { instrumentation.runOnMainSync { monitor.removeLifecycleCallback(callback) } }
        }
        }
    }

    @Test fun repeatedThemeChangesAndBackgroundingKeepTheFinalChoice() = fixture {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            SystemClock.sleep(1000)
            lateinit var original: SettingsActivity
            var initial = false
            val taps = CountDownLatch(1)
            scenario.onActivity { activity ->
                original = activity
                initial = activity.findViewById<MaterialSwitch>(R.id.switchDarkMode).isChecked
                activity.findViewById<View>(R.id.rowDarkMode).performClick()
                Handler(Looper.getMainLooper()).postDelayed({
                    activity.findViewById<View>(R.id.rowDarkMode).performClick()
                }, 100)
                Handler(Looper.getMainLooper()).postDelayed({
                    activity.findViewById<View>(R.id.rowDarkMode).performClick()
                    taps.countDown()
                }, 180)
            }
            assertTrue(taps.await(4, TimeUnit.SECONDS))
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            SystemClock.sleep(500)
            scenario.onActivity { activity ->
                assertSame(original, activity)
                val switch = activity.findViewById<MaterialSwitch>(R.id.switchDarkMode)
                assertNotNull("A paused transition must return its switch to the row", switch)
                assertEquals(!initial, switch.isChecked)
                assertEquals(!initial, preferences.darkModeOverride)
                assertEquals(!initial, activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
                assertEquals(if (initial) 0f else 1f, (switch.thumbIconDrawable as ThemeSwitchIcon).progress, 0f)
                settled(activity)
                assertEquals(.45f, activity.findViewById<View>(R.id.spinnerDefaultReminder).alpha, .001f)
            }
        }
    }

    private data class Frame(val height: List<Int>, val fullHeight: List<Int>, val alpha: List<Float>)
    private data class ThemeFrame(val pass: Int, val millis: Long, val progress: Float, val position: List<Int>, val sectionsReady: Boolean, val globalNightMode: Int)
}
