package com.deepsleep.chat.data.remote.openai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlNormalizerTest {

    @Test
    fun appendsChatCompletions() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            UrlNormalizer.chatUrl("https://api.deepseek.com/v1/"),
        )
        assertEquals(
            "https://api.deepseek.com/v1/models",
            UrlNormalizer.modelsUrl("https://api.deepseek.com/v1"),
        )
    }

    @Test
    fun keepsFullEndpoint() {
        assertEquals(
            "https://example.com/v1/chat/completions",
            UrlNormalizer.chatUrl("https://example.com/v1/chat/completions"),
        )
    }

    @Test
    fun v1FallbackOnlyWhenMissing() {
        assertEquals("https://api.example.com/v1", UrlNormalizer.v1Fallback("https://api.example.com"))
        assertNull(UrlNormalizer.v1Fallback("https://api.example.com/v1"))
        assertNull(UrlNormalizer.v1Fallback("https://api.example.com/api/v1"))
    }
}