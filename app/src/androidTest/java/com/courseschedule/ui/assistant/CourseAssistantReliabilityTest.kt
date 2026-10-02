package com.courseschedule.ui.assistant

import android.app.Application
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.courseschedule.data.AppDatabase
import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CourseAssistantReliabilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val stores = mutableListOf<ViewModelStore>()

    @Test fun originalConflictEditsAndDeleteUndoSurviveRestartButNewConflictsDisableUndo(): Unit = runBlocking {
        withFixture { database, semester ->
            val math = Course(semesterId = semester.id, courseName = "高等数学", classroom = "A101", dayOfWeek = 3,
                startSection = 1, endSection = 2, startWeek = 1, endWeek = 16)
            val first = math.copy(id = database.courseDao().insertCourse(math))
            val second = math.copy(id = database.courseDao().insertCourse(math.copy(courseName = "英语")), courseName = "英语")
            var model = model()
            val updated = first.copy(note = "新备注", reminderMinutes = 10)
            withContext(Dispatchers.Main) {
                model.receiveReply(AssistantCourseReply("只改备注和提醒", emptyList(), updates = listOf(AssistantCourseUpdate(first, listOf(updated)))), semester)
                model.confirmPending()
            }
            awaitIdle(model)
            assertEquals(updated, database.courseDao().getCourseById(first.id))
            withContext(Dispatchers.Main) { model.undo() }
            awaitIdle(model)
            assertEquals(first, database.courseDao().getCourseById(first.id))
            withContext(Dispatchers.Main) {
                model.receiveReply(AssistantCourseReply("删除数学", emptyList(), deletions = listOf(first)), semester)
                model.confirmPending()
            }
            awaitIdle(model)
            assertNull(database.courseDao().getCourseById(first.id))
            withContext(Dispatchers.Main) { stores.last().clear() }
            model = model()
            assertTrue(model.canUndo.value == true)
            withContext(Dispatchers.Main) { model.undo() }
            awaitIdle(model)
            assertEquals(first, database.courseDao().getCourseById(first.id))
            assertEquals(second, database.courseDao().getCourseById(second.id))
            withContext(Dispatchers.Main) {
                model.receiveReply(AssistantCourseReply("删除数学", emptyList(), deletions = listOf(first)), semester)
                model.confirmPending()
            }
            awaitIdle(model)
            val newId = database.courseDao().insertCourse(first.copy(id = 0, courseName = "后添加的新课程"))
            withContext(Dispatchers.Main) { model.refreshSemester() }
            awaitIdle(model)
            assertFalse(model.canUndo.value == true)
            assertTrue(model.undoUnavailableReason.value!!.contains("时间冲突"))
            database.courseDao().deleteCourseById(newId)
            withContext(Dispatchers.Main) { model.refreshSemester() }
            awaitIdle(model)
            assertTrue(model.canUndo.value == true)
            withContext(Dispatchers.Main) { model.undo() }
            awaitIdle(model)
            assertEquals(first, database.courseDao().getCourseById(first.id))
        }
    }

    @Test fun simpleLocalQueriesNeedNoApiAndNeverWriteCourses(): Unit = runBlocking {
        withFixture { database, semester ->
            val course = Course(semesterId = semester.id, courseName = "本地查询课", dayOfWeek = LocalDate.now().dayOfWeek.value,
                startSection = 3, endSection = 4, startWeek = 1, endWeek = 16)
            val saved = course.copy(id = database.courseDao().insertCourse(course))
            val model = model()
            withContext(Dispatchers.Main) { model.send("今天有哪些课？", 12) }
            awaitIdle(model)
            assertTrue(model.messages.value!!.last().content.contains("找到 1 项课程"))
            assertEquals(listOf(saved), database.courseDao().getCoursesBySemesterSync(semester.id))
            withContext(Dispatchers.Main) { model.send("明天有哪些课？", 12) }
            awaitIdle(model)
            assertTrue(model.messages.value!!.last().content.contains("没有找到"))
            assertFalse(model.canRetry.value == true)
            assertEquals(listOf(saved), database.courseDao().getCoursesBySemesterSync(semester.id))
        }
    }

    private suspend fun model(): CourseAssistantViewModel {
        val model = withContext(Dispatchers.Main) {
            CourseAssistantViewModel(context.applicationContext as Application) { error("Local queries must not create an API client") }.also {
                stores += ViewModelStore().apply { put("model", it) }
            }
        }
        awaitIdle(model)
        return model
    }

    private suspend fun withFixture(block: suspend (AppDatabase, Semester) -> Unit) {
        val database = AppDatabase.getDatabase(context)
        val previous = database.semesterDao().getCurrentSemesterSync()
        val start = LocalDate.now().with(java.time.DayOfWeek.MONDAY).minusWeeks(3).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val id = database.semesterDao().insertSemester(Semester(name = "离线回归学期", startDate = start, totalWeeks = 16))
        database.semesterDao().switchCurrentSemester(id)
        try { block(database, database.semesterDao().getSemesterById(id)!!) }
        finally {
            withContext(Dispatchers.Main) { stores.forEach { it.clear() }; stores.clear() }
            database.courseDao().deleteCoursesBySemester(id)
            database.semesterDao().getSemesterById(id)?.let { database.semesterDao().deleteSemester(it) }
            previous?.let { database.semesterDao().switchCurrentSemester(it.id) }
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
}
