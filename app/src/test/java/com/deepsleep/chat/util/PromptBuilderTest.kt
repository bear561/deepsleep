package com.deepsleep.chat.util

import com.deepsleep.chat.domain.model.Attachment
import com.deepsleep.chat.domain.model.ChatMessage
import com.deepsleep.chat.domain.model.Conversation
import com.deepsleep.chat.domain.model.MessageRole
import com.deepsleep.chat.domain.model.MessageStatus
import com.deepsleep.chat.domain.model.Provider
import com.deepsleep.chat.domain.model.SearchSource
import com.deepsleep.chat.domain.model.TextLimits
import java.io.File
import java.nio.file.Files
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

    @Test
    fun attachesLocalImagesAsPromptImageParts() {
        val dir = Files.createTempDirectory("deepsleep-images").toFile()
        File(dir, "a.jpg").writeBytes(byteArrayOf(1, 2, 3, 4))
        try {
            val history = listOf(
                ChatMessage(
                    id = "u",
                    conversationId = "c",
                    seq = 1,
                    role = MessageRole.USER,
                    content = "这张图里是什么？",
                    status = MessageStatus.COMPLETE,
                    attachments = listOf(
                        Attachment(
                            id = "img",
                            name = "照片.jpg",
                            mime = "image/jpeg",
                            isImage = true,
                            localFile = "a.jpg",
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
                imageDir = dir,
            )
            val user = built.messages.last()
            assertEquals(1, user.images.size)
            assertTrue(user.images.single().path.endsWith("a.jpg"))
            assertEquals("image/jpeg", user.images.single().mime)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun replacesMissingImagesWithTextPlaceholder() {
        val history = listOf(
            ChatMessage(
                id = "u",
                conversationId = "c",
                seq = 1,
                role = MessageRole.USER,
                content = "",
                status = MessageStatus.COMPLETE,
                attachments = listOf(
                    Attachment(
                        id = "img",
                        name = "旧图.jpg",
                        mime = "image/jpeg",
                        isImage = true,
                        localFile = "missing.jpg",
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
            imageDir = Files.createTempDirectory("deepsleep-empty").toFile(),
        )
        val user = built.messages.last()
        assertTrue(user.images.isEmpty())
        assertTrue(user.content.contains("[图片：旧图.jpg]"))
    }

    @Test
    fun capsImagesPerRequestAndKeepsNewest() {
        val dir = Files.createTempDirectory("deepsleep-many-images").toFile()
        try {
            val history = (1..10).map { index ->
                val name = "img$index.jpg"
                File(dir, name).writeBytes(byteArrayOf(index.toByte()))
                ChatMessage(
                    id = "u$index",
                    conversationId = "c",
                    seq = index.toLong(),
                    role = MessageRole.USER,
                    content = "问题 $index",
                    status = MessageStatus.COMPLETE,
                    attachments = listOf(
                        Attachment(
                            id = "a$index",
                            name = "图片$index.jpg",
                            mime = "image/jpeg",
                            isImage = true,
                            localFile = name,
                        ),
                    ),
                )
            }
            val built = PromptBuilder.build(
                provider = provider,
                conversation = conversation,
                history = history,
                sources = emptyList(),
                contextLimit = 200_000,
                imageDir = dir,
            )
            val images = built.messages.flatMap { it.images }
            assertEquals(TextLimits.MAX_IMAGES_PER_REQUEST, images.size)
            assertTrue(images.last().path.endsWith("img10.jpg"))
            assertTrue(built.messages.last().content.contains("问题 10"))
            // Oldest images were downgraded to placeholders instead of being dropped.
            assertTrue(built.messages.any { it.content.contains("未重复发送") })
        } finally {
            dir.deleteRecursively()
        }
    }
}