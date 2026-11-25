package com.deepsleep.chat.util

import com.deepsleep.chat.domain.model.ChatMessage
import com.deepsleep.chat.domain.model.Conversation
import com.deepsleep.chat.domain.model.MessageRole
import com.deepsleep.chat.domain.model.MessageStatus
import com.deepsleep.chat.domain.model.Provider
import com.deepsleep.chat.domain.model.SearchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {

    private val provider = Provider(
        id = "p",
        name = "Test",
        baseUrl = "https://example.com/v1",
        chatModelId = "model",
        systemPrompt = "You are helpful.",
    )

    private val conversation = Conversation(
        id = "c",
        title = "T",
        createdAt = 1,
        updatedAt = 1,
    )

    @Test
    fun includesSystemAndSearchSources() {
        val history = listOf(
            ChatMessage(
                id = "u",
                conversationId = "c",
                seq = 1,
                role = MessageRole.USER,
                content = "最新的新闻是什么？",
                status = MessageStatus.COMPLETE,
            ),
        )
        val built = PromptBuilder.build(
            provider = provider,
            conversation = conversation,
            history = history,
            sources = listOf(SearchSource("标题", "https://example.com/news", "摘要")),
            contextLimit = 32_000,
        )
        assertEquals("system", built.messages.first().role)
        assertTrue(built.messages.first().content.contains("You are helpful."))
        assertTrue(built.messages.first().content.contains("https://example.com/news"))
        assertFalse(built.trimmedHistory)
    }

    @Test
    fun trimsOldHistoryButKeepsLatestUserMessage() {
        val history = (1..20).map { index ->
            ChatMessage(
                id = "m$index",
                conversationId = "c",
                seq = index.toLong(),
                role = if (index % 2 == 1) MessageRole.USER else MessageRole.ASSISTANT,
                content = "这是一条比较长的测试消息 $index ".repeat(20),
                status = MessageStatus.COMPLETE,
            )
        } + ChatMessage(
            id = "latest",
            conversationId = "c",
            seq = 21,
            role = MessageRole.USER,
            content = "最新问题",
            status = MessageStatus.COMPLETE,
        )
        val built = PromptBuilder.build(
            provider = provider,
            conversation = conversation,
            history = history,
            sources = emptyList(),
            contextLimit = 2_000,
        )
        assertTrue(built.trimmedHistory)
        val nonSystem = built.messages.filter { it.role != "system" }
        assertEquals("user", nonSystem.last().role)
        assertEquals(history.last().content, nonSystem.last().content)
    }

    @Test
    fun includesCurrentDeviceTimeInEverySystemPrompt() {
        val built = PromptBuilder.build(
            provider = provider,
            conversation = conversation,
            history = emptyList(),
            sources = emptyList(),
            contextLimit = 32_000,
        )
        val system = built.messages.first().content
        assertTrue(system.contains("当前设备时间"))
        assertTrue(system.contains("You are helpful."))
    }

    @Test
    fun includesAttachmentTextForUserMessages() {
        val history = listOf(
            ChatMessage(
                id = "u",
                conversationId = "c",
                seq = 1,
                role = MessageRole.USER,
                content = "总结附件",
                status = MessageStatus.COMPLETE,
                attachments = listOf(
                    com.deepsleep.chat.domain.model.Attachment(
                        id = "a",
                        name = "note.txt",
                        text = "附件正文",
                    ),
                ),
            ),
        )
        val built = PromptBuilder.build(
            provider = provider,
            conversation = conversation,
            history = history,
            sources = emptyList(),
            contextLimit = 32_000,
        )
        val user = built.messages.last()
        assertTrue(user.content.contains("<attachment name=\"note.txt\">"))
        assertTrue(user.content.contains("附件正文"))
    }
}