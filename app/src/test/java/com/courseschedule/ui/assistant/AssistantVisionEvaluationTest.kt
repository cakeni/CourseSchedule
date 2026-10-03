package com.courseschedule.ui.assistant

import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.data.entity.StudyTask
import com.courseschedule.domain.StudyTaskRules
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64

/** Opt-in fixture exporter/parser for live model evaluation. Credentials never enter these files. */
class AssistantVisionEvaluationTest {
    @Test fun evaluateExportedVisionAndTaskFixtures() {
        val path = System.getenv("ASSISTANT_VISION_EVAL_DIR")
        assumeTrue("Live evaluation is opt-in", !path.isNullOrBlank())
        val dir = File(path!!).apply { mkdirs() }
        val today = LocalDate.of(2026, 10, 3)
        val semester = Semester(9, "2026秋季", LocalDate.of(2026, 8, 31).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), 20, true)
        val math = Course(11, "高等数学", classroom = "A101", dayOfWeek = 1, startSection = 1, endSection = 2, startWeek = 1, endWeek = 20, semesterId = 9)
        val task = StudyTask(31, 9, 11, "高等数学", "第三章习题", "homework", StudyTaskRules.deadline("2026-10-04", "20:00"))
        val tasks = listOf(task, task.copy(id = 32, title = "期中考试", kind = "exam", dueAt = StudyTaskRules.deadline("2026-10-09", "09:00")))
        data class Case(val id: String, val text: String, val image: String? = null, val courses: List<Course> = listOf(math), val check: (AssistantCourseReply) -> Unit)
        val cases = listOf(
            Case("notice", "请按图片通知调整课程，只调整这一次。", "notice.jpg") { r ->
                assertTrue(r.requiresConfirmation); assertEquals(1, r.updates.size)
                val rows = r.updates.single().replacements
                assertTrue(rows.any { it.dayOfWeek == 4 && it.startSection == 5 && it.endSection == 6 && it.startWeek == 6 && it.classroom == "B302" })
                assertTrue(rows.any { it.dayOfWeek == 1 && it.endWeek == 20 })
                assertTrue(r.deletions.isEmpty())
            },
            Case("timetable", "把截图上的课程加到课表，先让我核对。", "timetable.jpg", emptyList()) { r ->
                assertTrue(r.requiresConfirmation)
                assertEquals(setOf("大学英语", "线性代数"), r.courses.map { it.courseName }.toSet())
                assertTrue(r.courses.any { it.courseName == "大学英语" && it.dayOfWeek == 2 && it.startSection == 3 && it.endSection == 4 && it.classroom == "C201" })
                assertTrue(r.courses.any { it.courseName == "线性代数" && it.dayOfWeek == 4 && it.startSection == 5 && it.endSection == 6 && it.classroom == "D302" })
                assertTrue(r.courses.all { it.startWeek == 1 && it.endWeek == 16 })
            },
            Case("missing-weeks", "把截图中的课加入课表。", "missing-weeks.jpg", emptyList()) { r -> assertFalse(r.requiresConfirmation); assertTrue(r.reply.contains("周")) },
            Case("image-report", "把图片里的报告截止事项记下来，提前一天提醒。", "report.jpg") { r ->
                val saved = r.studyChanges!!.single().after!!
                assertEquals("report", saved.kind); assertEquals(StudyTaskRules.deadline("2026-10-08", "18:00"), saved.dueAt); assertEquals(1440, saved.reminderMinutes)
            },
            Case("image-no-time", "把这个作业截止事项记下来。", "no-time.jpg") { r -> assertFalse(r.requiresConfirmation); assertTrue(r.studyChanges.isNullOrEmpty()) },
            Case("image-instructions", "读出截图文字，先别改课程。", "instructions.jpg") { r -> assertFalse(r.requiresConfirmation); assertFalse(r.undo); assertTrue(r.studyChanges.isNullOrEmpty()) },
            Case("create-homework", "给高等数学记一个作业：第四章习题，明天晚上20点交，提前一小时提醒。") { r ->
                val saved = r.studyChanges!!.single().after!!; assertEquals("homework", saved.kind); assertEquals("高等数学", saved.courseName)
                assertEquals(StudyTaskRules.deadline("2026-10-04", "20:00"), saved.dueAt); assertEquals(60, saved.reminderMinutes)
            },
            Case("query-week", "这周还有什么作业或报告要交？") { r -> assertNotNull(r.studyQuery); assertEquals("week", r.studyQuery!!.window); assertEquals("pending", r.studyQuery.status) },
            Case("query-exams", "最近30天有什么考试？") { r -> assertNotNull(r.studyQuery); assertEquals("exam", r.studyQuery!!.kind); assertEquals("upcoming", r.studyQuery.window); assertEquals(30, r.studyQuery.daysAhead) },
            Case("complete-homework", "第三章习题已经交了，标记完成。") { r -> assertNotNull(r.studyChanges!!.single().after!!.completedAt); assertEquals(task, r.studyChanges.single().original) },
            Case("missing-time", "给高等数学记一个报告，下周五交。") { r -> assertFalse(r.requiresConfirmation); assertTrue(r.studyChanges.isNullOrEmpty()) }
        )
        val exported = JsonArray()
        val sectionTimes = List(12) { "%02d:00".format(8 + it) }
        cases.forEach { case ->
            val ref = case.image?.let { "evaluation/12345678-1234-1234-1234-123456789012.jpg" }
            val message = AssistantMessage("user", case.text, imageRef = ref)
            val images = if (case.image != null) mapOf(ref!! to "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(File(dir, case.image).readBytes())) else emptyMap()
            val request = AssistantCourseClient.createRequest("deepseek-flash", listOf(message), semester, 5, sectionTimes,
                sectionTimes.map { it.replace(":00", ":45") }, case.courses, imageData = images, today = today, studyTasks = tasks)
            File(dir, "${case.id}-request.json").writeText(request)
            exported.add(JsonObject().apply { addProperty("id", case.id); addProperty("image", case.image) })
            val response = File(dir, "${case.id}-response.json")
            if (response.exists()) case.check(AssistantCourseClient.parseResponse(response.readText(), semester.totalWeeks,
                case.courses, semester, today = today, studyTasks = tasks))
        }
        File(dir, "cases.json").writeText(exported.toString())
    }
}
