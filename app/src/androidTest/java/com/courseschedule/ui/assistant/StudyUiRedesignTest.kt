package com.courseschedule.ui.assistant

import android.content.Intent
import android.graphics.Bitmap
import android.widget.NumberPicker
import androidx.lifecycle.Observer
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.courseschedule.R
import com.courseschedule.data.AppDatabase
import com.courseschedule.data.StudyTaskStore
import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.data.entity.StudyTask
import com.courseschedule.domain.StudyTaskRules
import com.courseschedule.ui.MainActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.courseschedule.utils.ReminderManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class StudyUiRedesignTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val database by lazy { AppDatabase.getDatabase(context) }

    private suspend fun fixture(block: suspend (Semester) -> Unit) {
        val old = database.semesterDao().getCurrentSemesterSync()
        val semester = Semester(name = "2026 秋季学期", startDate = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), totalWeeks = 20)
            .let { it.copy(id = database.semesterDao().insertSemester(it)) }
        database.semesterDao().switchCurrentSemester(semester.id)
        try { block(semester) } finally {
            database.studyTaskDao().forSemester(semester.id).forEach { ReminderManager(context).cancelStudyReminder(it.id) }
            database.courseDao().deleteCoursesBySemester(semester.id)
            database.semesterDao().deleteSemester(semester)
            old?.let { database.semesterDao().switchCurrentSemester(it.id) }
            ReminderManager(context).restoreReminders()
        }
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        if (name !in setOf("list", "search", "today", "completed", "empty")) {
            onView(withId(R.id.btnTaskSave)).check(matches(isDisplayed()))
            onView(withId(R.id.btnTaskCancel)).check(matches(isDisplayed()))
        }
        val frame = CountDownLatch(1)
        instrumentation.runOnMainSync {
            android.view.Choreographer.getInstance().postFrameCallback {
                android.view.Choreographer.getInstance().postFrameCallback { frame.countDown() }
            }
        }
        assertTrue(frame.await(2, TimeUnit.SECONDS))
        val suffix = InstrumentationRegistry.getArguments().getString("proofSuffix", "light")
        val dir = File(context.getExternalFilesDir(null), "todo-motion-proof").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
            File(dir, "$name-$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun awaitRows(semesterId: Long, predicate: (List<StudyTask>) -> Boolean) {
        val latch = CountDownLatch(1)
        val live = database.studyTaskDao().observe(semesterId)
        val observer = Observer<List<StudyTask>> { if (predicate(it)) latch.countDown() }
        instrumentation.runOnMainSync { live.observeForever(observer) }
        try { assertTrue("Expected saved task state", latch.await(15, TimeUnit.SECONDS)) }
        finally { instrumentation.runOnMainSync { live.removeObserver(observer) } }
        instrumentation.waitForIdleSync()
    }

    @Test fun inlineSelectionsSurviveRotationAndSaveExactDeadlineAcrossYearBoundary(): Unit = runBlocking {
        fixture { semester ->
            val course = Course(semesterId = semester.id, courseName = "大学物理", dayOfWeek = 1, startSection = 1, endSection = 2, startWeek = 1, endWeek = 20)
                .let { it.copy(id = database.courseDao().insertCourse(it)) }
            val original = StudyTaskStore(context).save(StudyTask(semesterId = semester.id, title = "光电效应实验报告", kind = "report",
                dueAt = StudyTaskRules.deadline("2027-12-31", "20:00")))
            ActivityScenario.launch<StudyTaskEditorActivity>(Intent(context, StudyTaskEditorActivity::class.java).putExtra("study_task_id", original.id)).use { editor ->
                onView(withId(R.id.btnTaskSave)).check(matches(isEnabled()))
                screenshot("editor")
                onView(withId(R.id.btnKindExam)).perform(scrollTo(), click())
                onView(withId(R.id.btnTaskCourse)).perform(scrollTo(), click())
                screenshot("courses")
                onView(withText("大学物理")).perform(scrollTo(), click())
                onView(withId(R.id.btnTaskDate)).perform(scrollTo(), click())
                onView(withId(R.id.btnNextMonth)).perform(scrollTo(), click())
                onView(withId(R.id.tvCalendarMonth)).check(matches(withText("2028年1月")))
                screenshot("calendar")
                editor.recreate()
                onView(withId(R.id.btnTaskSave)).check(matches(isEnabled()))
                onView(withContentDescription("2028-01-02")).perform(scrollTo(), click())
                onView(withId(R.id.btnTaskTime)).perform(scrollTo(), click())
                editor.onActivity { activity ->
                    activity.findViewById<NumberPicker>(R.id.taskHour).value = 23
                    activity.findViewById<NumberPicker>(R.id.taskMinute).value = 45
                }
                screenshot("time")
                onView(withId(R.id.btnTimeDone)).perform(scrollTo(), click())
                onView(withId(R.id.btnTaskReminder)).perform(scrollTo(), click())
                screenshot("reminders")
                onView(withText("提前1天")).perform(scrollTo(), click())
                editor.recreate()
                onView(withId(R.id.btnTaskSave)).check(matches(isEnabled()))
                onView(withId(R.id.btnKindExam)).perform(scrollTo()).check(matches(isChecked()))
                onView(withId(R.id.tvTaskTimeValue)).perform(scrollTo()).check(matches(withText("23:45")))
                onView(withId(R.id.btnTaskSave)).perform(click())
                awaitRows(semester.id) { rows -> rows.any { it.id == original.id && it.kind == "exam" && it.reminderMinutes == 1440 } }
            }
            val saved = database.studyTaskDao().find(original.id)!!
            assertEquals(StudyTaskRules.deadline("2028-01-02", "23:45"), saved.dueAt)
            assertEquals("exam", saved.kind); assertEquals(1440, saved.reminderMinutes)
            assertEquals(course.id, saved.courseId); assertEquals("大学物理", saved.courseName)
        }
    }

    @Test fun newTaskRequiresExplicitDateAndTimeAndDiscardLeavesDatabaseUntouched(): Unit = runBlocking {
        fixture { semester ->
            ActivityScenario.launch<StudyTaskEditorActivity>(Intent(context, StudyTaskEditorActivity::class.java)).use {
                onView(withId(R.id.btnTaskSave)).check(matches(isEnabled())).perform(click())
                onView(withId(R.id.tvTaskError)).check(matches(withText("请选择明确的截止日期和时间。")))
                onView(withId(R.id.etTaskTitle)).perform(scrollTo(), replaceText("第三章习题")); androidx.test.espresso.Espresso.closeSoftKeyboard()
                onView(withId(R.id.btnTaskCancel)).perform(click())
                onView(withId(R.id.btnKeepEditing)).perform(scrollTo(), click())
                onView(withId(R.id.etTaskTitle)).perform(scrollTo()).check(matches(withText("第三章习题")))
                onView(withId(R.id.btnTaskCancel)).perform(click())
                onView(withId(R.id.btnConfirmAction)).perform(scrollTo(), click())
            }
            assertTrue(database.studyTaskDao().forSemester(semester.id).isEmpty())
        }
    }

    @Test fun staleEditCannotOverwriteOtherChangesAndDeleteHasInlineConfirmation(): Unit = runBlocking {
        fixture { semester ->
            val original = StudyTaskStore(context).save(StudyTask(semesterId = semester.id, title = "期中考试复习", kind = "exam",
                dueAt = System.currentTimeMillis() + 86400000L))
            var edited = original
            ActivityScenario.launch<StudyTaskEditorActivity>(Intent(context, StudyTaskEditorActivity::class.java).putExtra("study_task_id", original.id)).use {
                onView(withId(R.id.btnTaskSave)).check(matches(isEnabled()))
                edited = StudyTaskStore(context).save(original.copy(note = "已在其他页面更新", updatedAt = original.updatedAt + 1), original)
                onView(withId(R.id.etTaskTitle)).perform(replaceText("旧页面修改")); androidx.test.espresso.Espresso.closeSoftKeyboard()
                onView(withId(R.id.btnTaskSave)).perform(click())
                onView(withId(R.id.tvTaskError)).check(matches(isDisplayed()))
                assertEquals(edited, database.studyTaskDao().find(original.id))
            }
            ActivityScenario.launch<StudyTaskEditorActivity>(Intent(context, StudyTaskEditorActivity::class.java).putExtra("study_task_id", original.id)).use {
                onView(withId(R.id.btnTaskSave)).check(matches(isEnabled()))
                onView(withId(R.id.btnTaskDelete)).perform(scrollTo(), click())
                onView(withId(R.id.btnKeepEditing)).perform(scrollTo(), click())
                assertNotNull(database.studyTaskDao().find(original.id))
                onView(withId(R.id.btnTaskDelete)).perform(scrollTo(), click())
                onView(withId(R.id.btnConfirmAction)).perform(scrollTo(), click())
                awaitRows(semester.id) { rows -> rows.none { it.id == original.id } }
            }
            assertNull(database.studyTaskDao().find(original.id))
        }
    }

    @Test fun listGroupsDeadlinesAndFiltersPersistAcrossRotation(): Unit = runBlocking {
        fixture { semester ->
            val today = LocalDate.now()
            listOf("第三章习题" to 0L, "光电效应实验报告" to 1L, "期中考试" to 4L, "课程小论文" to 12L).forEachIndexed { index, (title, days) ->
                StudyTaskStore(context).save(StudyTask(semesterId = semester.id, title = title, kind = listOf("homework", "report", "exam", "report")[index],
                    courseName = listOf("高等数学", "大学物理", "英语", "课程报告")[index],
                    dueAt = today.plusDays(days).atTime(23, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), reminderMinutes = 60))
            }
            ActivityScenario.launch<StudyTasksActivity>(Intent(context, StudyTasksActivity::class.java).putExtra(StudyTasksActivity.EXTRA_PRIMARY_PAGE, true)).use { list ->
                onView(withText("第三章习题")).check(matches(isDisplayed()))
                screenshot("list")
                onView(withText("课程小论文")).perform(scrollTo()).check(matches(isDisplayed()))
                onView(withId(R.id.btnAddTask)).check(matches(isDisplayed()))
                onView(withText("课程小论文")).check { view, _ ->
                    val taskBounds = android.graphics.Rect()
                    val addBounds = android.graphics.Rect()
                    assertTrue(view.getGlobalVisibleRect(taskBounds))
                    assertTrue(view.rootView.findViewById<android.view.View>(R.id.btnAddTask).getGlobalVisibleRect(addBounds))
                    assertTrue("New task action must not overlap the last row", taskBounds.bottom <= addBounds.top)
                }
                onView(withId(R.id.btnTasksUpcoming)).perform(scrollTo(), click())
                list.recreate()
                onView(withId(R.id.btnTasksUpcoming)).check(matches(isChecked()))
                onView(withId(R.id.etTaskSearch)).perform(replaceText("物理")); androidx.test.espresso.Espresso.closeSoftKeyboard()
                onView(withText("光电效应实验报告")).check(matches(isDisplayed()))
                screenshot("search")
            }
        }
    }

    @Test fun learningIsAnIndependentDestinationFromEveryMainTab(): Unit = runBlocking {
        fixture { semester ->
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
                onView(withId(R.id.nav_study)).perform(click())
                onView(withId(R.id.btnStudyBack)).check(matches(withEffectiveVisibility(Visibility.GONE)))
                onView(withId(R.id.tvTaskOverview)).check(matches(withText("待办")))
                onView(withId(R.id.bottomNavigation)).check { view, _ -> assertEquals(R.id.nav_study, (view as BottomNavigationView).selectedItemId) }
                screenshot("empty")
                onView(withId(R.id.nav_import)).perform(click())
                onView(withId(R.id.bottomNavigation)).check { view, _ -> assertEquals(R.id.nav_import, (view as BottomNavigationView).selectedItemId) }
                onView(withId(R.id.nav_study)).perform(click())
                onView(withId(R.id.nav_settings)).perform(click())
                onView(withId(R.id.bottomNavigation)).check { view, _ -> assertEquals(R.id.nav_settings, (view as BottomNavigationView).selectedItemId) }
                onView(withId(R.id.nav_study)).perform(click())
                onView(withId(R.id.btnStudyReminders)).perform(click())
                onView(withId(R.id.nav_study)).perform(click())
                onView(withId(R.id.nav_home)).perform(click())
                onView(withId(R.id.bottomNavigation)).check { view, _ -> assertEquals(R.id.nav_home, (view as BottomNavigationView).selectedItemId) }
                assertEquals(semester.id, database.semesterDao().getCurrentSemesterSync()?.id)
                assertTrue(database.studyTaskDao().forSemester(semester.id).isEmpty())
            }
        }
    }

    @Test fun todayIncludesOverdueAndCompletionUpdatesCountsAndCanBeReopened(): Unit = runBlocking {
        fixture { semester ->
            val today = LocalDate.now()
            listOf("补交实验报告" to -1L, "第三章习题" to 0L, "明天的复习" to 1L).forEach { (title, days) ->
                StudyTaskStore(context).save(StudyTask(semesterId = semester.id, title = title,
                    dueAt = today.plusDays(days).atTime(23, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()))
            }
            val overdue = database.studyTaskDao().forSemester(semester.id).first { it.title == "补交实验报告" }
            ActivityScenario.launch<StudyTasksActivity>(Intent(context, StudyTasksActivity::class.java).putExtra(StudyTasksActivity.EXTRA_PRIMARY_PAGE, true)).use { list ->
                onView(withId(R.id.btnTasksToday)).check(matches(withContentDescription("今天，2 项"))).perform(click())
                onView(withText("补交实验报告")).check(matches(isDisplayed()))
                screenshot("today")
                onView(withContentDescription("完成 补交实验报告")).perform(click())
                awaitRows(semester.id) { rows -> rows.first { it.id == overdue.id }.completedAt != null }
                onView(withId(R.id.btnTasksToday)).check(matches(withContentDescription("今天，1 项")))
                onView(withId(R.id.btnTasksCompleted)).check(matches(withText("已完成 1"))).perform(click())
                onView(withText("补交实验报告")).check(matches(isDisplayed()))
                screenshot("completed")
                list.recreate()
                onView(withId(R.id.btnTasksCompleted)).check(matches(isChecked()))
                onView(withContentDescription("重新打开 补交实验报告")).perform(click())
                awaitRows(semester.id) { rows -> rows.first { it.id == overdue.id }.completedAt == null }
                onView(withId(R.id.btnTasksToday)).check(matches(withContentDescription("今天，2 项"))).perform(click())
                onView(withText("补交实验报告")).check(matches(isDisplayed()))
            }
        }
    }

    @Test fun deadlineShortcutsRemainExplicitAndSaveExactTomorrowEndOfDay(): Unit = runBlocking {
        fixture { semester ->
            ActivityScenario.launch<StudyTaskEditorActivity>(Intent(context, StudyTaskEditorActivity::class.java)).use { editor ->
                onView(withId(R.id.btnTaskSave)).check(matches(isEnabled()))
                onView(withId(R.id.etTaskTitle)).perform(replaceText("提交课程报告")); androidx.test.espresso.Espresso.closeSoftKeyboard()
                onView(withId(R.id.btnTaskDate)).perform(scrollTo(), click())
                onView(withId(R.id.btnDateTomorrow)).perform(scrollTo(), click())
                onView(withId(R.id.btnTaskSave)).perform(click())
                onView(withId(R.id.tvTaskError)).check(matches(withText("请选择明确的截止日期和时间。")))
                onView(withId(R.id.btnTaskTime)).perform(scrollTo(), click())
                onView(withId(R.id.btnTimeEndOfDay)).perform(scrollTo(), click())
                editor.recreate()
                onView(withId(R.id.tvTaskTimeValue)).perform(scrollTo()).check(matches(withText("23:59")))
                onView(withId(R.id.btnTaskSave)).perform(click())
                awaitRows(semester.id) { rows -> rows.any { it.title == "提交课程报告" } }
            }
            val saved = database.studyTaskDao().forSemester(semester.id).single()
            assertEquals(StudyTaskRules.deadline(LocalDate.now().plusDays(1).toString(), "23:59"), saved.dueAt)
        }
    }
}
