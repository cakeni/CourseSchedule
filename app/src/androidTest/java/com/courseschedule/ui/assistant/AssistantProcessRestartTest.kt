package com.courseschedule.ui.assistant

import android.content.Intent
import android.graphics.Bitmap
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.courseschedule.data.AppDatabase
import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Run these two stages separately with am force-stop between them. No external API calls. */
@RunWith(AndroidJUnit4::class)
class AssistantProcessRestartTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val fixtureFile = File(context.getExternalFilesDir(null), "assistant-restart-fixture.json")
    private data class Fixture(val previousSemesterId: Long?, val semester: Semester,
        val original: Course, val conversationId: String)

    @Test fun prepareBeforeProcessKill(): Unit = runBlocking {
        val database = AppDatabase.getDatabase(context)
        val previous = database.semesterDao().getCurrentSemesterSync()
        val id = database.semesterDao().insertSemester(Semester(name = "重启恢复测试学期", startDate = 1000, totalWeeks = 16))
        database.semesterDao().switchCurrentSemester(id)
        val semester = database.semesterDao().getSemesterById(id)!!
        val course = Course(courseName = "高等数学", classroom = "A101", teacher = "张老师",
            dayOfWeek = 3, startSection = 1, endSection = 2, startWeek = 1, endWeek = 16,
            semesterId = id, colorIndex = 8, reminderMinutes = 15, note = "原备注", createTime = 1234)
        val original = course.copy(id = database.courseDao().insertCourse(course))
        ActivityScenario.launch<CourseAssistantActivity>(Intent(context, CourseAssistantActivity::class.java)).use { scenario ->
            lateinit var model: CourseAssistantViewModel
            scenario.onActivity { model = ViewModelProvider(it)[CourseAssistantViewModel::class.java] }
            awaitIdle(model)
            withContext(Dispatchers.Main) {
                model.receiveReply(AssistantCourseReply("新增化学", listOf(course.copy(id = 0,
                    courseName = "化学", dayOfWeek = 5, startSection = 3, endSection = 4))), semester)
                model.confirmPending()
            }
            awaitIdle(model)
            withContext(Dispatchers.Main) {
                model.receiveReply(AssistantCourseReply("改教室", emptyList(), updates = listOf(
                    AssistantCourseUpdate(original, listOf(original.copy(classroom = "B201"))))), semester)
                model.updateDraft("接下来查周三的课")
            }
            assertTrue(model.canUndo.value == true)
            assertNotNull(model.pendingChanges.value)
            fixtureFile.writeText(Gson().toJson(Fixture(previous?.id, semester, original, model.conversationId!!)))
            assertEquals(original, database.courseDao().getCourseById(original.id))
        }
    }

    @Test fun verifyAfterProcessKill(): Unit = runBlocking {
        val fixture = Gson().fromJson(fixtureFile.readText(), Fixture::class.java)
        val database = AppDatabase.getDatabase(context)
        try {
            var model: CourseAssistantViewModel? = null
            ActivityScenario.launch<CourseAssistantActivity>(Intent(context, CourseAssistantActivity::class.java)).use { scenario ->
                scenario.onActivity { model = ViewModelProvider(it)[CourseAssistantViewModel::class.java] }
                awaitIdle(model!!)
                assertEquals(fixture.conversationId, model!!.conversationId)
                assertEquals("接下来查周三的课", model!!.draft.value)
                assertEquals(3, model!!.messages.value!!.size)
                assertNotNull(model!!.pendingChanges.value)
                assertTrue(model!!.canUndo.value == true)
                assertFalse(model!!.canRetry.value == true)
                assertEquals(fixture.original, database.courseDao().getCourseById(fixture.original.id))
                screenshot("assistant-restored-pending")
                withContext(Dispatchers.Main) {
                    model!!.updateDraft("")
                    model!!.confirmPending(); model!!.confirmPending()
                }
                awaitIdle(model!!)
                assertEquals(fixture.original.copy(classroom = "B201"), database.courseDao().getCourseById(fixture.original.id))
                assertNull(model!!.pendingChanges.value)
                assertEquals(4, database.assistantConversationDao().messageCount(fixture.conversationId))
            }
            ActivityScenario.launch<CourseAssistantActivity>(Intent(context, CourseAssistantActivity::class.java)).use { scenario ->
                scenario.onActivity { model = ViewModelProvider(it)[CourseAssistantViewModel::class.java] }
                awaitIdle(model!!)
                assertNull(model!!.pendingChanges.value)
                assertTrue(model!!.canUndo.value == true)
                assertEquals(2, database.courseDao().getCoursesBySemesterSync(fixture.semester.id).size)
                screenshot("assistant-restored-history")
                withContext(Dispatchers.Main) { model!!.undo() }
                awaitIdle(model!!)
                assertEquals(fixture.original, database.courseDao().getCourseById(fixture.original.id))
                assertFalse(model!!.canUndo.value == true)
            }
        } finally {
            database.courseDao().deleteCoursesBySemester(fixture.semester.id)
            database.semesterDao().deleteSemester(fixture.semester)
            fixture.previousSemesterId?.let { database.semesterDao().switchCurrentSemester(it) }
            com.courseschedule.utils.ReminderManager(context).restoreReminders()
            fixtureFile.delete()
        }
    }

    private fun awaitIdle(model: CourseAssistantViewModel) {
        val latch = CountDownLatch(1)
        val observer = Observer<Boolean> { if (!it) latch.countDown() }
        instrumentation.runOnMainSync { model.busy.observeForever(observer) }
        try { assertTrue(latch.await(15, TimeUnit.SECONDS)) }
        finally { instrumentation.runOnMainSync { model.busy.removeObserver(observer) } }
        instrumentation.waitForIdleSync()
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }
}
