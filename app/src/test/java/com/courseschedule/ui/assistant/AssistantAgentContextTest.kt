package com.courseschedule.ui.assistant

import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.data.entity.StudyTask
import com.courseschedule.domain.StudyTaskRules
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class AssistantAgentContextTest {
    private val semester = Semester(id = 7, name = "测试学期", startDate = 1000, totalWeeks = 16)
    private val math = Course(id = 12, semesterId = 7, courseName = "数学", dayOfWeek = 3,
        startSection = 1, endSection = 2, startWeek = 1, endWeek = 16)
    private val english = math.copy(id = 13, courseName = "英语", dayOfWeek = 5)
    private val date = LocalDate.of(2026, 10, 4)
    private fun request(plan: AssistantCourseReply? = null, choice: AssistantTargetChoice? = null,
        tasks: List<StudyTask> = emptyList(), messages: List<AssistantMessage> = listOf(AssistantMessage("user", "测试"))) =
        JsonParser.parseString(AssistantCourseClient.createRequest("synthetic-model", messages, semester, 2,
            listOf("08:00"), listOf("08:45"), listOf(math, english), pending = plan, today = date,
            studyTasks = tasks, targetChoice = choice)).asJsonObject
    private fun prompt(body: JsonObject) = body.getAsJsonArray("messages")[0].asJsonObject.get("content").asString
    private fun tools(body: JsonObject) = body.getAsJsonArray("tools").map { it.asJsonObject.getAsJsonObject("function").get("name").asString }.toSet()

    @Test fun promptSeparatesRolePreferencesFromActionsWithoutAssigningUserAnIdentity() {
        val policy = prompt(request())
        assertFalse(policy.contains("项羽")); assertFalse(policy.contains("虞姬"))
        assertTrue(policy.contains("用户身份只依据用户明确说过的信息"))
        assertTrue(policy.contains("示例放进代码块"))
        assertTrue(policy.contains("只追问真正缺少的信息"))
    }

    @Test fun taskDatesUseExplicitDeviceLocalDateAndTimeAndExcludeOtherSemesters() {
        val task = StudyTask(id = 21, semesterId = 7, title = "报告", kind = "report",
            dueAt = StudyTaskRules.deadline("2026-10-05", "18:35"))
        val policy = prompt(request(tasks = listOf(task, task.copy(id = 22, semesterId = 8, title = "别的学期"))))
        assertTrue(policy.contains("\"date\":\"2026-10-05\"")); assertTrue(policy.contains("\"time\":\"18:35\""))
        assertFalse(policy.contains("dueAt")); assertFalse(policy.contains("别的学期"))
    }

    @Test fun planIndexesMatchCreateThenEachReplacementOrder() {
        val plan = AssistantCourseReply("方案", listOf(math.copy(id = 0)), updates = listOf(
            AssistantCourseUpdate(english, listOf(english.copy(classroom = "B201"), english.copy(id = 0, classroom = "C301")))))
        val text = prompt(request(plan))
        val encoded = text.substringAfter("方案数据：").substringBefore('\n')
        val json = JsonParser.parseString(encoded).asJsonObject
        assertEquals(0, json.getAsJsonArray("courses")[0].asJsonObject.get("index").asInt)
        assertEquals(listOf(1, 2), json.getAsJsonArray("updates").map { it.asJsonObject.get("index").asInt })
        assertEquals("C301", json.getAsJsonArray("updates")[1].asJsonObject.getAsJsonObject("after").get("classroom").asString)
    }

    @Test fun deletionOnlyPlansDoNotOfferImpossibleRevisionTools() {
        val allowed = setOf("cancel_pending_plan", "query_courses", "query_study_tasks")
        assertEquals(allowed, tools(request(AssistantCourseReply("删除", emptyList(), deletions = listOf(math)))))
        val task = StudyTask(id = 21, semesterId = 7, title = "报告", kind = "report", dueAt = StudyTaskRules.deadline("2026-10-05", "18:35"))
        assertEquals(allowed, tools(request(AssistantCourseReply("删除", emptyList(), studyChanges = listOf(AssistantStudyChange(task, null))))))
    }

    @Test fun unresolvedChoiceSuppliesOriginalRequestAndLocalCandidatesAndCanBeCancelled() {
        val choice = AssistantTargetChoice(semester, AssistantRetryRequest("明天改数学教室", 2, date.toString()), listOf(math, math.copy(id = 14, dayOfWeek = 5)))
        val body = request(choice = choice)
        assertTrue(prompt(body).contains("原请求日期：2026-10-04"))
        assertTrue(prompt(body).contains("原请求：明天改数学教室"))
        assertTrue(prompt(body).contains("\"id\":14"))
        assertTrue("cancel_pending_plan" in tools(body))
    }

    @Test fun retryBindingIsIndependentOfConversationFocusAndSurvivesPersistence() {
        val retry = AssistantRetryRequest("改英语教室", 2, date.toString(), bindingRecorded = true)
        val state = AssistantConversationState(retryRequest = retry, selectedTarget = math)
        val restored = AssistantConversationCodec.decode(AssistantConversationCodec.encode(state), 7)
        assertEquals(math, restored.selectedTarget); assertNull(restored.retryRequest!!.boundTarget)
        val bound = state.copy(retryRequest = retry.copy(boundTarget = english))
        assertEquals(english, AssistantConversationCodec.decode(AssistantConversationCodec.encode(bound), 7).retryRequest!!.boundTarget)
        listOf(retry.copy(boundTarget = english, bindingRecorded = false), retry.copy(boundTarget = english.copy(semesterId = 8))).forEach {
            assertThrows(Exception::class.java) { AssistantConversationCodec.decode(AssistantConversationCodec.encode(state.copy(retryRequest = it)), 7) }
        }
    }

    @Test fun choiceCanPersistDuringAnUnrelatedRequestWithoutBecomingAPlan() {
        val retry = AssistantRetryRequest("你好", 2, date.toString(), bindingRecorded = true)
        val choice = AssistantTargetChoice(semester, retry.copy(text = "改数学教室"), listOf(math, math.copy(id = 14, dayOfWeek = 5)))
        val state = AssistantConversationState(retryRequest = retry, requestRunning = true, targetChoice = choice)
        val restored = AssistantConversationCodec.decode(AssistantConversationCodec.encode(state), 7)
        assertEquals(choice, restored.targetChoice); assertNull(restored.pending)
    }

    @Test fun queryReceiptsAreMarkedReadOnlyInModelHistory() {
        val body = request(messages = listOf(AssistantMessage("assistant", "空闲节次", "query_text"), AssistantMessage("user", "谢谢")))
        assertTrue(body.getAsJsonArray("messages")[1].asJsonObject.get("content").asString.contains("本机只读查询结果，未修改数据"))
    }
}
