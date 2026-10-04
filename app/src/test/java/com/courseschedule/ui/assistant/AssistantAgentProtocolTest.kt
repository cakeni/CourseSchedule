package com.courseschedule.ui.assistant

import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class AssistantAgentProtocolTest {
    private val today = LocalDate.now()
    private val semester = Semester(id = 7, name = "协议测试", totalWeeks = 16,
        startDate = today.minusWeeks(2).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
    private val original = Course(id = 42, semesterId = 7, courseName = "数学", classroom = "A101",
        teacher = "张老师", dayOfWeek = 2, startSection = 1, endSection = 2, startWeek = 1, endWeek = 16)
    private val addition = """{"courses":[{"courseName":"英语","teacher":"","classroom":"","dayOfWeek":3,"startSection":3,"endSection":4,"weeks":[1,2]}]}"""
    private val pending = AssistantCourseReply("待确认", listOf(original.copy(id = 0)))
    private val config = AssistantApiConfig("https://example.com/v1", "synthetic-model", "synthetic-key")

    private fun request(plan: AssistantCourseReply? = null, undo: Boolean = false) = AssistantCourseClient.createRequest(
        config.model, listOf(AssistantMessage("user", "测试请求")), semester, 3, listOf("08:00"), listOf("08:45"),
        listOf(original), undo, pending = plan, today = today)
    private fun parse(body: String, plan: AssistantCourseReply? = null, selected: Course? = null,
        tools: Set<String>? = null, legacy: Boolean = false) = AssistantCourseClient.parseResponse(body, 16,
        listOf(original), semester, 3, plan, today, selected, allowLegacyOperations = legacy, allowedTools = tools)
    private fun text(value: String, finish: String = "stop") = JsonObject().apply {
        add("choices", JsonParser.parseString("""[{"finish_reason":"$finish","message":{}}]"""))
        getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message").addProperty("content", value)
    }.toString()
    private fun tool(name: String, args: String) = JsonObject().apply {
        add("choices", JsonParser.parseString("""[{"finish_reason":"tool_calls","message":{"content":null,"tool_calls":[{"id":"call_test","type":"function","function":{}}]}}]"""))
        getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message").getAsJsonArray("tool_calls")[0]
            .asJsonObject.getAsJsonObject("function").apply { addProperty("name", name); addProperty("arguments", args) }
    }.toString()
    private fun assertNoOperation(reply: AssistantCourseReply) {
        assertFalse(reply.requiresConfirmation); assertFalse(reply.undo); assertFalse(reply.cancelPending)
        assertFalse(reply.queryRequested); assertNull(reply.query); assertNull(reply.studyQuery); assertNull(reply.targetCandidates)
    }

    @Test fun ordinaryConversationAndExplanationsHaveNoOperationAuthority() {
        listOf("你好！", "项王，妾身在。", "你刚才说你叫小林。", "牛顿第一定律说明物体会保持静止或匀速直线运动。",
            "示例代码：\n```json\n{\"hello\":\"world\"}\n```").forEach { value ->
            val reply = parse(text(value)); assertEquals(value, reply.reply); assertNoOperation(reply)
        }
    }

    @Test fun chatDuringPendingPlanDoesNotCancelOrReplaceIt() {
        assertNoOperation(parse(text("先陪你聊聊，方案还等你确认。"), pending,
            tools = setOf("revise_course_plan", "cancel_pending_plan")))
    }

    @Test fun fencedOperationExamplesRemainTextInNativeAndLegacyModes() {
        val example = "```json\n{\"version\":1,\"action\":\"change\",\"reply\":\"示例\",${addition.removePrefix("{")}\n```"
        listOf(false, true).forEach { legacy ->
            val reply = parse(text(example), legacy = legacy)
            assertEquals(example, reply.reply); assertNoOperation(reply)
        }
    }

    @Test fun generalJsonWithVersionAndActionFieldsIsNotASchedulingEnvelope() {
        val example = """{"version":1,"action":"refresh","name":"学习示例"}"""
        val reply = parse(text(example))
        assertEquals(example, reply.reply); assertNoOperation(reply)
    }

    @Test fun nativeChangesAreOnlyProposalsAndPreserveCourseDuration() {
        assertTrue(parse(tool("propose_course_changes", addition)).requiresConfirmation)
        val update = parse(tool("propose_course_changes", """{"updates":[{"id":42,"startSection":5}],"targetQuery":{"courseName":"数学"}}""")).updates.single()
        assertEquals(original, update.original)
        assertEquals(original.copy(startSection = 5, endSection = 6), update.replacements.single())
    }

    @Test fun existingCourseChangeRequiresOriginalTargetOrExplicitSelection() {
        val body = tool("propose_course_changes", """{"updates":[{"id":42,"classroom":"B202"}]}""")
        assertThrows(AssistantAgentFailure::class.java) { parse(body) }
        assertEquals("B202", parse(body, selected = original).updates.single().replacements.single().classroom)
    }

    @Test fun movingOneOccurrencePreservesOriginalDurationAndOtherWeeks() {
        val reply = parse(tool("propose_course_changes", """{"targetQuery":{"courseName":"数学"},"occurrences":[{"id":42,"operation":"move","source":{"week":3},"target":{"week":3,"dayOfWeek":4,"startSection":5}}]}"""))
        val replacements = reply.updates.single().replacements
        val moved = replacements.single { it.dayOfWeek == 4 }
        assertEquals(5, moved.startSection); assertEquals(6, moved.endSection)
        assertEquals(3, moved.startWeek); assertEquals(3, moved.endWeek)
        assertTrue(reply.deletions.isEmpty())
        assertEquals((1..16).filter { it != 3 }, replacements.filter { it.dayOfWeek == 2 }.flatMap {
            (it.startWeek..it.endWeek).filter { week -> com.courseschedule.domain.ScheduleRules.isCourseInWeek(it, week) } }.sorted())
    }

    @Test fun nativeQueryStudyProposalRevisionUndoAndCancelUseSeparateRoutes() {
        assertNotNull(parse(tool("query_courses", """{"query":{"courseName":"数学"}}""")).query)
        assertNotNull(parse(tool("query_study_tasks", """{"taskQuery":{"window":"upcoming","daysAhead":0}}""")).studyQuery)
        val task = parse(tool("propose_study_changes", """{"taskCreates":[{"title":"喝水","kind":"reminder","due":{"dayOffset":1,"time":"14:05"}}]}"""))
        assertTrue(task.requiresConfirmation); assertEquals("喝水", task.studyChanges!!.single().after!!.title)
        assertEquals("14:05", java.time.Instant.ofEpochMilli(task.studyChanges.single().after!!.dueAt).atZone(ZoneId.systemDefault()).toLocalTime().toString())
        val revised = parse(tool("revise_study_plan", """{"taskRevisions":[{"index":0,"title":"吃饭"}]}"""), task)
        assertTrue(revised.revisedPending); assertEquals("吃饭", revised.studyChanges!!.single().after!!.title)
        assertTrue(parse(tool("undo_course_changes", "{}"), tools = setOf("undo_course_changes")).undo)
        assertTrue(parse(tool("cancel_pending_plan", "{}"), pending, tools = setOf("cancel_pending_plan")).cancelPending)
        assertEquals("B202", parse(tool("revise_course_plan", """{"revisions":[{"index":0,"classroom":"B202"}]}"""), pending).courses.single().classroom)
    }

    @Test fun schemasRejectUnknownNamesSpoofingNullsTypesAndOutOfRangeValues() {
        val invalid = listOf("invented_tool" to "{}", "cancel_pending_plan" to "{\"action\":\"change\"}",
            "query_courses" to "{}", "query_courses" to "{\"query\":null}",
            "query_courses" to "{\"query\":{\"dayOfWeek\":8}}", "query_courses" to "{\"query\":{\"dayOfWeek\":\"2\"}}",
            "query_courses" to "{\"query\":{\"dayOfWeek\":1.5}}", "query_courses" to "{\"query\":{\"extra\":true}}",
            "propose_course_changes" to "{\"deleteIds\":[9223372036854775808]}",
            "propose_study_changes" to "{\"taskCreates\":[{\"title\":\"喝水\",\"kind\":\"daily\",\"due\":{\"dayOffset\":1,\"time\":\"14:05\"}}]}")
        invalid.forEach { (name, args) -> assertThrows("$name $args", AssistantAgentFailure::class.java) { parse(tool(name, args)) } }
    }

    @Test fun multipleToolCallsAreRejectedTogetherWithoutAcceptingFirstOne() {
        val body = JsonParser.parseString(tool("propose_course_changes", addition)).asJsonObject
        val calls = body.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message").getAsJsonArray("tool_calls")
        calls.add(calls[0].deepCopy())
        assertThrows(AssistantAgentFailure::class.java) { parse(body.toString()) }
    }

    @Test fun textCannotSmuggleOperationJsonIntoNativeToolMode() {
        assertThrows(AssistantAgentFailure::class.java) { parse(text("""{"version":1,"action":"change","reply":"已添加",${addition.removePrefix("{")}""")) }
        val reply = parse(text("""{"version":1,"action":"chat","reply":"你好","courses":[],"undo":false}"""))
        assertEquals("你好", reply.reply); assertNoOperation(reply)
        assertThrows(AssistantAgentFailure::class.java) { parse(text("""{"reply":"你好","courses":[""")) }
    }

    @Test fun legacyAdapterIsExplicitAndRetainsCapabilityRestrictions() {
        val payload = """{"version":1,"action":"change","reply":"添加英语",${addition.removePrefix("{")}"""
        assertTrue(parse(text(payload), legacy = true, tools = setOf("propose_course_changes")).requiresConfirmation)
        assertThrows(AssistantAgentFailure::class.java) { parse(text(payload), pending, legacy = true, tools = setOf("revise_course_plan")) }
        assertThrows(AssistantAgentFailure::class.java) { parse(text("""{"reply":"添加",${addition.removePrefix("{")}"""), legacy = true, tools = emptySet()) }
        assertThrows(AssistantAgentFailure::class.java) { parse(text("""{"version":2,"action":"cancel","reply":"取消"}"""), pending, legacy = true) }
    }

    @Test fun currentStatePublishesOnlyAvailableCapabilities() {
        fun names(value: String) = JsonParser.parseString(value).asJsonObject.getAsJsonArray("tools").map {
            it.asJsonObject.getAsJsonObject("function").get("name").asString }.toSet()
        assertFalse("undo_course_changes" in names(request()))
        assertTrue("undo_course_changes" in names(request(undo = true)))
        assertEquals(setOf("revise_course_plan", "cancel_pending_plan", "query_courses", "query_study_tasks"), names(request(pending)))
        assertThrows(AssistantAgentFailure::class.java) { parse(tool("undo_course_changes", "{}"), tools = names(request())) }
        assertThrows(AssistantAgentFailure::class.java) { parse(tool("propose_course_changes", addition), pending, tools = names(request(pending))) }
    }

    @Test fun invalidReplyGetsExactlyOneRepairUsingOriginalUserRequest() {
        val connections = mutableListOf<FakeConnection>()
        val client = AssistantCourseClient { FakeConnection(if (connections.isEmpty()) text("{\"reply\":") else text("你好！")).also { connections += it } }
        assertEquals("你好！", client.chat(config, request(), 16, listOf(original), semester).reply)
        assertEquals(2, connections.size)
        val repaired = JsonParser.parseString(connections[1].request.toString("UTF-8")).asJsonObject
        assertTrue(repaired.getAsJsonArray("messages").last().asJsonObject.get("content").asString.contains("未执行任何操作"))
        assertEquals("测试请求", repaired.getAsJsonArray("messages")[1].asJsonObject.get("content").asString)
        assertTrue(repaired.has("tools"))
    }

    @Test fun repeatedInvalidReplyStopsAfterTwoAttempts() {
        var count = 0
        val client = AssistantCourseClient { count++; FakeConnection(text("{\"reply\":")) }
        assertThrows(AssistantAgentFailure::class.java) { client.chat(config, request(), 16, semester = semester) }
        assertEquals(2, count)
    }

    @Test fun unsupportedToolsUsesOneBoundedLegacyFallback() {
        val connections = mutableListOf<FakeConnection>()
        val client = AssistantCourseClient { (if (connections.isEmpty()) FakeConnection("", 400, "unknown parameter tools")
            else FakeConnection(text("你好，兼容通道也可以聊天。"))).also { connections += it } }
        assertEquals("你好，兼容通道也可以聊天。", client.chat(config, request(), 16, semester = semester).reply)
        assertEquals(2, connections.size)
        val fallback = JsonParser.parseString(connections[1].request.toString("UTF-8")).asJsonObject
        assertFalse(fallback.has("tools")); assertFalse(fallback.has("tool_choice"))
        assertTrue(fallback.getAsJsonArray("messages").last().asJsonObject.get("content").asString.contains("兼容通道"))
    }

    @Test fun authenticationQuotaGenericRequestAndNetworkFailuresAreNotRetried() {
        listOf(401, 403, 429, 400, 500).forEach { status ->
            var count = 0
            val client = AssistantCourseClient { count++; FakeConnection("", status) }
            assertThrows(AssistantAgentFailure::class.java) { client.chat(config, request(), 16, semester = semester) }
            assertEquals(1, count)
        }
        var count = 0
        val client = AssistantCourseClient { count++; FakeConnection("", networkFailure = true) }
        val error = assertThrows(AssistantAgentFailure::class.java) { client.chat(config, request(), 16) }
        assertEquals(AssistantFailureKind.NETWORK, error.reason); assertEquals(1, count)
    }

    @Test fun cancellationDuringRepairPreventsSecondRequest() {
        var count = 0
        val client = AssistantCourseClient { count++; FakeConnection(text("{\"reply\":")) }
        assertThrows(CancellationException::class.java) { client.chat(config, request(), 16) {
            if (it == "正在重新整理回复…") client.cancel()
        } }
        assertEquals(1, count)
    }

    @Test fun truncatedEmptyAndMalformedResponsesHaveTypedFailures() {
        listOf(text("半条回复", "length"), text(""), "{}", "{", text("拒绝", "content_filter")).forEach {
            assertThrows(AssistantAgentFailure::class.java) { parse(it) }
        }
    }

    @Test fun readOnlyQueriesDuringPendingPlansDoNotRequireConfirmationOrRevision() {
        val query = parse(tool("query_courses", """{"query":{"courseName":"数学"}}"""), pending, tools = setOf("query_courses"))
        assertNotNull(query.query); assertFalse(query.requiresConfirmation); assertFalse(query.cancelPending)
        val tasks = parse(tool("query_study_tasks", """{"taskQuery":{}}"""), pending, tools = setOf("query_study_tasks"))
        assertNotNull(tasks.studyQuery); assertFalse(tasks.requiresConfirmation)
    }

    @Test fun studyRevisionWithoutChangedFieldsIsRejected() {
        val plan = parse(tool("propose_study_changes", """{"taskCreates":[{"title":"喝水","kind":"reminder","due":{"dayOffset":1,"time":"14:05"}}]}"""))
        assertThrows(AssistantAgentFailure::class.java) { parse(tool("revise_study_plan", """{"taskRevisions":[{"index":0}]}"""), plan) }
    }

    @Test fun contradictoryQueryDateAndWeekdayAreRejectedBeforeReceivingReply() {
        val wrongDay = today.dayOfWeek.value % 7 + 1
        val error = assertThrows(AssistantAgentFailure::class.java) {
            parse(tool("query_courses", """{"query":{"dayOfWeek":$wrongDay,"whenTo":{"dayOffset":0}}}"""))
        }
        assertEquals(AssistantFailureKind.OPERATION, error.reason)
        assertTrue(error.message!!.contains("日期与星期不一致"))
    }

    private class FakeConnection(private val body: String, private val status: Int = 200,
        private val error: String = "", private val networkFailure: Boolean = false) : HttpURLConnection(URL("https://example.com/v1/chat/completions")) {
        val request = ByteArrayOutputStream()
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getOutputStream(): ByteArrayOutputStream { if (networkFailure) throw IOException("synthetic"); return request }
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
        override fun getErrorStream() = ByteArrayInputStream(error.toByteArray())
    }
}
