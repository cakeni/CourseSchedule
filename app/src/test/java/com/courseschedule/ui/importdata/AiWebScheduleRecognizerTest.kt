package com.courseschedule.ui.importdata

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
