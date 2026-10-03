package com.courseschedule.data.entity

import androidx.room.Entity
import androidx.room.Embedded
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "assistant_conversations", foreignKeys = [ForeignKey(
    entity = Semester::class, parentColumns = ["id"], childColumns = ["semesterId"],
    onDelete = ForeignKey.CASCADE)], indices = [Index("semesterId")])
data class AssistantConversation(
    @PrimaryKey val id: String,
    val semesterId: Long,
    val title: String,
    val updatedAt: Long,
    val stateJson: String = "{}",
    val revision: Long = 0
)

@Entity(tableName = "assistant_messages", foreignKeys = [ForeignKey(
    entity = AssistantConversation::class, parentColumns = ["id"], childColumns = ["conversationId"],
    onDelete = ForeignKey.CASCADE)], indices = [Index("conversationId")])
data class AssistantChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: String,
    val role: String,
    val content: String,
    val kind: String = "chat",
    val createdAt: Long = System.currentTimeMillis()
)

data class AssistantHistoryMessage(
    @Embedded val message: AssistantChatMessage,
    val title: String
)
