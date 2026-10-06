package com.courseschedule.ui

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.StaticLayout
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
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
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Native visual review with the same dense arrangement in every display mode. */
@RunWith(AndroidJUnit4::class)
class SchedulePalettePreviewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val database = AppDatabase.getDatabase(context)
    private val suffix = InstrumentationRegistry.getArguments().getString("proofSuffix", "after-dark")
    private val folder = File(context.getExternalFilesDir(null), "schedule-palette-proof").apply { mkdirs() }

    private fun table(activity: MainActivity): CourseTableView? {
        val pager = activity.findViewById<ViewPager2>(R.id.weekPager)
        return (pager.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(pager.currentItem)
            ?.itemView?.findViewById(R.id.courseTableView)
    }

    private fun dialog(activity: MainActivity) = MainActivity::class.java.getDeclaredField("courseFocusDialog")
        .apply { isAccessible = true }.get(activity) as? CourseFocusDialog

    private fun await(scenario: ActivityScenario<MainActivity>, condition: (MainActivity) -> Boolean) {
        val ready = CountDownLatch(1)
        scenario.onActivity { activity ->
            val deadline = SystemClock.uptimeMillis() + 12000
            fun check() {
                if (condition(activity)) ready.countDown()
                else if (!activity.isDestroyed && SystemClock.uptimeMillis() < deadline) Handler(Looper.getMainLooper()).postDelayed({ check() }, 30)
            }
            check()
        }
        assertTrue("Native preview did not settle", ready.await(13, TimeUnit.SECONDS))
    }

    private fun settle() { SystemClock.sleep(700); instrumentation.waitForIdleSync() }
    private fun shot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(folder, "$name-$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun denseWeekPreservesItsCoursesThroughDetailAndWeekChanges(): Unit = runBlocking {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val previous = database.semesterDao().getCurrentSemesterSync()
        val preferences = SchedulePreferences(context)
        val settings = preferences.snapshot()
        val nightMode = AppCompatDelegate.getDefaultNightMode()
        val semester = Semester(name = "2026 秋季学期", totalWeeks = 20,
            startDate = LocalDate.of(2026, 9, 28).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
            .let { it.copy(id = database.semesterDao().insertSemester(it), isCurrent = true) }
        database.semesterDao().switchCurrentSemester(semester.id)
        preferences.reminderEnabled = false
        preferences.showWeekend = true
        preferences.showInactiveCourses = true
        preferences.sectionHeightDp = 64
        instrumentation.runOnMainSync {
            AppCompatDelegate.setDefaultNightMode(if (suffix.contains("dark")) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        }
        fun course(name: String, teacher: String, room: String, day: Int, start: Int, end: Int, color: Int, firstWeek: Int = 1) =
            Course(courseName = name, teacher = teacher, classroom = room, dayOfWeek = day, startSection = start,
                endSection = end, colorIndex = color, startWeek = firstWeek, endWeek = 20, semesterId = semester.id, reminderMinutes = -1)
        val drafts = listOf(
            course("马克思主义基本原理", "任重远", "明志楼B303", 1, 1, 2, 5),
            course("神经网络与深度学习导论", "郑津", "明理楼B407", 2, 3, 5, 15),
            course("体能训练1", "段月明", "运动场二", 1, 6, 7, 1),
            course("技术经济", "唐海军", "明德楼A205", 3, 6, 7, 14),
            course("大数据平台技术", "赖俊良", "明德楼A205", 5, 6, 7, 13),
            course("大数据平台技术", "赖俊良", "明德楼A205", 1, 8, 9, 13),
            course("马克思主义基本原理", "任重远", "明志楼B303", 3, 8, 9, 5),
            course("大学物理", "何山", "明理楼软件实验室", 3, 1, 2, 10, 6),
            course("形势与政策5", "罗利琼", "明志楼A209", 5, 1, 2, 13),
            course("计算机组成原理", "王波", "明辨楼C402", 3, 3, 4, 7, 6),
            course("计算机组成原理", "王波", "明辨楼C402", 5, 8, 9, 7, 6)
        )
        val courses = drafts.zip(database.courseDao().insertCourses(drafts)) { course, id -> course.copy(id = id) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await(scenario) { ViewModelProvider(it)[CourseViewModel::class.java].allCourses.value?.size == courses.size }
                scenario.onActivity { ViewModelProvider(it)[CourseViewModel::class.java].setCurrentWeek(2) }
                await(scenario) { it.findViewById<ViewPager2>(R.id.weekPager).currentItem == 1 && table(it)?.continuityCourses()?.size == courses.size }
                settle()
                scenario.onActivity { activity ->
                    val source = table(activity)!!
                    val inactive = courses.first { it.startWeek > 2 }
                    val bounds = source.continuityBounds(inactive)
                    val density = source.resources.displayMetrics.density
                    val bitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
                    val pixel = try {
                        source.draw(Canvas(bitmap))
                        bitmap.getPixel((bounds.right - 4 * density).toInt(), (bounds.top + 12 * density).toInt())
                    } finally { bitmap.recycle() }
                    assertEquals("Inactive cards must fade over the real backdrop", 117, Color.alpha(pixel))
                    val layouts = CourseTableView::class.java.getDeclaredField("courseTextLayoutCache")
                        .apply { isAccessible = true }.get(source) as Map<*, *>
                    val title = layouts.values.map { layout ->
                        layout!!.javaClass.getDeclaredField("name").apply { isAccessible = true }.get(layout) as StaticLayout
                    }.first { it.text.toString().contains(inactive.courseName) }
                    assertTrue(title.text.toString().startsWith("[非本周]\n"))
                    assertEquals(117, title.paint.alpha)
                    assertEquals(Color.WHITE, title.paint.color or (0xff shl 24))
                    File(folder, "inactive-render-$suffix.json").writeText(JSONObject()
                        .put("fillAlpha", Color.alpha(pixel)).put("titleAlpha", title.paint.alpha)
                        .put("title", title.text.toString()).put("titleRgb", "#FFFFFF").toString(2))
                }
                shot("grid-seven")
                scenario.onActivity { activity ->
                    val source = table(activity)!!
                    val bounds = source.continuityBounds(courses.first())
                    val down = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.centerX(), bounds.centerY(), 0)
                        try { source.dispatchTouchEvent(event) } finally { event.recycle() }
                    }
                }
                await(scenario) { dialog(it)?.isShowing == true }
                settle(); shot("course-focus")
                scenario.onActivity { dialog(it)!!.cancel() }
                await(scenario) { dialog(it) == null }
                settle(); shot("grid-return")
                scenario.onActivity { it.findViewById<View>(R.id.btnNextWeek).performClick() }
                await(scenario) { it.findViewById<ViewPager2>(R.id.weekPager).currentItem == 2 }
                settle(); shot("grid-next")
                scenario.onActivity { it.findViewById<View>(R.id.btnPreviousWeek).performClick() }
                await(scenario) { it.findViewById<ViewPager2>(R.id.weekPager).currentItem == 1 }
                settle()
                preferences.showWeekend = false
                scenario.recreate()
                await(scenario) { table(it)?.continuityCourses()?.size == courses.size }
                settle(); shot("grid-five")
            }
            assertEquals(courses.sortedBy { it.id }, database.courseDao().getCoursesBySemesterSync(semester.id).sortedBy { it.id })
        } finally {
            database.courseDao().deleteCoursesBySemester(semester.id)
            database.semesterDao().deleteSemester(semester)
            previous?.let { database.semesterDao().switchCurrentSemester(it.id) }
            preferences.applySnapshot(settings)
            instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(nightMode) }
        }
    }
}
