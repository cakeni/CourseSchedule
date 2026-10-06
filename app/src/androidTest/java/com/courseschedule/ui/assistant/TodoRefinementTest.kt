package com.courseschedule.ui.assistant

import android.animation.ValueAnimator
import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.courseschedule.R
import com.courseschedule.data.AppDatabase
import com.courseschedule.data.StudyTaskStore
import com.courseschedule.data.entity.Semester
import com.courseschedule.data.entity.StudyTask
import com.courseschedule.utils.ReminderManager
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

@RunWith(AndroidJUnit4::class)
class TodoRefinementTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val database by lazy { AppDatabase.getDatabase(context) }

    private suspend fun fixture(block: suspend (Semester) -> Unit) {
        val old = database.semesterDao().getCurrentSemesterSync()
        val draft = Semester(name = "2026 秋季学期", startDate = System.currentTimeMillis(), totalWeeks = 20)
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

    private fun settle() {
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).postDelayed({ done.countDown() }, 450L)
        assertTrue(done.await(3, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }

    private fun proof(name: String, frame: Bitmap? = null) {
        val suffix = InstrumentationRegistry.getArguments().getString("proofSuffix", "light")
        val bitmap = frame ?: instrumentation.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "todo-refinement-proof").apply { mkdirs() }
            .resolve("$name-$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun longTitlesRemainReadableAndFilterMovesContinuouslyToLatestSelection(): Unit = runBlocking {
        fixture { semester ->
            val titles = listOf("完成神经网络与深度学习导论第三章习题并整理课堂中的关键知识点", "提交光电效应实验报告", "准备期中考试", "整理课程提醒")
            listOf(0L, 1L, 4L, 12L).forEachIndexed { index, days ->
                StudyTaskStore(context).save(StudyTask(semesterId = semester.id, title = titles[index],
                    kind = listOf("homework", "report", "exam", "reminder")[index], courseName = listOf("神经网络与深度学习导论", "大学物理", "高等数学", "课程安排")[index],
                    dueAt = LocalDate.now().plusDays(days).atTime(23, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()))
            }
            ActivityScenario.launch<StudyTasksActivity>(Intent(context, StudyTasksActivity::class.java)
                .putExtra(StudyTasksActivity.EXTRA_PRIMARY_PAGE, true)).use { scenario ->
                settle()
                scenario.onActivity { activity ->
                    val row = activity.findViewById<ViewGroup>(R.id.taskRows).getChildAt(1) as ViewGroup
                    val title = row.getChildAt(0).findViewById<TextView>(R.id.tvTaskTitle)
                    val kind = row.getChildAt(0).findViewById<TextView>(R.id.tvTaskKind)
                    assertEquals(titles[0], title.text.toString())
                    assertTrue("Long titles must wrap instead of being squeezed by a type tag", title.lineCount >= 2)
                    assertTrue((0 until title.lineCount).all { title.layout.getEllipsisCount(it) == 0 })
                    assertTrue("Type tags belong below the title", kind.top + (kind.parent as View).top >= title.bottom)
                    assertEquals("4 项待完成", activity.findViewById<TextView>(R.id.tvTaskCount).text.toString())
                }
                proof("list")
                val moved = CountDownLatch(1)
                val centers = mutableListOf<Float>()
                var origin = 0f
                var target = 0f
                scenario.onActivity { activity ->
                    val filters = activity.findViewById<ViewGroup>(R.id.taskFilters)
                    val all = activity.findViewById<View>(R.id.btnTasksPending)
                    val today = activity.findViewById<View>(R.id.btnTasksToday)
                    origin = (all.left + all.right) / 2f
                    target = (today.left + today.right) / 2f
                    val color = activity.getColor(R.color.task_filter_selected)
                    val started = SystemClock.uptimeMillis()
                    val sampler = object : Choreographer.FrameCallback {
                        override fun doFrame(frameTimeNanos: Long) {
                            val bitmap = Bitmap.createBitmap(filters.width, filters.height, Bitmap.Config.ARGB_8888)
                            filters.background.draw(Canvas(bitmap))
                            val positions = (0 until bitmap.width).filter { bitmap.getPixel(it, bitmap.height / 2) == color }
                            if (positions.isNotEmpty()) centers += (positions.first() + positions.last()) / 2f
                            bitmap.recycle()
                            if (SystemClock.uptimeMillis() - started < 320L) Choreographer.getInstance().postFrameCallback(this) else moved.countDown()
                        }
                    }
                    today.performClick()
                    Choreographer.getInstance().postFrameCallback(sampler)
                }
                assertTrue(moved.await(3, TimeUnit.SECONDS))
                assertTrue("Selection must finish at the selected filter", kotlin.math.abs(centers.last() - target) < 2f)
                if (ValueAnimator.areAnimatorsEnabled()) assertTrue("The pill must slide rather than jump: $centers", centers.any { it > target + 3f && it < origin - 3f })
                proof("today")
                scenario.onActivity { activity ->
                    activity.findViewById<View>(R.id.btnTasksPending).performClick()
                    activity.findViewById<View>(R.id.btnTasksUpcoming).performClick()
                    activity.findViewById<View>(R.id.btnTasksToday).performClick()
                }
                settle()
                scenario.recreate()
                settle()
                scenario.onActivity { activity ->
                    assertTrue(activity.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnTasksToday).isChecked)
                    assertEquals("今天与逾期", activity.findViewById<TextView>(R.id.tvTaskListTitle).text.toString())
                    val filters = activity.findViewById<ViewGroup>(R.id.taskFilters)
                    val today = activity.findViewById<View>(R.id.btnTasksToday)
                    val bitmap = Bitmap.createBitmap(filters.width, filters.height, Bitmap.Config.ARGB_8888)
                    filters.background.draw(Canvas(bitmap))
                    assertEquals(activity.getColor(R.color.task_filter_selected), bitmap.getPixel((today.left + today.right) / 2, filters.height / 2))
                    bitmap.recycle()
                }
            }
        }
    }

    @Test fun completionFeedbackNeverCoversTaskTitleAndSettlesAfterBackgrounding(): Unit = runBlocking {
        assumeTrue(ValueAnimator.areAnimatorsEnabled())
        fixture { semester ->
            val task = StudyTaskStore(context).save(StudyTask(semesterId = semester.id, title = "提交神经网络课程实验报告并完成结果分析", kind = "report",
                courseName = "神经网络与深度学习导论", dueAt = System.currentTimeMillis() + 86400000L))
            ActivityScenario.launch<StudyTasksActivity>(Intent(context, StudyTasksActivity::class.java)
                .putExtra(StudyTasksActivity.EXTRA_PRIMARY_PAGE, true)).use { scenario ->
                settle()
                val captured = CountDownLatch(1)
                var titleUncovered = false
                var frame: Bitmap? = null
                scenario.onActivity { activity ->
                    val row = activity.findViewById<ViewGroup>(R.id.taskRows).findViewWithTag<ViewGroup>(task.id)
                    val title = row.findViewById<TextView>(R.id.tvTaskTitle)
                    val kind = row.findViewById<TextView>(R.id.tvTaskKind)
                    val bounds = Rect(0, 0, title.width, title.height).also { row.offsetDescendantRectToMyCoords(title, it) }
                    fun titlePixels(): Bitmap {
                        val bitmap = Bitmap.createBitmap(row.width, row.height, Bitmap.Config.ARGB_8888)
                        row.draw(Canvas(bitmap))
                        return Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width(), bounds.height()).also { bitmap.recycle() }
                    }
                    val before = titlePixels()
                    var statusAt = 0L
                    var frames = 0
                    val sampler = object : Choreographer.FrameCallback {
                        override fun doFrame(frameTimeNanos: Long) {
                            frames++
                            if (kind.text.toString() == "已完成" && statusAt == 0L) statusAt = SystemClock.uptimeMillis()
                            if (statusAt > 0L && SystemClock.uptimeMillis() - statusAt >= 80L && row.alpha == 1f) {
                                val after = titlePixels()
                                titleUncovered = before.sameAs(after)
                                after.recycle(); before.recycle()
                                val decor = activity.window.decorView
                                frame = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) }
                                captured.countDown()
                            } else if (frames < 180 && row.isAttachedToWindow) Choreographer.getInstance().postFrameCallback(this)
                            else { before.recycle(); captured.countDown() }
                        }
                    }
                    row.findViewById<CheckBox>(R.id.checkTaskDone).performClick()
                    Choreographer.getInstance().postFrameCallback(sampler)
                }
                assertTrue(captured.await(5, TimeUnit.SECONDS))
                assertTrue("Task title pixels must remain unchanged during completion feedback", titleUncovered)
                proof("completion-local", frame!!)
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                settle()
                assertNotNull(database.studyTaskDao().find(task.id)?.completedAt)
                scenario.onActivity { activity ->
                    assertEquals("0 项待完成", activity.findViewById<TextView>(R.id.tvTaskCount).text.toString())
                    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.taskEmpty).visibility)
                }
                onView(withId(R.id.btnTasksCompleted)).perform(click())
                settle()
                proof("completed")
            }
        }
    }
}
