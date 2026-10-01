package com.courseschedule.ui.assistant

import android.app.Application
import android.content.Intent
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.courseschedule.R
import com.courseschedule.data.AppDatabase
import com.courseschedule.data.entity.Semester
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AssistantConversationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val stores = mutableListOf<ViewModelStore>()

    @Test fun staleAndDamagedPlansAreDiscardedAndReceiptFailureRollsBackMutation(): Unit = runBlocking {
        withFixture { database, semester ->
            var model = model()
            val course = com.courseschedule.data.entity.Course(courseName = "高等数学", classroom = "A101",
                dayOfWeek = 3, startSection = 1, endSection = 2, startWeek = 1, endWeek = 16, semesterId = semester.id)
            val original = course.copy(id = database.courseDao().insertCourse(course))
            val patch = AssistantCourseReply("改教室", emptyList(), updates = listOf(
                AssistantCourseUpdate(original, listOf(original.copy(classroom = "B201")))))
            withContext(Dispatchers.Main) { model.receiveReply(patch, semester); stores.last().clear() }
            val outside = original.copy(teacher = "其他页面修改的老师")
            database.courseDao().updateCourse(outside)
            model = model()
            assertNull(model.pendingChanges.value)
            assertTrue(model.messages.value!!.last().content.contains("方案已过期"))
            assertEquals(outside, database.courseDao().getCourseById(original.id))
            val dao = database.assistantConversationDao()
            val id = model.conversationId!!
            withContext(Dispatchers.Main) {
                model.receiveReply(AssistantCourseReply("删除", emptyList(), deletions = listOf(outside)), semester)
            }
            val row = dao.conversation(id)!!
            val count = dao.messageCount(id)
            assertEquals(1, dao.updateConversation(id, "另一个页面已更新", row.updatedAt, row.stateJson, row.revision))
            withContext(Dispatchers.Main) { model.confirmPending() }
            awaitIdle(model)
            assertEquals(outside, database.courseDao().getCourseById(original.id))
            assertEquals(count, dao.messageCount(id))
            withContext(Dispatchers.Main) { stores.last().clear() }
            val latest = dao.conversation(id)!!
            assertEquals(1, dao.updateConversation(id, latest.title, latest.updatedAt, "{broken", latest.revision))
            model = model()
            assertNull(model.pendingChanges.value)
            assertFalse(model.canUndo.value == true)
            assertFalse(model.canRetry.value == true)
            assertTrue(model.messages.value!!.last().content.contains("操作状态无法读取"))
            assertEquals(count + 1, dao.messageCount(id))
            assertEquals(outside, database.courseDao().getCourseById(original.id))
        }
    }

    @Test fun historyContinuesAfterExitAndDeletingChatKeepsCourses(): Unit = runBlocking {
        withFixture { database, semester ->
            val requests = mutableListOf<String>()
            var round = 0
            val factory = {
                AssistantCourseClient { FakeConnection(if (round++ == 0)
                    envelope("""{"reply":"这门课叫什么？","courses":[]}""") else
                    envelope("""{"reply":"准备添加","courses":[{"courseName":"高等数学","teacher":"张老师",
                    "classroom":"A101","dayOfWeek":3,"startSection":1,"endSection":2,
                    "weeks":[1,2,3,4],"note":"保留备注","reminderMinutes":15}]}"""), capture = requests) }
            }
            var model = model(factory)
            withContext(Dispatchers.Main) { model.send("周三早八添加一门课", 1) }
            awaitIdle(model)
            assertTrue(database.courseDao().getCoursesBySemesterSync(semester.id).isEmpty())
            val id = model.conversationId!!
            withContext(Dispatchers.Main) { model.updateDraft("高等数学，连续两节"); stores.last().clear() }
            model = model(factory)
            assertEquals(id, model.conversationId)
            assertEquals("高等数学，连续两节", model.draft.value)
            assertEquals(2, model.messages.value!!.size)
            withContext(Dispatchers.Main) { model.send(model.draft.value!!, 1) }
            awaitIdle(model)
            val history = JsonParser.parseString(requests.last()).asJsonObject.getAsJsonArray("messages")
            assertTrue(history.toString().contains("周三早八添加一门课"))
            assertTrue(history.toString().contains("这门课叫什么"))
            assertTrue(history.toString().contains("高等数学，连续两节"))
            val saved = database.courseDao().getCoursesBySemesterSync(semester.id).single()
            assertEquals(3, saved.dayOfWeek)
            assertEquals(15, saved.reminderMinutes)
            assertFalse(model.canRetry.value == true)
            assertTrue(model.canUndo.value == true)
            withContext(Dispatchers.Main) { model.updateDraft("下一条草稿"); stores.last().clear() }

            ActivityScenario.launch<CourseAssistantActivity>(Intent(context, CourseAssistantActivity::class.java)).use { scenario ->
                scenario.onActivity { model = ViewModelProvider(it)[CourseAssistantViewModel::class.java] }
                awaitIdle(model)
                assertEquals(id, model.conversationId)
                assertTrue(model.canUndo.value == true)
                onView(withId(R.id.etMessage)).check(matches(withText("下一条草稿")))
                onView(withContentDescription(R.string.assistant_history)).perform(click())
                onView(withText(R.string.assistant_history)).check(matches(isDisplayed()))
                val historyImage = instrumentation.uiAutomation.takeScreenshot()
                java.io.File(context.getExternalFilesDir(null), "assistant-conversation-history.png").outputStream().use {
                    historyImage.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                historyImage.recycle()
                onView(withText(R.string.cancel)).perform(click())
                withContext(Dispatchers.Main) { model.newConversation() }
                awaitIdle(model)
                assertNotEquals(id, model.conversationId)
                assertTrue(model.messages.value!!.isEmpty())
                assertEquals("", model.draft.value)
                assertFalse(model.canUndo.value == true)
                withContext(Dispatchers.Main) { model.openHistory(id) }
                awaitIdle(model)
                assertEquals("下一条草稿", model.draft.value)
                assertEquals(4, model.messages.value!!.size)
                withContext(Dispatchers.Main) { model.deleteConversation() }
                awaitIdle(model)
                assertNull(database.assistantConversationDao().conversation(id))
                assertEquals(0, database.assistantConversationDao().messageCount(id))
                assertEquals(saved, database.courseDao().getCourseById(saved.id))
            }
        }
    }

    @Test fun failedRequestRetriesExplicitlyAndCancellationNeverExecutesLateResponse(): Unit = runBlocking {
        withFixture { database, semester ->
            var attempts = 0
            val reply = envelope("""{"reply":"添加英语","courses":[{"courseName":"大学英语","teacher":"",
                "classroom":"","dayOfWeek":3,"startSection":3,"endSection":4,"weeks":[1,2],"note":""}]}""")
            val factory = { AssistantCourseClient { FakeConnection(reply, status = if (attempts++ == 0) 429 else 200) } }
            var model = model(factory)
            withContext(Dispatchers.Main) { model.send("周三第3-4节添加大学英语", 1) }
            awaitIdle(model)
            assertTrue(model.canRetry.value == true)
            assertTrue(model.messages.value!!.last().content.contains("额度不足"))
            assertTrue(database.courseDao().getCoursesBySemesterSync(semester.id).isEmpty())
            withContext(Dispatchers.Main) { stores.last().clear() }
            model = model(factory)
            assertTrue(model.canRetry.value == true)
            assertEquals(1, attempts)
            withContext(Dispatchers.Main) { model.retry() }
            awaitIdle(model)
            assertEquals(2, attempts)
            assertEquals(1, database.courseDao().getCoursesBySemesterSync(semester.id).size)
            assertFalse(model.canRetry.value == true)
            withContext(Dispatchers.Main) { model.retry() }
            assertEquals(2, attempts)
            withContext(Dispatchers.Main) { stores.last().clear() }

            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            model = model { AssistantCourseClient { FakeConnection(reply, started = started, release = release) } }
            withContext(Dispatchers.Main) { model.send("再添加一门课", 1) }
            assertTrue(started.await(15, TimeUnit.SECONDS))
            withContext(Dispatchers.Main) { model.stopRequest() }
            awaitIdle(model)
            assertTrue(model.messages.value!!.last().content.contains("请求已停止"))
            assertTrue(model.canRetry.value == true)
            assertEquals(1, database.courseDao().getCoursesBySemesterSync(semester.id).size)
            assertEquals(0, release.count)
            withContext(Dispatchers.Main) { stores.last().clear() }
            val restored = model(factory)
            assertTrue(restored.canRetry.value == true)
            assertEquals(2, attempts)
        }
    }

    @Test fun semesterIsolationOlderHistoryAndInterruptedStateDoNotReplayOperations(): Unit = runBlocking {
        withFixture { database, semester ->
            var model = model()
            val id = model.conversationId!!
            val dao = database.assistantConversationDao()
            repeat(75) { index -> dao.insertMessage(com.courseschedule.data.entity.AssistantChatMessage(
                conversationId = id, role = if (index % 2 == 0) "user" else "assistant", content = "历史消息$index")) }
            val state = AssistantConversationState(retryRequest = AssistantRetryRequest("周三加课", 1), requestRunning = true)
            val row = dao.conversation(id)!!
            assertEquals(1, dao.updateConversation(id, "周三历史", 1234, com.google.gson.Gson().toJson(state), row.revision))
            withContext(Dispatchers.Main) { stores.last().clear() }
            model = model()
            assertTrue(model.canRetry.value == true)
            assertTrue(model.messages.value!!.last().content.contains("上次请求已中断"))
            assertEquals(60, model.messages.value!!.size)
            assertTrue(model.hasOlderMessages.value == true)
            withContext(Dispatchers.Main) { model.loadOlderMessages() }
            awaitIdle(model)
            assertEquals(76, model.messages.value!!.size)
            assertEquals("历史消息0", model.messages.value!!.first().content)
            assertFalse(model.hasOlderMessages.value == true)
            assertTrue(database.courseDao().getCoursesBySemesterSync(semester.id).isEmpty())
            val otherId = database.semesterDao().insertSemester(Semester(name = "另一学期", startDate = 2000))
            try {
                database.semesterDao().switchCurrentSemester(otherId)
                withContext(Dispatchers.Main) { model.refreshSemester() }
                awaitIdle(model)
                assertTrue(model.messages.value!!.isEmpty())
                assertFalse(model.canRetry.value == true)
                assertTrue(model.history().all { it.semesterId == otherId })
                database.semesterDao().switchCurrentSemester(semester.id)
                withContext(Dispatchers.Main) { model.refreshSemester() }
                awaitIdle(model)
                assertEquals(id, model.conversationId)
                assertEquals(60, model.messages.value!!.size)
                assertTrue(model.canRetry.value == true)
            } finally {
                database.semesterDao().switchCurrentSemester(semester.id)
                database.semesterDao().deleteSemester(database.semesterDao().getSemesterById(otherId)!!)
            }
        }
    }

    private suspend fun model(factory: () -> AssistantCourseClient = { AssistantCourseClient() }): CourseAssistantViewModel {
        val value = withContext(Dispatchers.Main) {
            CourseAssistantViewModel(context.applicationContext as Application, factory).also {
                stores += ViewModelStore().apply { put("model", it) }
                it.configure(AssistantApiConfig("https://example.com/v1", "测试模型", "synthetic-test-value"), false)
            }
        }
        awaitIdle(value)
        return value
    }

    private suspend fun withFixture(block: suspend (AppDatabase, Semester) -> Unit) {
        val database = AppDatabase.getDatabase(context)
        val previous = database.semesterDao().getCurrentSemesterSync()
        val configStore = AssistantConfigStore(context)
        val previousConfig = configStore.load()
        val id = database.semesterDao().insertSemester(Semester(name = "聊天记录测试学期", startDate = 1000, totalWeeks = 16))
        database.semesterDao().switchCurrentSemester(id)
        val semester = database.semesterDao().getSemesterById(id)!!
        try { block(database, semester) }
        finally {
            withContext(Dispatchers.Main) { stores.forEach { it.clear() }; stores.clear() }
            database.courseDao().deleteCoursesBySemester(id)
            database.semesterDao().deleteSemester(semester)
            previous?.let { database.semesterDao().switchCurrentSemester(it.id) }
            configStore.save(previousConfig.copy(apiKey = previousConfig.apiKey.ifBlank { "synthetic-test-value" }),
                previousConfig.apiKey.isNotBlank())
            com.courseschedule.utils.ReminderManager(context).restoreReminders()
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

    private fun envelope(payload: String): String = com.google.gson.JsonObject().apply {
        add("choices", JsonParser.parseString("""[{"finish_reason":"stop","message":{}}]"""))
        getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message").addProperty("content", payload)
    }.toString()

    private class FakeConnection(private val body: String, private val status: Int = 200,
        private val capture: MutableList<String>? = null, private val started: CountDownLatch? = null,
        private val release: CountDownLatch? = null) : HttpURLConnection(URL("https://example.com/v1/chat/completions")) {
        private val request = ByteArrayOutputStream()
        override fun connect() = Unit
        override fun disconnect() { release?.countDown() }
        override fun usingProxy() = false
        override fun getOutputStream() = request
        override fun getResponseCode() = status
        override fun getInputStream(): ByteArrayInputStream {
            capture?.add(request.toString("UTF-8"))
            started?.countDown()
            release?.await(15, TimeUnit.SECONDS)
            return ByteArrayInputStream(body.toByteArray())
        }
    }
}
