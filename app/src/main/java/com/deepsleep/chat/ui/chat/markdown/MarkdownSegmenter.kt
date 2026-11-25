package com.deepsleep.chat.ui.chat.markdown

sealed interface MarkdownPart {
    data class Markdown(val text: String) : MarkdownPart
    data class Code(val language: String?, val code: String) : MarkdownPart
}

object MarkdownSegmenter {

    fun split(text: String): List<MarkdownPart> {
        if (text.isBlank()) return emptyList()
        val lines = text.split('\n')
        val parts = mutableListOf<MarkdownPart>()
        val buffer = StringBuilder()

        fun flushMarkdown() {
            val content = buffer.toString().trim('\n')
            if (content.isNotBlank()) parts.add(MarkdownPart.Markdown(content))
            buffer.clear()
        }

        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trimStart()
            val marker = when {
                trimmed.startsWith("```") -> "```"
                trimmed.startsWith("~~~") -> "~~~"
                else -> null
            }
            if (marker == null) {
                buffer.appendLine(line)
                index++
                continue
            }
            flushMarkdown()
            val language = trimmed.removePrefix(marker).trim()
                .substringBefore(' ')
                .takeIf { it.isNotBlank() && it.length <= 30 }
            index++
            val code = StringBuilder()
            while (index < lines.size && !lines[index].trimStart().startsWith(marker)) {
                code.appendLine(lines[index])
                index++
            }
            if (index < lines.size) index++ // closing fence
            parts.add(MarkdownPart.Code(language, code.toString().trimEnd('\n')))
        }
        flushMarkdown()
        return parts
    }
}