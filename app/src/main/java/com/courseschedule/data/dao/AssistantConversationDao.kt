package com.courseschedule.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.courseschedule.data.entity.AssistantChatMessage
import com.courseschedule.data.entity.AssistantConversation

@Dao
interface AssistantConversationDao {
    @Insert suspend fun insertConversation(conversation: AssistantConversation)
    @Query("UPDATE assistant_conversations SET title = :title, updatedAt = :updatedAt, stateJson = :stateJson, revision = revision + 1 WHERE id = :id AND revision = :expectedRevision")
    suspend fun updateConversation(id: String, title: String, updatedAt: Long, stateJson: String, expectedRevision: Long): Int
    @Insert suspend fun insertMessage(message: AssistantChatMessage): Long

    @Query("SELECT * FROM assistant_conversations WHERE semesterId = :semesterId ORDER BY updatedAt DESC, id DESC")
    suspend fun conversations(semesterId: Long): List<AssistantConversation>

    @Query("SELECT id FROM assistant_conversations")
    suspend fun conversationIds(): List<String>

    @Query("SELECT * FROM assistant_conversations WHERE id = :id")
    suspend fun conversation(id: String): AssistantConversation?

    @Query("SELECT * FROM assistant_messages WHERE conversationId = :id ORDER BY id DESC LIMIT :limit")
    suspend fun messages(id: String, limit: Int): List<AssistantChatMessage>

    @Query("SELECT * FROM assistant_messages WHERE conversationId = :id AND id < :beforeId ORDER BY id DESC LIMIT :limit")
    suspend fun messagesBefore(id: String, beforeId: Long, limit: Int): List<AssistantChatMessage>

    @Query("SELECT EXISTS(SELECT 1 FROM assistant_messages WHERE conversationId = :id AND id < :beforeId)")
    suspend fun hasMessagesBefore(id: String, beforeId: Long): Boolean

    @Query("SELECT COUNT(*) FROM assistant_messages WHERE conversationId = :id")
    suspend fun messageCount(id: String): Int

    @Query("DELETE FROM assistant_conversations WHERE id = :id")
    suspend fun deleteConversation(id: String)
}
