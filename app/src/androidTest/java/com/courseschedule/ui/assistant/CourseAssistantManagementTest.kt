package com.courseschedule.ui.assistant

import android.content.Intent
import android.graphics.Bitmap
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.courseschedule.R
import com.courseschedule.data.AppDatabase
import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CourseAssistantManagementTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun editsQueriesDeletesAndUndoPreserveMetadataAndPendingSurvivesRotation(): Unit = runBlocking {
        withFixture { database, semester, scenario, model ->
            val original = database.courseDao().getCoursesBySemesterSync(semester.id)
            val math = original.first { it.courseName == "高等数学" }
            val patch = decode("""{"reply":"修改教室和提醒","courses":[],
                "updates":[{"id":${math.id},"classroom":"B201","reminderMinutes":10}]}""", semester, original)
            withContext(Dispatchers.Main) { model.receiveReply(patch, semester) }
            assertEquals(original, database.courseDao().getCoursesBySemesterSync(semester.id))
            onView(withId(R.id.btnSend)).check(matches(not(isEnabled())))
            onView(withId(R.id.pendingCard)).perform(scrollTo()).check(matches(isDisplayed()))
            screenshot("assistant-edit-preview")
            scenario.recreate()
            scenario.onActivity {
                assertSame(model, ViewModelProvider(it)[CourseAssistantViewModel::class.java])
                assertEquals(patch.updates, model.pendingChanges.value?.updates)
            }
            onView(withId(R.id.btnConfirmPending)).perform(scrollTo(), click())
            awaitIdle(model)
            val edited = database.courseDao().getCourseById(math.id)!!
            assertEquals(math.copy(classroom = "B201", reminderMinutes = 10), edited)
            onView(withId(R.id.pendingCard)).check(matches(withEffectiveVisibility(Visibility.GONE)))
            screenshot("assistant-edit-result")
            val rows = database.courseDao().getCoursesBySemesterSync(semester.id)
            withContext(Dispatchers.Main) {
                model.receiveReply(decode("""{"reply":"找到数学","courses":[],"queryIds":[${math.id}]}""",
                    semester, rows), semester)
            }
            assertEquals(rows, database.courseDao().getCoursesBySemesterSync(semester.id))
            assertTrue(model.canUndo.value == true)
            onView(withText(containsString("找到 1 项课程"))).check(matches(isDisplayed()))
            screenshot("assistant-query-result")
            onView(withText(R.string.assistant_undo_action)).perform(click())
            awaitIdle(model)
            assertEquals(math, database.courseDao().getCourseById(math.id))

            val deletion = decode("""{"reply":"删除数学","courses":[],"deleteIds":[${math.id}]}""",
                semester, original)
            withContext(Dispatchers.Main) { model.receiveReply(deletion, semester) }
            onView(withId(R.id.btnCancelPending)).perform(scrollTo(), click())
            awaitIdle(model)
            assertEquals(original, database.courseDao().getCoursesBySemesterSync(semester.id))
            withContext(Dispatchers.Main) { model.receiveReply(deletion, semester) }
            onView(withId(R.id.btnConfirmPending)).perform(scrollTo()).check(matches(withText(R.string.assistant_confirm_delete)))
            screenshot("assistant-delete-preview")
            onView(withId(R.id.btnConfirmPending)).perform(click())
            awaitIdle(model)
            assertNull(database.courseDao().getCourseById(math.id))
            assertEquals(original.filter { it.id != math.id }, database.courseDao().getCoursesBySemesterSync(semester.id))
            screenshot("assistant-delete-result")
            withContext(Dispatchers.Main) {
                model.receiveReply(decode("""{"reply":"撤销删除","courses":[],"undo":true}""",
                    semester, emptyList()), semester)
            }
            assertEquals(original, database.courseDao().getCoursesBySemesterSync(semester.id))
            assertFalse(model.canUndo.value == true)
            screenshot("assistant-delete-undone")
            withContext(Dispatchers.Main) {
                model.receiveReply(decode("""{"reply":"新增化学并设置提醒","courses":[
                    {"courseName":"化学","teacher":"","classroom":"C101","dayOfWeek":6,
                    "startSection":1,"endSection":2,"weeks":[1,2],"note":"","reminderMinutes":25}]}""",
                    semester, original), semester)
            }
            assertEquals(25, database.courseDao().getCoursesBySemesterSync(semester.id)
                .single { it.courseName == "化学" }.reminderMinutes)
            withContext(Dispatchers.Main) { model.undo() }
            awaitIdle(model)
            assertEquals(original, database.courseDao().getCoursesBySemesterSync(semester.id))
            val remainingWeeks = (1..16).filter { it != 2 }.joinToString(",")
            withContext(Dispatchers.Main) {
                model.receiveReply(decode("""{"reply":"仅取消第2周数学","courses":[],
                    "updates":[{"id":${math.id},"weeks":[$remainingWeeks]}]}""", semester, original), semester)
                model.confirmPending()
            }
            awaitIdle(model)
            val fragments = database.courseDao().getCoursesBySemesterSync(semester.id)
                .filter { it.courseName == "高等数学" }
            assertTrue(fragments.size > 1)
            assertTrue(fragments.any { it.id == math.id })
            assertEquals((1..16).filter { it != 2 }, fragments.flatMap { course ->
                (1..16).filter { com.courseschedule.domain.ScheduleRules.isCourseInWeek(course, it) }
            }.sorted())
            assertTrue(fragments.all {
                it.colorIndex == math.colorIndex && it.reminderMinutes == math.reminderMinutes &&
                    it.note == math.note && it.createTime == math.createTime
            })
            withContext(Dispatchers.Main) { model.undo() }
            awaitIdle(model)
            assertEquals(original, database.courseDao().getCoursesBySemesterSync(semester.id))
        }
    }

    @Test fun mixedConflictStaleTargetsAndLaterEditsNeverPartiallyOverwriteCourses(): Unit = runBlocking {
        withFixture { database, semester, _, model ->
            val original = database.courseDao().getCoursesBySemesterSync(semester.id)
            val math = original.first { it.courseName == "高等数学" }
            val english = original.first { it.courseName == "大学英语" }
            val mixed = decode("""{"reply":"安排更改",
                "courses":[{"courseName":"化学","teacher":"","classroom":"","dayOfWeek":6,
                "startSection":1,"endSection":1,"weeks":[1,2],"note":""}],
                "updates":[{"id":${math.id},"dayOfWeek":3,"startSection":3,"endSection":4}],
                "deleteIds":[${english.id}]}""", semester, original)
            withContext(Dispatchers.Main) { model.receiveReply(mixed, semester); model.confirmPending() }
            awaitIdle(model)
            assertEquals(original, database.courseDao().getCoursesBySemesterSync(semester.id))
            assertTrue(model.messages.value!!.last().content.contains("时间冲突"))

            val patch = decode("""{"reply":"改教室","courses":[],"updates":[{"id":${math.id},"classroom":"B302"}]}""",
                semester, original)
            withContext(Dispatchers.Main) { model.receiveReply(patch, semester) }
            val outsideEdit = math.copy(teacher = "其他页面编辑的老师")
            database.courseDao().updateCourse(outsideEdit)
            withContext(Dispatchers.Main) { model.confirmPending() }
            awaitIdle(model)
            assertEquals(outsideEdit, database.courseDao().getCourseById(math.id))
            assertTrue(model.messages.value!!.last().content.contains("已被修改"))

            val latest = database.courseDao().getCoursesBySemesterSync(semester.id)
            val fresh = decode("""{"reply":"改教室","courses":[],"updates":[{"id":${math.id},"classroom":"B302"}]}""",
                semester, latest)
            withContext(Dispatchers.Main) { model.receiveReply(fresh, semester); model.confirmPending() }
            awaitIdle(model)
            val after = database.courseDao().getCourseById(math.id)!!
            val laterEdit = after.copy(classroom = "C999", note = "之后的新修改")
            database.courseDao().updateCourse(laterEdit)
            withContext(Dispatchers.Main) { model.undo() }
            awaitIdle(model)
            assertEquals(laterEdit, database.courseDao().getCourseById(math.id))
            assertTrue(model.messages.value!!.last().content.contains("发生了变化"))

            val current = database.courseDao().getCoursesBySemesterSync(semester.id)
            val deletion = decode("""{"reply":"删除数学","courses":[],"deleteIds":[${math.id}]}""", semester, current)
            withContext(Dispatchers.Main) { model.receiveReply(deletion, semester); model.confirmPending() }
            awaitIdle(model)
            val newId = database.courseDao().insertCourse(laterEdit.copy(id = 0, courseName = "新占用时段的课程"))
            withContext(Dispatchers.Main) { model.undo() }
            awaitIdle(model)
            assertNull(database.courseDao().getCourseById(math.id))
            assertNotNull(database.courseDao().getCourseById(newId))
            assertTrue(model.messages.value!!.last().content.contains("时间冲突"))

            val otherId = database.semesterDao().insertSemester(Semester(name = "另一个学期",
                startDate = semester.startDate, totalWeeks = 16))
            try {
                val snapshot = database.courseDao().getCoursesBySemesterSync(semester.id)
                withContext(Dispatchers.Main) {
                    model.receiveReply(decode("""{"reply":"删除英语","courses":[],"deleteIds":[${english.id}]}""",
                        semester, snapshot), semester)
                }
                database.semesterDao().switchCurrentSemester(otherId)
                withContext(Dispatchers.Main) { model.confirmPending() }
                awaitIdle(model)
                assertEquals(english, database.courseDao().getCourseById(english.id))
                assertTrue(model.messages.value!!.last().content.contains("学期设置已变化"))
                database.semesterDao().switchCurrentSemester(semester.id)
                database.courseDao().deleteCourseById(newId)
                val foreign = laterEdit.copy(semesterId = otherId, courseName = "其他学期占用原编号的课程")
                database.courseDao().insertCourse(foreign)
                withContext(Dispatchers.Main) { model.undo() }
                awaitIdle(model)
                assertEquals(foreign, database.courseDao().getCourseById(math.id))
                assertTrue(database.courseDao().getCoursesBySemesterSync(semester.id).none { it.id == math.id })
                assertTrue(model.messages.value!!.last().content.contains("发生了变化"))
            } finally {
                database.semesterDao().switchCurrentSemester(semester.id)
                database.courseDao().deleteCoursesBySemester(otherId)
                database.semesterDao().getSemesterById(otherId)?.let { database.semesterDao().deleteSemester(it) }
            }
        }
    }

    private suspend fun withFixture(block: suspend (AppDatabase, Semester,
        ActivityScenario<CourseAssistantActivity>, CourseAssistantViewModel) -> Unit) {
        val database = AppDatabase.getDatabase(context)
        val previous = database.semesterDao().getCurrentSemesterSync()
        val store = AssistantConfigStore(context)
        val previousConfig = store.load()
        val id = database.semesterDao().insertSemester(Semester(name = "课程管理测试学期",
            startDate = System.currentTimeMillis(), totalWeeks = 16))
        database.semesterDao().switchCurrentSemester(id)
        val semester = database.semesterDao().getSemesterById(id)!!
        val math = Course(semesterId = id, courseName = "高等数学", teacher = "张老师", classroom = "A101",
            dayOfWeek = 2, startSection = 1, endSection = 2, startWeek = 1, endWeek = 16,
            colorIndex = 8, reminderMinutes = 15, note = "原来的备注", createTime = 1234)
        database.courseDao().insertCourses(listOf(math,
            math.copy(courseName = "大学英语", classroom = "A201", dayOfWeek = 5,
                startSection = 3, endSection = 4, colorIndex = 3, reminderMinutes = 10),
            math.copy(courseName = "大学物理", classroom = "A301", dayOfWeek = 3,
                startSection = 3, endSection = 4, colorIndex = 5, reminderMinutes = 5)))
        try {
            ActivityScenario.launch<CourseAssistantActivity>(Intent(context, CourseAssistantActivity::class.java)).use { scenario ->
                lateinit var model: CourseAssistantViewModel
                scenario.onActivity {
                    model = ViewModelProvider(it)[CourseAssistantViewModel::class.java]
                    model.configure(AssistantApiConfig("https://example.com/v1", "测试模型", "synthetic-test-value"), false)
                    model.messages.value = listOf(AssistantMessage("user", "帮我调整一下高等数学的课程安排"))
                }
                awaitIdle(model)
                block(database, semester, scenario, model)
            }
        } finally {
            try {
                if (previousConfig.apiKey.isBlank()) {
                    store.save(previousConfig.copy(apiKey = "synthetic-test-value"), false)
                } else store.save(previousConfig, true)
            } finally {
                database.courseDao().deleteCoursesBySemester(id)
                database.semesterDao().deleteSemester(semester)
                previous?.let { database.semesterDao().switchCurrentSemester(it.id) }
                com.courseschedule.utils.ReminderManager(context).restoreReminders()
            }
        }
    }

    private fun decode(payload: String, semester: Semester, contextCourses: List<Course>): AssistantCourseReply {
        val envelope = JsonObject().apply {
            add("choices", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("finish_reason", "stop")
                    add("message", JsonObject().apply { addProperty("content", payload) })
                })
            })
        }
        return AssistantCourseClient.parseResponse(envelope.toString(), semester.totalWeeks, contextCourses)
    }

    private fun awaitIdle(model: CourseAssistantViewModel) {
        val latch = CountDownLatch(1)
        val observer = Observer<Boolean> { if (!it) latch.countDown() }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { model.busy.observeForever(observer) }
        try { assertTrue(latch.await(15, TimeUnit.SECONDS)) }
        finally { instrumentation.runOnMainSync { model.busy.removeObserver(observer) } }
        instrumentation.waitForIdleSync()
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
