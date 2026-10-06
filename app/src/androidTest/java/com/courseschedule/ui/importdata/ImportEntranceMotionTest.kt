package com.courseschedule.ui.importdata

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.Rect
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.courseschedule.R
import com.courseschedule.data.AppDatabase
import com.courseschedule.data.entity.Semester
import com.courseschedule.utils.ReminderManager
import com.google.android.material.card.MaterialCardView
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ImportEntranceMotionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val database by lazy { AppDatabase.getDatabase(context) }
    private val suffix = InstrumentationRegistry.getArguments().getString("proofSuffix", "light")
    private val folder by lazy { File(context.getExternalFilesDir(null), "import-entrance-proof").apply { mkdirs() } }
    private val revealedIds = listOf(R.id.tvSchoolImportTitle, R.id.tvSchoolImportDescription,
        R.id.schoolImportArrow, R.id.tvOtherImportMethods, R.id.cardImportAssistant,
        R.id.cardImportJson, R.id.cardImportText, R.id.cardImportTips)

    private suspend fun fixture(block: suspend () -> Unit) {
        val previousSemester = database.semesterDao().getCurrentSemesterSync()
        val previousTheme = AppCompatDelegate.getDefaultNightMode()
        val draft = Semester(name = "2026 秋季学期", startDate = System.currentTimeMillis(), totalWeeks = 20)
        val semester = draft.copy(id = database.semesterDao().insertSemester(draft))
        database.semesterDao().switchCurrentSemester(semester.id)
        val theme = InstrumentationRegistry.getArguments().getString("themeMode", "light")
        instrumentation.runOnMainSync {
            AppCompatDelegate.setDefaultNightMode(if (theme == "dark") AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        }
        try { block() } finally {
            database.semesterDao().deleteSemester(semester)
            previousSemester?.let { database.semesterDao().switchCurrentSemester(it.id) }
            instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(previousTheme) }
            ReminderManager(context).restoreReminders()
        }
    }

    private fun screenBounds(view: View): List<Int> {
        // Window-manager screen coordinates may be assigned after the first traversal.
        // Entry motion must preserve the views' layout positions inside the page.
        return listOf(view.left, view.top, view.width, view.height)
    }

    private fun screenshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
            folder.resolve("$name-$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun settled(activity: ImportActivity) {
        val hero = activity.findViewById<MaterialCardView>(R.id.cardImportSchool)
        val outline = Outline().also { hero.outlineProvider.getOutline(hero, it) }
        val bounds = Rect()
        assertTrue(outline.getRect(bounds))
        assertEquals(Rect(0, 0, hero.width, hero.height), bounds)
        revealedIds.forEach { id ->
            val view = activity.findViewById<View>(id)
            assertEquals("Content must be fully visible", 1f, view.alpha, .001f)
            assertEquals(0f, view.translationY, .001f)
            assertEquals(1f, view.scaleX, .001f)
            assertEquals(1f, view.scaleY, .001f)
        }
    }

    private fun <A : Activity> awaitPage(type: Class<A>): A {
        val ready = CountDownLatch(1)
        val deadline = SystemClock.uptimeMillis() + 5000L
        var result: A? = null
        instrumentation.runOnMainSync {
            fun check() {
                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .firstOrNull { type.isInstance(it) && it.window.decorView.hasWindowFocus() }
                if (activity != null) { result = type.cast(activity); ready.countDown() }
                else if (SystemClock.uptimeMillis() < deadline) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ check() }, 16L)
            }
            check()
        }
        assertTrue("Navigation did not present ${type.simpleName}", ready.await(6, TimeUnit.SECONDS))
        return result!!
    }

    @Test fun shapeExpandsWithoutMovingTextOrNavigationOnRepeatedEntry(): Unit = runBlocking {
        fixture {
            // Tab entries normally happen after the main window has already been presented.
            ActivityScenario.launch(com.courseschedule.ui.MainActivity::class.java).use {
                SystemClock.sleep(800)
                instrumentation.waitForIdleSync()
            }
            val pages = mutableListOf<MutableList<Frame>>()
            val completed = mutableListOf<CountDownLatch>()
            val failures = mutableListOf<String>()
            val listeners = mutableListOf<Pair<View, ViewTreeObserver.OnDrawListener>>()
            val samplers = mutableListOf<Choreographer.FrameCallback>()
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            val callback = ActivityLifecycleCallback { activity, stage ->
                if (stage == Stage.CREATED && activity is ImportActivity) {
                    val hero = activity.findViewById<MaterialCardView>(R.id.cardImportSchool)
                    val content = activity.findViewById<ViewGroup>(R.id.importContent)
                    val title = activity.findViewById<TextView>(R.id.tvSchoolImportTitle)
                    val views = revealedIds.map { activity.findViewById<View>(it) }
                    val fixed = listOf(activity.findViewById<View>(R.id.toolbar), content.getChildAt(0), hero,
                        activity.findViewById<View>(R.id.schoolIconContainer), title,
                        activity.findViewById<View>(R.id.bottomNavigation))
                    var geometry: List<List<Int>>? = null
                    var textSize = 0f
                    val samples = mutableListOf<Frame>()
                    val finished = CountDownLatch(1)
                    pages += samples
                    completed += finished
                    fun sample() {
                        val outline = Outline().also { hero.outlineProvider.getOutline(hero, it) }
                        val rect = Rect()
                        if (!outline.getRect(rect)) {
                            // The framework's background drawable obtains its bounds on its first draw.
                            // With no custom outline and no animation, this is the normal full-size card.
                            if (!ValueAnimator.areAnimatorsEnabled() && hero.outlineProvider === ViewOutlineProvider.BACKGROUND)
                                rect.set(0, 0, hero.width, hero.height)
                            else failures += "Primary entry lost its rounded outline"
                        }
                        val positions = fixed.map(::screenBounds)
                        if (geometry == null) { geometry = positions; textSize = title.textSize }
                        if (positions != geometry) failures += "Page content or navigation moved during entry"
                        if (title.textSize != textSize || content.alpha != 1f || content.translationY != 0f)
                            failures += "Text size or entire page changed during entry"
                        if ((fixed + views).any { it.scaleX != 1f || it.scaleY != 1f || it.translationY != 0f })
                            failures += "A content view was scaled or translated"
                        samples += Frame(listOf(rect.left, rect.top, rect.right, rect.bottom), hero.width, hero.height, views.map { it.alpha })
                    }
                    var sampling = false
                    val listener = ViewTreeObserver.OnDrawListener {
                        if (!sampling) {
                            sampling = true
                            sample()
                            val started = SystemClock.uptimeMillis()
                            val sampler = object : Choreographer.FrameCallback {
                                override fun doFrame(frameTimeNanos: Long) {
                                    if (hero.isAttachedToWindow) {
                                        sample()
                                        val last = samples.last()
                                        if (last.bounds == listOf(0, 0, hero.width, hero.height) && last.alpha.all { it == 1f }) {
                                            finished.countDown()
                                            return
                                        }
                                    }
                                    if (SystemClock.uptimeMillis() - started < 5000L) Choreographer.getInstance().postFrameCallback(this)
                                }
                            }
                            samplers += sampler
                            Choreographer.getInstance().postFrameCallback(sampler)
                        }
                    }
                    hero.viewTreeObserver.addOnDrawListener(listener)
                    listeners += hero to listener
                }
            }
            instrumentation.runOnMainSync { monitor.addLifecycleCallback(callback) }
            try {
                repeat(2) { pass ->
                    ActivityScenario.launch(ImportActivity::class.java).use { scenario ->
                        assertTrue("Entry animation did not reach a complete frame", completed[pass].await(6, TimeUnit.SECONDS))
                        instrumentation.waitForIdleSync()
                        scenario.onActivity { activity ->
                            settled(activity)
                            val title = activity.findViewById<TextView>(R.id.tvSchoolImportTitle)
                            val description = activity.findViewById<TextView>(R.id.tvSchoolImportDescription)
                            assertTrue((0 until title.lineCount).all { title.layout.getEllipsisCount(it) == 0 })
                            assertTrue("Import description must fit its slot", description.bottom <= (description.parent as View).height)
                            assertEquals(R.id.nav_import, activity.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNavigation).selectedItemId)
                        }
                        if (pass == 0) screenshot("settled")
                    }
                }
            } finally {
                instrumentation.runOnMainSync {
                    monitor.removeLifecycleCallback(callback)
                    samplers.forEach { Choreographer.getInstance().removeFrameCallback(it) }
                    listeners.forEach { (view, listener) ->
                        if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(listener)
                    }
                }
            }
            folder.resolve("frames-$suffix.json").writeText(Gson().toJson(mapOf("pages" to pages, "failures" to failures)))
            assertEquals(2, pages.size)
            pages.forEach { frames ->
                assertTrue("Entry must draw actual frames", frames.isNotEmpty())
                val last = frames.last()
                assertEquals(listOf(0, 0, last.width, last.height), last.bounds)
                assertEquals(List(revealedIds.size) { 1f }, last.alpha)
                frames.zipWithNext().forEach { (previous, next) ->
                    assertTrue("The primary outline must expand continuously", next.bounds[0] <= previous.bounds[0] && next.bounds[1] <= previous.bounds[1] &&
                        next.bounds[2] >= previous.bounds[2] && next.bounds[3] >= previous.bounds[3])
                    assertTrue("Content must not flash or restart", next.alpha.zip(previous.alpha).all { (now, before) -> now + .001f >= before })
                }
                if (ValueAnimator.areAnimatorsEnabled()) {
                    assertTrue("The entry must begin as a compact shape", frames.first().bounds.let { it[2] - it[0] } < last.width * .7f)
                    assertTrue("The title must appear after the shape starts expanding", frames.any { it.alpha[0] > .01f && it.alpha[0] < .99f })
                    assertTrue("Primary text must lead secondary methods", frames.any { it.alpha[0] - it.alpha[4] > .4f })
                    assertTrue("Secondary methods must never precede the primary title", frames.all { it.alpha[0] + .001f >= it.alpha[4] })
                } else {
                    assertTrue("Disabled animations must show the complete page immediately", frames.all { it.bounds == last.bounds && it.alpha == last.alpha })
                }
            }
            assertTrue(failures.take(8).joinToString(), failures.isEmpty())
        }
    }

    @Test fun earlyClickBackgroundAndRecreationLeaveCompleteUsableContent(): Unit = runBlocking {
        fixture {
            ActivityScenario.launch(ImportActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.findViewById<View>(R.id.cardImportText).performClick()
                    // Clicking a secondary method during entry must complete the visual state first.
                    if (activity.findViewById<View>(R.id.cardImportSchool).width > 0) settled(activity)
                }
                onView(withId(R.id.etImportText)).check(matches(isDisplayed()))
                onView(withText(R.string.cancel)).perform(click())
                scenario.recreate()
                lateinit var paused: ImportActivity
                scenario.onActivity { paused = it }
                scenario.moveToState(Lifecycle.State.CREATED)
                instrumentation.runOnMainSync { if (paused.findViewById<View>(R.id.cardImportSchool).width > 0) settled(paused) }
                scenario.moveToState(Lifecycle.State.RESUMED)
                SystemClock.sleep(1000)
                scenario.onActivity(::settled)
                scenario.recreate()
                SystemClock.sleep(1000)
                scenario.onActivity(::settled)
                onView(withId(R.id.cardImportText)).perform(click())
                onView(withId(R.id.etImportText)).check(matches(isDisplayed()))
                onView(withText(R.string.cancel)).perform(click())
                val abandoned = mutableListOf<ImportActivity>()
                repeat(3) {
                    val current = awaitPage(ImportActivity::class.java)
                    abandoned += current
                    instrumentation.runOnMainSync { current.findViewById<View>(R.id.nav_settings).performClick(); settled(current) }
                    val settings = awaitPage(com.courseschedule.ui.settings.SettingsActivity::class.java)
                    instrumentation.runOnMainSync { settings.findViewById<View>(R.id.nav_import).performClick() }
                }
                val returned = awaitPage(ImportActivity::class.java)
                SystemClock.sleep(1000)
                instrumentation.runOnMainSync {
                    abandoned.forEach(::settled)
                    settled(returned)
                    returned.finish()
                }
            }
        }
    }

    private data class Frame(val bounds: List<Int>, val width: Int, val height: Int, val alpha: List<Float>)
}
