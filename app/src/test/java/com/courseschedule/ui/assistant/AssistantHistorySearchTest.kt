package com.courseschedule.ui.assistant

import com.courseschedule.data.entity.AssistantChatMessage
import org.junit.Assert.*
import org.junit.Test

class AssistantHistorySearchTest {
    @Test fun nativeQueryUsesVisibleTextAndKeepsDatabaseIdentity() {
        val text = "统计学%_ \"原教室\"\\讲义\n课程 #987"
        val stored = AssistantChatMessage(id = 23, conversationId = "chat", role = "assistant",
            content = AssistantQueryMessage.encode(text, listOf(42)), kind = "query", createdAt = 1234)
        val message = AssistantHistorySearch.message(stored)
        assertEquals(text, message.content)
        assertEquals(23L, message.id)
        assertEquals(listOf(42L), message.courseIds)
        assertFalse(message.content.contains("courseIds"))
        assertEquals(1234L, message.createdAt)
        assertEquals("query", message.kind)
    }

    @Test fun brokenQueryDoesNotExposeStorageAndPlainTextRemainsLiteral() {
        val stored = AssistantChatMessage(id = 24, conversationId = "chat", role = "assistant",
            content = "{broken courseIds:987}", kind = "query")
        val broken = AssistantHistorySearch.message(stored)
        assertEquals("error", broken.kind)
        assertFalse(broken.content.contains("987"))
        assertTrue(broken.courseIds.isEmpty())
        val plain = AssistantHistorySearch.message(stored.copy(kind = "chat", role = "user"))
        assertEquals(stored.content, plain.content)
        assertEquals("user", plain.role)
        assertEquals(24L, plain.id)
    }

    @Test fun snippetCentersLiteralMatchAndIncludesNearbyContext() {
        val text = "前".repeat(200) + "原教室\nB201%_\\ABC" + "后".repeat(200)
        val snippet = AssistantHistorySearch.snippet(text, "b201%_\\abc")
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
        assertTrue(snippet.contains("原教室 B201%_\\ABC"))
        assertTrue(snippet.length <= 120)
        assertEquals("第一条记录", AssistantHistorySearch.snippet("第一条记录", "第一"))
    }
}
