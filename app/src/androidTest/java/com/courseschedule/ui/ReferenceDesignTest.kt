package com.courseschedule.ui

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.PixelCopy
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.ColorUtils
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import com.courseschedule.R
import com.courseschedule.data.AppDatabase
import com.courseschedule.data.StudyTaskStore
import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.data.entity.StudyTask
import com.courseschedule.ui.assistant.AssistantMessage
import com.courseschedule.ui.assistant.CourseAssistantActivity
import com.courseschedule.ui.assistant.CourseAssistantViewModel
import com.courseschedule.ui.assistant.StudyTasksActivity
import com.courseschedule.ui.importdata.ImportActivity
import com.courseschedule.viewmodel.CourseViewModel
import com.google.android.material.textfield.TextInputEditText
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.courseschedule.utils.ReminderManager
import com.courseschedule.utils.SchedulePreferences
import com.courseschedule.view.CourseTableView
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
@RunWith(AndroidJUnit4::class)
class ReferenceDesignTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val database by lazy { AppDatabase.getDatabase(context) }
    private val suffix = InstrumentationRegistry.getArguments().getString("proofSuffix", "light")
    private val folder = File(context.getExternalFilesDir(null), "reference-design-proof").apply { mkdirs() }

    private suspend fun fixture(block: suspend (Semester) -> Unit) {
        val previous = database.semesterDao().getCurrentSemesterSync()
        val preferences = SchedulePreferences(context)
        val snapshot = preferences.snapshot()
        val previousNight = AppCompatDelegate.getDefaultNightMode()
        val semester = Semester(name = "2026 秋季学期", startDate = LocalDate.now()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), totalWeeks = 20)
            .let { it.copy(id = database.semesterDao().insertSemester(it)) }
        database.semesterDao().switchCurrentSemester(semester.id)
        preferences.showWeekend = false
        preferences.showInactiveCourses = false
        preferences.sectionHeightDp = 64
        withContext(Dispatchers.Main) {
            AppCompatDelegate.setDefaultNightMode(if (suffix.contains("dark")) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        }
        try { block(semester) } finally {
            database.studyTaskDao().forSemester(semester.id).forEach { ReminderManager(context).cancelStudyReminder(it.id) }
            database.courseDao().deleteCoursesBySemester(semester.id)
            database.semesterDao().deleteSemester(semester)
            previous?.let { database.semesterDao().switchCurrentSemester(it.id) }
            preferences.applySnapshot(snapshot)
            withContext(Dispatchers.Main) { AppCompatDelegate.setDefaultNightMode(previousNight) }
            ReminderManager(context).restoreReminders()
        }
    }

    private fun settle(ms: Long = 450L) { SystemClock.sleep(ms); instrumentation.waitForIdleSync() }

    private fun <A : Activity> await(scenario: ActivityScenario<A>, condition: (A) -> Boolean) {
        val latch = CountDownLatch(1)
        val deadline = SystemClock.uptimeMillis() + 8000
        scenario.onActivity { activity ->
            fun check() {
                if (condition(activity)) latch.countDown()
                else if (!activity.isDestroyed && SystemClock.uptimeMillis() < deadline) Handler(Looper.getMainLooper()).postDelayed({ check() }, 16)
            }
            check()
        }
        assertTrue("UI state did not settle", latch.await(9, TimeUnit.SECONDS))
    }

    private fun screenshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot().also { image ->
            File(folder, "$name-$suffix.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun awaitNavigation(id: Int) {
        val ready = CountDownLatch(1)
        val deadline = SystemClock.uptimeMillis() + 8000
        instrumentation.runOnMainSync {
            fun check() {
                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .firstOrNull { it.window.decorView.hasWindowFocus() &&
                        it.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNavigation)?.selectedItemId == id }
                if (activity != null) activity.window.decorView.postOnAnimation { ready.countDown() }
                else if (SystemClock.uptimeMillis() < deadline) Handler(Looper.getMainLooper()).postDelayed({ check() }, 16)
            }
            check()
        }
        assertTrue("Navigation destination $id did not gain focus", ready.await(9, TimeUnit.SECONDS))
    }

    private fun dialog(activity: MainActivity): CourseFocusDialog? = MainActivity::class.java
        .getDeclaredField("courseFocusDialog").apply { isAccessible = true }.get(activity) as? CourseFocusDialog

    private fun table(activity: MainActivity): CourseTableView? {
        val pager = activity.findViewById<ViewPager2>(R.id.weekPager)
        return (pager.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(pager.currentItem)
            ?.itemView?.findViewById(R.id.courseTableView)
    }

    private fun tapCourse(table: CourseTableView, course: Course) {
        val bounds = table.continuityBounds(course)
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, bounds.centerX(), bounds.centerY(), 0)
            try { assertTrue(table.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
    }

    @Test fun a_courseCardExpandsIntoFocusAndReturns(): Unit = runBlocking {
        fixture { semester ->
            val names = listOf("高等数学", "线性代数", "大学英语", "大学物理", "程序设计", "创新实践", "体育")
            val drafts = (0 until 10).map { index -> Course(courseName = names[index % 7], teacher = "王老师",
                classroom = "教一 302", dayOfWeek = index % 5 + 1, startSection = index / 5 * 3 + 1,
                endSection = index / 5 * 3 + 2, startWeek = 1, endWeek = 20, semesterId = semester.id, colorIndex = index) }
            val ids = database.courseDao().insertCourses(drafts)
            val courses = drafts.zip(ids) { course, id -> course.copy(id = id) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await(scenario) { table(it) != null && it.findViewById<View>(R.id.weekPager).visibility == View.VISIBLE }
                settle(650)
                screenshot("schedule")
                scenario.onActivity { tapCourse(table(it)!!, courses.first()) }
                SystemClock.sleep(100)
                screenshot("course-opening")
                settle(400)
                scenario.onActivity { activity ->
                    val sheet = dialog(activity)!!
                    val content = sheet.findViewById<TextView>(R.id.tvDetailTitle)!!.parent.parent as View
                    assertEquals(1f, content.alpha, .001f)
                    assertNull(content.clipBounds)
                    assertEquals(courses.first().courseName, sheet.findViewById<TextView>(R.id.tvDetailTitle)!!.text.toString())
                }
                screenshot("course-detail")
                scenario.onActivity { dialog(it)!!.cancel() }
                SystemClock.sleep(90)
                screenshot("course-closing")
                await(scenario) { dialog(it) == null }
                settle(100)
                screenshot("course-returned")
                scenario.onActivity { tapCourse(table(it)!!, courses[2]) }
                await(scenario) { dialog(it)?.isShowing == true }
                SystemClock.sleep(55)
                scenario.onActivity { dialog(it)!!.dismiss() }
                await(scenario) { dialog(it) == null }
                scenario.onActivity { assertNotNull(table(it)); assertEquals(1f, table(it)!!.alpha, .001f) }
            }
        }
    }

    @Test fun b_taskCompletionShowsFeedbackAndMovesTheRemainingRows(): Unit = runBlocking {
        fixture { semester ->
            val tasks = (1..3).map { index -> StudyTaskStore(context).save(StudyTask(semesterId = semester.id,
                title = listOf("完成第三章习题", "准备课程汇报", "整理实验记录")[index - 1],
                kind = listOf("homework", "exam", "report")[index - 1], dueAt = System.currentTimeMillis() + 86400000L)) }
            ActivityScenario.launch<StudyTasksActivity>(Intent(context, StudyTasksActivity::class.java)
                .putExtra(StudyTasksActivity.EXTRA_PRIMARY_PAGE, true)).use { scenario ->
                await(scenario) { descendants(it.findViewById(R.id.taskRows)).count { row -> row.tag is Long } == 3 }
                settle()
                lateinit var completing: View
                lateinit var survivor: View
                lateinit var root: View
                val heights = mutableListOf<Int>()
                val listener = ViewTreeObserver.OnPreDrawListener { heights += completing.height; true }
                scenario.onActivity { activity ->
                    root = activity.findViewById(android.R.id.content)
                    val rows = descendants(activity.findViewById(R.id.taskRows))
                    completing = rows.first { it.tag == tasks[0].id }
                    survivor = rows.first { it.tag == tasks[1].id }
                    root.viewTreeObserver.addOnPreDrawListener(listener)
                }
                screenshot("todo-before")
                val feedbackFrame = CountDownLatch(1)
                lateinit var feedbackImage: Bitmap
                var feedbackResult = PixelCopy.ERROR_UNKNOWN
                scenario.onActivity { activity ->
                    val decor = activity.window.decorView
                    feedbackImage = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                    fun captureFeedback() = PixelCopy.request(activity.window, feedbackImage, { result ->
                        feedbackResult = result
                        feedbackFrame.countDown()
                    }, Handler(Looper.getMainLooper()))
                    completing.findViewById<CheckBox>(R.id.checkTaskDone).performClick()
                    if (!android.animation.ValueAnimator.areAnimatorsEnabled()) captureFeedback()
                    else {
                        val deadline = SystemClock.uptimeMillis() + 8000
                        val field = StudyTasksActivity::class.java.getDeclaredField("rowCompletion").apply { isAccessible = true }
                        fun watchFeedback() {
                            if (field.get(activity) != null) Handler(Looper.getMainLooper()).postDelayed({ captureFeedback() }, 140)
                            else if (SystemClock.uptimeMillis() < deadline) Handler(Looper.getMainLooper()).postDelayed({ watchFeedback() }, 8)
                        }
                        watchFeedback()
                    }
                }
                assertTrue("Completion feedback did not begin", feedbackFrame.await(9, TimeUnit.SECONDS))
                // Copy a feedback frame asynchronously; synchronous screenshots can stall this short animation.
                await(scenario) { descendants(it.findViewById(R.id.taskRows)).count { row -> row.tag is Long } == 2 }
                scenario.onActivity { activity ->
                    root.viewTreeObserver.removeOnPreDrawListener(listener)
                    assertEquals(tasks[1].title, descendants(activity.findViewById(R.id.taskRows)).first { it.tag == tasks[1].id }.findViewById<TextView>(R.id.tvTaskTitle).text.toString())
                    assertTrue(activity.findViewById<View>(R.id.btnAddTask).isEnabled)
                }
                try {
                    assertEquals("Completion feedback frame must be captured", PixelCopy.SUCCESS, feedbackResult)
                    if (android.animation.ValueAnimator.areAnimatorsEnabled()) assertTrue("Row must shrink through intermediate sizes; recorded heights=$heights", heights.distinct().size > 2)
                    File(folder, "todo-completing-$suffix.png").outputStream().use {
                        feedbackImage.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally { feedbackImage.recycle() }
                assertNotNull(database.studyTaskDao().find(tasks[0].id)!!.completedAt)
                screenshot("todo-after")
                File(folder, "row-heights-$suffix.json").writeText(Gson().toJson(heights))
            }
        }
    }

    @Test fun c_primaryPagesUseTheSameCapsuleNavigation(): Unit = runBlocking {
        fixture {
            ActivityScenario.launch(MainActivity::class.java).use {
                awaitNavigation(R.id.nav_home)
                settle(650)
                for ((id, name) in listOf(R.id.nav_study to "todo-navigation", R.id.nav_import to "import",
                    R.id.nav_settings to "settings", R.id.nav_home to "navigation-return")) {
                    onView(withId(id)).perform(click())
                    awaitNavigation(id)
                    settle(650)
                    instrumentation.runOnMainSync {
                        val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single()
                        val nav = activity.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNavigation)
                        assertNotNull(nav.getTag(R.id.reference_navigation))
                        assertFalse(nav.isItemActiveIndicatorEnabled)
                        assertEquals(id, nav.selectedItemId)
                    }
                    screenshot(name)
                }
            }
        }
    }

    @Test fun e_coursePaletteRestoresTheReferenceAndKeepsNightTextReadable() {
        val ids = listOf(R.color.course_red, R.color.course_pink, R.color.course_purple, R.color.course_deep_purple,
            R.color.course_indicate, R.color.course_blue, R.color.course_light_blue, R.color.course_cyan, R.color.course_teal,
            R.color.course_green, R.color.course_light_green, R.color.course_lime, R.color.course_yellow, R.color.course_amber,
            R.color.course_orange, R.color.course_brown)
        val results = mutableListOf<Map<String, Any>>()
        for (night in listOf(false, true)) {
            val config = Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            val themed = context.createConfigurationContext(config)
            val title = themed.getColor(R.color.schedule_card_name)
            val secondary = themed.getColor(R.color.schedule_card_detail)
            ids.forEachIndexed { index, id ->
                val fill = themed.getColor(id)
                val mainContrast = ColorUtils.calculateContrast(title, fill)
                val secondaryContrast = ColorUtils.calculateContrast(secondary, fill)
                val expected = if (night) intArrayOf(Color.parseColor("#A84F58"), Color.parseColor("#9B4F70"), Color.parseColor("#7657AE"), Color.parseColor("#61509E"), Color.parseColor("#5169A8"), Color.parseColor("#3D73A0"), Color.parseColor("#3E789B"), Color.parseColor("#3D7E82"), Color.parseColor("#3E786D"), Color.parseColor("#4D7959"), Color.parseColor("#667844"), Color.parseColor("#74783F"), Color.parseColor("#857238"), Color.parseColor("#906837"), Color.parseColor("#985844"), Color.parseColor("#755B50"))
                    else intArrayOf(Color.parseColor("#E78387"), Color.parseColor("#DD86A8"), Color.parseColor("#A58BE2"), Color.parseColor("#8F82D3"), Color.parseColor("#819DDB"), Color.parseColor("#68A6DA"), Color.parseColor("#73B4DE"), Color.parseColor("#70C2C7"), Color.parseColor("#68B8A8"), Color.parseColor("#7BB38C"), Color.parseColor("#9CB97B"), Color.parseColor("#B0BE73"), Color.parseColor("#CDB36C"), Color.parseColor("#DAA56E"), Color.parseColor("#E28C70"), Color.parseColor("#B39182"))
                assertEquals("Historical swatch $index / night=$night", expected[index], fill)
                assertEquals(Color.WHITE, title)
                assertEquals(Color.WHITE, secondary)
                if (night) {
                    assertTrue("Night title color $index: $mainContrast", mainContrast >= 4.5)
                    assertTrue("Night metadata color $index: $secondaryContrast", secondaryContrast >= 4.5)
                }
                results += mapOf("night" to night, "index" to index, "title" to mainContrast, "metadata" to secondaryContrast)
            }
        }
        File(folder, "contrast-$suffix.json").writeText(Gson().toJson(results))
    }
    @Test fun d_assistantUsesTheNewSurfacesAndStillSavesLocalReminders(): Unit = runBlocking {
        fixture { semester ->
            ActivityScenario.launch(CourseAssistantActivity::class.java).use { scenario ->
                lateinit var model: CourseAssistantViewModel
                scenario.onActivity { model = ViewModelProvider(it)[CourseAssistantViewModel::class.java] }
                await(scenario) { model.busy.value == false }
                settle()
                screenshot("assistant-welcome")
                scenario.onActivity { model.send("提醒我喝水", 1) }
                await(scenario) { model.busy.value == false && model.messages.value!!.isNotEmpty() }
                lateinit var oldMessage: View
                val id = model.messages.value!!.last().id
                scenario.onActivity { activity -> oldMessage = descendants(activity.findViewById(R.id.messagesContainer)).first { it.tag == id } }
                scenario.onActivity { model.send("明天18:00提醒我喝水", 1) }
                await(scenario) { model.busy.value == false && model.pendingChanges.value != null }
                settle()
                screenshot("assistant-confirm")
                scenario.onActivity { activity -> activity.findViewById<View>(R.id.btnConfirmPending).performClick() }
                await(scenario) { model.busy.value == false && model.messages.value!!.last().kind == "reminder_result" }
                settle()
                scenario.onActivity { activity ->
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.pendingCard).visibility)
                    assertTrue(descendants(activity.findViewById(R.id.messagesContainer)).any { it.id == R.id.btnReminderSettings && it.visibility == View.VISIBLE })
                }
                assertEquals(1, database.studyTaskDao().forSemester(semester.id).size)
                screenshot("assistant-saved")
            }
        }
    }

}
