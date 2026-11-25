package com.deepsleep.chat.data.remote.openai

object UrlNormalizer {

    fun chatUrl(baseUrl: String): String = endpoint(baseUrl, "chat/completions")

    fun modelsUrl(baseUrl: String): String = endpoint(baseUrl, "models")

    fun endpoint(baseUrl: String, path: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return if (base.endsWith("/$path")) base else "$base/$path"
    }

    /**
     * Some users paste `https://api.example.com` while the OpenAI-compatible
     * endpoint actually lives under `/v1`. We only try this as a fallback after
     * a 404, never by silently rewriting the primary URL.
     */
    fun v1Fallback(baseUrl: String): String? {
        val base = baseUrl.trim().trimEnd('/')
        if (base.contains("/v1") || base.endsWith("/chat/completions") || base.endsWith("/models")) {
            return null
        }
        return "$base/v1"
    }

    fun previewChatUrl(baseUrl: String): String = runCatching { chatUrl(baseUrl) }.getOrDefault(baseUrl)
}