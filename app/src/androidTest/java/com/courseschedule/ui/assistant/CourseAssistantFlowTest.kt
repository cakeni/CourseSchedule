package com.courseschedule.ui.assistant

import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.courseschedule.R
import com.courseschedule.data.AppDatabase
import com.courseschedule.ui.MainActivity
import com.courseschedule.ui.importdata.ImportActivity
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CourseAssistantFlowTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun encryptedConfigRoundTripsAndSessionKeyIsNotPersisted() {
        val isolated = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir(): File = File(super.getNoBackupFilesDir(), "assistant-test")
                .apply { mkdirs() }
        }
        val store = AssistantConfigStore(isolated)
        val config = AssistantApiConfig("https://example.com/v1", "test-model", "synthetic-test-value")
        try {
            store.save(config, true)
            assertEquals(config, store.load())
            val bytes = File(isolated.noBackupFilesDir, "course-assistant-api").readBytes()
            assertFalse(bytes.toString(Charsets.UTF_8).contains(config.apiKey))
            store.save(config, false)
            assertEquals("", store.load().apiKey)
            assertEquals(config.baseUrl, store.load().baseUrl)
            store.clear()
            assertEquals(AssistantApiConfig(), store.load())
        } finally { store.clear() }
    }

    @Test fun modelReplyIsSavedConflictAndDuplicateRejectedAndUndoSurvivesRotation(): Unit = runBlocking {
        val database = AppDatabase.getDatabase(context)
        val original = database.semesterDao().getCurrentSemesterSync()
        val id = database.semesterDao().insertSemester(com.courseschedule.data.entity.Semester(
            name = "加课测试学期", startDate = System.currentTimeMillis(), totalWeeks = 16))
        database.semesterDao().switchCurrentSemester(id)
        val semester = database.semesterDao().getSemesterById(id)!!
        try {
            ActivityScenario.launch<CourseAssistantActivity>(Intent(context, CourseAssistantActivity::class.java)).use { scenario ->
                lateinit var model: CourseAssistantViewModel
                scenario.onActivity { model = ViewModelProvider(it)[CourseAssistantViewModel::class.java] }
                awaitLoaded(model)
                val row = """{"courseName":"高等数学","teacher":"张老师","classroom":"A101",
                    "dayOfWeek":2,"startSection":1,"endSection":1,"weeks":[1,2,3,4],"note":""}"""
                val content = JsonParser.parseString("""{"reply":"准备添加","courses":[$row]}""")
                val envelope = com.google.gson.JsonObject().apply {
                    add("choices", com.google.gson.JsonArray().apply {
                        add(com.google.gson.JsonObject().apply {
                            addProperty("finish_reason", "stop")
                            add("message", com.google.gson.JsonObject().apply { addProperty("content", content.toString()) })
                        })
                    })
                }
                val reply = AssistantCourseClient.parseResponse(envelope.toString(), 16)
                withContext(Dispatchers.Main) {
                    model.messages.value = listOf(AssistantMessage("user", "周二早八添加高等数学，在 A101，第 1–4 周"))
                }
                withContext(Dispatchers.Main) { model.saveCourses(reply, semester) }
                onView(withText(org.hamcrest.Matchers.containsString("已添加到"))).check(matches(isDisplayed()))
                onView(withText(R.string.assistant_undo_action)).check(matches(isDisplayed()))
                screenshot("assistant-result")
                val saved = database.courseDao().getCoursesBySemesterSync(id).single()
                assertEquals(2, saved.dayOfWeek)
                assertEquals(1, saved.startSection)
                assertEquals("高等数学", saved.courseName)
                assertTrue(saved.id > 0)
                assertEquals(com.courseschedule.utils.SchedulePreferences(context).defaultReminderMinutes, saved.reminderMinutes)
                withContext(Dispatchers.Main) {
                    try { model.saveCourses(reply, semester); fail("duplicate must be rejected") }
                    catch (_: IllegalArgumentException) { }
                    try { model.saveCourses(reply.copy(courses = reply.courses.map { it.copy(courseName = "英语") }), semester)
                        fail("conflict must be rejected") }
                    catch (_: IllegalArgumentException) { }
                }
                assertEquals(1, database.courseDao().getCoursesBySemesterSync(id).size)
                scenario.recreate()
                awaitLoaded(model)
                scenario.onActivity {
                    val retained = ViewModelProvider(it)[CourseAssistantViewModel::class.java]
                    assertSame(model, retained)
                    assertTrue(retained.canUndo.value == true)
                    retained.undo()
                }
                // Await the Room write without repeatedly polling the device.
                val done = java.util.concurrent.CountDownLatch(1)
                lateinit var observer: androidx.lifecycle.Observer<Boolean>
                observer = androidx.lifecycle.Observer { busy -> if (!busy) done.countDown() }
                InstrumentationRegistry.getInstrumentation().runOnMainSync { model.busy.observeForever(observer) }
                assertTrue(done.await(15, java.util.concurrent.TimeUnit.SECONDS))
                InstrumentationRegistry.getInstrumentation().runOnMainSync { model.busy.removeObserver(observer) }
                assertTrue(database.courseDao().getCoursesBySemesterSync(id).isEmpty())
                onView(withText(org.hamcrest.Matchers.containsString("已撤销"))).check(matches(isDisplayed()))
            }
        } finally {
            database.courseDao().deleteCoursesBySemester(id)
            database.semesterDao().deleteSemester(semester)
            original?.let { database.semesterDao().switchCurrentSemester(it.id) }
            com.courseschedule.utils.ReminderManager(context).restoreReminders()
        }
    }

    @Test fun importPageOpensAssistantAndExampleKeepsDraftWhileConfiguring() {
        ActivityScenario.launch<ImportActivity>(Intent(context, ImportActivity::class.java)).use {
            onView(withId(R.id.cardImportAssistant)).perform(scrollTo()).check(matches(isDisplayed()))
            screenshot("assistant-import-page")
            onView(withId(R.id.cardImportAssistant)).perform(click())
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            onView(withId(R.id.starterContent)).check(matches(isDisplayed()))
            onView(withId(R.id.btnExampleSimple)).perform(scrollTo(), click())
            onView(withId(R.id.etMessage)).check(matches(withText(R.string.assistant_example_simple)))
            onView(withId(R.id.messagesContainer)).check(matches(hasChildCount(0)))
            onView(withId(R.id.etMessage)).perform(click())
            onView(withId(R.id.etMessage)).perform(object : androidx.test.espresso.ViewAction {
                override fun getConstraints() = isDisplayed()
                override fun getDescription() = "Show the keyboard and await its layout"
                override fun perform(controller: androidx.test.espresso.UiController, view: android.view.View) {
                    view.requestFocus()
                    controller.loopMainThreadForAtLeast(300)
                    (view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as
                        android.view.inputmethod.InputMethodManager).showSoftInput(view, 0)
                    val deadline = android.os.SystemClock.uptimeMillis() + 5000
                    while (androidx.core.view.ViewCompat.getRootWindowInsets(view)
                        ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) != true &&
                        android.os.SystemClock.uptimeMillis() < deadline) {
                        controller.loopMainThreadForAtLeast(100)
                    }
                    assertTrue(androidx.core.view.ViewCompat.getRootWindowInsets(view)
                        ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true)
                }
            })
            onView(withId(R.id.btnSend)).check(matches(isDisplayed()))
            screenshot("assistant-keyboard")
            onView(withId(R.id.etMessage)).perform(closeSoftKeyboard())
            onView(withId(R.id.btnSend)).perform(click())
            onView(withId(R.id.etApiUrl)).check(matches(isDisplayed()))
            onView(withText(R.string.cancel)).perform(click())
            onView(withId(R.id.etMessage)).check(matches(withText(R.string.assistant_example_simple)))
            androidx.test.espresso.Espresso.pressBack()
            onView(withId(R.id.cardImportAssistant)).check(matches(isDisplayed()))
        }
    }

    private fun awaitLoaded(model: CourseAssistantViewModel) {
        val latch = java.util.concurrent.CountDownLatch(1)
        val observer = androidx.lifecycle.Observer<Boolean> { if (!it) latch.countDown() }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { model.busy.observeForever(observer) }
        try { assertTrue(latch.await(15, java.util.concurrent.TimeUnit.SECONDS)) }
        finally { instrumentation.runOnMainSync { model.busy.removeObserver(observer) } }
        instrumentation.waitForIdleSync()
    }

    @Test fun mainMenuOpensAssistantAndConfigurationDialog() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            screenshot("assistant-entry")
            onView(withContentDescription(R.string.course_tools)).perform(click())
            onView(withText(R.string.assistant_entry_description)).check(matches(isDisplayed()))
            screenshot("assistant-entry-menu")
            androidx.test.espresso.Espresso.pressBack()
            onView(withId(R.id.weekPager)).check(matches(isDisplayed()))
            onView(withContentDescription(R.string.course_tools)).perform(click())
            onView(withText(R.string.assistant_title)).perform(click())
            screenshot("assistant-chat")
            onView(withId(R.id.btnConfigureApi)).perform(click())
            onView(withId(R.id.etApiUrl)).check(matches(isDisplayed()))
            onView(withId(R.id.etApiModel)).check(matches(isDisplayed()))
            onView(withId(R.id.etApiKey)).check(matches(isDisplayed()))
            screenshot("assistant-api-config")
            onView(withText(R.string.cancel)).perform(click())
            onView(withId(R.id.btnSend)).check(matches(isDisplayed()))
        }
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
