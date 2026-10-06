package com.courseschedule.ui

import android.animation.ValueAnimator
import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Animatable
import android.view.Choreographer
import android.widget.ImageView
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.NumberPicker
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.courseschedule.R
import com.courseschedule.data.AppDatabase
import com.courseschedule.data.StudyTaskStore
import com.courseschedule.data.entity.Semester
import com.courseschedule.data.entity.StudyTask
import com.courseschedule.domain.StudyTaskRules
import com.courseschedule.ui.assistant.StudyTaskEditorActivity
import com.courseschedule.ui.assistant.StudyTasksActivity
import com.courseschedule.utils.ReminderManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.google.android.material.R as MaterialR

@RunWith(AndroidJUnit4::class)
class TodoNavigationMotionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val database by lazy { AppDatabase.getDatabase(context) }
    private val ids = listOf(R.id.nav_home, R.id.nav_study, R.id.nav_import, R.id.nav_settings)

    private suspend fun fixture(block: suspend (Semester) -> Unit) {
        val old = database.semesterDao().getCurrentSemesterSync()
        val draft = Semester(name = "2026 秋季学期", startDate = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), totalWeeks = 20)
        val semester = draft.copy(id = database.semesterDao().insertSemester(draft))
        database.semesterDao().switchCurrentSemester(semester.id)
        val previousTheme = AppCompatDelegate.getDefaultNightMode()
        val proofTheme = InstrumentationRegistry.getArguments().getString("themeMode")
        if (proofTheme != null) instrumentation.runOnMainSync {
            AppCompatDelegate.setDefaultNightMode(if (proofTheme == "dark") AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        }
        try { block(semester) } finally {
            database.studyTaskDao().forSemester(semester.id).forEach { ReminderManager(context).cancelStudyReminder(it.id) }
            database.semesterDao().deleteSemester(semester)
            old?.let { database.semesterDao().switchCurrentSemester(it.id) }
            ReminderManager(context).restoreReminders()
            if (proofTheme != null) instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(previousTheme) }
        }
    }

    private fun settle(delay: Long = 700L) {
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).postDelayed({ done.countDown() }, delay)
        assertTrue(done.await(3, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }

    private fun baseY(view: View, ancestor: ViewGroup): Int {
        var child = view
        var y = 0
        while (child !== ancestor) {
            y += child.top
            val parent = child.parent as View
            y -= parent.scrollY
            child = parent
        }
        return y + IntArray(2).also(ancestor::getLocationOnScreen)[1]
    }

    private fun geometry(nav: BottomNavigationView): List<Int> {
        val y = IntArray(2).also(nav::getLocationOnScreen)[1]
        return listOf(y, nav.height) + ids.flatMap { id ->
            val item = nav.findViewById<View>(id)
            val icon = item.findViewById<View>(MaterialR.id.navigation_bar_item_icon_view)
            val label = item.findViewById<View>(MaterialR.id.navigation_bar_item_large_label_view)
            val indicator = item.findViewById<View>(MaterialR.id.navigation_bar_item_active_indicator_view)
            listOf(baseY(icon, nav), baseY(label, nav), icon.width, icon.height, indicator.width, indicator.height)
        }
    }

    private fun proof(name: String, data: Any) {
        val suffix = InstrumentationRegistry.getArguments().getString("proofSuffix", "animated")
        File(context.getExternalFilesDir(null), "icon-feedback-proof").apply { mkdirs() }
            .resolve("$name-$suffix.json").writeText(Gson().toJson(data))
    }

    @Test fun todoEntryLayersAnimateAfterDataLoadsAndKeepNavigationStable(): Unit = runBlocking {
        fixture { semester ->
            val pending = StudyTaskStore(context).save(StudyTask(semesterId = semester.id, title = "切换时直接显示待办", dueAt = System.currentTimeMillis() + 86400000L))
            StudyTaskStore(context).save(StudyTask(semesterId = semester.id, title = "已完成事项", dueAt = pending.dueAt, completedAt = System.currentTimeMillis()))
            var expectEmpty = false
            val frames = mutableListOf<Int>()
            val motionFrames = mutableListOf<MutableList<List<Float>>>()
            val failures = mutableListOf<String>()
            val listeners = mutableListOf<Pair<View, ViewTreeObserver.OnDrawListener>>()
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            val callback = ActivityLifecycleCallback { activity, stage ->
                if (stage == Stage.CREATED && activity is StudyTasksActivity) {
                    val content = activity.findViewById<View>(R.id.taskContent)
                    val rows = activity.findViewById<ViewGroup>(R.id.taskRows)
                    val empty = activity.findViewById<View>(R.id.taskEmpty)
                    val count = activity.findViewById<TextView>(R.id.btnTasksPending)
                    val completed = activity.findViewById<TextView>(R.id.btnTasksCompleted)
                    val layers = listOf(R.id.taskHeader, R.id.taskFilters, R.id.taskSearch, R.id.taskListHeader).map { activity.findViewById<View>(it) } +
                        if (expectEmpty) empty else rows
                    val nav = activity.findViewById<BottomNavigationView>(R.id.bottomNavigation)
                    var navigationGeometry = emptyList<Int>()
                    val page = frames.size
                    frames += 0
                    motionFrames += mutableListOf<List<Float>>()
                    val listener = ViewTreeObserver.OnDrawListener {
                        frames[page]++
                        if (content.alpha != 1f || content.translationY != 0f) failures += "Page $page: whole tab faded or moved"
                        val sample = layers.flatMap { listOf(it.alpha, it.translationY / it.resources.displayMetrics.density) }
                        motionFrames[page].lastOrNull()?.let { previous ->
                            for (index in layers.indices) {
                                if (sample[index * 2] + 0.001f < previous[index * 2] || sample[index * 2 + 1] > previous[index * 2 + 1] + 0.001f)
                                    failures += "Page $page: layer $index flashed or restarted"
                            }
                        }
                        motionFrames[page] += sample
                        val currentGeometry = geometry(nav)
                        if (navigationGeometry.isEmpty()) navigationGeometry = currentGeometry
                        if (currentGeometry != navigationGeometry) failures += "Page $page: navigation moved during entry"
                        if (expectEmpty) {
                            if (empty.visibility != View.VISIBLE || count.contentDescription.toString() != "全部，0 项") failures += "Page $page: empty data was not ready"
                        } else {
                            if (empty.visibility != View.GONE || rows.findViewWithTag<View>(pending.id) == null) failures += "Page $page: placeholder drawn before saved rows"
                            if (count.contentDescription.toString() != "全部，1 项" || completed.text.toString() != "已完成 1") failures += "Page $page: placeholder counts drawn"
                        }
                    }
                    content.viewTreeObserver.addOnDrawListener(listener)
                    listeners += content to listener
                }
            }
            instrumentation.runOnMainSync { monitor.addLifecycleCallback(callback) }
            fun navigate(destination: Int) {
                val ready = CountDownLatch(1)
                val focusListeners = mutableListOf<Pair<View, ViewTreeObserver.OnWindowFocusChangeListener>>()
                fun watch(activity: android.app.Activity) {
                    val decor = activity.window.decorView
                    fun afterPresentation() = decor.postOnAnimation { decor.postOnAnimation { ready.countDown() } }
                    if (decor.hasWindowFocus()) afterPresentation() else {
                        val listener = object : ViewTreeObserver.OnWindowFocusChangeListener {
                            override fun onWindowFocusChanged(hasFocus: Boolean) {
                                if (hasFocus) {
                                    decor.viewTreeObserver.removeOnWindowFocusChangeListener(this)
                                    afterPresentation()
                                }
                            }
                        }
                        decor.viewTreeObserver.addOnWindowFocusChangeListener(listener)
                        focusListeners += decor to listener
                    }
                }
                val expected = when (destination) {
                    R.id.nav_home -> MainActivity::class.java
                    R.id.nav_import -> com.courseschedule.ui.importdata.ImportActivity::class.java
                    R.id.nav_settings -> com.courseschedule.ui.settings.SettingsActivity::class.java
                    else -> StudyTasksActivity::class.java
                }
                val resumed = ActivityLifecycleCallback { activity, stage -> if (stage == Stage.RESUMED && expected.isInstance(activity)) watch(activity) }
                instrumentation.runOnMainSync {
                    monitor.addLifecycleCallback(resumed)
                    monitor.getActivitiesInStage(Stage.RESUMED).firstOrNull { expected.isInstance(it) }?.let(::watch)
                }
                try {
                    onView(withId(destination)).perform(click())
                    assertTrue("Destination $destination must be presented and focused before the next navigation", ready.await(5, TimeUnit.SECONDS))
                } finally {
                    instrumentation.runOnMainSync {
                        monitor.removeLifecycleCallback(resumed)
                        focusListeners.forEach { (view, listener) -> if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
                    }
                }
            }
            try {
                ActivityScenario.launch(MainActivity::class.java).use {
                    for (origin in listOf(R.id.nav_home, R.id.nav_import, R.id.nav_settings, R.id.nav_home)) {
                        navigate(origin)
                        navigate(R.id.nav_study)
                        settle(650L)
                    }
                    navigate(R.id.nav_home)
                    database.studyTaskDao().forSemester(semester.id).forEach { StudyTaskStore(context).delete(it) }
                    instrumentation.runOnMainSync { expectEmpty = true }
                    navigate(R.id.nav_study)
                    settle(650L)
                    assertEquals(5, frames.size)
                    assertTrue("Every destination must have actual drawn frames", frames.all { it > 0 })
                    motionFrames.forEachIndexed { page, samples ->
                        assertEquals("Every layer must settle on page $page", List(5) { listOf(1f, 0f) }.flatten(), samples.last())
                        assertTrue("The title must stay readable throughout entry", samples.all { it[0] == 1f && it[1] == 0f })
                        if (ValueAnimator.areAnimatorsEnabled()) {
                            for (index in 1 until 5) {
                                assertTrue("Layer $index must visibly move on page $page", samples.maxOf { it[index * 2 + 1] } - samples.minOf { it[index * 2 + 1] } > 1f)
                                assertTrue("Layer $index must visibly appear on page $page", samples.maxOf { it[index * 2] } - samples.minOf { it[index * 2] } > 0.03f)
                            }
                            assertTrue("Grouped entry must remain subtle", samples.all { sample -> (1 until 5).all { sample[it * 2 + 1] <= 8.01f } })
                        } else {
                            assertTrue("Reduced motion must show the settled state immediately", samples.all { it == List(5) { listOf(1f, 0f) }.flatten() })
                        }
                    }
                    proof("todo-first-draw", mapOf("drawnFramesPerPage" to frames, "layers" to listOf("header", "filters", "search", "list header", "results"),
                        "opacityAndOffsetDp" to motionFrames, "failures" to failures))
                    assertTrue(failures.take(6).joinToString(), failures.isEmpty())
                }
            } finally {
                instrumentation.runOnMainSync {
                    monitor.removeLifecycleCallback(callback)
                    listeners.forEach { (view, listener) -> if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(listener) }
                }
            }
        }
    }

    @Test fun interruptedEntrySettlesBeforeFilteringNavigationAndRecreation(): Unit = runBlocking {
        fixture { semester ->
            StudyTaskStore(context).save(StudyTask(semesterId = semester.id, title = "进入时切换分类", dueAt = System.currentTimeMillis() + 86400000L))
            ActivityScenario.launch<StudyTasksActivity>(Intent(context, StudyTasksActivity::class.java)
                .putExtra(StudyTasksActivity.EXTRA_PRIMARY_PAGE, true)).use { scenario ->
                val finished = CountDownLatch(1)
                val failures = mutableListOf<String>()
                scenario.onActivity { activity ->
                    val layers = listOf(R.id.taskHeader, R.id.tvTaskReminderStatus, R.id.taskFilters, R.id.taskSearch, R.id.taskListHeader).map { activity.findViewById<View>(it) }
                    fun checkSettled(reason: String) {
                        if (layers.any { it.alpha != 1f || it.translationY != 0f }) failures += reason
                    }
                    Handler(Looper.getMainLooper()).postDelayed({
                        activity.findViewById<View>(R.id.btnTasksUpcoming).performClick()
                        checkSettled("Entry layers were left offset after changing filters")
                        activity.findViewById<View>(R.id.nav_import).performClick()
                        Handler(Looper.getMainLooper()).postDelayed({
                            checkSettled("A delayed entrance restarted after navigation")
                            finished.countDown()
                        }, 600L)
                    }, 80L)
                }
                assertTrue(finished.await(3, TimeUnit.SECONDS))
                assertTrue(failures.joinToString(), failures.isEmpty())
                onView(withId(R.id.nav_study)).perform(click())
                settle(650L)
            }
            ActivityScenario.launch<StudyTasksActivity>(Intent(context, StudyTasksActivity::class.java)
                .putExtra(StudyTasksActivity.EXTRA_PRIMARY_PAGE, true)).use { scenario ->
                settle(650L)
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertEquals(1f, activity.findViewById<View>(R.id.taskHeader).alpha)
                    assertEquals(0f, activity.findViewById<View>(R.id.btnTasksToday).translationY)
                }
            }
        }
    }

    @Test fun allFourPagesKeepIdenticalNavigationGeometryAfterRepeatedSwitches(): Unit = runBlocking {
        fixture { semester ->
            ActivityScenario.launch(MainActivity::class.java).use {
                settle()
                var baseline = emptyList<Int>()
                val observations = mutableListOf<List<Int>>()
                onView(withId(R.id.bottomNavigation)).check { view, _ -> baseline = geometry(view as BottomNavigationView) }
                for (destination in listOf(R.id.nav_study, R.id.nav_import, R.id.nav_study, R.id.nav_settings, R.id.nav_study, R.id.nav_home)) {
                    onView(withId(destination)).perform(click())
                    settle()
                    onView(withId(R.id.bottomNavigation)).check { view, _ ->
                        val nav = view as BottomNavigationView
                        assertEquals(destination, nav.selectedItemId)
                        val actual = geometry(nav)
                        observations += actual
                        assertEquals("Navbar, all icons, labels and indicators must stay aligned", baseline, actual)
                        val item = nav.findViewById<View>(destination)
                        val icon = item.findViewById<ImageView>(MaterialR.id.navigation_bar_item_icon_view)
                        val bitmap = Bitmap.createBitmap(icon.width, icon.height, Bitmap.Config.ARGB_8888)
                        icon.draw(Canvas(bitmap))
                        val colors = IntArray(icon.width * icon.height).also { bitmap.getPixels(it, 0, icon.width, 0, 0, icon.width, icon.height) }
                        bitmap.recycle()
                        val actualTint = colors.filter { android.graphics.Color.alpha(it) == 255 }.groupingBy { it }.eachCount().maxBy { it.value }.key
                        assertEquals("The selected icon must retain the page's own palette", nav.itemIconTintList!!.getColorForState(item.drawableState, 0), actualTint)
                    }
                }
                proof("navigation-geometry", observations)
                assertEquals(semester.id, database.semesterDao().getCurrentSemesterSync()?.id)
            }
        }
    }

    @Test fun distinctIconMotionsRestoreTheirSilhouettesAndNeverMoveNavigationLabels(): Unit = runBlocking {
        fixture {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                settle()
                lateinit var nav: BottomNavigationView
                lateinit var observer: Choreographer.FrameCallback
                var checking = false
                var baseline = emptyList<Int>()
                var restingPixels = emptyList<List<Int>>()
                val samples = mutableListOf<List<Float>>()
                val failures = mutableListOf<String>()
                val animated = BooleanArray(ids.size)
                fun icons() = ids.map { id -> nav.findViewById<View>(id).findViewById<ImageView>(MaterialR.id.navigation_bar_item_icon_view) }
                fun pixels(icon: View): List<Int> {
                    val bitmap = Bitmap.createBitmap(icon.width, icon.height, Bitmap.Config.ARGB_8888)
                    icon.draw(Canvas(bitmap))
                    val values = IntArray(icon.width * icon.height)
                    bitmap.getPixels(values, 0, icon.width, 0, 0, icon.width, icon.height)
                    bitmap.recycle()
                    return values.toList()
                }
                scenario.onActivity { activity ->
                    nav = activity.findViewById(R.id.bottomNavigation)
                    baseline = geometry(nav)
                    restingPixels = icons().map(::pixels)
                    observer = object : Choreographer.FrameCallback {
                        override fun doFrame(frameTimeNanos: Long) {
                            if (!checking) return
                            if (geometry(nav) != baseline) failures += "Navigation layout shifted during animation"
                            samples += icons().flatMap { listOf(it.translationY, it.scaleX, it.rotation, it.rotationY) }
                            icons().forEachIndexed { index, icon ->
                                if ((icon.drawable as? Animatable)?.isRunning == true) animated[index] = true
                            }
                            Choreographer.getInstance().postFrameCallback(this)
                        }
                    }
                    checking = true
                    Choreographer.getInstance().postFrameCallback(observer)
                    ids.forEach { id -> nav.findViewById<View>(id).playNavigationMotion() }
                    Handler(Looper.getMainLooper()).postDelayed({ ids.forEach { id -> nav.findViewById<View>(id).playNavigationMotion() } }, 100L)
                }
                try {
                    settle(1400L)
                    scenario.onActivity {
                        checking = false
                        assertTrue("Actual display frames must be sampled", samples.size > 6)
                        if (ValueAnimator.areAnimatorsEnabled()) assertTrue("All four vector animations must actually run", animated.all { it })
                        assertTrue("The icon container must stay fixed while vector parts animate", samples.all { row -> row.chunked(4).all { it == listOf(0f, 1f, 0f, 0f) } })
                        assertTrue(failures.take(4).joinToString(), failures.isEmpty())
                        icons().forEachIndexed { index, icon ->
                            assertFalse("An interrupted animation must settle", (icon.drawable as? Animatable)?.isRunning == true)
                            assertEquals("Each final icon must match its original silhouette and tint", restingPixels[index], pixels(icon))
                        }
                        proof("icon-frames", mapOf("frames" to samples.size, "navigationDrift" to failures.size, "containerTransforms" to samples,
                            "motions" to listOf("calendar page fold", "check and line stroke reveal", "arrow upload with static tray", "gear step with settle")))
                    }
                } finally {
                    scenario.onActivity { checking = false; Choreographer.getInstance().removeFrameCallback(observer) }
                }
            }
        }
    }

    @Test fun expandedPickersKeepSaveAndCancelFixedAndClockGestureSavesTheDisplayedTime(): Unit = runBlocking {
        assumeTrue(ValueAnimator.areAnimatorsEnabled())
        fixture { semester ->
            val original = StudyTaskStore(context).save(StudyTask(semesterId = semester.id, title = "提交实验报告", dueAt = StudyTaskRules.deadline(LocalDate.now().plusDays(1).toString(), "20:00")))
            ActivityScenario.launch<StudyTaskEditorActivity>(Intent(context, StudyTaskEditorActivity::class.java).putExtra("study_task_id", original.id)).use { scenario ->
                settle()
                lateinit var root: View
                lateinit var listener: ViewTreeObserver.OnPreDrawListener
                var baseline = emptyList<Rect>()
                val errors = mutableListOf<String>()
                var frames = 0
                fun header() = listOf(R.id.btnTaskCancel, R.id.btnTaskSave).map { id -> Rect().also { root.findViewById<View>(id).getGlobalVisibleRect(it) } }
                scenario.onActivity { activity ->
                    root = activity.window.decorView
                    baseline = header()
                    listener = ViewTreeObserver.OnPreDrawListener {
                        frames++
                        if (header() != baseline) errors += "Fixed actions moved while a picker expanded"
                        true
                    }
                    root.viewTreeObserver.addOnPreDrawListener(listener)
                }
                var expectedTime = ""
                try {
                    onView(withId(R.id.btnTaskDate)).perform(scrollTo(), click()); settle(300L)
                    onView(withId(R.id.btnDateTomorrow)).perform(scrollTo(), click()); settle(300L)
                    onView(withId(R.id.btnTaskTime)).perform(scrollTo(), click()); settle(300L)
                    onView(withId(R.id.taskMinute)).perform(scrollTo(), swipeUp()); settle(900L)
                    scenario.onActivity { activity ->
                        val hour = activity.findViewById<NumberPicker>(R.id.taskHour).value
                        val minute = activity.findViewById<NumberPicker>(R.id.taskMinute).value
                        assertTrue("Clock gesture must advance a real minute", minute != 0)
                        expectedTime = "%02d:%02d".format(hour, minute)
                        assertEquals(expectedTime, activity.findViewById<TextView>(R.id.tvTaskTimeValue).text.toString())
                        assertTrue(frames > 12)
                        assertTrue(errors.take(4).joinToString(), errors.isEmpty())
                        proof("picker-frames", mapOf("sampledFrames" to frames, "headerDrift" to errors.size, "chosenTime" to expectedTime))
                    }
                } finally { scenario.onActivity { root.viewTreeObserver.removeOnPreDrawListener(listener) } }
                onView(withId(R.id.btnTimeDone)).perform(scrollTo(), click()); settle(300L)
                onView(withId(R.id.btnTaskSave)).perform(click()); settle()
                val saved = database.studyTaskDao().find(original.id)!!
                assertEquals(StudyTaskRules.deadline(LocalDate.now().plusDays(1).toString(), expectedTime), saved.dueAt)
            }
        }
    }
}
