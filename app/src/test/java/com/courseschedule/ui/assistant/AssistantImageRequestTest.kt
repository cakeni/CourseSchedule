package com.courseschedule.ui.assistant

import com.courseschedule.data.entity.Semester
import com.courseschedule.data.entity.AssistantChatMessage
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class AssistantImageRequestTest {
    private val ref = "conversation/12345678-1234-1234-1234-123456789012.jpg"
    @Test fun imageBlocksUseUserRoleAndHistorySearchReadsTextNotMetadata() {
        val stored = AssistantChatMessage(conversationId = "conversation", role = "user", kind = "image", content = AssistantImageMessage.encode("按截图整理课程", ref))
        val message = AssistantHistorySearch.message(stored)
        assertEquals("按截图整理课程", message.content)
        assertEquals(ref, message.imageRef)
        val body = AssistantCourseClient.createRequest("deepseek-flash", listOf(message), Semester(1,"测试",0), 1,
            listOf("08:00"), listOf("08:45"), imageData = mapOf(ref to "data:image/jpeg;base64,TEST"))
        val blocks = JsonParser.parseString(body).asJsonObject.getAsJsonArray("messages")[1].asJsonObject
        assertEquals("user", blocks.get("role").asString)
        val content = blocks.getAsJsonArray("content")
        assertEquals("text", content[0].asJsonObject.get("type").asString)
        assertEquals("high", content[1].asJsonObject.getAsJsonObject("image_url").get("detail").asString)
        assertEquals("data:image/jpeg;base64,TEST", content[1].asJsonObject.getAsJsonObject("image_url").get("url").asString)
    }
    @Test fun missingHistoricalImageIsExplicitlyMarkedAndNotSentAsAnEmptyBlock() {
        val body = AssistantCourseClient.createRequest("deepseek-flash", listOf(AssistantMessage("user", "截图", imageRef = ref)), Semester(1,"测试",0), 1,
            listOf("08:00"), listOf("08:45"))
        val message = JsonParser.parseString(body).asJsonObject.getAsJsonArray("messages")[1].asJsonObject
        assertTrue(message.get("content").asString.contains("本次未附上"))
        assertFalse(body.contains("image_url"))
    }
    @Test fun retriesDoNotAttachTheSameImageRepeatedly() {
        val messages = listOf(AssistantMessage("user", "第一次", imageRef = ref), AssistantMessage("assistant", "请求失败", "error"), AssistantMessage("user", "重试", imageRef = ref))
        val request = JsonParser.parseString(AssistantCourseClient.createRequest("deepseek-flash", messages, Semester(1,"测试",0), 1,
            listOf("08:00"), listOf("08:45"), imageData = mapOf(ref to "data:image/jpeg;base64,TEST"))).asJsonObject
        assertEquals(1, request.getAsJsonArray("messages").count { it.asJsonObject.get("content").isJsonArray })
    }
}
