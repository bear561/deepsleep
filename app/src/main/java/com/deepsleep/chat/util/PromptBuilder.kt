package com.deepsleep.chat.util

import com.deepsleep.chat.domain.model.Attachment
import com.deepsleep.chat.domain.model.ChatMessage
import com.deepsleep.chat.domain.model.Conversation
import com.deepsleep.chat.domain.model.MessageRole
import com.deepsleep.chat.domain.model.MessageStatus
import com.deepsleep.chat.domain.model.PromptImage
import com.deepsleep.chat.domain.model.PromptMessage
import com.deepsleep.chat.domain.model.Provider
import com.deepsleep.chat.domain.model.SearchSource
import com.deepsleep.chat.domain.model.TextLimits
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

data class BuiltPrompt(
    val messages: List<PromptMessage>,
    val trimmedHistory: Boolean,
    val estimatedTokens: Int,
)

object PromptBuilder {

    fun build(
        provider: Provider,
        conversation: Conversation?,
        history: List<ChatMessage>,
        sources: List<SearchSource>,
        contextLimit: Int,
        imageDir: File? = null,
    ): BuiltPrompt {
        val systemText = buildSystemPrompt(provider, conversation, sources)
        val candidates = history.mapNotNull { it.toPromptText(imageDir) }
        val budget = contextLimit.coerceAtLeast(2_000)
        val systemCost = TokenEstimator.estimate(systemText)
        var used = systemCost
        val kept = ArrayList<PromptMessage>()
        var trimmed = false

        // Walk backwards so the newest turns survive. Always keep the latest user turn.
        for (index in candidates.indices.reversed()) {
            val message = candidates[index]
            val cost = message.estimatedCost()
            val isLatest = index == candidates.lastIndex
            if (isLatest || used + cost <= budget) {
                kept.add(0, message)
                used += cost
            } else {
                trimmed = true
                break
            }
        }

        // Never resend more than a handful of images: they are expensive per
        // turn. Newest images win; older ones become a short text placeholder.
        var imageBudget = TextLimits.MAX_IMAGES_PER_REQUEST
        for (index in kept.indices.reversed()) {
            val message = kept[index]
            if (message.images.isEmpty()) continue
            val keep = message.images.take(imageBudget)
            val dropped = message.images.drop(keep.size)
            imageBudget -= keep.size
            if (dropped.isNotEmpty()) {
                val placeholder = dropped.joinToString("") { image ->
                    "\n\n[图片：${image.name.ifBlank { "图片" }}（较早，未重复发送）]"
                }
                kept[index] = message.copy(content = message.content + placeholder, images = keep)
            }
        }

        val result = ArrayList<PromptMessage>(kept.size + 1)
        if (systemText.isNotBlank()) result.add(PromptMessage("system", systemText))
        result.addAll(kept)
        return BuiltPrompt(
            messages = result,
            trimmedHistory = trimmed,
            estimatedTokens = used,
        )
    }

    private fun PromptMessage.estimatedCost(): Int =
        TokenEstimator.estimate(content) + images.size * IMAGE_TOKEN_ESTIMATE

    private fun ChatMessage.toPromptText(imageDir: File?): PromptMessage? {
        if (role != MessageRole.USER && role != MessageRole.ASSISTANT) return null
        if (role == MessageRole.ASSISTANT && (content.isBlank() || status == MessageStatus.ERROR)) return null
        if (role == MessageRole.USER && content.isBlank() && attachments.isEmpty()) return null

        var enriched = content
        val images = ArrayList<PromptImage>()
        if (role == MessageRole.USER && attachments.isNotEmpty()) {
            attachments.forEach { attachment ->
                if (attachment.isImage) {
                    val localName = attachment.localFile
                    val file = if (imageDir != null && localName != null) {
                        File(imageDir, localName)
                            .takeIf { it.isFile && it.length() <= TextLimits.MAX_IMAGE_BYTES }
                    } else {
                        null
                    }
                    if (file != null) {
                        images += PromptImage(
                            path = file.absolutePath,
                            mime = attachment.mime.ifBlank { "image/jpeg" },
                            name = attachment.name,
                        )
                    } else {
                        enriched += "\n\n[图片：${attachment.name}]"
                    }
                } else {
                    enriched += attachment.toBlock()
                }
            }
        }
        return PromptMessage(
            role = if (role == MessageRole.USER) "user" else "assistant",
            content = enriched,
            images = images,
        )
    }

    private fun Attachment.toBlock(): String = buildString {
        append("\n\n<attachment name=\"")
        append(name.replace("\"", "'"))
        append("\">\n")
        append(text)
        if (text.length >= com.deepsleep.chat.domain.model.TextLimits.MAX_CHARS_PER_ATTACHMENT) {
            append("\n…（附件内容已截断）")
        }
        append("\n</attachment>")
    }

    private fun buildSystemPrompt(
        provider: Provider,
        conversation: Conversation?,
        sources: List<SearchSource>,
    ): String {
        val base = conversation?.systemPrompt?.takeIf { it.isNotBlank() }
            ?: provider.systemPrompt.takeIf { it.isNotBlank() }
            ?: ""

        // LLMs have a training cutoff and cannot read the device clock. Give
        // every request an explicit local time so "today/now/latest" works.
        val now = ZonedDateTime.now()
        val weekday = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.SIMPLIFIED_CHINESE)
        val clock = buildString {
            append("当前设备时间：")
            append(now.format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")))
            append("（").append(now.zone.id).append("，").append(weekday).append("）。")
            append("当用户询问“现在、今天、最新”等信息时，请以这个时间为基准；")
            append("如果联网搜索结果包含更新的具体日期/时间，请以搜索结果为准并说明来源。")
        }

        val searchBlock = if (sources.isEmpty()) {
            ""
        } else {
            buildString {
                append("以下是本轮联网搜索的结果。请优先参考这些结果回答，并在正文中用 Markdown 链接")
                append("（[标题](URL)）标注来源；不要编造未出现在结果中的链接。若信息不足，请明确说明。\n")
                append("<search_results>\n")
                sources.forEachIndexed { index, source ->
                    append("[${index + 1}] ${source.title}\n")
                    append("URL: ${source.url}\n")
                    if (source.snippet.isNotBlank()) append("摘要: ${source.snippet.take(500)}\n")
                    append('\n')
                }
                append("</search_results>")
            }
        }

        return buildList {
            if (base.isNotBlank()) add(base)
            add(clock)
            if (searchBlock.isNotBlank()) add(searchBlock)
        }.joinToString("\n\n")
    }

    /** Vision models typically bill a fixed-ish amount per image; keep trimming conservative. */
    private const val IMAGE_TOKEN_ESTIMATE = 800
}