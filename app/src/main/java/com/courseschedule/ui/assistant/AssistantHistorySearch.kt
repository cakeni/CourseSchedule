package com.courseschedule.ui.assistant

import com.courseschedule.data.entity.AssistantChatMessage

internal data class AssistantHistoryHit(val conversationId: String, val title: String,
    val messageId: Long?, val createdAt: Long, val text: String)
internal data class AssistantHistoryResults(val hits: List<AssistantHistoryHit>, val hasMore: Boolean)
internal data class AssistantHistoryLocation(val messageId: Long, val keyword: String)

internal object AssistantHistorySearch {
    fun message(row: AssistantChatMessage): AssistantMessage = (if (row.kind == "query") runCatching {
        require(row.role == "assistant")
        AssistantQueryMessage.decode(row.content, row.createdAt)
    }.getOrElse { AssistantMessage("assistant", "这条查询记录无法读取，请重新查询。", "error", row.createdAt) }
        else AssistantMessage(row.role, row.content, row.kind, row.createdAt)).copy(id = row.id)

    fun snippet(text: String, keyword: String): String {
        val index = text.indexOf(keyword, ignoreCase = true).coerceAtLeast(0)
        val start = (index - 30).coerceAtLeast(0)
        val end = (index + keyword.length + 70).coerceAtMost(text.length)
        return (if (start > 0) "…" else "") + text.substring(start, end).replace(Regex("\\s+"), " ") +
            if (end < text.length) "…" else ""
    }
}
