package com.deepsleep.chat.data.repository

import androidx.room.withTransaction
import com.deepsleep.chat.data.local.AppDatabase
import com.deepsleep.chat.data.local.ConversationEntity
import com.deepsleep.chat.data.local.MessageDao
import com.deepsleep.chat.data.local.toDomain
import com.deepsleep.chat.data.local.toEntity
import com.deepsleep.chat.domain.model.Attachment
import com.deepsleep.chat.domain.model.ChatMessage
import com.deepsleep.chat.domain.model.Conversation
import com.deepsleep.chat.domain.model.MessageRole
import com.deepsleep.chat.domain.model.MessageStatus
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class ConversationSnapshot(
    val conversation: Conversation,
    val messages: List<ChatMessage>,
)

class ConversationRepository(
    private val database: AppDatabase,
) {
    private val conversationDao = database.conversationDao()
    private val messageDao: MessageDao = database.messageDao()

    fun observeConversations(): Flow<List<Conversation>> =
        conversationDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeConversations(query: String): Flow<List<Conversation>> {
        val trimmed = query.trim()
        val flow = if (trimmed.isEmpty()) conversationDao.observeAll() else conversationDao.observeSearch(trimmed)
        return flow.map { list -> list.map { it.toDomain() } }
    }

    fun observeConversation(id: String): Flow<Conversation?> =
        conversationDao.observeById(id).map { it?.toDomain() }

    suspend fun loadConversation(id: String): ConversationSnapshot? {
        val entity = conversationDao.getById(id) ?: return null
        return ConversationSnapshot(
            conversation = entity.toDomain(),
            messages = messageDao.getByConversation(id).map { it.toDomain() },
        )
    }

    suspend fun getConversation(id: String): Conversation? = conversationDao.getById(id)?.toDomain()

    suspend fun createConversation(
        providerId: String?,
        chatModelId: String?,
        reasoningModelId: String?,
        thinkingEnabled: Boolean,
        webSearchEnabled: Boolean,
    ): Conversation {
        val now = System.currentTimeMillis()
        val conversation = Conversation(
            id = UUID.randomUUID().toString(),
            title = "新对话",
            createdAt = now,
            updatedAt = now,
            providerId = providerId,
            chatModelId = chatModelId,
            reasoningModelId = reasoningModelId,
            thinkingEnabled = thinkingEnabled,
            webSearchEnabled = webSearchEnabled,
            source = "local",
        )
        conversationDao.upsert(conversation.toEntity())
        return conversation
    }

    suspend fun saveConversation(conversation: Conversation) {
        conversationDao.upsert(conversation.toEntity())
    }

    suspend fun renameConversation(id: String, title: String) {
        conversationDao.rename(id, title.trim().ifBlank { "未命名对话" }, System.currentTimeMillis())
    }

    suspend fun setPinned(id: String, pinned: Boolean) = conversationDao.setPinned(id, pinned)

    suspend fun deleteConversation(id: String) = conversationDao.delete(id)

    suspend fun touchConversation(id: String, preview: String? = null) {
        val existing = conversationDao.getById(id) ?: return
        conversationDao.upsert(
            existing.copy(
                updatedAt = System.currentTimeMillis(),
                lastPreview = preview?.take(120) ?: existing.lastPreview,
            ),
        )
    }

    suspend fun appendMessage(
        conversationId: String,
        role: MessageRole,
        content: String,
        reasoning: String = "",
        status: MessageStatus = MessageStatus.COMPLETE,
        thinkingEnabled: Boolean = false,
        modelId: String? = null,
        providerId: String? = null,
        attachments: List<Attachment> = emptyList(),
    ): ChatMessage {
        val seq = messageDao.maxSeq(conversationId) + 1
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            seq = seq,
            role = role,
            content = content,
            reasoning = reasoning,
            status = status,
            thinkingEnabled = thinkingEnabled,
            modelId = modelId,
            providerId = providerId,
            attachments = attachments,
        )
        messageDao.upsert(message.toEntity())
        return message
    }

    suspend fun updateMessage(message: ChatMessage) {
        messageDao.upsert(message.toEntity())
    }

    suspend fun deleteMessage(id: String) = messageDao.deleteById(id)

    suspend fun deleteMessagesAfter(conversationId: String, seq: Long) =
        messageDao.deleteAfter(conversationId, seq)

    suspend fun getMessages(conversationId: String): List<ChatMessage> =
        messageDao.getByConversation(conversationId).map { it.toDomain() }

    suspend fun getLastAssistant(conversationId: String): ChatMessage? =
        messageDao.getLastAssistant(conversationId)?.toDomain()

    suspend fun markStaleStreamingStopped() = messageDao.stopStaleStreaming()

    suspend fun findBySourceSessionId(sourceSessionId: String): Conversation? =
        conversationDao.findBySourceSessionId(sourceSessionId)?.toDomain()

    suspend fun insertImported(conversation: Conversation, messages: List<ChatMessage>) {
        database.withTransaction {
            conversationDao.upsert(conversation.toEntity())
            messageDao.upsertAll(messages.map { it.toEntity() })
        }
    }

    suspend fun getAllConversations(): List<Conversation> =
        conversationDao.getAll().map { it.toDomain() }

    suspend fun getAllMessages(): List<ChatMessage> =
        messageDao.getAll().map { it.toDomain() }

    suspend fun insertAllConversations(conversations: List<Conversation>) {
        conversationDao.upsertAll(conversations.map { it.toEntity() })
    }

    suspend fun insertAllMessages(messages: List<ChatMessage>) {
        messageDao.upsertAll(messages.map { it.toEntity() })
    }

    suspend fun clearAll() {
        database.withTransaction {
            conversationDao.deleteAll()
        }
    }

    suspend fun countConversations(): Int = conversationDao.count()
}