package com.courseschedule.ui.assistant

import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.courseschedule.R
import com.courseschedule.data.AppDatabase
import com.courseschedule.data.entity.Semester
import com.courseschedule.data.entity.StudyTask
import com.courseschedule.utils.ReminderManager
import com.courseschedule.utils.SchedulePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matchers.allOf
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AssistantReminderRedesignTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val db by lazy { AppDatabase.getDatabase(context) }

    private suspend fun fixture(block: suspend (Semester) -> Unit) {
        val nightMode = AppCompatDelegate.getDefaultNightMode()
        val suffix = InstrumentationRegistry.getArguments().getString("proofSuffix", "light")
        withContext(Dispatchers.Main) {
            AppCompatDelegate.setDefaultNightMode(if (suffix == "dark") AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        }
        val previous = db.semesterDao().getCurrentSemesterSync()
        val semester = Semester(name = "日常安排验证", startDate = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
            .let { it.copy(id = db.semesterDao().insertSemester(it)) }
        db.semesterDao().switchCurrentSemester(semester.id)
        try { block(semester.copy(isCurrent = true)) } finally {
            db.studyTaskDao().forSemester(semester.id).forEach { ReminderManager(context).cancelStudyReminder(it.id) }
            db.semesterDao().deleteSemester(semester)
            previous?.let { db.semesterDao().switchCurrentSemester(it.id) }
            ReminderManager(context).restoreReminders()
            withContext(Dispatchers.Main) { AppCompatDelegate.setDefaultNightMode(nightMode) }
        }
    }

    private fun idle(model: CourseAssistantViewModel) {
        val latch = CountDownLatch(1)
        val observer = Observer<Boolean> { if (it == false) latch.countDown() }
        instrumentation.runOnMainSync { model.busy.observeForever(observer) }
        assertTrue("Assistant operation timed out", latch.await(15, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { model.busy.removeObserver(observer) }
        instrumentation.waitForIdleSync()
    }

    private fun screenshot(name: String) {
        val suffix = InstrumentationRegistry.getArguments().getString("proofSuffix", "light")
        val folder = File(context.getExternalFilesDir(null), "assistant-redesign-proof").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().also {
            File(folder, "$name-$suffix.png").outputStream().use { out -> it.compress(Bitmap.CompressFormat.PNG, 100, out) }
            it.recycle()
        }
    }

    @Test fun localReminderNeedsNoServiceAndSurvivesConfirmationAndRecreation(): Unit = runBlocking {
        fixture { semester ->
            ActivityScenario.launch<CourseAssistantActivity>(Intent(context, CourseAssistantActivity::class.java)).use { scenario ->
                lateinit var model: CourseAssistantViewModel
                scenario.onActivity { model = ViewModelProvider(it)[CourseAssistantViewModel::class.java] }
                idle(model)
                val config = model.config
                val remember = model.remembersKey
                try {
                    withContext(Dispatchers.Main) { model.clearConfig() }
                    screenshot("welcome")
                    onView(withId(R.id.btnExampleReminder)).perform(scrollTo(), click())
                    onView(withId(R.id.btnSend)).perform(click())
                    idle(model)
                    assertEquals("reminder_question", model.messages.value!!.last().kind)
                    assertTrue(db.studyTaskDao().forSemester(semester.id).isEmpty())
                    screenshot("ask-time")
                    scenario.recreate()
                    scenario.onActivity { model = ViewModelProvider(it)[CourseAssistantViewModel::class.java] }
                    idle(model)
                    onView(withId(R.id.etMessage)).perform(replaceText("下午6点"), closeSoftKeyboard())
                    onView(withId(R.id.btnSend)).perform(click())
                    idle(model)
                    assertEquals("reminder_question", model.messages.value!!.last().kind)
                    scenario.recreate()
                    scenario.onActivity { model = ViewModelProvider(it)[CourseAssistantViewModel::class.java] }
                    idle(model)
                    onView(withId(R.id.etMessage)).perform(replaceText("明天"), closeSoftKeyboard())
                    onView(withId(R.id.btnSend)).perform(click())
                    idle(model)
                    assertEquals("吃饭", model.pendingChanges.value!!.studyChanges!!.single().after!!.title)
                    assertTrue(db.studyTaskDao().forSemester(semester.id).isEmpty())
                    onView(withId(R.id.btnConfirmPending)).perform(scrollTo()).check(matches(withText("保存提醒")))
                    screenshot("confirm-reminder")
                    scenario.recreate()
                    scenario.onActivity { model = ViewModelProvider(it)[CourseAssistantViewModel::class.java] }
                    idle(model)
                    onView(withId(R.id.btnConfirmPending)).perform(scrollTo(), click())
                    idle(model)
                    val task = db.studyTaskDao().forSemester(semester.id).single()
                    assertEquals("reminder", task.kind)
                    assertEquals(0, task.reminderMinutes)
                    assertEquals("reminder_result", model.messages.value!!.last().kind)
                    screenshot("saved-reminder")
                    onView(allOf(withId(R.id.btnReminderSettings), isDisplayed())).check(matches(isDisplayed()))
                    ActivityScenario.launch<StudyTaskEditorActivity>(Intent(context, StudyTaskEditorActivity::class.java).putExtra("study_task_id", task.id)).use {
                        onView(withId(R.id.btnKindReminder)).check(matches(isChecked()))
                        onView(withId(R.id.tvTaskScheduleLabel)).check(matches(withText("提醒时间")))
                        screenshot("reminder-editor")
                    }
                } finally {
                    withContext(Dispatchers.Main) {
                        if (config.apiKey.isNotBlank()) model.configure(config, remember)
                    }
                }
            }
        }
    }

    @Test fun savedReminderAlarmDeliversAfterChatClosesAndDoesNotDuplicate(): Unit = runBlocking {
        fixture { semester ->
            val prefs = SchedulePreferences(context)
            val previous = prefs.reminderEnabled
            prefs.reminderEnabled = true
            val manager = ReminderManager(context)
            val task = StudyTask(semesterId = semester.id, title = "吃饭提醒验证", kind = "reminder", dueAt = System.currentTimeMillis() + 3000L, reminderMinutes = 0)
                .let { it.copy(id = db.studyTaskDao().insert(it)) }
            val history = context.getSharedPreferences("reminder_delivery", 0)
            val latch = CountDownLatch(1)
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == "study_delivered_${task.id}") latch.countDown()
            }
            history.registerOnSharedPreferenceChangeListener(listener)
            try {
                manager.setStudyReminder(task)
                assertTrue("Actual reminder notification did not arrive", latch.await(25, TimeUnit.SECONDS))
                val notifications = context.getSystemService(NotificationManager::class.java)
                val delivered = notifications.activeNotifications.single { it.tag == "study_${task.id}" }
                assertEquals("事项提醒", delivered.notification.extras.getString("android.title"))
                manager.restoreReminders()
                assertEquals(delivered.postTime, notifications.activeNotifications.single { it.tag == "study_${task.id}" }.postTime)
            } finally {
                history.unregisterOnSharedPreferenceChangeListener(listener)
                prefs.reminderEnabled = previous
            }
        }
    }
}
