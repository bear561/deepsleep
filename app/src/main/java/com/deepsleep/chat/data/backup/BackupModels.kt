package com.deepsleep.chat.data.backup

import com.deepsleep.chat.domain.model.Attachment
import com.deepsleep.chat.domain.model.SearchConfig
import com.deepsleep.chat.domain.model.SearchSource
import com.deepsleep.chat.domain.model.StoredProvider
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

class BackupException(message: String) : Exception(message)

data class BackupInspection(
    val sessionCount: Int,
    val messageCount: Int,
    val duplicateCount: Int,
    val formatVersion: Int,
)

data class BackupImportResult(
    val conversations: Int,
    val messages: Int,
    val providers: Int = 0,
    val skipped: Int = 0,
    val warnings: List<String> = emptyList(),
)

data class BackupExportResult(
    val conversations: Int,
    val messages: Int,
    val skipped: Int = 0,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class OwnBackupFile(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val exportedAt: Long = 0L,
    val settings: OwnSettings? = null,
    val providers: List<StoredProvider> = emptyList(),
    val conversations: List<OwnConversation> = emptyList(),
    val messages: List<OwnMessage> = emptyList(),
) {
    companion object {
        const val FORMAT = "deepsleep-backup"
        const val VERSION = 1
    }
}

@Serializable
data class OwnSettings(
    val themeMode: String = "SYSTEM",
    val contextTokenLimit: Int = 32_000,
    val autoTitle: Boolean = false,
    val search: SearchConfig = SearchConfig(),
    val updateRepo: String = "bear561/deepsleep",
    val updateManifestUrl: String = "",
    val autoCheckUpdates: Boolean = true,
)

@Serializable
data class OwnConversation(
    val id: String,
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

@Serializable
data class OwnMessage(
    val id: String,
    val conversationId: String,
    val seq: Long,
    val role: String,
    val content: String = "",
    val reasoning: String = "",
    val status: String,
    val thinkingEnabled: Boolean = false,
    val modelId: String? = null,
    val providerId: String? = null,
    val attachments: List<Attachment> = emptyList(),
    val sources: List<SearchSource> = emptyList(),
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val reasoningMs: Long? = null,
    val error: String? = null,
    val createdAt: Long,
)

@Serializable
data class DsManifest(
    @SerialName("export_format_version") val exportFormatVersion: Int = 0,
)

@Serializable
data class DsSessionFile(
    val id: String,
    val title: String? = null,
    val pinned: Boolean = false,
    @SerialName("current_message_id") val currentMessageId: Long? = null,
    @SerialName("inserted_at") val insertedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val messages: List<DsMessage> = emptyList(),
)

@Serializable
data class DsMessage(
    val id: Long,
    @SerialName("parent_id") val parentId: Long? = null,
    val role: String = "",
    val status: String? = null,
    @SerialName("thinking_enabled") val thinkingEnabled: Boolean = false,
    @SerialName("search_enabled") val searchEnabled: Boolean = false,
    @SerialName("accumulated_token_usage") val accumulatedTokenUsage: Long? = null,
    @SerialName("inserted_at") val insertedAt: String? = null,
    val fragments: List<JsonElement> = emptyList(),
)

@Serializable
data class DsIndexEntry(
    val id: String,
    val title: String? = null,
    val pinned: Boolean = false,
    @SerialName("message_count") val messageCount: Int = 0,
    @SerialName("inserted_at") val insertedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)