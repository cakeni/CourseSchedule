package com.courseschedule.ui.addcourse

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.NumberPicker
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.courseschedule.R
import com.courseschedule.data.AppDatabase
import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.utils.ReminderManager
import com.courseschedule.utils.SchedulePreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CourseEditorRedesignTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val suffix = InstrumentationRegistry.getArguments().getString("proofSuffix", "light")
    private val folder = File(context.getExternalFilesDir(null), "course-editor-proof").apply { mkdirs() }

    private fun settle(ms: Long = 450) {
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).postDelayed({ done.countDown() }, ms)
        assertTrue(done.await(5, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }

    private fun ready(scenario: ActivityScenario<AddCourseActivity>) {
        val done = CountDownLatch(1)
        scenario.onActivity { activity ->
            val root = activity.window.decorView
            if (activity.findViewById<View>(R.id.btnSave).isEnabled) done.countDown()
            else root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (activity.findViewById<View>(R.id.btnSave).isEnabled) {
                        root.viewTreeObserver.removeOnPreDrawListener(this); done.countDown()
                    }
                    return true
                }
            })
        }
        assertTrue("Editor failed to load", done.await(10, TimeUnit.SECONDS)); settle()
    }

    private fun shot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(folder, "$name-$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun intent(day: Int = 3, start: Int = 3, end: Int = 4) = Intent(context, AddCourseActivity::class.java)
        .putExtra(AddCourseActivity.EXTRA_DAY_OF_WEEK, day)
        .putExtra(AddCourseActivity.EXTRA_SECTION, start)
        .putExtra(AddCourseActivity.EXTRA_END_SECTION, end)

    private fun fixture(block: (AppDatabase, Semester) -> Unit) = runBlocking {
        val database = AppDatabase.getDatabase(context)
        val previous = database.semesterDao().getCurrentSemesterSync()
        val preferences = SchedulePreferences(context)
        val enabled = preferences.reminderEnabled
        preferences.reminderEnabled = false
        val semester = Semester(name = "2026 秋季学期", startDate = 1790000000000L, totalWeeks = 20)
            .let { it.copy(id = database.semesterDao().insertSemester(it), isCurrent = true) }
        database.semesterDao().switchCurrentSemester(semester.id)
        try { block(database, semester) }
        finally {
            database.courseDao().getCoursesBySemesterSync(semester.id).forEach { ReminderManager(context).cancelReminder(it.id) }
            database.courseDao().deleteCoursesBySemester(semester.id)
            database.semesterDao().deleteSemester(semester)
            previous?.let { database.semesterDao().switchCurrentSemester(it.id) }
            preferences.reminderEnabled = enabled
        }
    }

    @Test fun editAndRotationPreserveCustomColorReminderAndDraft() = fixture { database, semester ->
        val original = Course(courseName = "高等数学", teacher = "张老师", classroom = "明理楼 B407", dayOfWeek = 3,
            startSection = 3, endSection = 4, startWeek = 1, endWeek = 16, semesterId = semester.id,
            colorIndex = -4123, reminderMinutes = 45, note = "携带教材")
            .let { it.copy(id = runBlocking { database.courseDao().insertCourse(it) }) }
        ActivityScenario.launch<AddCourseActivity>(intent().putExtra("is_edit", true).putExtra("course_id", original.id)).use { scenario ->
            ready(scenario)
            scenario.onActivity { activity ->
                assertEquals("提前45分钟", activity.findViewById<TextView>(R.id.tvReminder).text.toString())
                activity.findViewById<EditText>(R.id.etClassroom).setText("明理楼 B408")
                activity.findViewById<View>(R.id.rowWeeks).performClick()
                activity.findViewById<View>(R.id.weekOdd).performClick()
            }
            settle(); scenario.recreate(); ready(scenario)
            scenario.onActivity { activity ->
                assertEquals("明理楼 B408", activity.findViewById<EditText>(R.id.etClassroom).text.toString())
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.weekPanel).visibility)
                assertTrue(activity.findViewById<View>(R.id.weekOdd).isSelected)
                activity.findViewById<View>(R.id.btnSave).performClick()
            }
            settle(900)
            val saved = runBlocking { database.courseDao().getCourseById(original.id) }!!
            assertEquals(original.copy(classroom = "明理楼 B408", weekType = 1), saved)
            assertEquals(1, runBlocking { database.courseDao().getCoursesBySemesterSync(semester.id) }.size)
        }
    }

    @Test fun conflictKeepsDraftAndRetrySavesExactlyOnce() = fixture { database, semester ->
        runBlocking { database.courseDao().insertCourse(Course(courseName = "大学物理", dayOfWeek = 3,
            startSection = 3, endSection = 4, startWeek = 1, endWeek = 16, semesterId = semester.id)) }
        ActivityScenario.launch<AddCourseActivity>(intent()).use { scenario ->
            ready(scenario)
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.etCourseName).setText("高等数学")
                activity.findViewById<View>(R.id.btnSave).performClick()
                activity.findViewById<View>(R.id.btnSave).performClick()
            }
            settle(900)
            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.formError).visibility)
                assertEquals("高等数学", activity.findViewById<EditText>(R.id.etCourseName).text.toString())
                assertTrue(activity.findViewById<View>(R.id.btnSave).isEnabled)
                activity.findViewById<View>(R.id.day2).performClick()
                activity.findViewById<View>(R.id.btnSave).performClick()
                activity.findViewById<View>(R.id.btnSave).performClick()
            }
            settle(900)
            val courses = runBlocking { database.courseDao().getCoursesBySemesterSync(semester.id) }
            assertEquals(2, courses.size)
            assertEquals(2, courses.single { it.courseName == "高等数学" }.dayOfWeek)
        }
    }

    @Test fun semesterSwitchRejectsStaleWriteWithoutLosingText() = fixture { database, semester ->
        ActivityScenario.launch<AddCourseActivity>(intent()).use { scenario ->
            ready(scenario)
            val another = semester.copy(id = 0, name = "其他学期", isCurrent = false)
                .let { it.copy(id = runBlocking { database.semesterDao().insertSemester(it) }) }
            try {
                runBlocking { database.semesterDao().switchCurrentSemester(another.id) }
                scenario.onActivity { activity ->
                    activity.findViewById<EditText>(R.id.etCourseName).setText("不会误存的课程")
                    activity.findViewById<View>(R.id.btnSave).performClick()
                }
                settle(900)
                assertTrue(runBlocking { database.courseDao().getCoursesBySemesterSync(another.id) }.isEmpty())
                assertTrue(runBlocking { database.courseDao().getCoursesBySemesterSync(semester.id) }.isEmpty())
                scenario.onActivity { activity ->
                    assertEquals(context.getString(R.string.editor_changed_semester), activity.findViewById<TextView>(R.id.formError).text.toString())
                    assertEquals("不会误存的课程", activity.findViewById<EditText>(R.id.etCourseName).text.toString())
                }
            } finally { runBlocking { database.semesterDao().deleteSemester(another); database.semesterDao().switchCurrentSemester(semester.id) } }
        }
    }

    @Test fun nativeWheelKeepsOrderedRangeAndLiveTime() = fixture { _, _ ->
        ActivityScenario.launch<AddCourseActivity>(intent(start = 1, end = 2)).use { scenario ->
            ready(scenario)
            scenario.onActivity { it.findViewById<View>(R.id.rowSections).performClick() }; settle()
            repeat(3) {
                scenario.onActivity { activity ->
                    val wheel = activity.findViewById<NumberPicker>(R.id.sectionStart)
                    wheel.requestFocus()
                    wheel.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
                    wheel.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN))
                }
                settle(420)
            }
            scenario.onActivity { activity ->
                val start = activity.findViewById<NumberPicker>(R.id.sectionStart).value
                val end = activity.findViewById<NumberPicker>(R.id.sectionEnd).value
                assertTrue("Native wheel didn't change", start > 2)
                assertEquals(start, end)
                assertEquals("第${start}节", activity.findViewById<TextView>(R.id.tvSections).text.toString())
                assertTrue(activity.findViewById<TextView>(R.id.tvSectionTimes).text.contains(SchedulePreferences(context).sectionTimes[start - 1]))
            }
            shot("ordered-sections")
        }
    }

    @Test fun dirtyExitRequiresChoiceAndKeepEditingRetainsText() = fixture { database, semester ->
        ActivityScenario.launch<AddCourseActivity>(intent()).use { scenario ->
            ready(scenario)
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.etCourseName).setText("尚未保存的课程")
                activity.findViewById<View>(R.id.btnBack).performClick()
            }
            onView(withText(R.string.editor_keep_editing)).perform(click())
            scenario.onActivity { activity -> assertEquals("尚未保存的课程", activity.findViewById<EditText>(R.id.etCourseName).text.toString()) }
            assertTrue(runBlocking { database.courseDao().getCoursesBySemesterSync(semester.id) }.isEmpty())
        }
    }

    @Test fun blankNameIsFocusedAndDeletingRequiresConfirmation() = fixture { database, semester ->
        ActivityScenario.launch<AddCourseActivity>(intent()).use { scenario ->
            ready(scenario)
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.btnSave).performClick()
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.nameError).visibility)
                assertTrue(activity.findViewById<View>(R.id.etCourseName).hasFocus())
            }
            assertTrue(runBlocking { database.courseDao().getCoursesBySemesterSync(semester.id) }.isEmpty())
        }
        val original = Course(courseName = "将被删除的课程", dayOfWeek = 1, startSection = 1, endSection = 2,
            startWeek = 1, endWeek = 16, semesterId = semester.id)
            .let { it.copy(id = runBlocking { database.courseDao().insertCourse(it) }) }
        ActivityScenario.launch<AddCourseActivity>(intent().putExtra("is_edit", true).putExtra("course_id", original.id)).use { scenario ->
            ready(scenario)
            onView(withId(R.id.btnDelete)).perform(scrollTo(), click())
            onView(withText(R.string.cancel)).perform(click())
            settle()
            assertEquals(original, runBlocking { database.courseDao().getCourseById(original.id) })
            onView(withId(R.id.btnDelete)).perform(scrollTo(), click())
            onView(withText(R.string.delete)).perform(click())
            settle(900)
            assertNull(runBlocking { database.courseDao().getCourseById(original.id) })
        }
    }

    @Test fun captureEditorAndPanelMotion() = fixture { _, _ ->
        ActivityScenario.launch<AddCourseActivity>(intent(day = 3)).use { scenario ->
            ready(scenario)
            shot("empty")
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.etCourseName).setText("高等数学")
                activity.findViewById<EditText>(R.id.etTeacher).setText("张老师")
                activity.findViewById<EditText>(R.id.etClassroom).setText("明理楼 B407")
            }
            settle(); shot("overview")
            scenario.onActivity { it.findViewById<View>(R.id.day5).performClick() }; settle(650)
            scenario.onActivity { it.findViewById<View>(R.id.day3).performClick() }; settle(650)
            scenario.onActivity { it.findViewById<View>(R.id.rowSections).performClick() }; settle(650); shot("sections")
            scenario.onActivity { it.findViewById<View>(R.id.sectionPanelDone).performClick() }; settle(650)
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.rowWeeks).performClick()
                activity.findViewById<NestedScrollView>(R.id.formScroll).smoothScrollTo(0, (240 * activity.resources.displayMetrics.density).toInt())
            }
            settle(650); shot("weeks")
            scenario.onActivity { it.findViewById<View>(R.id.weekOdd).performClick() }; settle(650)
            scenario.onActivity { it.findViewById<View>(R.id.weekPanelDone).performClick() }; settle(650)
            scenario.onActivity { activity ->
                activity.findViewById<NestedScrollView>(R.id.formScroll).smoothScrollTo(0, 10000)
                activity.findViewById<View>(R.id.rowColor).performClick()
            }
            settle(650); shot("colors")
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.rowColor).performClick()
                activity.findViewById<View>(R.id.rowReminder).performClick()
                activity.findViewById<NestedScrollView>(R.id.formScroll).smoothScrollTo(0, 10000)
            }
            settle(650); shot("reminders")
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.rowReminder).performClick()
                activity.findViewById<EditText>(R.id.etNote).setText("携带教材，课前预习第二章")
            }
            settle(); shot("details")
            scenario.onActivity { activity ->
                activity.findViewById<NestedScrollView>(R.id.formScroll).scrollTo(0, 0)
                val name = activity.findViewById<EditText>(R.id.etCourseName)
                name.requestFocus()
                (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(name, InputMethodManager.SHOW_IMPLICIT)
            }
            onView(withId(R.id.etCourseName)).perform(click())
            settle(850); shot("keyboard")
            scenario.onActivity { activity ->
                val rect = Rect()
                assertTrue("Keyboard did not open", androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true)
                assertTrue("Pinned save is hidden by keyboard", activity.findViewById<View>(R.id.btnSave).getGlobalVisibleRect(rect))
                activity.findViewById<View>(R.id.rowSections).performClick()
            }
            settle(650); shot("keyboard-dismissed")
        }
    }
}
