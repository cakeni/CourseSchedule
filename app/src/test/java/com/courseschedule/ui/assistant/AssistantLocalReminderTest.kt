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
        listOf("1小时30分钟后提醒我喝水", "一个半小时后提醒我喝水").forEach {
            assertEquals(it, now.plusMinutes(90).toInstant().toEpochMilli(), reply(it).studyChanges!!.single().after!!.dueAt)
        }
        assertEquals(now.plusMinutes(30).toInstant().toEpochMilli(), reply("半个小时后提醒我喝水").studyChanges!!.single().after!!.dueAt)
        listOf("明天下午三点一刻提醒我喝水" to 15, "明天下午三点三刻提醒我喝水" to 45).forEach { (text, minute) ->
            assertEquals(text, now.plusDays(1).withHour(15).withMinute(minute).toInstant().toEpochMilli(), reply(text).studyChanges!!.single().after!!.dueAt)
        }
    }

    @Test fun asksForDateAmbiguousClockPastTimeAndRecurringRequests() {
        listOf("明天6点提醒我吃饭", "今天12:00提醒我吃饭", "每天12:00提醒我吃饭", "0分钟后提醒我吃饭", "明天25:00提醒我吃饭", "2026-02-30 12:00提醒我吃饭").forEach {
            assertFalse(it, reply(it).requiresConfirmation)
        }
    }

    @Test fun screenshotSequenceAndDottedTimeStayLocal() {
        assertFalse(reply("三点半提醒我").requiresConfirmation)
        val reminder = reply("14.05提醒我喝水").studyChanges!!.single().after!!
        assertEquals("喝水", reminder.title)
        assertEquals(now.withHour(14).withMinute(5).toInstant().toEpochMilli(), reminder.dueAt)
        assertEquals(reminder.dueAt, reply("14.05", source = "提醒我喝水").studyChanges!!.single().after!!.dueAt)
        val failedHistory = listOf(AssistantMessage("user", "14.05提醒我喝水"),
            AssistantMessage("assistant", "想在什么时候提醒？", "reminder_question"),
            AssistantMessage("user", "14.05"), AssistantMessage("assistant", "API 操作格式不完整", "error"))
        assertEquals(reminder.dueAt, reply("14.05", AssistantLocalReminder.followUpSource(failedHistory)).studyChanges!!.single().after!!.dueAt)
        listOf("明天14．05提醒我喝水", "明天十四点零五分提醒我喝水", "明天十四点零五提醒我喝水").forEach {
            assertEquals(it, now.plusDays(1).withHour(14).withMinute(5).toInstant().toEpochMilli(), reply(it).studyChanges!!.single().after!!.dueAt)
        }
    }

    @Test fun missingTitleAndPeriodCanBeSuppliedAcrossSeveralTurns() {
        val first = reply("三点半提醒我")
        val messages = mutableListOf(AssistantMessage("user", "三点半提醒我"), AssistantMessage("assistant", first.reply, "reminder_question"))
        val second = reply("喝水", AssistantLocalReminder.followUpSource(messages))
        assertFalse(second.requiresConfirmation)
        messages += listOf(AssistantMessage("user", "喝水"), AssistantMessage("assistant", second.reply, "reminder_question"))
        val task = reply("下午", AssistantLocalReminder.followUpSource(messages)).studyChanges!!.single().after!!
        assertEquals("喝水", task.title)
        assertEquals(now.withHour(15).withMinute(30).toInstant().toEpochMilli(), task.dueAt)
        assertNull(AssistantLocalReminder.followUpSource(messages + AssistantMessage("assistant", "保存成功", "reminder_result")))
        assertNull(AssistantLocalReminder.followUpSource(messages + listOf(AssistantMessage("assistant", "已取消", "cancel"),
            AssistantMessage("assistant", "什么时间？", "reminder_question"))))
        assertNull(AssistantLocalReminder.reply("查看课表", 5, now, source = "三点半提醒我"))
    }

    @Test fun correctionsReplaceOldTimingAndDoNotReadDatesFromTheEventTitle() {
        assertEquals(now.plusDays(1).withHour(8).toInstant().toEpochMilli(),
            reply("08:00", source = "明天下午6点提醒我吃饭").studyChanges!!.single().after!!.dueAt)
        assertEquals(now.plusDays(2).withHour(18).toInstant().toEpochMilli(),
            reply("后天", source = "提醒我吃饭 明天18:00").studyChanges!!.single().after!!.dueAt)
        val task = reply("明天18:00提醒我复习后天的考试").studyChanges!!.single().after!!
        assertEquals("复习后天的考试", task.title)
        assertEquals(now.plusDays(1).withHour(18).toInstant().toEpochMilli(), task.dueAt)
        val pending = reply("明天18:00提醒我吃饭")
        assertEquals(pending.studyChanges!!.single().after!!.dueAt,
            reply("提醒我喝水", pending = pending).studyChanges!!.single().after!!.dueAt)
    }

    @Test fun invalidAndPastClocksAskWithoutFallingBackToAnOlderValidClock() {
        listOf("25.00", "14.99", "24:00", "12.05").forEach {
            assertFalse(it, reply(it, source = "提醒我喝水 今天18:00").requiresConfirmation)
        }
        assertFalse(reply("14.05提醒我喝水", source = "明天18:00提醒我吃饭").revisedPending)
        assertFalse(reply("明天18:00提醒我" + "长".repeat(121)).requiresConfirmation)
    }

    @Test fun unrelatedCancellationAndCompoundRequestsStayOutOfTheLocalParser() {
        listOf("查看课表", "取消提醒我吃饭", "不要提醒我吃饭", "别提醒我吃饭", "别再 今天18:00提醒我吃饭", "明天12:00提醒我吃饭同时删除课程", "明天12:00提醒我吃饭，18:00提醒我喝水", "下周三14.05提醒我吃饭").forEach {
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
        val original = pending.studyChanges!!.single().after!!.copy(title = "明天的考试", reminderMinutes = 30)
        val custom = pending.copy(studyChanges = listOf(AssistantStudyChange(null, original)))
        val revised = reply("明天18.05", pending = custom).studyChanges!!.single().after!!
        assertEquals(original.title, revised.title)
        assertEquals(original.reminderMinutes, revised.reminderMinutes)
        assertFalse(reply("2100-01-01 18:00提醒我吃饭").requiresConfirmation)
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
