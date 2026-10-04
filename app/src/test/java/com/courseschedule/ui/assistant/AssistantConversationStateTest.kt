package com.courseschedule.ui.assistant

import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import org.junit.Assert.*
import org.junit.Test

class AssistantConversationStateTest {
    @Test fun persistedPlanAndUndoRetainFullIdentityAndMetadata() {
        val semester = Semester(id = 7, name = "学期", startDate = 1000, totalWeeks = 16, createTime = 100)
        val before = Course(id = 12, semesterId = 7, courseName = "高等数学", teacher = "张老师", classroom = "A101",
            dayOfWeek = 3, startSection = 1, endSection = 2, startWeek = 1, endWeek = 16,
            colorIndex = 8, reminderMinutes = 15, note = "保留原备注", createTime = 1234)
        val after = before.copy(classroom = "B201")
        val state = AssistantConversationState(pending = AssistantPendingOperation(semester,
            AssistantCourseReply("准备修改", emptyList(), updates = listOf(AssistantCourseUpdate(before, listOf(after))))),
            lastUndo = AssistantUndoBatch(semester, listOf(before), listOf(after),
                listOf(before.copy(id = 13, courseName = "原有冲突课程"))))
        assertEquals(state, AssistantConversationCodec.decode(AssistantConversationCodec.encode(state), 7))
        val retry = AssistantConversationState(retryRequest = AssistantRetryRequest("周三早八加课", 2), requestRunning = true)
        assertEquals(retry, AssistantConversationCodec.decode(AssistantConversationCodec.encode(retry), 7))
        assertEquals(AssistantConversationState(), AssistantConversationCodec.decode("{}", 7))
        assertFalse(AssistantConversationCodec.encode(state).contains("apiKey"))
    }

    @Test fun damagedOrForeignStateCannotBecomeAnExecutablePlan() {
        val semester = Semester(id = 7, name = "学期", startDate = 1000)
        val course = Course(id = 12, semesterId = 7, courseName = "数学", dayOfWeek = 3,
            startSection = 1, endSection = 1, startWeek = 1, endWeek = 16)
        val valid = AssistantConversationCodec.encode(AssistantConversationState(pending = AssistantPendingOperation(semester,
            AssistantCourseReply("删除", emptyList(), deletions = listOf(course)))))
        listOf("null", "{broken", "{\"requestRunning\":true}",
            valid.replace("\"deletions\":[", "\"deletions\":null,\"unknown\":["),
            valid.replace("\"teacher\":\"\"", "\"teacher\":null"),
            valid.replace("\"dayOfWeek\":3", "\"dayOfWeek\":8")).forEach {
            assertThrows(Exception::class.java) { AssistantConversationCodec.decode(it, 7) }
        }
        assertThrows(Exception::class.java) { AssistantConversationCodec.decode(valid, 8) }
    }
}
