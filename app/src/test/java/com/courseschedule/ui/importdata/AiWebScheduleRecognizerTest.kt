package com.courseschedule.ui.importdata

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

class AiWebScheduleRecognizerTest {

    @Test
    fun buildsPrivateTextRequestAndParsesStructuredSchedule() {
        val snapshot = "{\"tables\":[{\"rows\":[[{\"text\":\"星期二\"}]]}]}"
        val request = JsonParser.parseString(
            AiWebScheduleRecognizer.createRequest(snapshot, 20, AiWebProvider.DEEPSEEK)
        ).asJsonObject
        assertFalse(request.get("store").asBoolean)
        assertEquals("deepseek-flash", request.get("model").asString)
        assertEquals("none", request.getAsJsonObject("reasoning").get("effort").asString)
        val content = request.getAsJsonArray("input")[0].asJsonObject
            .getAsJsonArray("content")
        assertEquals(1, content.size())
        assertEquals("input_text", content[0].asJsonObject.get("type").asString)
        assertTrue(content[0].asJsonObject.get("text").asString.contains(snapshot))
        assertFalse(request.toString().contains("input_image"))
        assertTrue(request.get("instructions").asString.contains("不可信数据"))
        assertEquals("json_schema", request.getAsJsonObject("text")
            .getAsJsonObject("format").get("type").asString)

        val schedule = """
            {"courses":[{"courseName":"高等数学","teacher":"张老师","classroom":"A101",
              "dayOfWeek":2,"startSection":3,"endSection":4,"weeks":[1,3,5,8,9]}]}
        """.trimIndent()
        val response = JsonObject().apply {
            add("output", JsonArray().apply {
                add(JsonObject().apply {
                    add("content", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("type", "output_text")
                            addProperty("text", schedule)
                        })
                    })
                })
            })
        }.toString()
        val courses = AiWebScheduleRecognizer.parseResponse(
            response,
            20,
            AiWebProvider.DEEPSEEK
        ).courses
        assertEquals(2, courses.size)
        assertEquals(1, courses[0].startWeek)
        assertEquals(5, courses[0].endWeek)
        assertEquals(1, courses[0].weekType)
        assertEquals(8, courses[1].startWeek)
        assertEquals(9, courses[1].endWeek)
        assertEquals(0, courses[1].weekType)
        assertEquals("DeepSeek 网页识别，请核对", courses[0].note)

        val openAiRequest = JsonParser.parseString(
            AiWebScheduleRecognizer.createRequest(snapshot, 20, AiWebProvider.OPENAI)
        ).asJsonObject
        assertEquals("gpt-4o-mini", openAiRequest.get("model").asString)
        assertFalse(openAiRequest.has("reasoning"))
        assertEquals("https://api.deepseek.com/responses", AiWebProvider.DEEPSEEK.apiUrl)
        assertEquals("https://api.openai.com/v1/responses", AiWebProvider.OPENAI.apiUrl)
    }

    @Test
    fun browserCaptureReadsOnlyScheduleTextAndStructure() {
        val script = AcademicAiCaptureScript.SCRIPT
        assertTrue(script.contains("innerText"))
        assertTrue(script.contains("rowSpan"))
        assertTrue(script.contains("colSpan"))
        assertFalse(script.contains("outerHTML"))
        assertFalse(script.contains("document.cookie"))
        assertFalse(script.contains("location.href"))
        assertFalse(script.contains(".value"))
        assertFalse(script.contains("textContent"))
    }

    private val schedule = """{"courses":[{"courseName":"测试课程","teacher":"","classroom":"","dayOfWeek":5,"startSection":1,"endSection":2,"weeks":[6,7,8]}]}"""

    private fun response(vararg texts: String) = JsonObject().apply {
        addProperty("status", "completed")
        add("output", JsonArray().apply {
            add(JsonObject().apply {
                add("content", JsonArray().apply {
                    texts.forEach { text -> add(JsonObject().apply {
                        addProperty("type", "output_text"); addProperty("text", text)
                    }) }
                })
            })
        })
    }.toString()

    @Test fun joinsTextPartsAndAcceptsWholeJsonCodeBlockAndAggregatedOutput() {
        val split = schedule.length / 2
        val wrapped = "```json\n$schedule\n```"
        val roots = listOf(response(schedule.take(split), schedule.drop(split)), response(wrapped),
            JsonObject().apply { addProperty("output_text", schedule) }.toString())
        roots.forEach { root ->
            val course = AiWebScheduleRecognizer.parseResponse(root, 20, AiWebProvider.DEEPSEEK).courses.single()
            assertEquals(6, course.startWeek); assertEquals(8, course.endWeek); assertEquals(0, course.weekType)
        }
        assertEquals(2, AiWebScheduleRecognizer.responseDiagnostic(roots.first()).get("textParts").asInt)
        assertEquals(schedule.length, AiWebScheduleRecognizer.responseDiagnostic(roots.first()).get("textChars").asInt)
        val fallback = JsonParser.parseString(AiWebScheduleRecognizer.createRequest("{}", 20, AiWebProvider.DEEPSEEK, true)).asJsonObject
        assertEquals("json_object", fallback.getAsJsonObject("text").getAsJsonObject("format").get("type").asString)
        assertTrue(fallback.get("instructions").asString.contains("只输出一个 JSON 对象"))
    }

    @Test fun distinguishesIncompleteRefusalEmptyAndInvalidCourseResults() {
        val incomplete = assertThrows(AiResponseException::class.java) {
            AiWebScheduleRecognizer.parseResponse("""{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"}}""", 20, AiWebProvider.DEEPSEEK)
        }
        assertFalse(incomplete.retryable); assertTrue(incomplete.message!!.contains("截断"))
        val refusal = assertThrows(AiResponseException::class.java) {
            AiWebScheduleRecognizer.parseResponse("""{"output":[{"content":[{"type":"refusal","refusal":"PRIVATE_RESPONSE"}]}]}""", 20, AiWebProvider.DEEPSEEK)
        }
        assertFalse(refusal.retryable); assertFalse(refusal.message!!.contains("PRIVATE_RESPONSE"))
        assertTrue(assertThrows(AiResponseException::class.java) {
            AiWebScheduleRecognizer.parseResponse("{\"output\":[]}", 20, AiWebProvider.DEEPSEEK)
        }.retryable)
        assertTrue(assertThrows(ImportFormatException::class.java) {
            AiWebScheduleRecognizer.parseResponse(response("{\"courses\":[]}"), 20, AiWebProvider.DEEPSEEK)
        }.message!!.contains("没有识别到有效课程"))
        val payload = JsonParser.parseString(schedule).asJsonObject
        payload.getAsJsonArray("courses").apply {
            add(com.google.gson.JsonNull.INSTANCE)
            add("invalid row")
            add(JsonParser.parseString(schedule).asJsonObject.getAsJsonArray("courses")[0].deepCopy().asJsonObject.apply {
                addProperty("dayOfWeek", 2.5)
            })
        }
        assertEquals(1, AiWebScheduleRecognizer.parseResponse(response(payload.toString()), 20, AiWebProvider.DEEPSEEK).courses.size)
    }

    private class ResponseConnection(val status: Int, val body: String, val timeout: Boolean = false) :
        HttpURLConnection(URL("https://fixture.invalid/responses")) {
        val request = ByteArrayOutputStream()
        var disconnected = false
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true }
        override fun getOutputStream() = request
        override fun getResponseCode(): Int = if (timeout) throw SocketTimeoutException() else status
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
        override fun getErrorStream() = ByteArrayInputStream(body.toByteArray())
    }

    @Test fun deepseekFormatFailureRetriesOnceWithFreshConnectionAndSafeDiagnostics() {
        val connections = listOf(ResponseConnection(200, "{\"output\":[]}"), ResponseConnection(200, response(schedule)))
        var calls = 0
        val diagnostics = mutableListOf<String>()
        val result = AiWebScheduleRecognizer { connections[calls++] }.recognize("PRIVATE_SNAPSHOT", "PRIVATE_KEY", 20, AiWebProvider.DEEPSEEK, diagnostics::add)
        assertEquals(1, result.courses.size); assertEquals(2, calls)
        assertTrue(connections.all { it.disconnected })
        assertEquals(listOf("json_schema", "json_object"), connections.map {
            JsonParser.parseString(it.request.toString("UTF-8")).asJsonObject.getAsJsonObject("text").getAsJsonObject("format").get("type").asString
        })
        assertTrue(diagnostics.all { !it.contains("PRIVATE_KEY") && !it.contains("PRIVATE_SNAPSHOT") && !it.contains("测试课程") })
    }

    @Test fun authenticationTimeoutAndOpenAiFormatFailureAreNotRetried() {
        listOf(ResponseConnection(401, "PRIVATE_RESPONSE"), ResponseConnection(200, "", timeout = true),
            ResponseConnection(200, "{\"output\":[]}")).forEachIndexed { index, connection ->
            var calls = 0
            val error = assertThrows(ImportFormatException::class.java) {
                AiWebScheduleRecognizer { calls++; connection }.recognize("{}", "PRIVATE_KEY", 20,
                    if (index == 2) AiWebProvider.OPENAI else AiWebProvider.DEEPSEEK)
            }
            assertEquals(1, calls); assertTrue(connection.disconnected)
            assertFalse(error.message!!.contains("PRIVATE_KEY")); assertFalse(error.message!!.contains("PRIVATE_RESPONSE"))
        }
        var calls = 0
        val connections = List(2) { ResponseConnection(200, "{\"output\":[]}") }
        assertThrows(ImportFormatException::class.java) {
            AiWebScheduleRecognizer { connections[calls++] }.recognize("{}", "PRIVATE_KEY", 20, AiWebProvider.DEEPSEEK)
        }
        assertEquals(2, calls); assertTrue(connections.all { it.disconnected })
    }
}
