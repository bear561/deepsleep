package com.deepsleep.chat.data.remote.openai

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseParserTest {

    @Test
    fun parsesSequentialEvents() {
        val parser = SseParser(
            Buffer().writeUtf8(
                "data: {\"a\":1}\n\n" +
                    "data: {\"b\":2}\n\n" +
                    "data: [DONE]\n\n",
            ),
        )
        assertEquals("{\"a\":1}", parser.nextData())
        assertEquals("{\"b\":2}", parser.nextData())
        assertEquals("[DONE]", parser.nextData())
        assertNull(parser.nextData())
    }

    @Test
    fun joinsMultilineDataAndIgnoresComments() {
        val parser = SseParser(
            Buffer().writeUtf8(
                ": keep-alive\n" +
                    "event: message\n" +
                    "data: line1\n" +
                    "data: line2\n\n" +
                    "data: done\n\n",
            ),
        )
        assertEquals("line1\nline2", parser.nextData())
        assertEquals("done", parser.nextData())
    }

    @Test
    fun handlesCrLf() {
        val parser = SseParser(Buffer().writeUtf8("data: hello\r\n\r\n"))
        assertEquals("hello", parser.nextData())
    }

    @Test
    fun returnsLastEventWithoutTrailingBlankLine() {
        val parser = SseParser(Buffer().writeUtf8("data: tail"))
        assertEquals("tail", parser.nextData())
        assertNull(parser.nextData())
    }
}