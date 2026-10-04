package com.courseschedule.ui.assistant

import com.courseschedule.data.entity.Semester
import com.courseschedule.domain.StudyTaskRules
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class AssistantLocalReminderTest {
    private val now = ZonedDateTime.parse("2026-10-04T13:00:00+08:00[Asia/Hong_Kong]")
    private fun reply(text: String, source: String? = null, pending: AssistantCourseReply? = null) =
        AssistantLocalReminder.reply(text, 5, now, source, pending)!!

    @Test fun missingTimeAsksThenFollowUpCreatesAConfirmedLocalReminder() {
        assertFalse(reply("提醒我吃饭").requiresConfirmation)
        val result = reply("今天18:00", source = "提醒我吃饭")
        val task = result.studyChanges!!.single().after!!
        assertEquals("吃饭", task.title)
        assertEquals("reminder", task.kind)
        assertEquals(0, task.reminderMinutes)
        assertEquals(now.withHour(18).toInstant().toEpochMilli(), task.dueAt)
        assertTrue(result.requiresConfirmation)
        assertEquals("", task.courseName)
        assertTrue(StudyTaskRules.describe(task).contains("准时提醒"))
    }

    @Test fun supportsTimeBeforeOrAfterTitleAndExplicitEvening() {
        listOf("明天18:30提醒我吃饭", "提醒我明天18:30吃饭", "明天下午6点半提醒我吃饭", "明天下午六点半提醒我吃饭").forEach {
            val task = reply(it).studyChanges!!.single().after!!
            assertEquals("吃饭", task.title)
            assertEquals(now.plusDays(1).withHour(18).withMinute(30).toInstant().toEpochMilli(), task.dueAt)
        }
    }

    @Test fun followUpInheritsTheDateOrClockAlreadyProvided() {
        assertEquals(now.plusDays(1).withHour(18).toInstant().toEpochMilli(), reply("18:00", source = "明天提醒我吃饭").studyChanges!!.single().after!!.dueAt)
        assertEquals(now.plusDays(1).withHour(18).toInstant().toEpochMilli(), reply("明天", source = "18:00提醒我吃饭").studyChanges!!.single().after!!.dueAt)
        assertEquals(now.plusMinutes(30).toInstant().toEpochMilli(), reply("三十分钟后提醒我喝水").studyChanges!!.single().after!!.dueAt)
        assertEquals(now.plusMinutes(30).toInstant().toEpochMilli(), reply("半小时后提醒我喝水").studyChanges!!.single().after!!.dueAt)
        assertTrue(reply("明天12:00", source = "每天12:00提醒我吃饭").requiresConfirmation)
        assertEquals(now.plusDays(1).withHour(18).toInstant().toEpochMilli(), reply("明天", source = "下午6点 提醒我吃饭").studyChanges!!.single().after!!.dueAt)
    }

    @Test fun relativeReminderUsesCurrentClockAndKeepsMinutes() {
        val task = reply("30分钟后提醒我喝水").studyChanges!!.single().after!!
        assertEquals(now.plusMinutes(30).toInstant().toEpochMilli(), task.dueAt)
        assertEquals("喝水", task.title)
    }

    @Test fun asksForDateAmbiguousClockPastTimeAndRecurringRequests() {
        listOf("18:00提醒我吃饭", "明天6点提醒我吃饭", "今天12:00提醒我吃饭", "每天12:00提醒我吃饭", "0分钟后提醒我吃饭", "明天25:00提醒我吃饭", "2026-02-30 12:00提醒我吃饭").forEach {
            assertFalse(it, reply(it).requiresConfirmation)
        }
    }

    @Test fun unrelatedCancellationAndCompoundRequestsStayOutOfTheLocalParser() {
        listOf("查看课表", "取消提醒我吃饭", "不要提醒我吃饭", "别提醒我吃饭", "明天12:00提醒我吃饭同时删除课程", "明天12:00提醒我吃饭，18:00提醒我喝水").forEach {
            assertNull(it, AssistantLocalReminder.reply(it, 5, now, source = "提醒我吃饭"))
        }
    }

    @Test fun pendingTimeRevisionKeepsTheOriginalReminderAndCannotBecomeACourse() {
        val pending = reply("明天12:00提醒我吃饭")
        val result = reply("改成明天18:00", pending = pending)
        assertTrue(result.revisedPending)
        assertEquals("吃饭", result.studyChanges!!.single().after!!.title)
        assertEquals(now.plusDays(1).withHour(18).toInstant().toEpochMilli(), result.studyChanges.single().after!!.dueAt)
        assertNull(AssistantLocalReminder.reply("查看课表", 5, now, pending = pending))
        val semester = Semester(5, "测试学期", 0, 20, true)
        val state = AssistantConversationState(pending = AssistantPendingOperation(semester, result.copy(revisedPending = false)))
        assertEquals(state, AssistantConversationCodec.decode(AssistantConversationCodec.encode(state), 5))
    }

    @Test fun remoteProtocolSupportsUnlinkedRemindersAndQueriesWithDefaultNotification() {
        val semester = Semester(5, "测试学期", 0, 20, true)
        val tomorrow = java.time.LocalDate.now().plusDays(1)
        val json = JsonParser.parseString("""{"version":1,"action":"task_change","reply":"核对提醒","taskCreates":[{"title":"吃饭","kind":"reminder","due":{"date":"$tomorrow","time":"12:00"}}]}""").asJsonObject
        val task = AssistantStudyProtocol.parse(json, semester, emptyList(), emptyList(), null, tomorrow.minusDays(1)).studyChanges!!.single().after!!
        assertEquals(0, task.reminderMinutes)
        assertEquals(listOf(task), AssistantStudyProtocol.query(AssistantStudyQuery(kind = "reminder"), listOf(task), tomorrow))
    }

    @Test fun anExpiredConfirmationCannotSaveASilentPastReminder() {
        val task = reply("今天18:00提醒我吃饭").studyChanges!!.single().after!!
        try {
            StudyTaskRules.validateReminderTime(task, now = task.dueAt + 1)
            fail("Expired reminder must be rejected")
        } catch (_: IllegalArgumentException) { }
        StudyTaskRules.validateReminderTime(task.copy(id = 10, note = "修改备注"), task.copy(id = 10), task.dueAt + 1)
    }
}
