package com.deepsleep.chat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelConfigsTest {

    @Test
    fun parsesModelsWithDisplayNameAndReasoning() {
        val models = ModelConfigs.parse(
            """
            deepseek-chat | DeepSeek Chat
            deepseek-reasoner | DeepSeek Reasoner | true
            # comment
            """.trimIndent(),
        )
        assertEquals(2, models.size)
        assertEquals("deepseek-chat", models[0].id)
        assertEquals("DeepSeek Chat", models[0].displayName)
        assertTrue(models[1].supportsReasoning)
    }

    @Test
    fun roundTripsModels() {
        val text = "a | A | true\nb"
        val formatted = ModelConfigs.format(ModelConfigs.parse(text))
        val again = ModelConfigs.parse(formatted)
        assertEquals(2, again.size)
        assertTrue(again.first().supportsReasoning)
    }

    @Test
    fun parsesHeadersAndRejectsMalformed() {
        assertEquals(mapOf("X-Title" to "DeepSleep"), ModelConfigs.parseHeaders("X-Title: DeepSleep"))
        assertNull(ModelConfigs.parseHeaders("no separator"))
    }
}