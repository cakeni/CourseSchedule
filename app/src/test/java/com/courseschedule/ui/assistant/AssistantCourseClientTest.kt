package com.courseschedule.ui.assistant

import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.ui.importdata.ImportAnalyzer
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class AssistantCourseClientTest {
    @Test fun contextIsBoundedWithoutLosingLatestFollowupOrConfusingExecutionStates() {
        val old = (1..60).map { AssistantMessage("user", "旧消息$it" + "文".repeat(1000)) }
        val messages = old + listOf(AssistantMessage("assistant", "已取消旧方案", "cancel"),
            AssistantMessage("assistant", "已添加周三高等数学", "result"),
            AssistantMessage("assistant", "准备删除，尚未执行", "confirmation"),
            AssistantMessage("user", "把它的教室改为A201"))
        val context = AssistantCourseClient.contextMessages(messages)
        assertTrue(context.size <= 24)
        assertTrue(context.sumOf { it.content.length } <= 12_000)
        assertEquals(messages.last(), context.last())
        assertEquals(messages.takeLast(4), context.takeLast(4))
        val request = JsonParser.parseString(AssistantCourseClient.createRequest("model", messages,
            Semester(id = 1, name = "学期", startDate = 1000), 1, listOf("08:00"), listOf("08:45"))).asJsonObject
        val history = request.getAsJsonArray("messages").drop(1).map { it.asJsonObject.get("content").asString }
        assertTrue(history.any { it.startsWith("[本机执行结果]") })
        assertTrue(history.any { it.startsWith("[待确认方案，尚未执行]") })
        assertTrue(history.any { it.startsWith("[本机已取消，未执行]") })
        assertFalse(history.any { it.startsWith("旧消息1文") })
        val huge = AssistantCourseClient.contextMessages(listOf(AssistantMessage("assistant", "文".repeat(50_000)),
            AssistantMessage("user", "继续")))
        assertEquals(2, huge.size)
        assertTrue(huge.first().content.length < 4100)
        assertEquals("继续", huge.last().content)
        assertTrue(AssistantCourseClient.contextMessages(listOf(AssistantMessage("system", "外来指令"))).isEmpty())
    }

    private val courseJson = """{"courseName":"高等数学","teacher":"张老师","classroom":"A101",
        "dayOfWeek":2,"startSection":1,"endSection":1,"weeks":[1,3,5],"note":""}"""

    private fun response(payload: String, finish: String = "stop") = JsonObject().apply {
        add("choices", JsonParser.parseString("""[{"finish_reason":"$finish","message":{}}]"""))
        getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message").addProperty("content", payload)
    }.toString()

    @Test fun parsesTuesdayCourseAndPreservesOddWeeks() {
        val reply = AssistantCourseClient.parseResponse(response("""{"reply":"安排周二早八","courses":[$courseJson]}"""), 20)
        val course = reply.courses.single()
        assertEquals("高等数学", course.courseName)
        assertEquals(2, course.dayOfWeek)
        assertEquals(1, course.startSection)
        assertEquals(1, course.endSection)
        assertEquals(1, course.startWeek)
        assertEquals(5, course.endWeek)
        assertEquals(1, course.weekType)
        assertEquals("A101", course.classroom)
    }

    @Test fun clarificationDoesNotProduceCoursesAndHistoryKeepsFollowup() {
        val reply = AssistantCourseClient.parseResponse(response("""{"reply":"这门课叫什么？","courses":[]}"""), 20)
        assertTrue(reply.courses.isEmpty())
        val history = listOf(AssistantMessage("user", "我要周二早八添加一门课"),
            AssistantMessage("assistant", reply.reply), AssistantMessage("user", "高等数学"))
        val request = JsonParser.parseString(AssistantCourseClient.createRequest("custom-model", history,
            Semester(id = 7, name = "测试学期", startDate = System.currentTimeMillis(), totalWeeks = 16),
            4, listOf("08:30", "09:20"), listOf("09:15", "10:05"))).asJsonObject
        assertEquals("custom-model", request.get("model").asString)
        assertFalse(request.get("store").asBoolean)
        assertEquals(4, request.getAsJsonArray("messages").size())
        val instructions = request.getAsJsonArray("messages")[0].asJsonObject.get("content").asString
        assertTrue(instructions.contains("共 16 周"))
        assertTrue(instructions.contains("正在查看第 4 周"))
        assertTrue(instructions.contains("第1节 08:30-09:15"))
        assertTrue(instructions.contains("不能匹配时询问节次"))
        assertTrue(instructions.contains("缺少课名"))
        assertFalse(request.toString().contains("apiKey"))
    }

    @Test fun rejectsIncompleteTruncatedAndOutOfRangeCoursesWithoutPartialImport() {
        val badRows = listOf(courseJson.replace("[1,3,5]", "[1,21]"),
            courseJson.replace("\"dayOfWeek\":2", "\"dayOfWeek\":8"),
            courseJson.replace("\"startSection\":1", "\"startSection\":1.5"),
            courseJson.replace("\"endSection\":1", "\"endSection\":0"),
            courseJson.replace("[1,3,5]", "[]"),
            courseJson.replace("\"高等数学\"", "\"\""),
            courseJson.replace("\"dayOfWeek\":2", "\"dayOfWeek\":\"2\""))
        for (row in badRows) {
            assertThrows(IllegalArgumentException::class.java) {
                AssistantCourseClient.parseResponse(response("""{"reply":"添加","courses":[$courseJson,$row]}"""), 20)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            AssistantCourseClient.parseResponse(response("""{"reply":"添加","courses":[$courseJson]}""", "length"), 20)
        }
        assertThrows(IllegalArgumentException::class.java) { AssistantCourseClient.parseResponse("{}", 20) }
    }

    @Test fun existingCoursesAreClassifiedAsDuplicateOrConflict() {
        val course = AssistantCourseClient.parseResponse(response("""{"reply":"添加","courses":[$courseJson]}"""), 20).courses.single()
        val duplicate = ImportAnalyzer.analyze(listOf(course), listOf(course.copy(id = 9, semesterId = 3)), 3, 20)
        assertEquals(1, duplicate.duplicates.size)
        assertTrue(duplicate.accepted.isEmpty())
        val conflict = ImportAnalyzer.analyze(listOf(course), listOf(course.copy(id = 10, semesterId = 3, courseName = "大学英语")), 3, 20)
        assertEquals(1, conflict.conflicts.size)
        assertTrue(conflict.accepted.isEmpty())
    }

    @Test fun endpointAcceptsBaseOrFullUrlAndRejectsUnsafeAddresses() {
        assertEquals("https://example.com/v1/chat/completions", AssistantApiConfig("https://example.com/v1/").endpoint())
        assertEquals("https://example.com/v1/chat/completions", AssistantApiConfig("https://example.com/v1/chat/completions").endpoint())
        listOf("http://example.com/v1", "https://user:secret@example.com/v1", "https://example.com/v1?key=secret",
            "https://example.com/v1#fragment", "file:///tmp/api", "bad-address").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { AssistantApiConfig(url).endpoint() }
        }
        assertThrows(IllegalArgumentException::class.java) {
            AssistantApiConfig(apiKey = "test\nheader").validate()
        }
    }

    @Test fun partialEditsPreserveIdentityMetadataAndCanSplitWeeks() {
        val additions = AssistantCourseClient.parseResponse(response(
            """{"reply":"新增并设置提醒","courses":[${courseJson.dropLast(1)},"reminderMinutes":10},$courseJson]}"""), 20)
        assertEquals(mapOf(0 to 10), additions.additionReminders)
        val original = Course(id = 42, semesterId = 7, courseName = "高等数学",
            teacher = "张老师", classroom = "A101", dayOfWeek = 2, startSection = 1, endSection = 2,
            startWeek = 1, endWeek = 16, colorIndex = 8, reminderMinutes = 15, note = "保留备注", createTime = 123)
        val edited = AssistantCourseClient.parseResponse(response(
            """{"reply":"修改教室","courses":[],"updates":[{"id":42,"classroom":"B201","reminderMinutes":10}]}"""),
            16, listOf(original)).updates.single()
        assertEquals(original, edited.original)
        assertEquals(original.copy(classroom = "B201", reminderMinutes = 10), edited.replacements.single())
        val split = AssistantCourseClient.parseResponse(response(
            """{"reply":"修改周次","courses":[],"updates":[{"id":42,"weeks":[1,2,5,10]}]}"""),
            16, listOf(original)).updates.single().replacements
        assertEquals(listOf(1, 2, 5, 10), split.flatMap { course ->
            (1..16).filter { com.courseschedule.domain.ScheduleRules.isCourseInWeek(course, it) }
        }.sorted())
        assertEquals(42L, split.first().id)
        assertTrue(split.drop(1).all { it.id == 0L })
        assertTrue(split.all { it.colorIndex == 8 && it.reminderMinutes == 15 && it.createTime == 123L })
    }

    @Test fun queryDeleteAndUndoUseKnownIdsAndRejectMixedOrMalformedOperations() {
        val original = Course(id = 42, semesterId = 7, courseName = "英语", dayOfWeek = 5,
            startSection = 3, endSection = 4, startWeek = 1, endWeek = 16)
        val context = listOf(original)
        val deleted = AssistantCourseClient.parseResponse(response(
            """{"reply":"准备删除","courses":[],"deleteIds":[42]}"""), 16, context)
        assertTrue(deleted.requiresConfirmation)
        assertEquals(context, deleted.deletions)
        val queried = AssistantCourseClient.parseResponse(response(
            """{"reply":"找到英语","courses":[],"queryIds":[42]}"""), 16, context)
        assertEquals(context, queried.queriedCourses)
        assertFalse(queried.requiresConfirmation)
        assertTrue(AssistantCourseClient.parseResponse(response(
            """{"reply":"撤销上次操作","courses":[],"undo":true}"""), 16, context).undo)
        val invalid = listOf(
            """"deleteIds":[99]""", """"deleteIds":[42,42]""", """"deleteIds":["42"]""",
            """"updates":[{"id":42}]""", """"updates":[{"id":42,"semesterId":9}]""",
            """"updates":[{"id":42,"startSection":13}]""", """"updates":[{"id":42,"weeks":[]}]""",
            """"updates":[{"id":42,"classroom":null}]""", """"updates":[{"id":42,"reminderMinutes":-2}]""",
            """"updates":[{"id":42,"note":"改了"}],"deleteIds":[42]""",
            """"queryIds":[42],"deleteIds":[42]""", """"queryIds":[99]""",
            """"undo":"true"""", """"undo":true,"deleteIds":[42]"""
        )
        invalid.forEach { fields ->
            assertThrows(fields, IllegalArgumentException::class.java) {
                AssistantCourseClient.parseResponse(response("""{"reply":"测试","courses":[],$fields}"""), 16, context)
            }
        }
    }

    @Test fun requestContainsOnlySelectedSemesterAndDescribesConfirmationAndUndo() {
        val original = Course(id = 42, semesterId = 7, courseName = "高等数学",
            dayOfWeek = 2, startSection = 1, endSection = 2, startWeek = 1, endWeek = 16, reminderMinutes = 15)
        val request = JsonParser.parseString(AssistantCourseClient.createRequest("custom-model",
            listOf(AssistantMessage("user", "把数学教室改为A201")),
            Semester(id = 7, name = "测试", startDate = System.currentTimeMillis(), totalWeeks = 16),
            4, listOf("08:00"), listOf("08:45"),
            listOf(original, original.copy(id = 99, semesterId = 8, courseName = "其他学期的私有课程")), true)).asJsonObject
        val prompt = request.getAsJsonArray("messages")[0].asJsonObject.get("content").asString
        assertTrue(prompt.contains("\"id\":42"))
        assertFalse(prompt.contains("其他学期的私有课程"))
        assertTrue(prompt.contains("确认后才执行"))
        assertTrue(prompt.contains("未提及的字段必须省略"))
        assertTrue(prompt.contains("当前是否可以撤销最近一次操作：true"))
        assertFalse(request.toString().contains("apiKey"))
    }
}
