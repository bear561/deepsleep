package com.deepsleep.chat.domain.model

import kotlinx.serialization.Serializable

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class MessageRole { USER, ASSISTANT, SYSTEM }

enum class MessageStatus { PENDING, STREAMING, COMPLETE, STOPPED, ERROR }

@Serializable
enum class SearchProviderType { TAVILY, SERPER, SEARXNG }

@Serializable
data class SearchConfig(
    val type: SearchProviderType = SearchProviderType.TAVILY,
    val baseUrl: String = "",
    val maxResults: Int = 5,
)

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val defaultProviderId: String? = null,
    val contextTokenLimit: Int = 32_000,
    val autoTitle: Boolean = false,
    val search: SearchConfig = SearchConfig(),
    val searchHasKey: Boolean = false,
    val onboardingCompleted: Boolean = false,
    val updateRepo: String = DEFAULT_UPDATE_REPO,
    val updateManifestUrl: String = "",
    val autoCheckUpdates: Boolean = true,
    val lastUpdateCheckAt: Long = 0L,
) {
    companion object {
        const val DEFAULT_UPDATE_REPO = "bear561/deepsleep"
    }
}

@Serializable
data class ModelConfig(
    val id: String,
    val displayName: String = id,
    val supportsReasoning: Boolean = false,
)

@Serializable
data class StoredProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val models: List<ModelConfig> = emptyList(),
    val chatModelId: String = "",
    val reasoningModelId: String? = null,
    val transcriptionModelId: String? = null,
    val systemPrompt: String = "",
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
    val isDefault: Boolean = false,
)

data class Provider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val models: List<ModelConfig> = emptyList(),
    val chatModelId: String = "",
    val reasoningModelId: String? = null,
    val transcriptionModelId: String? = null,
    val systemPrompt: String = "",
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
    val isDefault: Boolean = false,
    val hasApiKey: Boolean = false,
) {
    fun model(id: String?): ModelConfig? = models.firstOrNull { it.id == id }
    val displayChatModel: String get() = model(chatModelId)?.displayName ?: chatModelId
    val displayReasoningModel: String? get() = reasoningModelId?.let { model(it)?.displayName ?: it }
    val supportsReasoning: Boolean get() = !reasoningModelId.isNullOrBlank()
}

data class Conversation(
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
data class Attachment(
    val id: String,
    val name: String,
    val mime: String = "text/plain",
    val sizeBytes: Long = 0,
    val text: String = "",
    val isImage: Boolean = false,
    /** File name inside the app-private chat-images directory. Null for imported/legacy images. */
    val localFile: String? = null,
) {
    val truncated: Boolean get() = text.length >= TextLimits.MAX_CHARS_PER_ATTACHMENT
}

object TextLimits {
    const val MAX_CHARS_PER_ATTACHMENT = 20_000
    const val MAX_CHARS_TOTAL_ATTACHMENTS = 40_000
    const val MAX_ATTACHMENT_BYTES = 2_000_000L

    const val MAX_IMAGE_BYTES = 6_000_000L
    const val MAX_IMAGES_PER_MESSAGE = 6
    const val MAX_IMAGES_PER_REQUEST = 8
}

@Serializable
data class SearchSource(
    val title: String,
    val url: String,
    val snippet: String = "",
)

data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val reasoningTokens: Int = 0,
)

data class ChatMessage(
    val id: String,
    val conversationId: String,
    val seq: Long,
    val role: MessageRole,
    val content: String = "",
    val reasoning: String = "",
    val status: MessageStatus = MessageStatus.PENDING,
    val thinkingEnabled: Boolean = false,
    val modelId: String? = null,
    val providerId: String? = null,
    val attachments: List<Attachment> = emptyList(),
    val sources: List<SearchSource> = emptyList(),
    val usage: TokenUsage? = null,
    val reasoningMs: Long? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

data class PromptMessage(
    val role: String,
    val content: String,
    val images: List<PromptImage> = emptyList(),
)

/** A local image that should be sent as an OpenAI-style image_url part. */
data class PromptImage(
    val path: String,
    val mime: String = "image/jpeg",
    val name: String = "",
)

object ProviderPresets {
    data class Preset(
        val label: String,
        val baseUrl: String,
        val models: List<ModelConfig>,
        val needsApiKey: Boolean = true,
        val transcriptionModelId: String = "",
    )

    val all: List<Preset> = listOf(
        Preset(
            label = "DeepSeek 官方",
            baseUrl = "https://api.deepseek.com/v1",
            models = listOf(
                ModelConfig("deepseek-chat", "DeepSeek Chat", supportsReasoning = false),
                ModelConfig("deepseek-reasoner", "DeepSeek Reasoner", supportsReasoning = true),
            ),
        ),
        Preset(
            label = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            models = emptyList(),
        ),
        Preset(
            label = "硅基流动",
            baseUrl = "https://api.siliconflow.cn/v1",
            models = emptyList(),
            transcriptionModelId = "FunAudioLLM/SenseVoiceSmall",
        ),
        Preset(
            label = "Ollama（局域网）",
            baseUrl = "http://192.168.1.100:11434/v1",
            models = emptyList(),
            needsApiKey = false,
        ),
    )
}