package com.courseschedule.ui.assistant

import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AssistantTargetSelectionTest {
    private val today = LocalDate.of(2026, 9, 8)
    private val semester = Semester(id = 7, name = "测试学期", totalWeeks = 16,
        startDate = LocalDate.of(2026, 8, 31).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
    private val first = Course(id = 12, semesterId = 7, courseName = "数学", teacher = "张老师", classroom = "A101",
        dayOfWeek = 3, startSection = 1, endSection = 2, startWeek = 1, endWeek = 16)
    private val second = first.copy(id = 13, dayOfWeek = 5, teacher = "李老师", classroom = "C101", note = "原备注")
    private fun parse(payload: String, bound: Course? = null, rows: List<Course> = listOf(first, second)): AssistantCourseReply {
        val response = JsonObject().apply { add("choices", JsonArray().apply { add(JsonObject().apply {
            addProperty("finish_reason", "stop")
            add("message", JsonObject().apply { addProperty("content", payload) })
        }) }) }.toString()
        return AssistantCourseClient.parseResponse(response, 16, rows, semester, 2, today = today, selectedTarget = bound)
    }

    @Test fun ambiguousLegacyOrConditionBasedWriteBecomesChoiceWithoutPlan() {
        for (target in listOf("", "\"targetQuery\":{\"courseName\":\"数学\"},")) {
            val reply = parse("""{"version":1,"action":"change","reply":"改教室",$target"updates":[{"id":12,"classroom":"B201"}]}""")
            assertEquals(listOf(first, second), reply.targetCandidates)
            assertFalse(reply.requiresConfirmation)
            assertTrue(reply.updates.isEmpty())
            assertTrue(reply.deletions.isEmpty())
        }
    }

    @Test fun sufficientOriginalConditionsAvoidChoiceAndCannotPointToDifferentId() {
        val reply = parse("""{"version":1,"action":"change","reply":"改周五数学",
            "targetQuery":{"courseName":"数学","dayOfWeek":5},"updates":[{"id":13,"classroom":"B201"}]}""")
        assertNull(reply.targetCandidates)
        assertEquals(second.copy(classroom = "B201"), reply.updates.single().replacements.single())
        assertThrows(IllegalArgumentException::class.java) { parse("""{"version":1,"action":"change","reply":"目标不符",
            "targetQuery":{"courseName":"数学","dayOfWeek":5},"deleteIds":[12]}""") }
    }

    @Test fun clarificationUsesLocalCandidatesAndDateFiltersIncludeOddWeeks() {
        val reply = parse("""{"version":1,"action":"clarify","reply":"哪门数学",
            "targetQuery":{"courseName":"数学"}}""")
        assertEquals(listOf(first, second), reply.targetCandidates)
        val filtered = parse("""{"version":1,"action":"change","reply":"改本周数学",
            "targetQuery":{"courseName":"数学","whenTo":{"week":2}},"updates":[{"id":12,"note":"新备注"}]}""",
            rows = listOf(first, second.copy(weekType = 1)))
        assertNull(filtered.targetCandidates)
        assertEquals(first, filtered.updates.single().original)
    }

    @Test fun chosenTargetPreservesItsOwnMetadataAndRejectsWrongOrAdditionalTargets() {
        val reply = parse("""{"version":1,"action":"change","reply":"改教室","updates":[{"id":13,"classroom":"B201"}]}""", second)
        assertEquals(second.copy(classroom = "B201"), reply.updates.single().replacements.single())
        for (payload in listOf(
            """{"version":1,"action":"change","reply":"错误目标","deleteIds":[12]}""",
            """{"version":1,"action":"change","reply":"附带目标","deleteIds":[12,13]}""")) {
            assertThrows(IllegalArgumentException::class.java) { parse(payload, second) }
        }
        assertThrows(IllegalArgumentException::class.java) { parse(
            """{"version":1,"action":"change","reply":"过期目标","deleteIds":[13]}""", second.copy(note = "已变化")) }
    }

    @Test fun occurrencesIncludingCopiesRequireSelectionAndQueriesDoNot() {
        val copy = parse("""{"version":1,"action":"change","reply":"补课","occurrences":[
            {"id":12,"operation":"copy","source":{"week":2},"target":{"dayOfWeek":6,"startSection":3}}]}""")
        assertEquals(listOf(first, second), copy.targetCandidates)
        assertTrue(copy.courses.isEmpty())
        val query = parse("""{"version":1,"action":"query","reply":"查数学","query":{"courseName":"数学"}}""")
        assertNull(query.targetCandidates)
        assertNotNull(query.query)
    }

    @Test fun chosenRequestAndSelectionSurvivePersistenceWhileLegacyDateRemainsUnknown() {
        val request = AssistantRetryRequest("明天改数学", 2, today.toString())
        val state = AssistantConversationState(targetChoice = AssistantTargetChoice(semester, request, listOf(first, second)),
            selectedTarget = first)
        assertEquals(state, AssistantConversationCodec.decode(AssistantConversationCodec.encode(state), 7))
        val retry = AssistantConversationState(retryRequest = request, selectedTarget = second)
        assertEquals(retry, AssistantConversationCodec.decode(AssistantConversationCodec.encode(retry), 7))
        assertNull(AssistantConversationCodec.decode("""{"retryRequest":{"text":"明天查课","displayedWeek":2}}""", 7).retryRequest!!.requestDate)
        assertThrows(Exception::class.java) { AssistantConversationCodec.decode(
            AssistantConversationCodec.encode(retry.copy(retryRequest = request.copy(requestDate = "bad-date"))), 7) }
    }
}
