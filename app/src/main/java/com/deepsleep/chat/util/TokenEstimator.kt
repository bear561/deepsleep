package com.deepsleep.chat.util

object TokenEstimator {
    /**
     * Rough estimate only. CJK characters are usually ~1 token each and Latin
     * text ~4 characters per token. We deliberately avoid a tokenizer dependency.
     */
    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var ascii = 0
        for (ch in text) {
            if (ch.code > 127) cjk++ else ascii++
        }
        return cjk + (ascii + 3) / 4
    }
}