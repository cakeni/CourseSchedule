package com.courseschedule.ui.assistant

import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.data.entity.StudyTask
import com.courseschedule.domain.StudyTaskRules
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AssistantStudyProtocolTest {
    private val semester = Semester(5, "测试学期", 0, 20, true)
    private val course = Course(8, "高等数学", dayOfWeek = 1, startSection = 1, endSection = 2, startWeek = 1, endWeek = 20, semesterId = 5)
    private val today = LocalDate.of(2026, 10, 3)
    private val task = StudyTask(id = 10, semesterId = 5, courseId = 8, courseName = "高等数学", title = "第三章习题", dueAt = StudyTaskRules.deadline("2026-10-04", "20:00"))
    private fun parse(json: String, tasks: List<StudyTask> = listOf(task), pending: AssistantCourseReply? = null) =
        AssistantStudyProtocol.parse(JsonParser.parseString(json).asJsonObject, semester, listOf(course), tasks, pending, today)

    @Test fun creationResolvesRelativeDateAndRequiresConfirmation() {
        val reply = parse("""{"version":1,"action":"task_change","reply":"请核对","taskCreates":[{"title":"实验报告","kind":"report","courseName":"高等数学","due":{"weekOffset":1,"dayOfWeek":5,"time":"18:30"},"reminderMinutes":1440}]}""")
        val task = reply.studyChanges!!.single().after!!
        assertEquals(StudyTaskRules.deadline("2026-10-09", "18:30"), task.dueAt)
        assertEquals(8L, task.courseId)
        assertEquals(1440, task.reminderMinutes)
        assertTrue(reply.requiresConfirmation)
        assertTrue(reply.courses.isEmpty())
    }
    @Test fun completionPreservesOriginalDeadlineAndCourse() {
        val reply = parse("""{"version":1,"action":"task_change","reply":"确认完成","taskUpdates":[{"id":10,"targetTitle":"第三章习题","completed":true}]}""")
        val changed = reply.studyChanges!!.single()
        assertEquals(task, changed.original)
        assertEquals(task.dueAt, changed.after!!.dueAt)
        assertEquals(task.courseId, changed.after.courseId)
        assertNotNull(changed.after.completedAt)
    }
    @Test fun duplicateTitlesNeedOriginalDateOrExplicitId() {
        val tasks = listOf(task, task.copy(id = 11, dueAt = StudyTaskRules.deadline("2026-10-05", "20:00")))
        rejects { parse("""{"version":1,"action":"task_change","reply":"完成","taskUpdates":[{"id":10,"targetTitle":"第三章习题","completed":true}]}""", tasks) }
        assertTrue(parse("""{"version":1,"action":"task_change","reply":"完成","taskUpdates":[{"id":10,"targetTitle":"第三章习题","targetDueDate":"2026-10-04","completed":true}]}""", tasks).requiresConfirmation)
    }
    @Test fun missingTimeUnknownCourseAndMixedCourseWritesAreRejected() {
        rejects { parse("""{"version":1,"action":"task_change","reply":"创建","taskCreates":[{"title":"报告","kind":"report","due":{"dayOffset":1}}]}""") }
        rejects { parse("""{"version":1,"action":"task_change","reply":"创建","taskCreates":[{"title":"报告","kind":"report","courseName":"不存在","due":{"dayOffset":1,"time":"20:00"}}]}""") }
        rejects { parse("""{"version":1,"action":"task_query","reply":"查询","taskQuery":{"window":"week"},"courses":[]}""") }
    }
    @Test fun revisionRetainsTargetAndOriginalSnapshot() {
        val initial = parse("""{"version":1,"action":"task_change","reply":"修改","taskUpdates":[{"id":10,"targetTitle":"第三章习题","due":{"dayOffset":2,"time":"20:00"}}]}""")
        val revised = parse("""{"version":1,"action":"task_revise","reply":"补充","taskRevisions":[{"index":0,"reminderMinutes":60}]}""", pending = initial)
        assertTrue(revised.revisedPending)
        assertEquals(task, revised.studyChanges!!.single().original)
        assertEquals(initial.studyChanges!!.single().after!!.dueAt, revised.studyChanges.single().after!!.dueAt)
        assertEquals(60, revised.studyChanges.single().after!!.reminderMinutes)
        rejects { parse("""{"version":1,"action":"task_revise","reply":"补充","taskRevisions":[{"index":1,"title":"其他"}]}""", pending = initial) }
    }
    @Test fun queriesFilterByRealLocalDatesKindsAndCompletion() {
        val items = listOf(task, task.copy(id = 11, kind = "exam", dueAt = StudyTaskRules.deadline("2026-10-09", "09:00")), task.copy(id = 12, completedAt = 1234))
        assertEquals(listOf(task), AssistantStudyProtocol.query(AssistantStudyQuery(window = "week"), items, today))
        assertEquals(listOf(items[1]), AssistantStudyProtocol.query(AssistantStudyQuery(kind = "exam", window = "upcoming", daysAhead = 30), items, today))
        assertEquals(listOf(items[2]), AssistantStudyProtocol.query(AssistantStudyQuery(status = "completed"), items, today))
        rejects { AssistantStudyProtocol.validateQuery(AssistantStudyQuery(window = "range", startDate = "2026-10-05", endDate = "2026-10-04")) }
    }
    @Test fun completedDisabledAndOverdueTasksHaveNoTrigger() {
        assertNull(StudyTaskRules.trigger(task.copy(completedAt = 1234), task.dueAt - 1000))
        assertNull(StudyTaskRules.trigger(task, task.dueAt - 1000))
        assertNull(StudyTaskRules.trigger(task.copy(reminderMinutes = 0), task.dueAt + 1))
        assertEquals(task.dueAt - 60 * 60_000L, StudyTaskRules.trigger(task.copy(reminderMinutes = 60), task.dueAt - 60 * 60_000L - 1000))
    }
    @Test fun nonExistentLocalDeadlineAndInvalidTimesAreRejected() {
        rejects { StudyTaskRules.deadline("2026-03-08", "02:30", ZoneId.of("America/New_York")) }
        rejects { StudyTaskRules.deadline("2026-10-03", "24:00") }
        rejects { StudyTaskRules.deadline("2026-02-30", "20:00") }
    }
    @Test fun envelopesMayContainEmptyUnusedFieldsButNeverMixedWrites() {
        val empty = AssistantStudyProtocol.normalizeEnvelope(JsonParser.parseString("""{"action":"task_change","courses":[],"updates":[],"undo":false}""").asJsonObject)
        assertFalse(empty.has("courses")); assertFalse(empty.has("undo"))
        rejects { AssistantStudyProtocol.normalizeEnvelope(JsonParser.parseString("""{"action":"task_change","courses":[{"courseName":"不能同时加课"}]}""").asJsonObject) }
        rejects { AssistantStudyProtocol.normalizeEnvelope(JsonParser.parseString("""{"action":"change","taskCreates":[{"title":"不能同时加任务"}]}""").asJsonObject) }
    }
    @Test fun taskPendingAndImageRetrySurviveStateCodec() {
        val reply = parse("""{"version":1,"action":"task_change","reply":"完成","taskUpdates":[{"id":10,"targetTitle":"第三章习题","completed":true}]}""")
        val state = AssistantConversationState(pending = AssistantPendingOperation(semester, reply))
        assertEquals(state, AssistantConversationCodec.decode(AssistantConversationCodec.encode(state), 5))
        val ref = "test-conversation/12345678-1234-1234-1234-123456789012.jpg"
        val retry = AssistantConversationState(retryRequest = AssistantRetryRequest("识别图片", 1, "2026-10-03", ref), requestRunning = true)
        assertEquals(retry, AssistantConversationCodec.decode(AssistantConversationCodec.encode(retry), 5))
        rejects { AssistantImageMessage.encode("截图", "../../secret.jpg") }
    }
    private fun rejects(block: () -> Unit) { try { block(); fail("Must reject invalid operation") } catch (_: IllegalArgumentException) { } }
}
