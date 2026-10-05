package com.courseschedule.ui

import android.Manifest
import android.animation.ValueAnimator
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.NumberPicker
import android.widget.TextView
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
import com.courseschedule.utils.ReminderManager
import com.courseschedule.utils.SchedulePreferences
import com.courseschedule.view.CourseTableView
import com.courseschedule.viewmodel.CourseViewModel
import com.google.android.material.card.MaterialCardView
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
                if (!suffix.contains("dark")) {
                    assertTrue("Day background must follow the purple course color", Color.blue(purple) - Color.red(purple) > 30 && Color.red(purple) > Color.green(purple))
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
                    assertEquals("Night background must remain neutral after changing course color", initialSurface, changedSurface)
                    assertTrue(maxOf(Color.red(changedSurface), Color.green(changedSurface), Color.blue(changedSurface)) <= 20)
                } else {
                    assertNotEquals("Day details must follow the selected course color", initialSurface, changedSurface)
                    assertTrue("Day background must follow the blue course color", Color.green(changedSurface) - Color.red(changedSurface) > 35 && Color.blue(changedSurface) - Color.green(changedSurface) > 25)
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

    @Test fun h_detailsHaveASoftGradientAndClearBorderlessInformationCards() = fixture { _, original ->
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            open(scenario, original)
            scenario.onActivity { activity ->
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
                assertTrue("The tinted surface must retain subtle transparency", samples.all { Color.alpha(it) in 240..254 })
                val span = listOf(samples.maxOf(Color::red) - samples.minOf(Color::red),
                    samples.maxOf(Color::green) - samples.minOf(Color::green),
                    samples.maxOf(Color::blue) - samples.minOf(Color::blue)).maxOrNull()!!
                assertTrue("The gradient must stay soft", span in if (night) 5..24 else 10..55)
                if (night) {
                    assertTrue("Night background must retain a neutral black base", samples.all { maxOf(Color.red(it), Color.green(it), Color.blue(it)) <= 20 && Color.red(it) == Color.green(it) && Color.green(it) == Color.blue(it) })
                } else {
                    assertTrue("Day background must keep the original lime course hue", samples.all { Color.green(it) > Color.red(it) && Color.red(it) - Color.blue(it) > 40 })
                }
                val expectedText = if (night) Color.rgb(243, 244, 246) else Color.rgb(32, 35, 40)
                val titleColor = sheet.findViewById<TextView>(R.id.tvDetailTitle)!!.currentTextColor
                assertEquals(expectedText, titleColor)
                val cards = descendants(content).filterIsInstance<MaterialCardView>()
                assertTrue(cards.size >= 3)
                assertTrue("Information cards must remain clear and softly translucent",
                    cards.all { it.cardBackgroundColor.defaultColor == if (night) Color.argb(203, 23, 23, 23) else Color.argb(235, 255, 255, 255) })
                assertTrue("Information cards should not have heavy outlines", cards.all { it.strokeWidth == 0 })
                val save = sheet.findViewById<MaterialButton>(R.id.btnEditCourse)!!
                assertEquals(if (night) Color.rgb(242, 244, 247) else Color.rgb(32, 35, 40), save.backgroundTintList!!.defaultColor)
                assertEquals(if (night) Color.rgb(21, 23, 26) else Color.WHITE, save.currentTextColor)
                File(folder, "refined-material-$suffix.json").writeText(JSONObject()
                    .put("surfacePixels", JSONArray(samples.map { String.format("#%08X", it) }))
                    .put("surfaceAlpha", JSONArray(samples.map(Color::alpha)))
                    .put("gradientChannelSpan", span)
                    .put("mode", if (night) "dark" else "light")
                    .put("textRgb", String.format("#%06X", titleColor and 0xFFFFFF))
                    .put("saveButtonArgb", String.format("#%08X", save.backgroundTintList!!.defaultColor))
                    .put("saveTextRgb", String.format("#%06X", save.currentTextColor and 0xFFFFFF))
                    .put("contentCardAlpha", JSONArray(cards.map { Color.alpha(it.cardBackgroundColor.defaultColor) }))
                    .put("contentCardStrokeDp", JSONArray(cards.map { it.strokeWidth })).toString(2))
            }
            shot("refined-material")
            scenario.onActivity { dialog(it)!!.cancel() }
            await(scenario) { dialog(it) == null }
            assertEquals(original, runBlocking { database.courseDao().getCourseById(original.id) })
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
