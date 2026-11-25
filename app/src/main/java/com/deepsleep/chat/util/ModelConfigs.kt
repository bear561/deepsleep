package com.deepsleep.chat.util

import com.deepsleep.chat.domain.model.ModelConfig

object ModelConfigs {

    fun parse(text: String): List<ModelConfig> {
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split('|').map { it.trim() }
                val id = parts.getOrNull(0).orEmpty()
                if (id.isBlank()) return@mapNotNull null
                val display = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: id
                val reasoning = parts.getOrNull(2)?.let { isTrue(it) } ?: false
                ModelConfig(id = id, displayName = display, supportsReasoning = reasoning)
            }
            .distinctBy { it.id }
            .toList()
    }

    fun format(models: List<ModelConfig>): String = models.joinToString("\n") { formatLine(it) }

    fun formatLine(model: ModelConfig): String = buildString {
        append(model.id)
        if (model.displayName != model.id) {
            append(" | ").append(model.displayName)
            if (model.supportsReasoning) append(" | true")
        } else if (model.supportsReasoning) {
            append(" | ").append(model.id).append(" | true")
        }
    }

    fun parseHeaders(text: String): Map<String, String>? {
        val result = linkedMapOf<String, String>()
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val separator = trimmed.indexOf(':')
            if (separator <= 0 || separator == trimmed.lastIndex) return null
            val name = trimmed.substring(0, separator).trim()
            val value = trimmed.substring(separator + 1).trim()
            if (name.isEmpty() || value.isEmpty()) return null
            result[name] = value
        }
        return result
    }

    fun formatHeaders(headers: Map<String, String>): String =
        headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }

    private fun isTrue(value: String): Boolean = value.equals("true", true) ||
        value.equals("yes", true) ||
        value.equals("1", true) ||
        value == "是" ||
        value.contains("思考") ||
        value.contains("reason", true)
}