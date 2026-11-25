package com.deepsleep.chat.data.local

import com.deepsleep.chat.domain.model.Attachment
import com.deepsleep.chat.domain.model.ChatMessage
import com.deepsleep.chat.domain.model.Conversation
import com.deepsleep.chat.domain.model.MessageRole
import com.deepsleep.chat.domain.model.MessageStatus
import com.deepsleep.chat.domain.model.SearchSource
import com.deepsleep.chat.domain.model.TokenUsage
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val mappingJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun ConversationEntity.toDomain(): Conversation = Conversation(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    pinned = pinned,
    providerId = providerId,
    chatModelId = chatModelId,
    reasoningModelId = reasoningModelId,
    thinkingEnabled = thinkingEnabled,
    webSearchEnabled = webSearchEnabled,
    systemPrompt = systemPrompt,
    lastPreview = lastPreview,
    source = source,
    sourceSessionId = sourceSessionId,
)

fun Conversation.toEntity(): ConversationEntity = ConversationEntity(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    pinned = pinned,
    providerId = providerId,
    chatModelId = chatModelId,
    reasoningModelId = reasoningModelId,
    thinkingEnabled = thinkingEnabled,
    webSearchEnabled = webSearchEnabled,
    systemPrompt = systemPrompt,
    lastPreview = lastPreview,
    source = source,
    sourceSessionId = sourceSessionId,
)

fun MessageEntity.toDomain(): ChatMessage = ChatMessage(
    id = id,
    conversationId = conversationId,
    seq = seq,
    role = runCatching { MessageRole.valueOf(role) }.getOrDefault(MessageRole.ASSISTANT),
    content = content,
    reasoning = reasoning,
    status = runCatching { MessageStatus.valueOf(status) }.getOrDefault(MessageStatus.COMPLETE),
    thinkingEnabled = thinkingEnabled,
    modelId = modelId,
    providerId = providerId,
    attachments = decodeList(attachmentsJson, Attachment.serializer()),
    sources = decodeList(searchSourcesJson, SearchSource.serializer()),
    usage = if (promptTokens == null && completionTokens == null) {
        null
    } else {
        TokenUsage(
            promptTokens = promptTokens ?: 0,
            completionTokens = completionTokens ?: 0,
            totalTokens = totalTokens ?: ((promptTokens ?: 0) + (completionTokens ?: 0)),
        )
    },
    reasoningMs = reasoningMs,
    error = error,
    createdAt = createdAt,
)

fun ChatMessage.toEntity(): MessageEntity = MessageEntity(
    id = id,
    conversationId = conversationId,
    seq = seq,
    role = role.name,
    content = content,
    reasoning = reasoning,
    status = status.name,
    thinkingEnabled = thinkingEnabled,
    modelId = modelId,
    providerId = providerId,
    attachmentsJson = encodeList(attachments, Attachment.serializer()),
    searchSourcesJson = encodeList(sources, SearchSource.serializer()),
    promptTokens = usage?.promptTokens,
    completionTokens = usage?.completionTokens,
    totalTokens = usage?.totalTokens,
    reasoningMs = reasoningMs,
    error = error,
    createdAt = createdAt,
)

private fun <T> encodeList(list: List<T>, serializer: kotlinx.serialization.KSerializer<T>): String? {
    if (list.isEmpty()) return null
    return mappingJson.encodeToString(ListSerializer(serializer), list)
}

private fun <T> decodeList(json: String?, serializer: kotlinx.serialization.KSerializer<T>): List<T> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        mappingJson.decodeFromString(ListSerializer(serializer), json)
    }.getOrDefault(emptyList())
}