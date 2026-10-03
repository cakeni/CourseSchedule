package com.courseschedule.ui.assistant

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView
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

    @Test fun queryDetailsUseLatestCourseAndReturnToChatForSingleAndMultipleResults(): Unit = runBlocking {
        withFixture { database, semester, scenario, model ->
            val original = database.courseDao().getCoursesBySemesterSync(semester.id)
            val math = original.first { it.courseName == "高等数学" }
            val physics = original.first { it.courseName == "大学物理" }
            withContext(Dispatchers.Main) {
                model.receiveReply(AssistantCourseReply("查数学", emptyList(), query = AssistantCourseQuery(courseName = math.courseName)), semester)
            }
            assertEquals(listOf(math.id), model.messages.value!!.last().courseIds)
            val edited = math.copy(classroom = "B901")
            database.courseDao().updateCourse(edited)
            var expectedName = math.courseName
            var expectedRoom = edited.classroom
            var loaded = CountDownLatch(1)
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val application = context.applicationContext as android.app.Application
            val callbacks = object : android.app.Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) {
                    if (activity !is com.courseschedule.ui.addcourse.AddCourseActivity) return
                    val view = activity.window.decorView
                    view.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                        override fun onPreDraw(): Boolean {
                            if (activity.findViewById<TextView>(R.id.etCourseName)?.text?.toString() == expectedName &&
                                activity.findViewById<TextView>(R.id.etClassroom)?.text?.toString() == expectedRoom) {
                                view.viewTreeObserver.removeOnPreDrawListener(this)
                                loaded.countDown()
                            }
                            return true
                        }
                    })
                }
                override fun onActivityStarted(activity: android.app.Activity) = Unit
                override fun onActivityResumed(activity: android.app.Activity) = Unit
                override fun onActivityPaused(activity: android.app.Activity) = Unit
                override fun onActivityStopped(activity: android.app.Activity) = Unit
                override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) = Unit
                override fun onActivityDestroyed(activity: android.app.Activity) = Unit
            }
            instrumentation.runOnMainSync { application.registerActivityLifecycleCallbacks(callbacks) }
            try {
                onView(withId(R.id.btnViewQueriedCourses)).perform(scrollTo(), click())
                assertTrue("课程编辑页未加载最新课程", loaded.await(15, TimeUnit.SECONDS))
                onView(withId(R.id.etClassroom)).check(matches(withText("B901")))
                screenshot("assistant-query-course-details")
                androidx.test.espresso.Espresso.pressBack()
                awaitIdle(model)
                onView(withId(R.id.etMessage)).check(matches(isDisplayed()))
                assertEquals(edited, database.courseDao().getCourseById(math.id))
                withContext(Dispatchers.Main) {
                    model.receiveReply(AssistantCourseReply("查全学期", emptyList(), query = AssistantCourseQuery()), semester)
                }
                expectedName = physics.courseName
                expectedRoom = physics.classroom
                loaded = CountDownLatch(1)
                onView(org.hamcrest.Matchers.allOf(withId(R.id.btnViewQueriedCourses), withText(R.string.assistant_select_queried_course)))
                    .perform(scrollTo(), click())
                onView(withText(containsString("课程 #${physics.id}"))).perform(click())
                assertTrue("多条查询打开了错误课程", loaded.await(15, TimeUnit.SECONDS))
                onView(withId(R.id.etCourseName)).check(matches(withText(physics.courseName)))
                androidx.test.espresso.Espresso.pressBack()
                awaitIdle(model)
                scenario.recreate()
                awaitIdle(model)
                assertEquals(3, model.messages.value!!.last().courseIds.size)
                database.courseDao().deleteCoursesByIds(listOf(math.id))
                try { model.coursesForDetails(listOf(math.id)); fail("deleted query target must not open") }
                catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("删除")) }
                assertEquals(physics, database.courseDao().getCourseById(physics.id))
            } finally { instrumentation.runOnMainSync { application.unregisterActivityLifecycleCallbacks(callbacks) } }
        }
    }

    @Test fun conflictReceiptSuggestsValidSameDaySectionsWithoutSaving(): Unit = runBlocking {
        withFixture { database, semester, _, model ->
            val original = database.courseDao().getCoursesBySemesterSync(semester.id)
            val math = original.first { it.courseName == "高等数学" }
            val reply = decode("""{"version":1,"action":"change","reply":"移到物理时段",
                "updates":[{"id":${math.id},"dayOfWeek":3,"startSection":3,"endSection":4}]}""", semester, original)
            withContext(Dispatchers.Main) {
                try { model.receiveReply(reply, semester); fail("conflict must be rejected before preview") }
                catch (error: IllegalArgumentException) {
                    assertTrue(error.message!!.contains("同日备选节次"))
                    assertTrue(error.message!!.contains("周三第1–2节"))
                    assertTrue(error.message!!.contains("周次和课时长不变"))
                }
            }
            assertNull(model.pendingChanges.value)
            assertEquals(original, database.courseDao().getCoursesBySemesterSync(semester.id))
        }
    }

    @Test fun inheritedConflictsAllowMetadataAndUndoButLaterCoursesBlockRestore(): Unit = runBlocking {
        withFixture { database, semester, _, model ->
            val math = database.courseDao().getCoursesBySemesterSync(semester.id).first { it.courseName == "高等数学" }
            database.courseDao().insertCourse(math.copy(id = 0, courseName = "原有冲突课程"))
            val preferences = com.courseschedule.utils.SchedulePreferences(context)
            val wasEnabled = preferences.reminderEnabled
            preferences.reminderEnabled = false
            try {
                var rows = database.courseDao().getCoursesBySemesterSync(semester.id)
                withContext(Dispatchers.Main) {
                    model.receiveReply(decode("""{"reply":"只改备注提醒","updates":[{"id":${math.id},"note":"新备注","reminderMinutes":20}]}""",
                        semester, rows), semester)
                    model.confirmPending()
                }
                awaitIdle(model)
                val edited = database.courseDao().getCourseById(math.id)!!
                assertEquals(math.copy(note = "新备注", reminderMinutes = 20), edited)
                assertTrue(model.messages.value!!.last().content.contains("总提醒关闭"))
                rows = database.courseDao().getCoursesBySemesterSync(semester.id)
                withContext(Dispatchers.Main) {
                    model.receiveReply(decode("""{"reply":"删除数学","deleteIds":[${math.id}]}""", semester, rows), semester)
                    model.confirmPending()
                }
                awaitIdle(model)
                assertNull(database.courseDao().getCourseById(math.id))
                withContext(Dispatchers.Main) { model.undo() }
                awaitIdle(model)
                assertEquals(edited, database.courseDao().getCourseById(math.id))
                rows = database.courseDao().getCoursesBySemesterSync(semester.id)
                withContext(Dispatchers.Main) {
                    model.receiveReply(decode("""{"reply":"再次删除","deleteIds":[${math.id}]}""", semester, rows), semester)
                    model.confirmPending()
                }
                awaitIdle(model)
                val newId = database.courseDao().insertCourse(math.copy(id = 0, courseName = "后来新增的课程"))
                withContext(Dispatchers.Main) { model.undo() }
                awaitIdle(model)
                assertNull(database.courseDao().getCourseById(math.id))
                assertNotNull(database.courseDao().getCourseById(newId))
                assertTrue(model.messages.value!!.last().content.contains("时间冲突"))
            } finally { preferences.reminderEnabled = wasEnabled }
        }
    }

    @Test fun singleOccurrenceCanBeRevisedConfirmedQueriedAndUndone(): Unit = runBlocking {
        withFixture { database, semester, _, model ->
            val original = database.courseDao().getCoursesBySemesterSync(semester.id)
            val math = original.first { it.courseName == "高等数学" }
            val moved = decode("""{"version":1,"action":"change","reply":"只移一次",
                "occurrences":[{"id":${math.id},"operation":"move","source":{"week":2},
                "target":{"dayOfWeek":6,"startSection":3}}]}""", semester, original)
            withContext(Dispatchers.Main) { model.receiveReply(moved, semester) }
            assertEquals(original, database.courseDao().getCoursesBySemesterSync(semester.id))
            onView(withId(R.id.etMessage)).check(matches(isEnabled()))
            val pending = model.pendingChanges.value!!
            val index = pending.updates.single().replacements.indexOfFirst { it.dayOfWeek == 6 }
            val revised = decode("""{"version":1,"action":"revise","reply":"只改补充方案",
                "revisions":[{"index":$index,"classroom":"B302"}]}""", semester, original, pending)
            withContext(Dispatchers.Main) { model.receiveReply(revised, semester) }
            assertEquals(original, database.courseDao().getCoursesBySemesterSync(semester.id))
            screenshot("assistant-single-occurrence-preview")
            onView(withId(R.id.btnConfirmPending)).perform(scrollTo(), click())
            awaitIdle(model)
            val saved = database.courseDao().getCoursesBySemesterSync(semester.id).filter { it.courseName == math.courseName }
            val single = saved.single { it.dayOfWeek == 6 }
            assertEquals(2, single.startWeek)
            assertEquals(2, single.endWeek)
            assertEquals(3, single.startSection)
            assertEquals(4, single.endSection)
            assertEquals("B302", single.classroom)
            assertEquals((1..16).filter { it != 2 }, saved.filter { it.dayOfWeek == math.dayOfWeek }.flatMap { row ->
                (1..16).filter { com.courseschedule.domain.ScheduleRules.isCourseInWeek(row, it) } }.sorted())
            withContext(Dispatchers.Main) { model.undo() }
            awaitIdle(model)
            assertEquals(original, database.courseDao().getCoursesBySemesterSync(semester.id))
            withContext(Dispatchers.Main) {
                model.receiveReply(decode("""{"version":1,"action":"query","reply":"模型内容不可作为结果",
                    "query":{"whenTo":{"week":2},"dayOfWeek":2}}""", semester, original), semester)
            }
            assertTrue(model.messages.value!!.last().content.contains("课程 #${math.id}"))
            assertFalse(model.messages.value!!.last().content.contains("模型内容不可作为结果"))
            withContext(Dispatchers.Main) {
                model.receiveReply(decode("""{"version":1,"action":"query","reply":"有课",
                    "query":{"courseName":"不存在的课程"}}""", semester, original), semester)
            }
            assertTrue(model.messages.value!!.last().content.contains("没有找到"))
            assertEquals("result", model.messages.value!!.last().kind)
            withContext(Dispatchers.Main) {
                model.receiveReply(decode("""{"version":1,"action":"query","reply":"空闲",
                    "query":{"whenTo":{"week":2},"dayOfWeek":2,"startSection":1,"endSection":4,"freeSlots":true}}""",
                    semester, original), semester)
            }
            assertTrue(model.messages.value!!.last().content.contains("第3节"))
            assertFalse(model.messages.value!!.last().content.contains("第1节"))
            assertEquals(original, database.courseDao().getCoursesBySemesterSync(semester.id))
            onView(withContentDescription(R.string.assistant_history)).perform(click())
            onView(withId(R.id.btnDrawerReminders)).perform(click())
            onView(withId(R.id.rowReminder)).check(matches(isDisplayed()))
            screenshot("assistant-settings-page")
            androidx.test.espresso.Espresso.pressBack()
            onView(withId(R.id.etMessage)).check(matches(isDisplayed()))
        }
    }

    @Test fun editsQueriesDeletesAndUndoPreserveMetadataAndPendingSurvivesRotation(): Unit = runBlocking {
        withFixture { database, semester, scenario, model ->
            val original = database.courseDao().getCoursesBySemesterSync(semester.id)
            val math = original.first { it.courseName == "高等数学" }
            val patch = decode("""{"reply":"修改教室和提醒","courses":[],
                "updates":[{"id":${math.id},"classroom":"B201","reminderMinutes":10}]}""", semester, original)
            withContext(Dispatchers.Main) { model.receiveReply(patch, semester) }
            assertEquals(original, database.courseDao().getCoursesBySemesterSync(semester.id))
            onView(withId(R.id.etMessage)).check(matches(isEnabled()))
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
            val queryDrawn = CountDownLatch(1)
            var queryVisible = false
            var queryGeometry = ""
            scenario.onActivity { activity ->
                val container = activity.findViewById<LinearLayout>(R.id.messagesContainer)
                val scroll = activity.findViewById<androidx.core.widget.NestedScrollView>(R.id.conversationScroll)
                val observer = object : ViewTreeObserver.OnDrawListener {
                    override fun onDraw() {
                        val body = container.getChildAt(container.childCount - 1)?.findViewById<TextView>(R.id.tvMessageBody)
                        if (queryDrawn.count > 0 && body?.text?.contains("找到 1 项课程") == true) {
                            val rect = Rect()
                            queryVisible = body.getGlobalVisibleRect(rect)
                            queryGeometry = "rect=$rect scroll=${scroll.scrollY} height=${scroll.height} content=${scroll.getChildAt(0).height}"
                            queryDrawn.countDown()
                            scroll.post { scroll.viewTreeObserver.removeOnDrawListener(this) }
                        }
                    }
                }
                scroll.viewTreeObserver.addOnDrawListener(observer)
            }
            withContext(Dispatchers.Main) {
                model.receiveReply(decode("""{"reply":"找到数学","courses":[],"queryIds":[${math.id}]}""",
                    semester, rows), semester)
            }
            assertEquals(rows, database.courseDao().getCoursesBySemesterSync(semester.id))
            assertTrue(model.canUndo.value == true)
            assertTrue("查询消息没有绘制", queryDrawn.await(15, TimeUnit.SECONDS))
            assertTrue("查询消息第一帧应可见：$queryGeometry", queryVisible)
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
                model.confirmPending()
            }
            awaitIdle(model)
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
            withContext(Dispatchers.Main) {
                try { model.receiveReply(mixed, semester); fail("conflict must fail before preview") }
                catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("时间冲突")) }
            }
            assertEquals(original, database.courseDao().getCoursesBySemesterSync(semester.id))
            assertNull(model.pendingChanges.value)

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

    private fun decode(payload: String, semester: Semester, contextCourses: List<Course>,
        pending: AssistantCourseReply? = null): AssistantCourseReply {
        val envelope = JsonObject().apply {
            add("choices", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("finish_reason", "stop")
                    add("message", JsonObject().apply { addProperty("content", payload) })
                })
            })
        }
        return AssistantCourseClient.parseResponse(envelope.toString(), semester.totalWeeks, contextCourses, semester, pending = pending)
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
