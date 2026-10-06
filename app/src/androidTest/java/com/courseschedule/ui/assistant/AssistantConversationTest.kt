package com.courseschedule.ui.assistant

import android.app.Application
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.EditText
import androidx.core.widget.NestedScrollView
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
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class AssistantConversationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val stores = mutableListOf<ViewModelStore>()

    @Test fun drawerNavigationAndPromptSelectionKeepDraftsAndNeverWriteCourses(): Unit = runBlocking {
        withFixture { database, semester ->
            ActivityScenario.launch<CourseAssistantActivity>(Intent(context, CourseAssistantActivity::class.java)).use { scenario ->
                lateinit var model: CourseAssistantViewModel
                scenario.onActivity { model = ViewModelProvider(it)[CourseAssistantViewModel::class.java] }
                awaitIdle(model)
                val firstId = model.conversationId!!
                onView(withId(R.id.btnSend)).check(matches(org.hamcrest.Matchers.not(isEnabled())))
                onView(withId(R.id.btnQuickPrompts)).check(matches(isDisplayed()))
                saveHistoryScreenshot("assistant-layout-empty")
                onView(withId(R.id.btnQuickPrompts)).perform(click())
                onView(withId(R.id.btnPromptQuery)).perform(click())
                onView(withId(R.id.etMessage)).check(matches(withText(R.string.assistant_example_query)))
                onView(withId(R.id.btnSend)).check(matches(isEnabled()))
                androidx.test.espresso.Espresso.closeSoftKeyboard()
                val draft = model.draft.value
                onView(withContentDescription(R.string.assistant_history)).perform(click())
                onView(withId(R.id.btnDrawerNewConversation)).check(matches(isDisplayed()))
                androidx.test.espresso.Espresso.pressBack()
                onView(withId(R.id.etMessage)).check(matches(withText(draft)))
                scenario.onActivity { assertFalse(it.isFinishing) }
                onView(withContentDescription(R.string.assistant_history)).perform(click())
                onView(withId(R.id.btnDrawerNewConversation)).perform(click())
                awaitIdle(model)
                assertNotEquals(firstId, model.conversationId)
                onView(withId(R.id.btnSend)).check(matches(org.hamcrest.Matchers.not(isEnabled())))
                withContext(Dispatchers.Main) { model.openHistory(firstId) }
                awaitIdle(model)
                onView(withId(R.id.etMessage)).check(matches(withText(draft)))
                assertTrue(database.courseDao().getCoursesBySemesterSync(semester.id).isEmpty())
            }
        }
    }

    @Test fun composerAndDrawerRemainReachableWithKeyboardAndDarkTheme(): Unit = runBlocking {
        withFixture { database, semester ->
            val previousMode = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode()
            try {
                ActivityScenario.launch<CourseAssistantActivity>(Intent(context, CourseAssistantActivity::class.java)).use { scenario ->
                    lateinit var model: CourseAssistantViewModel
                    scenario.onActivity { model = ViewModelProvider(it)[CourseAssistantViewModel::class.java] }
                    awaitIdle(model)
                    onView(withId(R.id.etMessage)).perform(click(), androidx.test.espresso.action.ViewActions.replaceText("先保留这条草稿"), showKeyboard())
                    awaitView(R.id.starterContent) { it.visibility == View.GONE }
                    onView(withId(R.id.btnSend)).check(matches(isDisplayed()))
                    saveHistoryScreenshot("assistant-layout-keyboard")
                    onView(withContentDescription(R.string.assistant_history)).perform(click())
                    onView(withId(R.id.etHistorySearch)).perform(click(), showKeyboard())
                    awaitView(R.id.historyFooter) { it.visibility == View.GONE }
                    onView(withId(R.id.historyResults)).check(matches(isDisplayed()))
                    saveHistoryScreenshot("assistant-layout-search-keyboard")
                    androidx.test.espresso.Espresso.closeSoftKeyboard()
                    onView(withId(R.id.btnCloseHistory)).perform(click())
                    withContext(Dispatchers.Main) { androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) }
                    instrumentation.waitForIdleSync()
                    onView(withId(R.id.etMessage)).check(matches(withText("先保留这条草稿")))
                    saveHistoryScreenshot("assistant-layout-dark")
                    onView(withContentDescription(R.string.assistant_history)).perform(click())
                    onView(withId(R.id.btnConfigureApi)).check(matches(isDisplayed()))
                    saveHistoryScreenshot("assistant-layout-drawer-dark")
                    assertTrue(database.courseDao().getCoursesBySemesterSync(semester.id).isEmpty())
                }
            } finally {
                withContext(Dispatchers.Main) { androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(previousMode) }
            }
        }
    }

    private fun awaitView(id: Int, condition: (View) -> Boolean) {
        val latch = CountDownLatch(1)
        onView(withId(id)).check { view, error ->
            if (error != null) throw error
            if (condition(view)) latch.countDown() else {
                view.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        if (condition(view)) {
                            view.viewTreeObserver.removeOnGlobalLayoutListener(this)
                            latch.countDown()
                        }
                    }
                })
            }
        }
        assertTrue("界面状态未更新：$id", latch.await(15, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }

    private fun showKeyboard() = object : androidx.test.espresso.ViewAction {
        override fun getConstraints() = isDisplayed()
        override fun getDescription() = "Open keyboard and await its insets"
        override fun perform(controller: androidx.test.espresso.UiController, view: View) {
            view.requestFocus()
            (view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showSoftInput(view, 0)
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            while (androidx.core.view.ViewCompat.getRootWindowInsets(view)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) != true &&
                android.os.SystemClock.uptimeMillis() < deadline) controller.loopMainThreadForAtLeast(100)
            assertTrue("键盘没有打开", androidx.core.view.ViewCompat.getRootWindowInsets(view)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true)
        }
    }

    @Test fun historySearchMatchesVisibleTextTitlesAndLiteralSymbolsWithinCurrentSemester(): Unit = runBlocking {
        withFixture { database, semester ->
            val model = model()
            val dao = database.assistantConversationDao()
            val id = model.conversationId!!
            val text = "统计学%_复习 \"原教室\"\\讲义\n教室 B901"
            val queryId = dao.insertMessage(com.courseschedule.data.entity.AssistantChatMessage(
                conversationId = id, role = "assistant", kind = "query", content = AssistantQueryMessage.encode(text, listOf(424242))))
            dao.insertMessage(com.courseschedule.data.entity.AssistantChatMessage(
                conversationId = id, role = "user", content = "ABC%_\\' 课程调整"))
            assertEquals(queryId, model.searchHistory("统计学%_").hits.single().messageId)
            assertEquals(queryId, model.searchHistory("\"原教室\"\\讲义").hits.single().messageId)
            assertEquals(1, model.searchHistory("abc%_\\'").hits.size)
            assertTrue(model.searchHistory("courseIds").hits.isEmpty())
            assertTrue(model.searchHistory("424242").hits.isEmpty())
            assertTrue(model.searchHistory("不存在的关键词").hits.isEmpty())
            val titleId = "title-${semester.id}"
            dao.insertConversation(com.courseschedule.data.entity.AssistantConversation(titleId, semester.id,
                "只有标题的实验室", 1234))
            assertEquals(titleId, model.searchHistory("实验室").hits.single().conversationId)
            assertNull(model.searchHistory("实验室").hits.single().messageId)
            val otherSemesterId = database.semesterDao().insertSemester(Semester(name = "搜索隔离学期", startDate = 1000))
            val foreignId = "foreign-$otherSemesterId"
            try {
                dao.insertConversation(com.courseschedule.data.entity.AssistantConversation(foreignId, otherSemesterId,
                    "统计学%_", 1234))
                dao.insertMessage(com.courseschedule.data.entity.AssistantChatMessage(
                    conversationId = foreignId, role = "user", content = text))
                assertEquals(listOf(id), model.searchHistory("统计学%_").hits.map { it.conversationId })
                withContext(Dispatchers.Main) { model.openHistory(foreignId) }
                awaitIdle(model)
                assertEquals(id, model.conversationId)
                assertTrue(model.messages.value!!.last().content.contains("学期已切换"))
            } finally { database.semesterDao().deleteSemester(database.semesterDao().getSemesterById(otherSemesterId)!!) }
            repeat(205) { index -> dao.insertMessage(com.courseschedule.data.entity.AssistantChatMessage(
                conversationId = id, role = "user", content = "不匹配的其他记录$index")) }
            assertEquals(queryId, model.searchHistory("统计学%_").hits.single().messageId)
            val denseIds = (0 until 55).map { index -> dao.insertMessage(com.courseschedule.data.entity.AssistantChatMessage(
                conversationId = id, role = "user", content = "密集命中$index")) }
            val dense = model.searchHistory("密集命中")
            assertEquals(denseIds.asReversed().take(50), dense.hits.map { it.messageId })
            assertTrue(dense.hasMore)
            for (invalid in listOf(" ", "长".repeat(101))) {
                try { model.searchHistory(invalid); fail("invalid search must be rejected") }
                catch (_: IllegalArgumentException) { }
            }
            withContext(Dispatchers.Main) { model.openHistory(id, Long.MAX_VALUE, "统计学") }
            awaitIdle(model)
            assertTrue(model.messages.value!!.last().content.contains("消息已被删除"))
            assertNull(model.historyLocation.value)
            dao.deleteConversation(titleId)
            withContext(Dispatchers.Main) { model.openHistory(titleId) }
            awaitIdle(model)
            assertTrue(model.messages.value!!.last().content.contains("对话已被删除"))
            assertTrue(database.courseDao().getCoursesBySemesterSync(semester.id).isEmpty())
        }
    }

    @Test fun searchResultLocatesOldMessageAndReturnsToLatestWithoutExecutingPlan(): Unit = runBlocking {
        withFixture { database, semester ->
            ActivityScenario.launch<CourseAssistantActivity>(Intent(context, CourseAssistantActivity::class.java)).use { scenario ->
                lateinit var model: CourseAssistantViewModel
                scenario.onActivity { model = ViewModelProvider(it)[CourseAssistantViewModel::class.java] }
                awaitIdle(model)
                val id = model.conversationId!!
                val dao = database.assistantConversationDao()
                var targetId = 0L
                repeat(150) { index ->
                    val messageId = dao.insertMessage(com.courseschedule.data.entity.AssistantChatMessage(conversationId = id,
                        role = if (index % 2 == 0) "assistant" else "user",
                        content = if (index == 65) "高等数学原教室改为 B201，其他安排保持不变。" else "普通历史消息$index"))
                    if (index == 65) targetId = messageId
                }
                withContext(Dispatchers.Main) {
                    model.updateDraft("保留草稿")
                    model.receiveReply(AssistantCourseReply("待确认的新课程", listOf(com.courseschedule.data.entity.Course(
                        courseName = "待确认的英语", dayOfWeek = 3, startSection = 1, endSection = 2,
                        startWeek = 1, endWeek = 4))), semester)
                }
                assertFalse(model.messages.value!!.any { it.id == targetId })
                onView(withContentDescription(R.string.assistant_history)).perform(click())
                val found = CountDownLatch(1)
                onView(withId(R.id.tvHistoryStatus)).check { view, error ->
                    if (error != null) throw error
                    (view as android.widget.TextView).addTextChangedListener(object : android.text.TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                        override fun afterTextChanged(s: android.text.Editable?) {
                            val list = view.rootView.findViewById<android.widget.ListView>(R.id.historyResults)
                            val hits = list.adapter?.let { adapter -> (0 until adapter.count).mapNotNull { adapter.getItem(it) as? AssistantHistoryHit } }.orEmpty()
                            if (hits.size == 1 && hits.single().messageId == targetId)
                                found.countDown()
                        }
                    })
                }
                onView(withId(R.id.etHistorySearch)).perform(androidx.test.espresso.action.ViewActions.replaceText("高等数学原教室"))
                onView(withId(R.id.btnHistorySearch)).perform(click())
                assertTrue("搜索结果未显示", found.await(15, TimeUnit.SECONDS))
                onView(withId(R.id.tvHistorySnippet)).check(matches(withText(org.hamcrest.Matchers.containsString("B201"))))
                saveHistoryScreenshot("assistant-history-search")
                onView(withId(R.id.tvHistorySnippet)).perform(click())
                awaitIdle(model)
                assertEquals(targetId, model.historyLocation.value!!.messageId)
                assertTrue(model.messages.value!!.any { it.id == targetId })
                assertTrue(model.messages.value!!.size <= 60)
                assertTrue(model.hasNewerMessages.value == true)
                assertTrue(model.hasOlderMessages.value == true)
                onView(org.hamcrest.Matchers.allOf(withId(R.id.tvMessageBody), withText(org.hamcrest.Matchers.containsString("高等数学原教室")))).check(matches(isDisplayed()))
                saveHistoryScreenshot("assistant-history-located")
                scenario.recreate()
                awaitIdle(model)
                assertEquals(targetId, model.historyLocation.value!!.messageId)
                onView(org.hamcrest.Matchers.allOf(withId(R.id.tvMessageBody), withText(org.hamcrest.Matchers.containsString("高等数学原教室")))).check(matches(isDisplayed()))
                onView(withId(R.id.btnOlderMessages)).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click())
                awaitIdle(model)
                assertFalse(model.hasOlderMessages.value == true)
                assertTrue(model.hasNewerMessages.value == true)
                onView(withId(R.id.btnLatestMessages)).perform(click())
                awaitIdle(model)
                assertNull(model.historyLocation.value)
                assertFalse(model.hasNewerMessages.value == true)
                assertEquals(60, model.messages.value!!.size)
                assertNotNull(model.pendingChanges.value)
                assertEquals("保留草稿", model.draft.value)
                onView(withId(R.id.etMessage)).check(matches(withText("保留草稿")))
                assertTrue(database.courseDao().getCoursesBySemesterSync(semester.id).isEmpty())
            }
        }
    }

    private fun saveHistoryScreenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun queryIdentitiesRestoreSeparatelyFromTextAndRejectOtherSemesters(): Unit = runBlocking {
        withFixture { database, semester ->
            val course = com.courseschedule.data.entity.Course(semesterId = semester.id, courseName = "课程名称\n课程 #987",
                dayOfWeek = 3, startSection = 1, endSection = 2, startWeek = 1, endWeek = 16)
            val saved = course.copy(id = database.courseDao().insertCourse(course))
            var model = model()
            withContext(Dispatchers.Main) {
                model.receiveReply(AssistantCourseReply("查询", emptyList(), query = AssistantCourseQuery()), semester)
                stores.last().clear()
            }
            model = model()
            assertEquals(listOf(saved.id), model.messages.value!!.last().courseIds)
            assertTrue(model.messages.value!!.last().content.contains("课程 #987"))
            assertEquals(listOf(saved), model.coursesForDetails(model.messages.value!!.last().courseIds))
            val otherId = database.semesterDao().insertSemester(Semester(name = "其他学期", startDate = 1000))
            try {
                val foreignId = database.courseDao().insertCourse(course.copy(semesterId = otherId))
                try { model.coursesForDetails(listOf(foreignId)); fail("foreign semester must not open") }
                catch (_: IllegalArgumentException) { }
                database.semesterDao().switchCurrentSemester(otherId)
                try { model.coursesForDetails(listOf(saved.id)); fail("switched semester must not open old result") }
                catch (_: IllegalArgumentException) { }
            } finally {
                database.semesterDao().switchCurrentSemester(semester.id)
                database.courseDao().deleteCoursesBySemester(otherId)
                database.semesterDao().deleteSemester(database.semesterDao().getSemesterById(otherId)!!)
            }
            val id = model.conversationId!!
            database.assistantConversationDao().insertMessage(com.courseschedule.data.entity.AssistantChatMessage(
                conversationId = id, role = "assistant", content = "{broken", kind = "query"))
            withContext(Dispatchers.Main) { stores.last().clear() }
            model = model()
            assertTrue(model.messages.value!!.last().courseIds.isEmpty())
            assertEquals("error", model.messages.value!!.last().kind)
            assertTrue(model.messages.value!!.last().content.contains("无法读取"))
        }
    }

    @Test fun conflictingAndDuplicateInitialPlansNeverReachConfirmation(): Unit = runBlocking {
        withFixture { database, semester ->
            val course = com.courseschedule.data.entity.Course(semesterId = semester.id, courseName = "数学",
                dayOfWeek = 3, startSection = 1, endSection = 2, startWeek = 1, endWeek = 16)
            val original = course.copy(id = database.courseDao().insertCourse(course))
            var round = 0
            val model = model { AssistantCourseClient { FakeConnection(envelope("""{"version":1,"action":"change",
                "reply":"准备新增","courses":[{"courseName":"${if (round++ == 0) "物理" else "数学"}","teacher":"","classroom":"",
                "dayOfWeek":3,"startSection":1,"endSection":2,"weeks":[${(1..16).joinToString(",")}],"note":""}]}""")) } }
            for (expected in listOf("时间冲突", "重复课程")) {
                withContext(Dispatchers.Main) { model.send("添加课程", 1) }
                awaitIdle(model)
                assertNull(model.pendingChanges.value)
                assertTrue(model.messages.value!!.last().content.contains(expected))
                assertEquals(listOf(original), database.courseDao().getCoursesBySemesterSync(semester.id))
                withContext(Dispatchers.Main) { model.confirmPending() }
                assertEquals(listOf(original), database.courseDao().getCoursesBySemesterSync(semester.id))
            }
        }
    }

    @Test fun retryAfterMidnightAndWeekBoundaryUsesPersistedDateAfterRestore(): Unit = runBlocking {
        withFixture { database, oldSemester ->
            val start = LocalDate.of(2026, 8, 31)
            val semester = oldSemester.copy(startDate = start.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
            database.semesterDao().updateSemester(semester)
            val monday = com.courseschedule.data.entity.Course(semesterId = semester.id, courseName = "下周一课程",
                dayOfWeek = 1, startSection = 1, endSection = 2, startWeek = 2, endWeek = 2)
            val saved = monday.copy(id = database.courseDao().insertCourse(monday))
            var today = LocalDate.of(2026, 9, 6)
            var attempt = 0
            val requests = mutableListOf<String>()
            val factory = { AssistantCourseClient { FakeConnection(envelope("""{"version":1,"action":"query",
                "reply":"查明天","query":{"whenTo":{"dayOffset":1}}}"""),
                status = if (attempt++ == 0) 500 else 200, capture = requests) } }
            var model = model(factory, { today })
            withContext(Dispatchers.Main) { model.send("明天有哪些课？", 1) }
            awaitIdle(model)
            val state = AssistantConversationCodec.decode(database.assistantConversationDao().conversation(model.conversationId!!)!!.stateJson, semester.id)
            assertEquals("2026-09-06", state.retryRequest!!.requestDate)
            today = today.plusDays(1)
            withContext(Dispatchers.Main) { stores.last().clear() }
            model = model(factory, { today })
            withContext(Dispatchers.Main) { model.retry() }
            awaitIdle(model)
            assertEquals(2, attempt)
            assertTrue(requests.single().contains("今天是 2026-09-06"))
            val result = model.messages.value!!.last().content
            assertTrue(result.contains("2026-09-07"))
            assertTrue(result.contains("课程 #${saved.id}"))
            assertFalse(model.canRetry.value == true)
            assertEquals(listOf(saved), database.courseDao().getCoursesBySemesterSync(semester.id))
        }
    }

    @Test fun legacyRetryWithoutDateRequiresReviewAndDoesNotCallProvider(): Unit = runBlocking {
        withFixture { database, semester ->
            var calls = 0
            val factory = { AssistantCourseClient { calls++; FakeConnection(envelope("""{"reply":"不应调用"}""")) } }
            var model = model(factory)
            val dao = database.assistantConversationDao()
            val current = dao.conversation(model.conversationId!!)!!
            val oldState = AssistantConversationState(retryRequest = AssistantRetryRequest("明天查课", 1))
            dao.updateConversation(current.id, current.title, current.updatedAt, AssistantConversationCodec.encode(oldState), current.revision)
            withContext(Dispatchers.Main) { stores.last().clear() }
            model = model(factory)
            withContext(Dispatchers.Main) { model.retry() }
            awaitIdle(model)
            assertEquals(0, calls)
            assertEquals("明天查课", model.draft.value)
            assertFalse(model.canRetry.value == true)
            assertTrue(model.messages.value!!.last().content.contains("旧请求未保存原日期"))
            assertTrue(database.courseDao().getCoursesBySemesterSync(semester.id).isEmpty())
        }
    }

    @Test fun staleTargetChoiceRestoresDraftWithoutRequestOrOverwrite(): Unit = runBlocking {
        withFixture { database, semester ->
            val first = com.courseschedule.data.entity.Course(semesterId = semester.id, courseName = "数学", teacher = "张老师",
                dayOfWeek = 3, startSection = 1, endSection = 2, startWeek = 1, endWeek = 16)
            val saved = first.copy(id = database.courseDao().insertCourse(first))
            database.courseDao().insertCourse(first.copy(dayOfWeek = 5))
            var calls = 0
            val model = model { AssistantCourseClient { calls++; FakeConnection(envelope("""{"version":1,"action":"change",
                "reply":"改教室","updates":[{"id":${saved.id},"classroom":"B302"}]}""")) } }
            val text = "把数学教室改成B302"
            withContext(Dispatchers.Main) { model.send(text, 1) }
            awaitIdle(model)
            assertNotNull(model.targetChoice.value)
            val outside = saved.copy(teacher = "其他页面的新老师")
            database.courseDao().updateCourse(outside)
            withContext(Dispatchers.Main) { model.chooseTarget(saved) }
            awaitIdle(model)
            assertEquals(1, calls)
            assertNull(model.targetChoice.value)
            assertNull(model.pendingChanges.value)
            assertFalse(model.canRetry.value == true)
            assertEquals(text, model.draft.value)
            assertEquals(outside, database.courseDao().getCourseById(saved.id))
            assertTrue(model.messages.value!!.last().content.contains("选中的课程已变化"))
            withContext(Dispatchers.Main) { model.send(text, 1) }
            awaitIdle(model)
            val freshChoice = model.targetChoice.value!!.candidates.first { it.id == saved.id }
            database.semesterDao().updateSemester(semester.copy(totalWeeks = 15))
            withContext(Dispatchers.Main) { model.chooseTarget(freshChoice) }
            awaitIdle(model)
            assertEquals(2, calls)
            assertNull(model.targetChoice.value)
            assertEquals(text, model.draft.value)
            assertEquals(outside, database.courseDao().getCourseById(saved.id))
            assertTrue(model.messages.value!!.last().content.contains("学期设置已变化"))
            database.semesterDao().updateSemester(semester)
        }
    }

    @Test fun targetChoiceRestoresAndUiSelectionKeepsCorrectCourseAndMetadata(): Unit = runBlocking {
        withFixture { database, semester ->
            val first = com.courseschedule.data.entity.Course(semesterId = semester.id, courseName = "数学", teacher = "张老师",
                classroom = "A101", dayOfWeek = 3, startSection = 1, endSection = 2, startWeek = 1, endWeek = 16)
            val firstSaved = first.copy(id = database.courseDao().insertCourse(first))
            val second = first.copy(dayOfWeek = 5, teacher = "李老师", classroom = "C101", note = "保留第二门课的备注")
            val secondSaved = second.copy(id = database.courseDao().insertCourse(second))
            var round = 0
            val requests = mutableListOf<String>()
            val factory = { AssistantCourseClient { FakeConnection(envelope(when (round++) {
                0 -> """{"version":1,"action":"change","reply":"模型猜第一门","updates":[{"id":${firstSaved.id},"classroom":"B302"}]}"""
                1 -> """{"version":1,"action":"change","reply":"修改选中课程","updates":[{"id":${secondSaved.id},"classroom":"B302"}]}"""
                2 -> "不客气，刚才那门课我还记得。"
                else -> """{"version":1,"action":"change","reply":"继续修改这门课","updates":[{"id":${secondSaved.id},"note":"新备注"}]}"""
            }), capture = requests) } }
            var model = model(factory)
            withContext(Dispatchers.Main) { model.send("把数学教室改成B302", 1) }
            awaitIdle(model)
            assertNull(model.pendingChanges.value)
            assertEquals(listOf(firstSaved, secondSaved), model.targetChoice.value!!.candidates)
            withContext(Dispatchers.Main) { stores.last().clear() }
            model = model(factory)
            assertNotNull(model.targetChoice.value)
            val application = context.applicationContext as Application
            val callbacks = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, bundle: Bundle?) {
                    if (activity is CourseAssistantActivity) {
                        val key = "androidx.lifecycle.ViewModelProvider.DefaultKey:${CourseAssistantViewModel::class.java.canonicalName}"
                        if (activity.viewModelStore[key] == null) activity.viewModelStore.put(key, model)
                    }
                }
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, bundle: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            }
            instrumentation.runOnMainSync { application.registerActivityLifecycleCallbacks(callbacks) }
            try {
                ActivityScenario.launch<CourseAssistantActivity>(Intent(context, CourseAssistantActivity::class.java)).use { scenario ->
                    scenario.recreate()
                    awaitIdle(model)
                    onView(withId(R.id.btnChooseTarget)).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click())
                    instrumentation.waitForIdleSync()
                    val choiceImage = instrumentation.uiAutomation.takeScreenshot()
                    java.io.File(context.getExternalFilesDir(null), "assistant-target-choice.png").outputStream().use {
                        choiceImage.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    choiceImage.recycle()
                    onView(withText(org.hamcrest.Matchers.containsString("课程 #${secondSaved.id}"))).perform(click())
                    awaitIdle(model)
                    assertNull(model.targetChoice.value)
                    val changed = secondSaved.copy(classroom = "B302")
                    assertEquals(changed, model.pendingChanges.value!!.updates.single().replacements.single())
                    assertEquals(secondSaved, database.courseDao().getCourseById(secondSaved.id))
                    onView(withId(R.id.btnConfirmPending)).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click())
                    awaitIdle(model)
                    assertEquals(firstSaved, database.courseDao().getCourseById(firstSaved.id))
                    assertEquals(changed, database.courseDao().getCourseById(secondSaved.id))
                    withContext(Dispatchers.Main) { model.send("谢谢你", 1) }
                    awaitIdle(model)
                    assertEquals("chat", model.messages.value!!.last().kind)
                    assertNull(model.pendingChanges.value)
                    withContext(Dispatchers.Main) { model.send("把这门课的备注改成新备注", 1) }
                    awaitIdle(model)
                    assertEquals(secondSaved.id, model.pendingChanges.value!!.updates.single().original.id)
                    assertEquals(4, round)
                    assertTrue(requests[1].contains("用户明确选中的课程"))
                    assertTrue(requests[3].contains("用户明确选中的课程"))
                    withContext(Dispatchers.Main) { model.cancelPending() }
                    awaitIdle(model)
                }
            } finally { instrumentation.runOnMainSync { application.unregisterActivityLifecycleCallbacks(callbacks) } }
        }
    }

    @Test fun failedRevisionRetainsPlanAndRetryRestoresItWithoutExecuting(): Unit = runBlocking {
        withFixture { database, semester ->
            val course = com.courseschedule.data.entity.Course(semesterId = semester.id, courseName = "数学",
                classroom = "A101", dayOfWeek = 3, startSection = 1, endSection = 2, startWeek = 1, endWeek = 16)
            val original = course.copy(id = database.courseDao().insertCourse(course))
            val requests = mutableListOf<String>()
            var attempt = 0
            val factory = { AssistantCourseClient { FakeConnection(envelope(when (attempt++) {
                0 -> """{"version":1,"action":"change","reply":"改为B201","updates":[{"id":${original.id},"classroom":"B201"}]}"""
                1, 2 -> """{"version":1,"action":"change","reply":"错误地新增目标","deleteIds":[${original.id}]}"""
                else -> """{"version":1,"action":"revise","reply":"改为B302","revisions":[{"index":0,"classroom":"B302"}]}"""
            }), capture = requests) } }
            var model = model(factory)
            withContext(Dispatchers.Main) { model.send("把数学教室改为B201", 1) }
            awaitIdle(model)
            val pending = model.pendingChanges.value!!
            withContext(Dispatchers.Main) { model.send("教室改成B302", 1) }
            awaitIdle(model)
            assertEquals(pending, model.pendingChanges.value)
            assertTrue(model.canRetry.value == true)
            assertEquals(original, database.courseDao().getCourseById(original.id))
            withContext(Dispatchers.Main) { stores.last().clear() }
            model = model(factory)
            assertEquals(pending, model.pendingChanges.value)
            assertTrue(model.canRetry.value == true)
            withContext(Dispatchers.Main) { model.retry() }
            awaitIdle(model)
            assertEquals(4, attempt)
            assertTrue(requests.last().contains("当前有待确认方案"))
            assertEquals("B302", model.pendingChanges.value!!.updates.single().replacements.single().classroom)
            assertEquals(original, database.courseDao().getCourseById(original.id))
            withContext(Dispatchers.Main) { stores.last().clear() }
            model = model(factory)
            assertEquals("B302", model.pendingChanges.value!!.updates.single().replacements.single().classroom)
            withContext(Dispatchers.Main) { model.confirmPending() }
            awaitIdle(model)
            assertEquals(original.copy(classroom = "B302"), database.courseDao().getCourseById(original.id))
            assertEquals(4, attempt)
        }
    }

    @Test fun restoredConversationIsAtLatestMessageOnItsFirstDraw(): Unit = runBlocking {
        withFixture { database, semester ->
            val id = java.util.UUID.randomUUID().toString()
            val dao = database.assistantConversationDao()
            dao.insertConversation(com.courseschedule.data.entity.AssistantConversation(
                id, semester.id, "已有对话", System.currentTimeMillis()))
            repeat(20) { index ->
                dao.insertMessage(com.courseschedule.data.entity.AssistantChatMessage(
                    conversationId = id, role = "assistant", content = "历史消息 $index\n" + "已保存的课程安排\n".repeat(8)))
            }
            val preferences = context.getSharedPreferences("assistant_conversations", android.content.Context.MODE_PRIVATE)
            preferences.edit().putString("active_${semester.id}", id).putString("draft_$id", "保留的草稿").commit()
            val firstDraw = CountDownLatch(1)
            var starterVisible = true
            var drawnMessageCount = 0
            var drawnDraft = ""
            var atLatestMessage = false
            val application = context.applicationContext as Application
            val callbacks = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    if (activity !is CourseAssistantActivity) return
                    val root = activity.findViewById<View>(android.R.id.content)
                    val listener = object : ViewTreeObserver.OnDrawListener {
                        override fun onDraw() {
                            if (firstDraw.count == 0L) return
                            starterVisible = activity.findViewById<View>(R.id.starterContent).visibility == View.VISIBLE
                            drawnMessageCount = activity.findViewById<LinearLayout>(R.id.messagesContainer).childCount
                            drawnDraft = activity.findViewById<EditText>(R.id.etMessage).text.toString()
                            atLatestMessage = !activity.findViewById<NestedScrollView>(R.id.conversationScroll).canScrollVertically(1)
                            firstDraw.countDown()
                            root.post { root.viewTreeObserver.removeOnDrawListener(this) }
                        }
                    }
                    root.viewTreeObserver.addOnDrawListener(listener)
                }
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            }
            instrumentation.runOnMainSync { application.registerActivityLifecycleCallbacks(callbacks) }
            try {
                ActivityScenario.launch<CourseAssistantActivity>(Intent(context, CourseAssistantActivity::class.java)).use { scenario ->
                    assertTrue("The assistant must draw after loading", firstDraw.await(15, TimeUnit.SECONDS))
                    assertFalse("Restored history must never draw the welcome screen", starterVisible)
                    assertEquals(20, drawnMessageCount)
                    assertEquals("保留的草稿", drawnDraft)
                    assertTrue("History must start at the latest message without scrolling after entry", atLatestMessage)
                    val presented = CountDownLatch(1)
                    scenario.onActivity { activity ->
                        activity.window.decorView.postOnAnimation {
                            activity.window.decorView.postOnAnimation { presented.countDown() }
                        }
                    }
                    assertTrue(presented.await(5, TimeUnit.SECONDS))
                    instrumentation.waitForIdleSync()
                    val screenshot = instrumentation.uiAutomation.takeScreenshot()
                    java.io.File(context.getExternalFilesDir(null), "assistant-restored-first-frame.png").outputStream().use { output ->
                        screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
                    }
                    screenshot.recycle()
                }
            } finally {
                instrumentation.runOnMainSync { application.unregisterActivityLifecycleCallbacks(callbacks) }
                preferences.edit().remove("active_${semester.id}").remove("draft_$id").commit()
            }
        }
    }

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
            assertTrue(database.courseDao().getCoursesBySemesterSync(semester.id).isEmpty())
            assertNotNull(model.pendingChanges.value)
            withContext(Dispatchers.Main) { model.confirmPending() }
            awaitIdle(model)
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
                onView(withId(R.id.btnCloseHistory)).perform(click())
                withContext(Dispatchers.Main) { model.newConversation() }
                awaitIdle(model)
                assertNotEquals(id, model.conversationId)
                assertTrue(model.messages.value!!.isEmpty())
                assertEquals("", model.draft.value)
                assertFalse(model.canUndo.value == true)
                withContext(Dispatchers.Main) { model.openHistory(id) }
                awaitIdle(model)
                assertEquals("下一条草稿", model.draft.value)
                assertEquals(5, model.messages.value!!.size)
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
            assertNotNull(model.pendingChanges.value)
            assertTrue(database.courseDao().getCoursesBySemesterSync(semester.id).isEmpty())
            withContext(Dispatchers.Main) { model.confirmPending() }
            awaitIdle(model)
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

    private suspend fun model(factory: () -> AssistantCourseClient = { AssistantCourseClient() }): CourseAssistantViewModel =
        model(factory, { LocalDate.now() })

    private suspend fun model(factory: () -> AssistantCourseClient, today: () -> LocalDate): CourseAssistantViewModel {
        val value = withContext(Dispatchers.Main) {
            CourseAssistantViewModel(context.applicationContext as Application, factory, today).also {
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

    @Test fun retryingUnrelatedRequestDoesNotBindPreviousConversationFocus(): Unit = runBlocking {
        withFixture { database, semester ->
            val base = com.courseschedule.data.entity.Course(semesterId = semester.id, courseName = "数学", classroom = "A101",
                dayOfWeek = 3, startSection = 1, endSection = 2, startWeek = 1, endWeek = 16)
            val math = base.copy(id = database.courseDao().insertCourse(base))
            val englishBase = base.copy(courseName = "英语", dayOfWeek = 5)
            val english = englishBase.copy(id = database.courseDao().insertCourse(englishBase))
            var round = 0
            val requests = mutableListOf<String>()
            val factory = { AssistantCourseClient {
                val index = round++
                FakeConnection(envelope(if (index == 0) """{"action":"query","query":{"courseName":"数学"}}"""
                    else """{"action":"change","targetQuery":{"courseName":"英语"},"updates":[{"id":${english.id},"classroom":"B202"}]}"""),
                    status = if (index == 1) 429 else 200, capture = requests)
            } }
            var model = model(factory)
            withContext(Dispatchers.Main) { model.send("查询数学", 1) }; awaitIdle(model)
            withContext(Dispatchers.Main) { model.send("把英语教室改成B202", 1) }; awaitIdle(model)
            assertTrue(model.canRetry.value == true)
            val stored = AssistantConversationCodec.decode(database.assistantConversationDao().conversation(model.conversationId!!)!!.stateJson, semester.id)
            assertEquals(math, stored.selectedTarget); assertNull(stored.retryRequest!!.boundTarget)
            assertTrue(stored.retryRequest.bindingRecorded)
            withContext(Dispatchers.Main) { stores.last().clear() }; model = model(factory)
            withContext(Dispatchers.Main) { model.retry() }; awaitIdle(model)
            assertEquals(3, round)
            assertEquals(english, model.pendingChanges.value!!.updates.single().original)
            assertTrue(requests.last().contains("当前没有已绑定课程目标"))
            assertEquals(math, database.courseDao().getCourseById(math.id))
            assertEquals(english, database.courseDao().getCourseById(english.id))
            withContext(Dispatchers.Main) { model.confirmPending() }; awaitIdle(model)
            assertEquals(english.copy(classroom = "B202"), database.courseDao().getCourseById(english.id))
            assertEquals(math, database.courseDao().getCourseById(math.id))
        }
    }

    @Test fun unresolvedChoiceSurvivesFailedChatRetryAndCanBeCancelledByTool(): Unit = runBlocking {
        withFixture { database, semester ->
            val base = com.courseschedule.data.entity.Course(semesterId = semester.id, courseName = "数学", dayOfWeek = 3,
                startSection = 1, endSection = 2, startWeek = 1, endWeek = 16)
            val first = base.copy(id = database.courseDao().insertCourse(base))
            val other = base.copy(dayOfWeek = 5)
            val second = other.copy(id = database.courseDao().insertCourse(other))
            var round = 0
            val factory = { AssistantCourseClient {
                val index = round++
                FakeConnection(envelope(when (index) {
                    0 -> """{"action":"clarify","targetQuery":{"courseName":"数学"}}"""
                    3 -> """{"action":"cancel","reply":"取消"}"""
                    else -> "好的，我们先聊聊。课程目标仍等你选择。"
                }), status = if (index == 1) 429 else 200)
            } }
            var model = model(factory)
            withContext(Dispatchers.Main) { model.send("把数学教室改成B201", 1) }; awaitIdle(model)
            val choice = model.targetChoice.value!!
            withContext(Dispatchers.Main) { model.send("先聊聊，今天有点累", 1) }; awaitIdle(model)
            assertEquals(choice, model.targetChoice.value); assertTrue(model.canRetry.value == true)
            withContext(Dispatchers.Main) { stores.last().clear() }; model = model(factory)
            assertEquals(choice, model.targetChoice.value)
            withContext(Dispatchers.Main) { model.retry() }; awaitIdle(model)
            assertEquals(choice, model.targetChoice.value); assertEquals("chat", model.messages.value!!.last().kind)
            withContext(Dispatchers.Main) { model.send("取消刚才的课程目标选择", 1) }; awaitIdle(model)
            assertNull(model.targetChoice.value); assertEquals("cancel", model.messages.value!!.last().kind)
            assertEquals(4, round); assertNull(model.pendingChanges.value)
            assertEquals(first, database.courseDao().getCourseById(first.id))
            assertEquals(second, database.courseDao().getCourseById(second.id))
        }
    }

    @Test fun readOnlyQueriesRetainPendingPlanAndNeverAppearAsSavedChanges(): Unit = runBlocking {
        withFixture { database, semester ->
            var round = 0
            val model = model { AssistantCourseClient { FakeConnection(envelope(when (round++) {
                0 -> """{"action":"change","courses":[{"courseName":"英语","teacher":"","classroom":"","dayOfWeek":3,"startSection":3,"endSection":4,"weeks":[1,2]}]}"""
                1 -> """{"action":"query","query":{"courseName":"数学"}}"""
                2 -> """{"action":"query","query":{"dayOfWeek":3,"whenTo":{"week":1},"freeSlots":true}}"""
                else -> """{"action":"task_query","taskQuery":{}}"""
            })) } }
            withContext(Dispatchers.Main) { model.send("添加英语，周三第3到4节，1到2周", 1) }; awaitIdle(model)
            val pending = model.pendingChanges.value!!
            listOf("查询数学", "查询第一周周三的空闲节次", "查询学习事项").forEachIndexed { index, input ->
                withContext(Dispatchers.Main) { model.send(input, 1) }; awaitIdle(model)
                assertEquals(pending, model.pendingChanges.value)
                assertEquals(if (index == 2) "task_query" else "query_text", model.messages.value!!.last().kind)
                assertFalse(model.canRetry.value == true)
                assertTrue(database.courseDao().getCoursesBySemesterSync(semester.id).isEmpty())
            }
            withContext(Dispatchers.Main) { stores.last().clear() }
            val restored = model()
            assertEquals(pending, restored.pendingChanges.value)
            withContext(Dispatchers.Main) { restored.confirmPending() }; awaitIdle(restored)
            assertEquals(1, database.courseDao().getCoursesBySemesterSync(semester.id).size)
            assertEquals("result", restored.messages.value!!.last().kind)
        }
    }

    @Test fun legacyRetryWithUnknownBindingRequiresReviewInsteadOfGuessingTarget(): Unit = runBlocking {
        withFixture { database, semester ->
            val base = com.courseschedule.data.entity.Course(semesterId = semester.id, courseName = "数学", dayOfWeek = 3,
                startSection = 1, endSection = 2, startWeek = 1, endWeek = 16)
            val math = base.copy(id = database.courseDao().insertCourse(base))
            var calls = 0
            val factory = { AssistantCourseClient { calls++; FakeConnection(envelope("不应调用")) } }
            var model = model(factory)
            val id = model.conversationId!!
            val dao = database.assistantConversationDao()
            val row = dao.conversation(id)!!
            val text = "把英语教室改成B201"
            val legacy = AssistantConversationState(retryRequest = AssistantRetryRequest(text, 1, LocalDate.now().toString()), selectedTarget = math)
            dao.updateConversation(id, row.title, row.updatedAt, AssistantConversationCodec.encode(legacy), row.revision)
            withContext(Dispatchers.Main) { stores.last().clear() }; model = model(factory)
            withContext(Dispatchers.Main) { model.retry() }; awaitIdle(model)
            assertEquals(0, calls); assertEquals(text, model.draft.value)
            assertTrue(model.messages.value!!.last().content.contains("未保存本次绑定"))
            assertFalse(model.canRetry.value == true); assertNull(model.pendingChanges.value)
            assertEquals(math, database.courseDao().getCourseById(math.id))
        }
    }

    // Fixtures use the same native function envelope as the production service.
    private fun envelope(payload: String): String {
        val root = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull()
        val action = root?.get("action")?.asString ?: if (root?.getAsJsonArray("courses")?.size()?.let { it > 0 } == true) "change" else "chat"
        val names = mapOf("change" to "propose_course_changes", "query" to "query_courses", "clarify" to "choose_course_target",
            "revise" to "revise_course_plan", "undo" to "undo_course_changes", "task_change" to "propose_study_changes",
            "task_query" to "query_study_tasks", "task_revise" to "revise_study_plan", "cancel" to "cancel_pending_plan")
        val toolName = names[action]?.takeIf { action != "clarify" || root!!.has("targetQuery") }
        return com.google.gson.JsonObject().apply {
            add("choices", JsonParser.parseString("""[{"finish_reason":"stop","message":{}}]"""))
            val choice = getAsJsonArray("choices")[0].asJsonObject
            val message = choice.getAsJsonObject("message")
            if (toolName == null) message.addProperty("content", payload)
            else {
                val args = root!!.deepCopy().apply {
                    listOf("version", "action", "reply", "undo").forEach { remove(it) }
                    if (action == "change" && !has("targetQuery") && (has("updates") || has("deleteIds")))
                        add("targetQuery", JsonParser.parseString("""{"courseName":"数学"}"""))
                }
                choice.addProperty("finish_reason", "tool_calls")
                message.add("tool_calls", JsonParser.parseString("""[{"id":"synthetic_call","type":"function","function":{}}]"""))
                message.getAsJsonArray("tool_calls")[0].asJsonObject.getAsJsonObject("function").apply {
                    addProperty("name", toolName); addProperty("arguments", args.toString())
                }
            }
        }.toString()
    }

    @Test fun roleConversationPersistsAsChatWithoutCourseOrTaskWrites(): Unit = runBlocking {
        withFixture { database, semester ->
            val inputs = listOf("你好", "我是项羽", "你是虞姬", "那陪我说两句")
            val replies = listOf("你好！", "项王，今天想聊些什么？", "项王，妾身在。", "帐外风起，愿陪你说说心事。")
            val requests = mutableListOf<String>()
            var index = 0
            var model = model { AssistantCourseClient { FakeConnection(envelope(replies[index++]), capture = requests) } }
            inputs.forEach { input ->
                withContext(Dispatchers.Main) { model.send(input, 1) }; awaitIdle(model)
                assertFalse(model.canRetry.value == true); assertNull(model.pendingChanges.value)
                assertEquals("chat", model.messages.value!!.last().kind)
            }
            assertEquals(4, index); assertEquals(8, model.messages.value!!.size)
            assertTrue(requests.last().contains("我是项羽")); assertTrue(requests.last().contains("你是虞姬"))
            assertTrue(database.courseDao().getCoursesBySemesterSync(semester.id).isEmpty())
            assertTrue(database.studyTaskDao().forSemester(semester.id).isEmpty())
            withContext(Dispatchers.Main) { stores.last().clear() }; model = model()
            assertEquals(replies.last(), model.messages.value!!.last().content)
            assertFalse(model.canRetry.value == true)
            val application = context.applicationContext as Application
            val callbacks = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, bundle: Bundle?) {
                    if (activity is CourseAssistantActivity) activity.viewModelStore.put(
                        "androidx.lifecycle.ViewModelProvider.DefaultKey:${CourseAssistantViewModel::class.java.canonicalName}", model)
                }
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, bundle: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            }
            instrumentation.runOnMainSync { application.registerActivityLifecycleCallbacks(callbacks) }
            try {
                ActivityScenario.launch<CourseAssistantActivity>(Intent(context, CourseAssistantActivity::class.java)).use {
                    awaitIdle(model)
                    onView(withText(replies.last())).check(matches(isDisplayed()))
                    val screenshot = instrumentation.uiAutomation.takeScreenshot()
                    java.io.File(context.getExternalFilesDir(null), "assistant-role-chat-refactor.png").outputStream().use {
                        screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    screenshot.recycle()
                }
            } finally { instrumentation.runOnMainSync { application.unregisterActivityLifecycleCallbacks(callbacks) } }
        }
    }

    @Test fun chatKeepsPendingPlanAcrossRestoreAndExplicitToolCancelClearsIt(): Unit = runBlocking {
        withFixture { database, semester ->
            var round = 0
            val requests = mutableListOf<String>()
            val factory = { AssistantCourseClient { FakeConnection(envelope(when (round++) {
                0 -> """{"action":"change","courses":[{"courseName":"英语","teacher":"","classroom":"","dayOfWeek":3,"startSection":3,"endSection":4,"weeks":[1,2]}]}"""
                1 -> "先陪你聊聊，刚才的安排还在等你确认。"
                else -> """{"version":1,"action":"cancel","reply":"取消"}"""
            }), capture = requests) } }
            var model = model(factory)
            withContext(Dispatchers.Main) { model.send("添加英语，周三第3到4节，1到2周", 1) }; awaitIdle(model)
            val pending = model.pendingChanges.value!!
            withContext(Dispatchers.Main) { model.send("先别安排，我有点累", 1) }; awaitIdle(model)
            assertEquals(pending, model.pendingChanges.value)
            assertEquals("chat", model.messages.value!!.last().kind)
            withContext(Dispatchers.Main) { stores.last().clear() }; model = model(factory)
            assertEquals(pending, model.pendingChanges.value)
            withContext(Dispatchers.Main) { model.send("取消刚才待确认的方案", 1) }; awaitIdle(model)
            assertNull(model.pendingChanges.value); assertEquals("cancel", model.messages.value!!.last().kind)
            assertEquals(3, round)
            val available = JsonParser.parseString(requests[1]).asJsonObject.getAsJsonArray("tools").map {
                it.asJsonObject.getAsJsonObject("function").get("name").asString }.toSet()
            assertEquals(setOf("revise_course_plan", "cancel_pending_plan", "query_courses", "query_study_tasks"), available)
            assertTrue(database.courseDao().getCoursesBySemesterSync(semester.id).isEmpty())
            assertTrue(database.studyTaskDao().forSemester(semester.id).isEmpty())
        }
    }

    @Test fun malformedProviderReplyRepairsOnceWithoutRecordingAFailedOperation(): Unit = runBlocking {
        withFixture { database, semester ->
            var calls = 0
            val model = model { AssistantCourseClient { FakeConnection(if (calls++ == 0) "{}" else envelope("项王，妾身在。")) } }
            withContext(Dispatchers.Main) { model.send("你是虞姬", 1) }; awaitIdle(model)
            assertEquals(2, calls); assertEquals(2, model.messages.value!!.size)
            assertEquals("项王，妾身在。", model.messages.value!!.last().content)
            assertFalse(model.canRetry.value == true)
            assertTrue(database.courseDao().getCoursesBySemesterSync(semester.id).isEmpty())
        }
    }

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
