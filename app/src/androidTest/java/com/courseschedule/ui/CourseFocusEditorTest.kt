package com.courseschedule.ui

import android.Manifest
import android.animation.ValueAnimator
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.InputDevice
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.NumberPicker
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.graphics.ColorUtils
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
import com.courseschedule.utils.ReminderManager
import com.courseschedule.utils.SchedulePreferences
import com.courseschedule.view.CourseTableView
import com.courseschedule.viewmodel.CourseViewModel
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class CourseFocusEditorTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val database = AppDatabase.getDatabase(context)
    private val suffix = InstrumentationRegistry.getArguments().getString("proofSuffix", "light")
    private val folder = File(context.getExternalFilesDir(null), "course-inline-proof").apply { mkdirs() }

    private fun fixture(block: (Semester, Course) -> Unit) = runBlocking {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val previous = database.semesterDao().getCurrentSemesterSync()
        val preferences = SchedulePreferences(context)
        val settings = preferences.snapshot()
        val night = AppCompatDelegate.getDefaultNightMode()
        val draft = Semester(name = "2026 秋季学期", totalWeeks = 20, startDate = LocalDate.now()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
        val semester = draft.copy(id = database.semesterDao().insertSemester(draft), isCurrent = true)
        database.semesterDao().switchCurrentSemester(semester.id)
        preferences.reminderEnabled = false
        preferences.showWeekend = false
        preferences.sectionHeightDp = 64
        instrumentation.runOnMainSync {
            AppCompatDelegate.setDefaultNightMode(if (suffix.contains("dark")) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        }
        val original = Course(courseName = "形势与政策5", teacher = "罗利琼", classroom = "明志楼A209",
            dayOfWeek = 5, startSection = 1, endSection = 2, startWeek = 6, endWeek = 8,
            semesterId = semester.id, colorIndex = 123, reminderMinutes = 45, note = "课程信息，请核对")
            .let { it.copy(id = database.courseDao().insertCourse(it)) }
        try { block(semester, original) } finally {
            database.courseDao().getCoursesBySemesterSync(semester.id).forEach { ReminderManager(context).cancelReminder(it.id) }
            database.courseDao().deleteCoursesBySemester(semester.id)
            database.semesterDao().deleteSemester(semester)
            previous?.let { database.semesterDao().switchCurrentSemester(it.id) }
            preferences.applySnapshot(settings)
            instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(night) }
        }
    }

    private fun await(scenario: ActivityScenario<MainActivity>, condition: (MainActivity) -> Boolean) {
        val done = CountDownLatch(1)
        scenario.onActivity { activity ->
            val deadline = SystemClock.uptimeMillis() + 12000
            fun check() {
                if (condition(activity)) done.countDown()
                else if (!activity.isDestroyed && SystemClock.uptimeMillis() < deadline) Handler(Looper.getMainLooper()).postDelayed({ check() }, 20)
            }
            check()
        }
        assertTrue("Inline editor did not settle", done.await(13, TimeUnit.SECONDS))
    }

    private fun settle(ms: Long = 400) { SystemClock.sleep(ms); instrumentation.waitForIdleSync() }
    private fun dialog(activity: MainActivity) = MainActivity::class.java.getDeclaredField("courseFocusDialog")
        .apply { isAccessible = true }.get(activity) as? CourseFocusDialog
    private fun table(activity: MainActivity): CourseTableView? {
        val pager = activity.findViewById<ViewPager2>(R.id.weekPager)
        return (pager.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(pager.currentItem)
            ?.itemView?.findViewById(R.id.courseTableView)
    }

    private fun open(scenario: ActivityScenario<MainActivity>, course: Course) {
        await(scenario) { ViewModelProvider(it)[CourseViewModel::class.java].allCourses.value?.any { row -> row.id == course.id } == true }
        scenario.onActivity { activity -> ViewModelProvider(activity)[CourseViewModel::class.java].setCurrentWeek(6) }
        await(scenario) { it.findViewById<ViewPager2>(R.id.weekPager).currentItem == 5 && table(it)?.continuityCourses()?.any { row -> row.id == course.id } == true }
        settle(550)
        scenario.onActivity { activity ->
            val table = table(activity)!!
            val bounds = table.continuityBounds(course)
            val time = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, bounds.centerX(), bounds.centerY(), 0)
                try { table.dispatchTouchEvent(event) } finally { event.recycle() }
            }
        }
        await(scenario) { dialog(it)?.isShowing == true }
        settle(550)
    }

    private fun shot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(folder, "$name-$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun text(sheet: CourseFocusDialog, id: Int, value: String) = sheet.findViewById<EditText>(id)!!.setText(value)
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun a_directChangesSurviveRecreationAndSaveAllFieldsWithoutAnotherActivity() = fixture { _, original ->
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            open(scenario, original)
            shot("inline-initial")
            scenario.onActivity { activity ->
                val sheet = dialog(activity)!!
                assertFalse(sheet.findViewById<View>(R.id.btnEditCourse)!!.isEnabled)
                text(sheet, R.id.tvDetailTitle, "形势与政策（专题）")
                text(sheet, R.id.tvDetailClassroom, "明理楼B408")
                text(sheet, R.id.tvDetailTeacher, "张老师")
                text(sheet, R.id.tvDetailNote, "携带课程资料")
                sheet.findViewById<View>(R.id.rowFocusTime)!!.performClick()
                sheet.findViewById<View>(R.id.focusDay3)!!.performClick()
                sheet.findViewById<NumberPicker>(R.id.focusSectionStart)!!.value = 3
                sheet.findViewById<NumberPicker>(R.id.focusSectionEnd)!!.value = 4
                sheet.findViewById<View>(R.id.btnFocusTimeDone)!!.performClick()
                sheet.findViewById<View>(R.id.rowFocusWeeks)!!.performClick()
                sheet.findViewById<NumberPicker>(R.id.focusWeekStart)!!.value = 7
                sheet.findViewById<NumberPicker>(R.id.focusWeekEnd)!!.value = 12
                sheet.findViewById<View>(R.id.focusOddWeek)!!.performClick()
                sheet.findViewById<View>(R.id.btnFocusWeeksDone)!!.performClick()
                assertTrue(sheet.findViewById<View>(R.id.btnEditCourse)!!.isEnabled)
            }
            settle(); scenario.recreate()
            await(scenario) { dialog(it)?.isShowing == true }
            settle()
            scenario.onActivity { activity ->
                val sheet = dialog(activity)!!
                assertEquals("形势与政策（专题）", sheet.findViewById<EditText>(R.id.tvDetailTitle)!!.text.toString())
                assertEquals("携带课程资料", sheet.findViewById<EditText>(R.id.tvDetailNote)!!.text.toString())
                assertTrue(sheet.findViewById<TextView>(R.id.tvDetailTime)!!.text.contains("周三"))
                assertTrue(sheet.findViewById<TextView>(R.id.tvDetailWeeks)!!.text.contains("单周"))
            }
            shot("inline-restored")
            scenario.onActivity { dialog(it)!!.findViewById<View>(R.id.btnEditCourse)!!.performClick() }
            await(scenario) { dialog(it) == null }
            val saved = runBlocking { database.courseDao().getCourseById(original.id) }!!
            assertEquals(original.copy(courseName = "形势与政策（专题）", classroom = "明理楼B408", teacher = "张老师",
                note = "携带课程资料", dayOfWeek = 3, startSection = 3, endSection = 4, startWeek = 7, endWeek = 12, weekType = 1), saved)
        }
    }

    @Test fun b_conflictPreservesDraftAndRetryUpdatesExactlyOneCourse() = fixture { _, original ->
        runBlocking { database.courseDao().insertCourse(original.copy(id = 0, courseName = "冲突课程", startSection = 3, endSection = 4)) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            open(scenario, original)
            scenario.onActivity { activity ->
                val sheet = dialog(activity)!!
                text(sheet, R.id.tvDetailClassroom, "新教室")
                sheet.findViewById<View>(R.id.rowFocusTime)!!.performClick()
                sheet.findViewById<NumberPicker>(R.id.focusSectionStart)!!.value = 3
                sheet.findViewById<NumberPicker>(R.id.focusSectionEnd)!!.value = 4
                sheet.findViewById<View>(R.id.btnFocusTimeDone)!!.performClick()
                sheet.findViewById<View>(R.id.btnEditCourse)!!.performClick()
            }
            await(scenario) { dialog(it)?.findViewById<View>(R.id.focusError)?.visibility == View.VISIBLE }
            assertEquals(original, runBlocking { database.courseDao().getCourseById(original.id) })
            scenario.onActivity { activity ->
                val sheet = dialog(activity)!!
                assertEquals("新教室", sheet.findViewById<EditText>(R.id.tvDetailClassroom)!!.text.toString())
                assertEquals(context.getString(R.string.course_conflict), sheet.findViewById<TextView>(R.id.focusError)!!.text.toString())
                sheet.findViewById<View>(R.id.rowFocusTime)!!.performClick()
                sheet.findViewById<NumberPicker>(R.id.focusSectionStart)!!.value = 5
                sheet.findViewById<NumberPicker>(R.id.focusSectionEnd)!!.value = 6
                sheet.findViewById<View>(R.id.btnFocusTimeDone)!!.performClick()
                sheet.findViewById<View>(R.id.btnEditCourse)!!.performClick()
            }
            await(scenario) { dialog(it) == null }
            assertEquals(original.copy(classroom = "新教室", startSection = 5, endSection = 6), runBlocking { database.courseDao().getCourseById(original.id) })
            assertEquals(2, runBlocking { database.courseDao().getCoursesBySemesterSync(original.semesterId).size })
        }
    }

    @Test fun c_dirtyReturnRequiresChoiceAndBlankNameCannotSave() = fixture { _, original ->
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            open(scenario, original)
            scenario.onActivity { activity ->
                val sheet = dialog(activity)!!
                text(sheet, R.id.tvDetailClassroom, "尚未保存的教室")
                sheet.cancel()
                assertEquals("A rejected close must keep its confirmation interactive", 0,
                    sheet.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                assertEquals(View.VISIBLE, sheet.findViewById<View>(R.id.focusConfirmation)!!.visibility)
                sheet.findViewById<View>(R.id.btnFocusKeep)!!.performClick()
                assertEquals("尚未保存的教室", sheet.findViewById<EditText>(R.id.tvDetailClassroom)!!.text.toString())
                text(sheet, R.id.tvDetailTitle, "")
                sheet.findViewById<View>(R.id.btnEditCourse)!!.performClick()
                assertTrue(sheet.findViewById<EditText>(R.id.tvDetailTitle)!!.hasFocus())
                assertEquals(context.getString(R.string.input_course_name), sheet.findViewById<TextView>(R.id.focusError)!!.text.toString())
                sheet.findViewById<View>(R.id.btnFocusCancel)!!.performClick()
                sheet.findViewById<View>(R.id.btnFocusDiscard)!!.performClick()
            }
            await(scenario) { dialog(it) == null }
            assertEquals(original, runBlocking { database.courseDao().getCourseById(original.id) })
        }
    }

    @Test fun d_staleCourseCannotBeOverwritten() = fixture { _, original ->
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            open(scenario, original)
            scenario.onActivity { text(dialog(it)!!, R.id.tvDetailClassroom, "自己的草稿") }
            val external = original.copy(teacher = "已更新的教师")
            runBlocking { database.courseDao().updateCourse(external) }
            scenario.onActivity { dialog(it)!!.findViewById<View>(R.id.btnEditCourse)!!.performClick() }
            await(scenario) { dialog(it)?.findViewById<View>(R.id.focusError)?.visibility == View.VISIBLE }
            assertEquals(external, runBlocking { database.courseDao().getCourseById(original.id) })
            scenario.onActivity { activity ->
                val sheet = dialog(activity)!!
                assertEquals("自己的草稿", sheet.findViewById<EditText>(R.id.tvDetailClassroom)!!.text.toString())
                assertEquals(context.getString(R.string.editor_changed_course), sheet.findViewById<TextView>(R.id.focusError)!!.text.toString())
                sheet.cancel(); sheet.findViewById<View>(R.id.btnFocusDiscard)!!.performClick()
            }
            await(scenario) { dialog(it) == null }
        }
    }

    private fun surfacePixel(sheet: CourseFocusDialog): Int {
        val content = sheet.findViewById<View>(R.id.courseDetailContent)!!
        val stage = content.parent as ViewGroup
        val layer = (0 until stage.childCount).map { stage.getChildAt(it) }
            .first { it.javaClass.simpleName == "CardLayer" }
        val bitmap = Bitmap.createBitmap(layer.width, layer.height, Bitmap.Config.ARGB_8888)
        return try {
            layer.draw(Canvas(bitmap))
            bitmap.getPixel(content.right - layer.left - (12 * context.resources.displayMetrics.density).roundToInt(),
                content.top - layer.top + (80 * context.resources.displayMetrics.density).roundToInt())
        } finally { bitmap.recycle() }
    }

    @Test fun e_colorReminderAndConfirmedDeletionRemainAvailableOnTheSamePage() = fixture { _, original ->
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            open(scenario, original)
            settle(550)
            var initialSurface = 0
            scenario.onActivity { initialSurface = surfacePixel(dialog(it)!!) }
            shot("refined-before")
            scenario.onActivity { dialog(it)!!.findViewById<View>(R.id.btnFocusMore)!!.performClick() }
            settle()
            scenario.onActivity { activity ->
                val sheet = dialog(activity)!!
                sheet.findViewById<RecyclerView>(R.id.focusColorChoices)!!.findViewHolderForAdapterPosition(2)!!.itemView.performClick()
                val purple = surfacePixel(sheet)
                val palette = activity.resources.obtainTypedArray(R.array.course_colors)
                assertEquals(palette.getColor(2, 0), (sheet.findViewById<View>(R.id.detailCourseColor)!!.background as GradientDrawable).color!!.defaultColor)
                palette.recycle()
                if (!suffix.contains("dark")) {
                    assertTrue("Changing course color must keep a bright readable surface", ColorUtils.calculateLuminance(purple) > .8)
                }
                sheet.findViewById<View>(R.id.btnFocusMore)!!.performClick()
                sheet.findViewById<NestedScrollView>(R.id.courseDetailScroll)!!.scrollTo(0, 0)
            }
            settle()
            shot("refined-purple")
            scenario.onActivity { dialog(it)!!.findViewById<View>(R.id.btnFocusMore)!!.performClick() }
            settle()
            scenario.onActivity { activity ->
                val sheet = dialog(activity)!!
                sheet.findViewById<RecyclerView>(R.id.focusColorChoices)!!.findViewHolderForAdapterPosition(5)!!.itemView.performClick()
                descendants(sheet.findViewById(R.id.focusReminderChoices)!!).filterIsInstance<TextView>()
                    .first { it.text.toString() == "提前10分钟" }.performClick()
                val changedSurface = surfacePixel(sheet)
                if (suffix.contains("dark")) {
                    assertTrue("Night tint must retain the dark base", ColorUtils.calculateLuminance(changedSurface) < .025)
                } else {
                    assertNotEquals("Day details must retain a subtle connection to the selected color", initialSurface, changedSurface)
                    assertTrue("Changing course color must preserve surface brightness", ColorUtils.calculateLuminance(changedSurface) > .74)
                }
                sheet.findViewById<View>(R.id.btnFocusMore)!!.performClick()
                sheet.findViewById<NestedScrollView>(R.id.courseDetailScroll)!!.scrollTo(0, 0)
            }
            settle()
            shot("refined-blue")
            scenario.onActivity { dialog(it)!!.findViewById<View>(R.id.btnEditCourse)!!.performClick() }
            await(scenario) { dialog(it) == null }
            val saved = runBlocking { database.courseDao().getCourseById(original.id) }!!
            assertEquals(original.copy(colorIndex = 5, reminderMinutes = 10), saved)
            open(scenario, saved)
            scenario.onActivity { activity ->
                val sheet = dialog(activity)!!
                sheet.findViewById<View>(R.id.btnFocusMore)!!.performClick()
                sheet.findViewById<View>(R.id.btnFocusDelete)!!.performClick()
                sheet.findViewById<View>(R.id.btnFocusKeep)!!.performClick()
            }
            assertEquals(saved, runBlocking { database.courseDao().getCourseById(original.id) })
            scenario.onActivity { activity ->
                val sheet = dialog(activity)!!
                sheet.findViewById<View>(R.id.btnFocusDelete)!!.performClick()
                sheet.findViewById<View>(R.id.btnFocusDiscard)!!.performClick()
            }
            await(scenario) { dialog(it) == null }
            assertNull(runBlocking { database.courseDao().getCourseById(original.id) })
        }
    }

    @Test fun f_inlinePanelsAndKeyboardKeepTheSaveActionVisible() = fixture { _, original ->
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            open(scenario, original)
            scenario.onActivity { dialog(it)!!.findViewById<View>(R.id.rowFocusTime)!!.performClick() }
            settle(); shot("inline-time-panel")
            scenario.onActivity { activity ->
                val sheet = dialog(activity)!!
                sheet.findViewById<View>(R.id.btnFocusTimeDone)!!.performClick()
                sheet.findViewById<View>(R.id.rowFocusWeeks)!!.performClick()
            }
            settle(); shot("inline-weeks-panel")
            scenario.onActivity { activity ->
                val sheet = dialog(activity)!!
                sheet.findViewById<View>(R.id.btnFocusWeeksDone)!!.performClick()
                val note = sheet.findViewById<EditText>(R.id.tvDetailNote)!!
                note.requestFocus()
                (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(note, InputMethodManager.SHOW_IMPLICIT)
            }
            await(scenario) { ViewCompat.getRootWindowInsets(dialog(it)!!.window!!.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            scenario.onActivity { text(dialog(it)!!, R.id.tvDetailNote, "课堂资料已核对") }
            settle(600)
            shot("inline-keyboard")
            scenario.onActivity { activity ->
                val sheet = dialog(activity)!!
                val save = sheet.findViewById<View>(R.id.btnEditCourse)!!
                val visible = Rect()
                assertTrue("Save must remain above the keyboard", save.getGlobalVisibleRect(visible))
                assertEquals(save.height, visible.height())
                val decor = sheet.window!!.decorView
                val origin = IntArray(2)
                decor.getLocationOnScreen(origin)
                val keyboardTop = origin[1] + decor.height - ViewCompat.getRootWindowInsets(decor)!!
                    .getInsets(WindowInsetsCompat.Type.ime()).bottom
                assertTrue("Save is covered by the keyboard: ${visible.bottom} > $keyboardTop", visible.bottom <= keyboardTop)
                assertTrue(save.isEnabled)
                save.performClick()
            }
            await(scenario) { dialog(it) == null }
            assertEquals(original.copy(note = "课堂资料已核对"), runBlocking { database.courseDao().getCourseById(original.id) })
        }
    }

    @Test fun g_sourceFrameIsPaintedAtTheActualCourseCellWithSystemBarInsets() = fixture { _, original ->
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            open(scenario, original)
            scenario.onActivity { activity ->
                val sheet = dialog(activity)!!
                // Freeze the first animation frame and inspect its actual pixels.
                val animation = CourseFocusDialog::class.java.getDeclaredField("animation").apply { isAccessible = true }
                (animation.get(sheet) as? ValueAnimator)?.apply { removeAllListeners(); cancel() }
                CourseFocusDialog::class.java.getDeclaredMethod("applyProgress", Float::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(sheet, 0f)
                val stage = sheet.findViewById<View>(R.id.courseDetailContent)!!.parent as ViewGroup
                val layer = (0 until stage.childCount).map { stage.getChildAt(it) }
                    .first { it.javaClass.simpleName == "CardLayer" }
                val source = table(activity)!!
                val expected = source.continuityBounds(original)
                val sourceOrigin = IntArray(2); source.getLocationOnScreen(sourceOrigin)
                val layerOrigin = IntArray(2); layer.getLocationOnScreen(layerOrigin)
                expected.offset((sourceOrigin[0] - layerOrigin[0]).toFloat(), (sourceOrigin[1] - layerOrigin[1]).toFloat())
                val bitmap = Bitmap.createBitmap(layer.width, layer.height, Bitmap.Config.ARGB_8888)
                try {
                    layer.draw(Canvas(bitmap))
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val painted = Rect(bitmap.width, bitmap.height, 0, 0)
                    pixels.forEachIndexed { index, color ->
                        if (android.graphics.Color.alpha(color) > 128) {
                            val x = index % bitmap.width; val y = index / bitmap.width
                            painted.left = minOf(painted.left, x); painted.top = minOf(painted.top, y)
                            painted.right = maxOf(painted.right, x + 1); painted.bottom = maxOf(painted.bottom, y + 1)
                        }
                    }
                    assertFalse("The source course must be painted", painted.isEmpty)
                    assertEquals(expected.left.roundToInt().toFloat(), painted.left.toFloat(), 2f)
                    assertEquals(expected.top.roundToInt().toFloat(), painted.top.toFloat(), 2f)
                    assertEquals(expected.right.roundToInt().toFloat(), painted.right.toFloat(), 2f)
                    assertEquals(expected.bottom.roundToInt().toFloat(), painted.bottom.toFloat(), 2f)
                } finally { bitmap.recycle() }
                sheet.dismissImmediately()
            }
        }
    }

    @Test fun h_detailsUseClearAlignedInformationAndReadableActions() = fixture { _, original ->
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun checkReadability(activity: MainActivity, empty: Boolean) {
                val sheet = dialog(activity)!!
                val content = sheet.findViewById<View>(R.id.courseDetailContent)!!
                val stage = content.parent as ViewGroup
                val layer = (0 until stage.childCount).map { stage.getChildAt(it) }
                    .first { it.javaClass.simpleName == "CardLayer" }
                val bitmap = Bitmap.createBitmap(layer.width, layer.height, Bitmap.Config.ARGB_8888)
                val samples = try {
                    layer.draw(Canvas(bitmap))
                    listOf(.14f to .14f, .86f to .14f, .14f to .86f, .86f to .86f).map { (x, y) ->
                        bitmap.getPixel((content.left - layer.left + content.width * x).roundToInt(),
                            (content.top - layer.top + content.height * y).roundToInt())
                    }
                } finally { bitmap.recycle() }
                val night = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                assertTrue("The information surface must remain visually stable", samples.all { Color.alpha(it) >= 240 })
                val span = listOf(samples.maxOf(Color::red) - samples.minOf(Color::red),
                    samples.maxOf(Color::green) - samples.minOf(Color::green),
                    samples.maxOf(Color::blue) - samples.minOf(Color::blue)).maxOrNull()!!
                assertTrue("The gradient must stay soft", span in 1..36)
                if (night) {
                    assertTrue("Night tint must retain a dark base", samples.all { ColorUtils.calculateLuminance(it) < .025 })
                } else {
                    assertTrue("Course tint must not compete with the information", samples.all { ColorUtils.calculateLuminance(it) > .74 })
                }
                val title = sheet.findViewById<TextView>(R.id.tvDetailTitle)!!
                val titleContrast = samples.minOf { ColorUtils.calculateContrast(title.currentTextColor,
                    ColorUtils.compositeColors(it, if (night) Color.BLACK else Color.WHITE)) }
                assertTrue("The title must be easy to identify", title.typeface.isBold)
                assertTrue("The title must have strong contrast", titleContrast >= 7)
                assertEquals("Details must return to an edge-to-edge bottom sheet", stage.paddingLeft, content.left)
                assertEquals("Details must stay above the navigation area", stage.height - stage.paddingBottom, content.bottom)
                assertTrue("The clean detail should leave the timetable visible", content.height < (stage.height - stage.paddingTop - stage.paddingBottom) * .75f)
                val values = listOf(R.id.tvDetailWeeks, R.id.tvDetailTime, R.id.tvDetailTeacher, R.id.tvDetailClassroom)
                    .map { sheet.findViewById<TextView>(it)!! }
                val leftEdges = values.map { value -> IntArray(2).also(value::getLocationOnScreen)[0] }
                assertTrue("The four main values must form one reading column", leftEdges.maxOrNull()!! - leftEdges.minOrNull()!! <= 1)
                val tops = values.map { value -> IntArray(2).also(value::getLocationOnScreen)[1] }
                assertTrue("Information must follow the original week, time, teacher, classroom order", tops.zipWithNext().all { (a, b) -> b > a })
                val valueContrasts = values.map { value -> samples.minOf { background -> ColorUtils.calculateContrast(
                    if (empty && value is EditText) value.currentHintTextColor else value.currentTextColor,
                    ColorUtils.compositeColors(background, if (night) Color.BLACK else Color.WHITE)) } }
                assertTrue("Main values and empty-field prompts must be readable", valueContrasts.all { it >= if (empty) 4.5 else 7.0 })
                assertTrue("Time must remain prominent", values.first().textSize >= values[1].textSize)
                if (activity.resources.configuration.fontScale <= 1.05f) {
                    values.forEach { value ->
                        val visible = Rect()
                        assertTrue("All four main values must fit on the first screen", value.getGlobalVisibleRect(visible) && visible.height() == value.height)
                    }
                }
                val save = sheet.findViewById<MaterialButton>(R.id.btnEditCourse)!!
                assertFalse("Unchanged data must not enable saving", save.isEnabled)
                assertEquals("Reading must not show an inactive edit toolbar", View.GONE, sheet.findViewById<View>(R.id.focusActions)!!.visibility)
                assertEquals("The disabled action must not fade its text", 1f, save.alpha, 0f)
                val saveColor = save.backgroundTintList!!.getColorForState(save.drawableState, save.backgroundTintList!!.defaultColor)
                val saveContrast = ColorUtils.calculateContrast(save.currentTextColor, saveColor)
                assertTrue("The disabled action must remain legible", saveContrast >= 4.5)
                File(folder, "readability-${if (empty) "empty" else "filled"}-$suffix.json").writeText(JSONObject()
                    .put("surfacePixels", JSONArray(samples.map { String.format("#%08X", it) }))
                    .put("gradientChannelSpan", span)
                    .put("mode", if (night) "dark" else "light")
                    .put("titleContrast", titleContrast)
                    .put("valueContrasts", JSONArray(valueContrasts))
                    .put("valueLeftEdges", JSONArray(leftEdges))
                    .put("disabledSaveContrast", saveContrast)
                    .put("fontScale", activity.resources.configuration.fontScale).toString(2))
            }
            open(scenario, original)
            scenario.onActivity { checkReadability(it, false) }
            shot("readability-filled")
            scenario.onActivity { activity ->
                val sheet = dialog(activity)!!
                text(sheet, R.id.tvDetailClassroom, "明理楼B408")
                assertTrue(sheet.findViewById<View>(R.id.btnEditCourse)!!.isEnabled)
                assertEquals(View.VISIBLE, sheet.findViewById<View>(R.id.focusActions)!!.visibility)
            }
            settle()
            scenario.onActivity { activity ->
                val sheet = dialog(activity)!!
                val save = sheet.findViewById<MaterialButton>(R.id.btnEditCourse)!!
                val visible = Rect()
                assertTrue("Editing must reveal an accessible save action", save.getGlobalVisibleRect(visible) && visible.height() == save.height)
                assertTrue(ColorUtils.calculateContrast(save.currentTextColor,
                    save.backgroundTintList!!.getColorForState(save.drawableState, save.backgroundTintList!!.defaultColor)) >= 4.5)
            }
            shot("classic-editing")
            scenario.onActivity { dialog(it)!!.cancel() }
            scenario.onActivity { dialog(it)!!.findViewById<View>(R.id.btnFocusDiscard)!!.performClick() }
            await(scenario) { dialog(it) == null }
            assertEquals(original, runBlocking { database.courseDao().getCourseById(original.id) })
            val empty = original.copy(courseName = "大学英语", classroom = "", teacher = "", dayOfWeek = 2,
                startSection = 3, endSection = 4, startWeek = 1, endWeek = 20, colorIndex = 1, note = "")
            runBlocking { database.courseDao().updateCourse(empty) }
            open(scenario, empty)
            scenario.onActivity { checkReadability(it, true) }
            shot("readability-empty")
            scenario.onActivity { activity ->
                val sheet = dialog(activity)!!
                assertEquals(View.GONE, sheet.findViewById<View>(R.id.rowDetailNote)!!.visibility)
                sheet.findViewById<View>(R.id.btnFocusMore)!!.performClick()
                assertEquals("An empty note must remain editable from More", View.VISIBLE, sheet.findViewById<View>(R.id.rowDetailNote)!!.visibility)
            }
            scenario.onActivity { dialog(it)!!.cancel() }
            await(scenario) { dialog(it) == null }
            assertEquals(empty, runBlocking { database.courseDao().getCourseById(original.id) })
        }
    }

    @Test fun j_sheetSurfaceContinuesBehindTheNavigationArea() = fixture { _, original ->
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            open(scenario, original)
            var seamY = 0
            var sampleX = 0
            var inset = 0
            scenario.onActivity { activity ->
                val sheet = dialog(activity)!!
                val content = sheet.findViewById<View>(R.id.courseDetailContent)!!
                val stage = content.parent as ViewGroup
                val layer = (0 until stage.childCount).map(stage::getChildAt).first { it.javaClass.simpleName == "CardLayer" }
                assertFalse("System insets must not clip the background", stage.clipToPadding)
                assertEquals("The glass background must cover the full window", stage.height, layer.height)
                assertEquals(stage.width, layer.width)
                assertEquals(0, layer.top)
                assertEquals("Controls must remain clear of system navigation", stage.height - stage.paddingBottom, content.bottom)
                inset = stage.paddingBottom
                assertEquals("A fixed navigation fill would recreate the gray strip", Color.TRANSPARENT, sheet.window!!.navigationBarColor)
                if (Build.VERSION.SDK_INT >= 29) assertFalse(sheet.window!!.isNavigationBarContrastEnforced)
                val bitmap = Bitmap.createBitmap(layer.width, layer.height, Bitmap.Config.ARGB_8888)
                try {
                    layer.draw(Canvas(bitmap))
                    val x = (stage.width * .06f).roundToInt()
                    val above = bitmap.getPixel(x, (content.bottom - 2).coerceIn(0, bitmap.height - 1))
                    val below = bitmap.getPixel(x, (content.bottom + 2).coerceIn(0, bitmap.height - 1))
                    val foot = bitmap.getPixel(x, bitmap.height - 3)
                    fun difference(a: Int, b: Int) = maxOf(kotlin.math.abs(Color.red(a) - Color.red(b)),
                        kotlin.math.abs(Color.green(a) - Color.green(b)), kotlin.math.abs(Color.blue(a) - Color.blue(b)))
                    assertTrue("The paper must reach the screen edge", Color.alpha(foot) >= 240)
                    assertTrue("Navigation must not introduce a horizontal color boundary", difference(above, below) <= 3)
                } finally { bitmap.recycle() }
                val origin = IntArray(2).also(stage::getLocationOnScreen)
                seamY = origin[1] + content.bottom
                sampleX = origin[0] + (stage.width * .06f).roundToInt()
            }
            val screen = instrumentation.uiAutomation.takeScreenshot()
            try {
                val above = screen.getPixel(sampleX, (seamY - 3).coerceIn(0, screen.height - 1))
                val below = screen.getPixel(sampleX, (seamY + 3).coerceIn(0, screen.height - 1))
                val difference = maxOf(kotlin.math.abs(Color.red(above) - Color.red(below)),
                    kotlin.math.abs(Color.green(above) - Color.green(below)), kotlin.math.abs(Color.blue(above) - Color.blue(below)))
                File(folder, "navigation-seam-$suffix.json").writeText(JSONObject().put("navigationInset", inset)
                    .put("screenChannelDifference", difference).put("above", String.format("#%08X", above))
                    .put("below", String.format("#%08X", below)).toString(2))
                File(folder, "navigation-seam-$suffix.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }
                assertTrue("System composition must not add a navigation strip: $difference", difference <= 4)
            } finally { screen.recycle() }
            scenario.onActivity { dialog(it)!!.cancel() }
            await(scenario) { dialog(it) == null }
        }
    }

    @Test fun k_acceptedCloseAllowsRealTouchesDuringTheRemainingAnimation() = fixture { _, original ->
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            open(scenario, original)
            val checks = JSONArray()
            repeat(3) { pass ->
                lateinit var closingSheet: CourseFocusDialog
                var x = 0f
                var y = 0f
                var heldAnimation = false
                scenario.onActivity { activity ->
                    closingSheet = dialog(activity)!!
                    if (pass == 1) {
                        text(closingSheet, R.id.tvDetailClassroom, "未保存的变更")
                        closingSheet.cancel()
                        assertEquals(View.VISIBLE, closingSheet.findViewById<View>(R.id.focusConfirmation)!!.visibility)
                        assertEquals(0, closingSheet.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                        closingSheet.findViewById<View>(R.id.btnFocusDiscard)!!.performClick()
                    } else closingSheet.cancel()
                    val animation = CourseFocusDialog::class.java.getDeclaredField("animation").apply { isAccessible = true }
                        .get(closingSheet) as? ValueAnimator
                    if (ValueAnimator.areAnimatorsEnabled()) {
                        assertNotNull("The close must still have its visual animation", animation)
                        animation!!.pause()
                        heldAnimation = true
                        assertTrue("The visual window is deliberately held to test input routing", closingSheet.isShowing)
                        val flags = closingSheet.window!!.attributes.flags
                        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
                        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
                    }
                    val source = table(activity)!!
                    val cell = source.continuityBounds(original)
                    val origin = IntArray(2).also(source::getLocationOnScreen)
                    x = cell.centerX() + origin[0]
                    y = cell.centerY() + origin[1]
                }
                instrumentation.waitForIdleSync()
                val downTime = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).apply {
                        source = InputDevice.SOURCE_TOUCHSCREEN
                    }
                    try { assertTrue("Touch injection failed", instrumentation.uiAutomation.injectInputEvent(event, true)) }
                    finally { event.recycle() }
                }
                await(scenario) { dialog(it) != null && dialog(it) !== closingSheet && dialog(it)!!.isShowing }
                scenario.onActivity { activity ->
                    assertFalse("Opening another course must remove the old closing window", closingSheet.isShowing)
                    assertEquals(original.courseName, dialog(activity)!!.findViewById<EditText>(R.id.tvDetailTitle)!!.text.toString())
                }
                assertEquals(original, runBlocking { database.courseDao().getCourseById(original.id) })
                checks.put(JSONObject().put("pass", pass).put("heldClosingAnimation", heldAnimation)
                    .put("realTouchOpenedNextSheet", true).put("originalCoursePreserved", true))
                settle(550)
            }
            File(folder, "close-input-$suffix.json").writeText(JSONObject().put("passes", checks).toString(2))
            scenario.onActivity { dialog(it)!!.cancel() }
            await(scenario) { dialog(it) == null }
        }
    }

    @Test fun i_softwareBackdropBlursFineContentAndPreservesTheColorFields() {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until 96) for (x in 0 until 96) {
                bitmap.setPixel(x, y, if (x < 48) Color.rgb(43, 137, 232) else Color.rgb(244, 159, 72))
                if (x in 8..40 && y in 12..80 && x % 6 < 2) bitmap.setPixel(x, y, Color.BLACK)
            }
            fun fineContrast(): Int = (12..80).sumOf { y -> (9..40).sumOf { x ->
                kotlin.math.abs(Color.blue(bitmap.getPixel(x, y)) - Color.blue(bitmap.getPixel(x - 1, y)))
            } }
            val before = fineContrast()
            blurCourseSnapshot(bitmap)
            val after = fineContrast()
            assertTrue("Text-sized detail must be softened", after < before / 4)
            val blue = bitmap.getPixel(8, 8)
            val amber = bitmap.getPixel(88, 48)
            assertTrue(Color.blue(blue) - Color.red(blue) > 60)
            assertTrue(Color.red(amber) - Color.blue(amber) > 100)
            File(folder, "software-blur-$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(folder, "software-blur-$suffix.json").writeText(JSONObject()
                .put("fineDetailBefore", before).put("fineDetailAfter", after)
                .put("blueField", String.format("#%08X", blue)).put("amberField", String.format("#%08X", amber)).toString(2))
        } finally { bitmap.recycle() }
    }
}
