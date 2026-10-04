package com.courseschedule.ui.assistant

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.courseschedule.data.AppDatabase
import com.courseschedule.data.entity.Semester
import com.courseschedule.data.entity.StudyTask
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

/** Two explicitly selected stages, with am force-stop between them. */
@RunWith(AndroidJUnit4::class)
class AssistantStudyRestartTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val db by lazy { AppDatabase.getDatabase(context) }
    private val file get() = File(context.getExternalFilesDir(null), "study-image-restart-fixture.json")
    private data class Fixture(val previous: Long?, val semester: Semester, val imageConversation: String,
        val pendingConversation: String, val imageRef: String)

    @Test fun prepareBeforeKill(): Unit = runBlocking {
        val previous = db.semesterDao().getCurrentSemesterSync()
        val semester = Semester(name = "图片事项重启验证", startDate = System.currentTimeMillis())
            .let { it.copy(id = db.semesterDao().insertSemester(it)) }
        db.semesterDao().switchCurrentSemester(semester.id)
        val current = db.semesterDao().getSemesterById(semester.id)!!
        try {
            ActivityScenario.launch<CourseAssistantActivity>(Intent(context, CourseAssistantActivity::class.java)).use { scenario ->
                lateinit var model: CourseAssistantViewModel
                scenario.onActivity { model = ViewModelProvider(it)[CourseAssistantViewModel::class.java] }; awaitIdle(model)
                val first = model.conversationId!!
                val source = File(context.cacheDir, "restart-image.png")
                val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
                source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                withContext(Dispatchers.Main) { model.selectImage(Uri.fromFile(source)) }; awaitIdle(model)
                source.delete()
                val ref = model.imageDraft.value!!
                withContext(Dispatchers.Main) { model.updateDraft("请识别这张课程截图"); model.newConversation() }; awaitIdle(model)
                val task = StudyTask(semesterId = semester.id, title = "重启后的实验报告", kind = "report", dueAt = System.currentTimeMillis() + 86400000L)
                withContext(Dispatchers.Main) {
                    model.receiveReply(AssistantCourseReply("核对报告", emptyList(), studyChanges = listOf(AssistantStudyChange(null, task))), current)
                    model.updateDraft("提前一天提醒")
                }
                file.writeText(Gson().toJson(Fixture(previous?.id, current, first, model.conversationId!!, ref)))
                assertTrue(db.studyTaskDao().forSemester(semester.id).isEmpty())
            }
        } catch (error: Throwable) {
            db.semesterDao().deleteSemester(semester); previous?.let { db.semesterDao().switchCurrentSemester(it.id) }; throw error
        }
    }

    @Test fun verifyAfterKill(): Unit = runBlocking {
        val fixture = Gson().fromJson(file.readText(), Fixture::class.java)
        try {
            ActivityScenario.launch<CourseAssistantActivity>(Intent(context, CourseAssistantActivity::class.java)).use { scenario ->
                lateinit var model: CourseAssistantViewModel
                scenario.onActivity { model = ViewModelProvider(it)[CourseAssistantViewModel::class.java] }; awaitIdle(model)
                assertEquals(fixture.pendingConversation, model.conversationId)
                assertEquals("提前一天提醒", model.draft.value)
                assertEquals("重启后的实验报告", model.pendingChanges.value!!.studyChanges!!.single().after!!.title)
                withContext(Dispatchers.Main) { model.confirmPending() }
                assertTrue(db.studyTaskDao().forSemester(fixture.semester.id).isEmpty())
                withContext(Dispatchers.Main) { model.updateDraft(""); model.confirmPending() }; awaitIdle(model)
                assertEquals(1, db.studyTaskDao().forSemester(fixture.semester.id).size)
                assertTrue(db.courseDao().getCoursesBySemesterSync(fixture.semester.id).isEmpty())
                withContext(Dispatchers.Main) { model.openHistory(fixture.imageConversation) }; awaitIdle(model)
                assertEquals("请识别这张课程截图", model.draft.value)
                assertEquals(fixture.imageRef, model.imageDraft.value)
                assertTrue(AssistantImages(context).file(fixture.imageRef).isFile)
                withContext(Dispatchers.Main) { model.deleteConversation() }; awaitIdle(model)
                assertFalse(AssistantImages(context).file(fixture.imageRef).exists())
            }
        } finally {
            db.semesterDao().deleteSemester(fixture.semester)
            fixture.previous?.let { db.semesterDao().switchCurrentSemester(it) }
            com.courseschedule.utils.ReminderManager(context).restoreReminders()
            file.delete()
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
