package com.deepsleep.chat.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversations",
    indices = [
        Index("updatedAt"),
        Index("sourceSessionId"),
    ],
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val providerId: String? = null,
    val chatModelId: String? = null,
    val reasoningModelId: String? = null,
    val thinkingEnabled: Boolean = false,
    val webSearchEnabled: Boolean = false,
    val systemPrompt: String? = null,
    val lastPreview: String = "",
    val source: String = "local",
    val sourceSessionId: String? = null,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("conversationId"),
        Index(value = ["conversationId", "seq"]),
    ],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val seq: Long,
    val role: String,
    val content: String = "",
    val reasoning: String = "",
    val status: String,
    @ColumnInfo(defaultValue = "0")
    val thinkingEnabled: Boolean = false,
    val modelId: String? = null,
    val providerId: String? = null,
    val attachmentsJson: String? = null,
    val searchSourcesJson: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val reasoningMs: Long? = null,
    val error: String? = null,
    val createdAt: Long,
)