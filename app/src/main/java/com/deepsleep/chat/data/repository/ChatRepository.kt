package com.deepsleep.chat.data.repository

import com.deepsleep.chat.data.remote.openai.ConnectionTestResult
import com.deepsleep.chat.data.remote.openai.OpenAiChatClient
import com.deepsleep.chat.data.remote.openai.StreamEvent
import com.deepsleep.chat.domain.model.ModelConfig
import com.deepsleep.chat.domain.model.PromptMessage
import com.deepsleep.chat.domain.model.Provider
import kotlinx.coroutines.flow.Flow

class ChatRepository(
    private val client: OpenAiChatClient,
) {
    fun streamReply(
        provider: Provider,
        apiKey: String?,
        modelId: String,
        messages: List<PromptMessage>,
    ): Flow<StreamEvent> = client.stream(provider, apiKey, modelId, messages)

    suspend fun testConnection(provider: Provider, apiKey: String?): ConnectionTestResult =
        client.testConnection(provider, apiKey)

    suspend fun listModels(provider: Provider, apiKey: String?): List<ModelConfig> =
        client.listModels(provider, apiKey)
}