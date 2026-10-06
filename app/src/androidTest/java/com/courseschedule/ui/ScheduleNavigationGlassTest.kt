package com.courseschedule.ui

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import com.courseschedule.R
import com.courseschedule.data.AppDatabase
import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.utils.SchedulePreferences
import com.courseschedule.view.CourseTableView
import com.courseschedule.viewmodel.CourseViewModel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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

@RunWith(AndroidJUnit4::class)
class ScheduleNavigationGlassTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val database = AppDatabase.getDatabase(context)
    private val suffix = InstrumentationRegistry.getArguments().getString("proofSuffix", "light")
    private val folder = File(context.getExternalFilesDir(null), "navigation-glass-proof").apply { mkdirs() }

    private fun await(scenario: ActivityScenario<MainActivity>, condition: (MainActivity) -> Boolean) {
        val done = CountDownLatch(1)
        scenario.onActivity { activity ->
            val deadline = SystemClock.uptimeMillis() + 10000
            fun check() {
                if (condition(activity)) done.countDown()
                else if (!activity.isDestroyed && SystemClock.uptimeMillis() < deadline) Handler(Looper.getMainLooper()).postDelayed({ check() }, 25)
            }
            check()
        }
        assertTrue("Timetable did not settle", done.await(11, TimeUnit.SECONDS))
    }

    private fun table(activity: MainActivity): CourseTableView? {
        val pager = activity.findViewById<ViewPager2>(R.id.weekPager)
        return (pager.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(pager.currentItem)
            ?.itemView?.findViewById(R.id.courseTableView)
    }

    private fun settle() { SystemClock.sleep(600); instrumentation.waitForIdleSync() }
    private fun shot(name: String): Bitmap = instrumentation.uiAutomation.takeScreenshot().also { image ->
        File(folder, "$name-$suffix.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun difference(a: Int, b: Int) = maxOf(kotlin.math.abs(Color.red(a) - Color.red(b)),
        kotlin.math.abs(Color.green(a) - Color.green(b)), kotlin.math.abs(Color.blue(a) - Color.blue(b)))

    @Test fun liveGlassKeepsItsSeamsSoftAndTheLastCoursesAboveNavigation(): Unit = runBlocking {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val oldSemester = database.semesterDao().getCurrentSemesterSync()
        val preferences = SchedulePreferences(context)
        val oldPreferences = preferences.snapshot()
        val oldTheme = AppCompatDelegate.getDefaultNightMode()
        val draft = Semester(name = "2026 秋季学期", totalWeeks = 20, startDate = LocalDate.now()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
        val semester = draft.copy(id = database.semesterDao().insertSemester(draft), isCurrent = true)
        database.semesterDao().switchCurrentSemester(semester.id)
        preferences.reminderEnabled = false
        preferences.showWeekend = true
        preferences.sectionHeightDp = 64
        instrumentation.runOnMainSync {
            AppCompatDelegate.setDefaultNightMode(if (suffix.contains("dark")) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        }
        fun course(name: String, day: Int, start: Int, end: Int, color: Int) = Course(
            courseName = name, teacher = "任重远", classroom = "明志楼B303", dayOfWeek = day,
            startSection = start, endSection = end, startWeek = 1, endWeek = 20, colorIndex = color,
            semesterId = semester.id, reminderMinutes = -1)
        val courses = listOf(course("马克思主义基本原理", 1, 1, 2, 5),
            course("神经网络与深度学习导论", 2, 3, 5, 4), course("计算机组成原理", 3, 3, 4, 7),
            course("形势与政策5", 5, 1, 2, 13), course("体能训练1", 1, 6, 7, 1),
            course("技术经济", 3, 6, 7, 14), course("大数据平台技术", 5, 6, 7, 13),
            course("蓝色课程", 1, 8, 12, 5), course("橙色课程", 3, 8, 12, 13))
        database.courseDao().insertCourses(courses)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await(scenario) { ViewModelProvider(it)[CourseViewModel::class.java].allCourses.value?.size == courses.size && (table(it)?.height ?: 0) > 0 }
                settle()
                val glassRect = Rect()
                val navRect = Rect()
                var inset = 0
                var coloredX = 0
                var sampleY = 0
                scenario.onActivity { activity ->
                    val root = activity.findViewById<View>(android.R.id.content)
                    val glass = activity.findViewById<ScheduleNavigationGlass>(R.id.navigationGlass)
                    val nav = activity.findViewById<View>(R.id.bottomNavigation)
                    glass.getGlobalVisibleRect(glassRect)
                    nav.getGlobalVisibleRect(navRect)
                    inset = ViewCompat.getRootWindowInsets(root)!!.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                    assertEquals("Navigation targets retain their 72dp height", (72f * nav.resources.displayMetrics.density).toInt(), nav.height)
                    assertEquals("Glass includes the gesture or button area", root.height, glassRect.bottom)
                    assertEquals("Navigation controls stay above system buttons", root.height - inset, navRect.bottom)
                    assertEquals("Timetable extends behind the glass", root.height, Rect().also { activity.findViewById<View>(R.id.weekPager).getGlobalVisibleRect(it) }.bottom)
                    assertEquals(Color.TRANSPARENT, activity.window.navigationBarColor)
                    coloredX = (table(activity)!!.continuityBounds(courses[7]).centerX()).toInt()
                    sampleY = glassRect.top + (46f * glass.resources.displayMetrics.density).toInt()
                }
                val before = shot("glass-initial")
                val oldColor = before.getPixel(coloredX, sampleY)
                val seamX = (before.width * .95f).toInt()
                val topSeam = difference(before.getPixel(seamX, glassRect.top - 2), before.getPixel(seamX, glassRect.top + 2))
                val bottomSeam = difference(before.getPixel(seamX, navRect.bottom - 2), before.getPixel(seamX, navRect.bottom + 2))
                before.recycle()
                assertTrue("The gradient starts without a visible line: $topSeam", topSeam <= 4)
                assertTrue("System navigation does not introduce a second line: $bottomSeam", bottomSeam <= 4)
                scenario.onActivity { activity ->
                    val table = table(activity)!!
                    val scroll = table.parent as NestedScrollView
                    scroll.scrollTo(0, table.height)
                }
                settle()
                val after = shot("glass-scrolled")
                val newColor = after.getPixel(coloredX, sampleY)
                after.recycle()
                assertTrue("Glass must refresh as courses move away: $oldColor -> $newColor", difference(oldColor, newColor) >= 5)
                scenario.onActivity { activity ->
                    val source = table(activity)!!
                    val origin = IntArray(2).also(source::getLocationOnScreen)
                    val bounds = source.continuityBounds(courses[7])
                    assertTrue("The last lesson must scroll completely above the navigation", bounds.bottom + origin[1] <= navRect.top - 1)
                    val glass = activity.findViewById<ScheduleNavigationGlass>(R.id.navigationGlass)
                    val software = Bitmap.createBitmap(glass.width, glass.height, Bitmap.Config.ARGB_8888)
                    try {
                        glass.draw(Canvas(software))
                        val field = ScheduleNavigationGlass::class.java.getDeclaredField("bitmap").apply { isAccessible = true }
                        val buffer = field.get(glass) as Bitmap
                        assertTrue("Fallback samples only a small strip", buffer.width <= 180)
                        glass.draw(Canvas(software))
                        assertSame("Fallback reuses its bitmap", buffer, field.get(glass))
                        File(folder, "glass-software-$suffix.png").outputStream().use { software.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    } finally { software.recycle() }
                }
                File(folder, "glass-seams-$suffix.json").writeText(JSONObject().put("topSeam", topSeam)
                    .put("navigationSeam", bottomSeam).put("systemInset", inset).put("scrollColorChange", difference(oldColor, newColor)).toString(2))
            }
        } finally {
            database.courseDao().deleteCoursesBySemester(semester.id)
            database.semesterDao().deleteSemester(semester)
            oldSemester?.let { database.semesterDao().switchCurrentSemester(it.id) }
            preferences.applySnapshot(oldPreferences)
            instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(oldTheme) }
        }
    }
}
