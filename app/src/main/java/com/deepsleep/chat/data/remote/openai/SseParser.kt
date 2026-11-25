package com.deepsleep.chat.data.remote.openai

import okio.BufferedSource

/**
 * Reads one SSE event at a time. OpenAI-compatible servers send one `data:`
 * line per JSON chunk, but we still support multi-line data and comments.
 */
class SseParser(private val source: BufferedSource) {

    fun nextData(): String? {
        val builder = StringBuilder()
        var hasData = false
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (line.isEmpty()) {
                if (hasData) return builder.toString()
                continue
            }
            if (line.startsWith(":")) continue
            if (line.startsWith("data:")) {
                if (hasData) builder.append('\n')
                hasData = true
                builder.append(line.removePrefix("data:").removePrefix(" "))
            }
            // `event:`, `id:` and `retry:` are irrelevant for chat completions.
        }
        return if (hasData) builder.toString() else null
    }
}