package com.courseschedule.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.courseschedule.R
import com.courseschedule.data.AppDatabase
import com.courseschedule.data.StudyTaskStore
import com.courseschedule.data.entity.Semester
import com.courseschedule.data.entity.StudyTask
import com.courseschedule.ui.assistant.StudyTasksActivity
import com.courseschedule.ui.importdata.ImportActivity
import com.courseschedule.ui.settings.SettingsActivity
import com.courseschedule.utils.ReminderManager
import com.courseschedule.utils.SchedulePreferences
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PrimaryNavigationGlassTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val database = AppDatabase.getDatabase(context)
    private val suffix = InstrumentationRegistry.getArguments().getString("proofSuffix", "light")
    private val folder = File(context.getExternalFilesDir(null), "primary-glass-proof").apply { mkdirs() }

    private fun fixture(block: () -> Unit): Unit = runBlocking {
        val oldSemester = database.semesterDao().getCurrentSemesterSync()
        val preferences = SchedulePreferences(context)
        val oldPreferences = preferences.snapshot()
        val oldTheme = AppCompatDelegate.getDefaultNightMode()
        val draft = Semester(name = "2026 秋季学期", startDate = System.currentTimeMillis(), totalWeeks = 20)
        val semester = draft.copy(id = database.semesterDao().insertSemester(draft))
        database.semesterDao().switchCurrentSemester(semester.id)
        preferences.reminderEnabled = false
        instrumentation.runOnMainSync {
            AppCompatDelegate.setDefaultNightMode(if (suffix.contains("dark")) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        }
        repeat(6) { index -> StudyTaskStore(context).save(StudyTask(semesterId = semester.id,
            title = "整理第${index + 1}章课程笔记并完成课后习题", courseName = "神经网络与深度学习导论",
            dueAt = System.currentTimeMillis() + (index + 1) * 86400000L, reminderMinutes = -1)) }
        try { block() } finally {
            database.studyTaskDao().forSemester(semester.id).forEach { ReminderManager(context).cancelStudyReminder(it.id) }
            database.semesterDao().deleteSemester(semester)
            oldSemester?.let { database.semesterDao().switchCurrentSemester(it.id) }
            preferences.applySnapshot(oldPreferences)
            instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(oldTheme) }
        }
    }

    private fun <A : Activity> await(scenario: ActivityScenario<A>, condition: (A) -> Boolean) {
        val done = CountDownLatch(1)
        scenario.onActivity { activity ->
            val deadline = SystemClock.uptimeMillis() + 10000
            fun check() {
                if (condition(activity)) done.countDown()
                else if (!activity.isDestroyed && SystemClock.uptimeMillis() < deadline) Handler(Looper.getMainLooper()).postDelayed({ check() }, 30)
            }
            check()
        }
        assertTrue("Page did not settle", done.await(11, TimeUnit.SECONDS))
    }

    private fun settle() { SystemClock.sleep(750); instrumentation.waitForIdleSync() }
    private fun shot(name: String): Bitmap = instrumentation.uiAutomation.takeScreenshot().also { image ->
        File(folder, "$name-$suffix.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun bounds(view: View) = Rect().also { view.getGlobalVisibleRect(it) }
    private fun difference(a: Int, b: Int) = maxOf(kotlin.math.abs(Color.red(a) - Color.red(b)),
        kotlin.math.abs(Color.green(a) - Color.green(b)), kotlin.math.abs(Color.blue(a) - Color.blue(b)))

    @Test fun everySecondaryPageBlursLiveContentAndKeepsItsLastRowReachable() = fixture {
        val evidence = JSONArray()
        for ((type, contentId, scrollId) in listOf(
            Triple(StudyTasksActivity::class.java, R.id.taskContent, R.id.taskScroll),
            Triple(ImportActivity::class.java, R.id.importContent, R.id.importScroll),
            Triple(SettingsActivity::class.java, R.id.settingsContent, R.id.settingsScroll))) {
            ActivityScenario.launch<Activity>(Intent(context, type).putExtra(StudyTasksActivity.EXTRA_PRIMARY_PAGE, true)).use { scenario ->
                await(scenario) { (it.findViewById<View>(R.id.navigationGlass)?.height ?: 0) > 0 }
                settle()
                shot(type.simpleName).recycle()
                lateinit var probe: View
                var x = 0
                var y = 0
                var seam = 0
                scenario.onActivity { activity ->
                    val content = activity.findViewById<ViewGroup>(contentId)
                    probe = View(activity).apply { setBackgroundColor(Color.rgb(40, 132, 225)) }
                    content.addView(probe, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (360 * activity.resources.displayMetrics.density).toInt()))
                }
                settle()
                scenario.onActivity { activity ->
                    val nav = bounds(activity.findViewById(R.id.bottomNavigation))
                    val target = nav.top + (24f * activity.resources.displayMetrics.density).toInt()
                    val scroll = activity.findViewById<NestedScrollView>(scrollId)
                    val origin = IntArray(2).also(probe::getLocationOnScreen)
                    scroll.scrollTo(0, (scroll.scrollY + origin[1] + probe.height / 2 - target).coerceAtLeast(0))
                    x = activity.window.decorView.width / 2
                    y = target
                }
                settle()
                val blue = shot(type.simpleName + "-blue")
                val blueColor = blue.getPixel(x, y)
                scenario.onActivity { activity -> seam = bounds(activity.findViewById(R.id.navigationGlass)).top }
                // Keep the seam sample outside the floating new-task button.
                val seamX = (blue.width * .08f).toInt()
                val seamDifference = difference(blue.getPixel(seamX, seam - 2), blue.getPixel(seamX, seam + 2))
                assertTrue("Gradient must start without a hard edge: $seamDifference", seamDifference <= 5)
                blue.recycle()
                scenario.onActivity { probe.setBackgroundColor(Color.rgb(235, 136, 48)) }
                settle()
                val orange = shot(type.simpleName + "-orange")
                val colorChange = difference(blueColor, orange.getPixel(x, y))
                orange.recycle()
                assertTrue("${type.simpleName} must update its real blur when content changes: $colorChange", colorChange >= 10)
                scenario.onActivity { activity ->
                    val scroll = activity.findViewById<NestedScrollView>(scrollId)
                    scroll.scrollTo(0, scroll.getChildAt(0).height)
                }
                settle()
                scenario.onActivity { activity ->
                    val nav = activity.findViewById<View>(R.id.bottomNavigation)
                    val navBounds = bounds(nav)
                    val inset = ViewCompat.getRootWindowInsets(activity.window.decorView)!!.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                    assertEquals("The glass must extend behind system navigation", activity.window.decorView.height,
                        bounds(activity.findViewById(R.id.navigationGlass)).bottom)
                    assertEquals("Buttons must stay above the system area", activity.window.decorView.height - inset, navBounds.bottom)
                    assertEquals((72f * activity.resources.displayMetrics.density).toInt(), nav.height)
                    assertEquals(Color.TRANSPARENT, activity.window.navigationBarColor)
                    val limit = activity.findViewById<View>(R.id.btnAddTask)?.let { bounds(it).top } ?: navBounds.top
                    val location = IntArray(2).also(probe::getLocationOnScreen)
                    assertTrue("The final row must scroll fully above fixed controls", location[1] + probe.height <= limit)
                    evidence.put(JSONObject().put("page", type.simpleName).put("topSeam", seamDifference)
                        .put("liveColorChange", colorChange).put("navigationInset", inset))
                }
            }
        }
        File(folder, "pages-$suffix.json").writeText(evidence.toString(2))
    }

    @Test fun todoSearchKeyboardKeepsTheNavigationAndAddActionVisible() = fixture {
        ActivityScenario.launch<StudyTasksActivity>(Intent(context, StudyTasksActivity::class.java)
            .putExtra(StudyTasksActivity.EXTRA_PRIMARY_PAGE, true)).use { scenario ->
            settle()
            scenario.onActivity { activity ->
                val search = activity.findViewById<EditText>(R.id.etTaskSearch)
                search.requestFocus()
                activity.getSystemService(InputMethodManager::class.java).showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
            }
            await(scenario) { (ViewCompat.getRootWindowInsets(it.window.decorView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0) > 0 }
            settle()
            scenario.onActivity { activity ->
                val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)!!
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                val nav = bounds(activity.findViewById(R.id.bottomNavigation))
                val action = bounds(activity.findViewById(R.id.btnAddTask))
                assertEquals("Navigation moves above the keyboard", activity.window.decorView.height - ime, nav.bottom)
                assertTrue("New task remains above the navigation", action.bottom < nav.top)
                assertEquals(activity.findViewById<View>(R.id.btnAddTask).height, action.height())
                val search = activity.findViewById<EditText>(R.id.etTaskSearch)
                assertTrue(bounds(search).bottom < action.top)
            }
            shot("todo-keyboard").recycle()
            scenario.onActivity { activity -> activity.getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(activity.findViewById<View>(R.id.etTaskSearch).windowToken, 0) }
            await(scenario) { ViewCompat.getRootWindowInsets(it.window.decorView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom == 0 }
            settle()
        }
    }
}
