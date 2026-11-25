package com.deepsleep.chat.data.backup

import com.deepsleep.chat.domain.model.Attachment
import com.deepsleep.chat.domain.model.SearchSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Test
    fun parsesDeepSeekV3SessionShape() {
        val text = """
            {
              "id": "session-1",
              "title": "测试",
              "pinned": false,
              "current_message_id": 2,
              "inserted_at": "2026-11-11T02:33:23.270Z",
              "updated_at": "2026-11-11T02:34:23Z",
              "messages": [
                {
                  "id": 1, "parent_id": null, "role": "USER", "status": "FINISHED",
                  "thinking_enabled": true, "search_enabled": false,
                  "accumulated_token_usage": 12,
                  "inserted_at": "2026-11-11T02:33:23Z",
                  "fragments": [{"type":"REQUEST","content":"你好"}]
                },
                {
                  "id": 2, "parent_id": 1, "role": "ASSISTANT", "status": "FINISHED",
                  "thinking_enabled": true, "search_enabled": true,
                  "accumulated_token_usage": 25,
                  "inserted_at": "2026-11-11T02:33:24Z",
                  "fragments": [
                    {"type":"THINK","content":"思考过程","elapsed_secs":1.5},
                    {"type":"RESPONSE","content":"回答"},
                    {"type":"TOOL_SEARCH","queries":["q"],"results":[
                      {"url":"https://example.com","title":"来源","snippet":"摘要"}
                    ],"status":"FINISHED"},
                    {"type":"FILE","files":[
                      {"id":"f1","file_name":"a.txt","file_size":10,"token_usage":null,
                       "inserted_at":"2026-11-11T02:33:23Z","export_path":null,
                       "is_image":false,"width":null,"height":null}
                    ]}
                  ]
                }
              ]
            }
        """.trimIndent()
        val session = json.decodeFromString(DsSessionFile.serializer(), text)
        assertEquals("session-1", session.id)
        assertEquals(2L, session.currentMessageId)
        assertEquals(2, session.messages.size)
        assertEquals("ASSISTANT", session.messages[1].role)
        assertEquals(4, session.messages[1].fragments.size)
        assertTrue(session.messages[1].fragments[0] is JsonObject)
    }

    @Test
    fun ownBackupRoundTripKeepsAttachmentsAndSources() {
        val backup = OwnBackupFile(
            exportedAt = 1000,
            conversations = listOf(
                OwnConversation(
                    id = "c1",
                    title = "对话",
                    createdAt = 1,
                    updatedAt = 2,
                ),
            ),
            messages = listOf(
                OwnMessage(
                    id = "m1",
                    conversationId = "c1",
                    seq = 1,
                    role = "USER",
                    content = "附件测试",
                    status = "COMPLETE",
                    attachments = listOf(
                        Attachment(id = "a1", name = "a.txt", text = "正文"),
                    ),
                    sources = listOf(SearchSource("t", "https://a", "s")),
                    createdAt = 1,
                ),
            ),
        )
        val encoded = json.encodeToString(OwnBackupFile.serializer(), backup)
        val decoded = json.decodeFromString(OwnBackupFile.serializer(), encoded)
        assertEquals(OwnBackupFile.FORMAT, decoded.format)
        assertEquals(1, decoded.conversations.size)
        assertEquals("正文", decoded.messages.first().attachments.first().text)
        assertEquals("https://a", decoded.messages.first().sources.first().url)
    }

    @Test
    fun rejectsUnknownHigherVersionByField() {
        val manifest = json.decodeFromString(
            DsManifest.serializer(),
            """{"export_format_version":4}""",
        )
        assertEquals(4, manifest.exportFormatVersion)
    }
}