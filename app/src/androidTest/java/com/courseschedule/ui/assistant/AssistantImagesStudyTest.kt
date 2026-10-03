package com.courseschedule.ui.assistant

import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
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
import com.courseschedule.data.StudyTaskStore
import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.data.entity.StudyTask
import com.courseschedule.domain.StudyTaskRules
import com.courseschedule.utils.AlarmReceiver
import com.courseschedule.utils.ReminderManager
import com.courseschedule.utils.SchedulePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AssistantImagesStudyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val db by lazy { AppDatabase.getDatabase(context) }

    private suspend fun fixture(block: suspend (Semester) -> Unit) {
        val old = db.semesterDao().getCurrentSemesterSync()
        val semester = Semester(name = "学习事项验证", startDate = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), totalWeeks = 20)
            .let { it.copy(id = db.semesterDao().insertSemester(it)) }
        db.semesterDao().switchCurrentSemester(semester.id)
        try { block(semester.copy(isCurrent = true)) } finally {
            db.studyTaskDao().forSemester(semester.id).forEach { ReminderManager(context).cancelStudyReminder(it.id) }
            db.courseDao().deleteCoursesBySemester(semester.id)
            db.semesterDao().deleteSemester(semester)
            old?.let { db.semesterDao().switchCurrentSemester(it.id) }
            ReminderManager(context).restoreReminders()
        }
    }
    private fun awaitIdle(model: CourseAssistantViewModel) {
        val latch = CountDownLatch(1)
        val observer = Observer<Boolean> { if (it == false) latch.countDown() }
        instrumentation.runOnMainSync { model.busy.observeForever(observer) }
        assertTrue("Assistant operation did not finish", latch.await(15, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { model.busy.removeObserver(observer) }
        instrumentation.waitForIdleSync()
    }
    private fun screenshot(name: String) {
        val dir = File(context.getExternalFilesDir(null), "images-study-proof").apply { mkdirs() }
        val suffix = InstrumentationRegistry.getArguments().getString("proofSuffix", "light")
        instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
            File(dir, "${name.removeSuffix("-light")}-$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
    private fun awaitTasks(id: Long, predicate: (List<StudyTask>) -> Boolean) {
        val latch = CountDownLatch(1)
        val live = db.studyTaskDao().observe(id)
        val observer = Observer<List<StudyTask>> { if (predicate(it)) latch.countDown() }
        instrumentation.runOnMainSync { live.observeForever(observer) }
        assertTrue(latch.await(15, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { live.removeObserver(observer) }
        instrumentation.waitForIdleSync()
    }
    private fun image(): File {
        val file = File(context.cacheDir, "notice.png")
        val bitmap = Bitmap.createBitmap(1080, 800, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 48f }
        listOf("课程调整通知", "高等数学：仅第6周周一第1–2节", "调整至第6周周四第5–6节", "教室 B302，其余周次保持不变").forEachIndexed { index, text -> canvas.drawText(text, 60f, 120f + index * 120f, paint) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        return file
    }

    @Test fun selectedImageSurvivesRotationAndNewConversationAndDeletionRemovesCopies(): Unit = runBlocking {
        fixture { semester ->
            ActivityScenario.launch<CourseAssistantActivity>(Intent(context, CourseAssistantActivity::class.java)).use { scenario ->
                lateinit var model: CourseAssistantViewModel
                scenario.onActivity { model = ViewModelProvider(it)[CourseAssistantViewModel::class.java] }
                awaitIdle(model)
                val first = model.conversationId!!
                val source = image()
                withContext(Dispatchers.Main) { model.selectImage(Uri.fromFile(source)) }
                awaitIdle(model)
                val ref = model.imageDraft.value!!
                source.delete()
                assertTrue(AssistantImages(context).file(ref).exists())
                onView(withId(R.id.imagePreview)).check(matches(isDisplayed()))
                onView(withId(R.id.btnSend)).check(matches(isEnabled()))
                screenshot("image-composer-light")
                scenario.recreate()
                scenario.onActivity { model = ViewModelProvider(it)[CourseAssistantViewModel::class.java] }
                awaitIdle(model)
                assertEquals(ref, model.imageDraft.value)
                withContext(Dispatchers.Main) { model.newConversation() }; awaitIdle(model)
                assertNull(model.imageDraft.value)
                withContext(Dispatchers.Main) { model.openHistory(first) }; awaitIdle(model)
                assertEquals(ref, model.imageDraft.value)
                withContext(Dispatchers.Main) { model.deleteConversation() }; awaitIdle(model)
                assertFalse(AssistantImages(context).file(ref).exists())
                assertTrue(db.courseDao().getCoursesBySemesterSync(semester.id).isEmpty())
            }
        }
    }

    @Test fun taskPreviewRequiresConfirmationAndUnsavedSupplementBlocksOldPlan(): Unit = runBlocking {
        fixture { semester ->
            ActivityScenario.launch<CourseAssistantActivity>(Intent(context, CourseAssistantActivity::class.java)).use { scenario ->
                lateinit var model: CourseAssistantViewModel
                scenario.onActivity { model = ViewModelProvider(it)[CourseAssistantViewModel::class.java] }; awaitIdle(model)
                val task = StudyTask(semesterId = semester.id, title = "物理实验报告", kind = "report", dueAt = System.currentTimeMillis() + 3 * 86400000L, reminderMinutes = 60)
                withContext(Dispatchers.Main) { model.receiveReply(AssistantCourseReply("请核对", emptyList(), studyChanges = listOf(AssistantStudyChange(null, task))), semester) }
                assertTrue(db.studyTaskDao().forSemester(semester.id).isEmpty())
                onView(withId(R.id.etMessage)).check(matches(withHint(R.string.study_revision_hint)))
                onView(withId(R.id.btnConfirmPending)).perform(scrollTo()).check(matches(isEnabled()))
                screenshot("task-pending-light")
                scenario.recreate(); scenario.onActivity { model = ViewModelProvider(it)[CourseAssistantViewModel::class.java] }; awaitIdle(model)
                assertNotNull(model.pendingChanges.value)
                onView(withId(R.id.etMessage)).perform(replaceText("改成后天交"))
                onView(withId(R.id.btnConfirmPending)).perform(scrollTo()).check(matches(org.hamcrest.Matchers.not(isEnabled())))
                withContext(Dispatchers.Main) { model.confirmPending() }
                assertTrue(db.studyTaskDao().forSemester(semester.id).isEmpty())
                onView(withId(R.id.etMessage)).perform(replaceText("")); androidx.test.espresso.Espresso.closeSoftKeyboard()
                onView(withId(R.id.btnConfirmPending)).perform(scrollTo(), click()); awaitIdle(model)
                assertEquals(1, db.studyTaskDao().forSemester(semester.id).size)
                assertTrue(db.courseDao().getCoursesBySemesterSync(semester.id).isEmpty())
            }
        }
    }

    @Test fun staleTaskConfirmationDoesNotOverwriteAnEdit(): Unit = runBlocking {
        fixture { semester ->
            val task = StudyTaskStore(context).save(StudyTask(semesterId = semester.id, title = "课程作业", dueAt = System.currentTimeMillis() + 86400000L))
            ActivityScenario.launch<CourseAssistantActivity>(Intent(context, CourseAssistantActivity::class.java)).use { scenario ->
                lateinit var model: CourseAssistantViewModel
                scenario.onActivity { model = ViewModelProvider(it)[CourseAssistantViewModel::class.java] }; awaitIdle(model)
                withContext(Dispatchers.Main) { model.receiveReply(AssistantCourseReply("完成", emptyList(), studyChanges = listOf(AssistantStudyChange(task, task.copy(completedAt = 1234)))), semester) }
                val edited = StudyTaskStore(context).save(task.copy(note = "其他页面已修改", updatedAt = task.updatedAt + 1), task)
                withContext(Dispatchers.Main) { model.confirmPending() }; awaitIdle(model)
                assertEquals(edited, db.studyTaskDao().find(task.id))
                assertNull(model.pendingChanges.value)
            }
        }
    }

    @Test fun studyListCompletionReopenAndCourseDeletionKeepDeadline(): Unit = runBlocking {
        fixture { semester ->
            val course = Course(courseName = "高等数学", dayOfWeek = 1, startSection = 1, endSection = 2, startWeek = 1, endWeek = 20, semesterId = semester.id)
                .let { it.copy(id = db.courseDao().insertCourse(it)) }
            val store = StudyTaskStore(context)
            val first = store.save(StudyTask(semesterId = semester.id, courseId = course.id, courseName = course.courseName, title = "第三章习题", dueAt = System.currentTimeMillis() + 86400000L, reminderMinutes = 60))
            store.save(StudyTask(semesterId = semester.id, title = "实验报告", kind = "report", dueAt = System.currentTimeMillis() + 2 * 86400000L))
            store.save(StudyTask(semesterId = semester.id, courseName = "", title = "期中考试", kind = "exam", dueAt = System.currentTimeMillis() + 5 * 86400000L))
            db.courseDao().deleteCourseById(course.id)
            assertEquals(first, db.studyTaskDao().find(first.id))
            ActivityScenario.launch<StudyTasksActivity>(Intent(context, StudyTasksActivity::class.java)).use { scenario ->
                instrumentation.waitForIdleSync()
                onView(withText("第三章习题")).check(matches(isDisplayed()))
                screenshot("study-list-light")
                onView(withContentDescription("完成 第三章习题")).perform(click())
                awaitTasks(semester.id) { it.find { row -> row.id == first.id }?.completedAt != null }
                onView(withId(R.id.btnTasksCompleted)).perform(click())
                onView(withText("第三章习题")).check(matches(isDisplayed()))
                screenshot("study-completed-light")
                onView(withContentDescription("重新打开 第三章习题")).perform(click())
                awaitTasks(semester.id) { it.find { row -> row.id == first.id }?.completedAt == null }
                assertNull(db.studyTaskDao().find(first.id)!!.completedAt)
                onView(withId(R.id.btnAddTask)).perform(click())
                onView(withText("保存")).perform(click())
                onView(withId(R.id.tvTaskError)).check(matches(withText("请选择明确的截止日期和时间。")))
                screenshot("study-form-light")
                onView(withId(R.id.btnTaskCancel)).perform(click())
            }
            ActivityScenario.launch<StudyTaskEditorActivity>(Intent(context, StudyTaskEditorActivity::class.java)).use { editor ->
                onView(withId(R.id.btnTaskSave)).check(matches(isEnabled()))
                editor.recreate()
                onView(withId(R.id.etTaskTitle)).check(matches(isDisplayed()))
            }
        }
    }

    @Test fun staleAndCompletedStudyBroadcastsCannotNotifyAndValidReminderIsDeduplicated(): Unit = runBlocking {
        fixture { semester ->
            val prefs = SchedulePreferences(context)
            val oldEnabled = prefs.reminderEnabled
            prefs.reminderEnabled = true
            val manager = ReminderManager(context)
            val notifications = context.getSystemService(NotificationManager::class.java)
            var task = StudyTask(semesterId = semester.id, title = "提醒验证事项", dueAt = System.currentTimeMillis() + 10 * 60_000L, reminderMinutes = 60)
                .let { it.copy(id = db.studyTaskDao().insert(it)) }
            fun deliver(stamp: String) {
                PendingIntent.getBroadcast(context, 812345, Intent(context, AlarmReceiver::class.java)
                    .putExtra("study_task_id", task.id).putExtra("study_stamp", stamp), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE).send()
                android.os.SystemClock.sleep(1200)
            }
            fun notification() = notifications.activeNotifications.firstOrNull { it.tag == "study_${task.id}" }
            try {
                // Keep the test focused on the broadcast path; the manager uses its normal PendingIntent identity.
                manager.cancelStudyReminder(task.id)
                val stamp = "${task.dueAt}:${task.reminderMinutes}:${task.updatedAt}"
                deliver(stamp)
                assertNotNull(notification())
                val posted = notification()!!.postTime
                deliver(stamp)
                assertEquals(posted, notification()!!.postTime)
                task = StudyTaskStore(context).save(task.copy(completedAt = System.currentTimeMillis(), updatedAt = task.updatedAt + 1), task)
                assertNull(notification())
                deliver(stamp)
                assertNull(notification())
                assertNull(PendingIntent.getBroadcast(context, 0, Intent(context, AlarmReceiver::class.java).apply {
                    action = "com.courseschedule.STUDY_REMINDER"; data = Uri.parse("courseschedule://study/${task.id}")
                }, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE))
            } finally { prefs.reminderEnabled = oldEnabled; manager.cancelStudyReminder(task.id) }
        }
    }

    @Test fun realStudyAlarmSurvivesReminderRestoreWithoutDuplicateDelivery(): Unit = runBlocking {
        fixture { semester ->
            val prefs = SchedulePreferences(context)
            val oldEnabled = prefs.reminderEnabled
            prefs.reminderEnabled = true
            val manager = ReminderManager(context)
            val task = StudyTask(semesterId = semester.id, title = "实际闹钟验证", dueAt = System.currentTimeMillis() + 10 * 60_000L, reminderMinutes = 60)
                .let { it.copy(id = db.studyTaskDao().insert(it)) }
            val history = context.getSharedPreferences("reminder_delivery", 0)
            val latch = CountDownLatch(1)
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == "study_delivered_${task.id}") latch.countDown()
            }
            history.registerOnSharedPreferenceChangeListener(listener)
            try {
                manager.setStudyReminder(task)
                assertTrue("Actual AlarmManager delivery must occur", latch.await(20, TimeUnit.SECONDS))
                val notifications = context.getSystemService(NotificationManager::class.java)
                val posted = notifications.activeNotifications.first { it.tag == "study_${task.id}" }.postTime
                manager.restoreReminders()
                assertEquals(posted, notifications.activeNotifications.first { it.tag == "study_${task.id}" }.postTime)
                assertTrue(manager.studyDelivered(task.id, "${task.dueAt}:${task.reminderMinutes}:${task.updatedAt}"))
            } finally { history.unregisterOnSharedPreferenceChangeListener(listener); prefs.reminderEnabled = oldEnabled }
        }
    }
}
