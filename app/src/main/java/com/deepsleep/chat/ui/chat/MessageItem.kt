package com.deepsleep.chat.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepsleep.chat.R
import com.deepsleep.chat.domain.model.ChatMessage
import com.deepsleep.chat.domain.model.MessageRole
import com.deepsleep.chat.domain.model.MessageStatus
import com.deepsleep.chat.domain.model.SearchSource
import com.deepsleep.chat.ui.chat.markdown.MarkdownMessage

@Composable
fun MessageItem(
    message: ChatMessage,
    isLastAssistant: Boolean,
    isGenerating: Boolean,
    thinkingExpected: Boolean,
    onRegenerate: () -> Unit,
    onEditUser: (ChatMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (message.role) {
        MessageRole.USER -> UserMessage(message, isGenerating, onEditUser, modifier)
        MessageRole.ASSISTANT -> AssistantMessage(
            message = message,
            isLastAssistant = isLastAssistant,
            isGenerating = isGenerating,
            thinkingExpected = thinkingExpected,
            onRegenerate = onRegenerate,
            modifier = modifier,
        )
        MessageRole.SYSTEM -> Unit
    }
}

@Composable
private fun UserMessage(
    message: ChatMessage,
    isGenerating: Boolean,
    onEditUser: (ChatMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.widthIn(max = 330.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (message.attachments.isNotEmpty()) {
                    message.attachments.forEach { attachment ->
                        Text(
                            text = "附件：${attachment.name}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                        )
                    }
                    if (message.content.isNotBlank()) Spacer(modifier = Modifier.size(6.dp))
                }
                if (message.content.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }
        if (!isGenerating) {
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SmallAction("复制") { copyToClipboard(context, message.content) }
                SmallAction("编辑") { onEditUser(message) }
            }
        }
    }
}

@Composable
private fun AssistantMessage(
    message: ChatMessage,
    isLastAssistant: Boolean,
    isGenerating: Boolean,
    thinkingExpected: Boolean,
    onRegenerate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxWidth()) {
        if (message.thinkingEnabled &&
            (message.reasoning.isNotBlank() ||
                (thinkingExpected && message.status == MessageStatus.STREAMING))
        ) {
            ReasoningPanel(
                messageId = message.id,
                reasoning = message.reasoning,
                reasoningMs = message.reasoningMs,
                status = message.status,
                contentStarted = message.content.isNotBlank(),
                showPlaceholder = thinkingExpected && message.content.isBlank(),
            )
            Spacer(modifier = Modifier.size(10.dp))
        }

        when {
            message.status == MessageStatus.STREAMING && message.content.isBlank() -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = if (thinkingExpected) "深度思考中…" else "正在生成…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            message.content.isNotBlank() -> {
                // Live Markdown while streaming, matching the official app instead
                // of showing raw Markdown punctuation until the answer finishes.
                MarkdownMessage(
                    text = message.content,
                    streaming = message.status == MessageStatus.STREAMING,
                )
            }
        }

        if (message.sources.isNotEmpty()) {
            Spacer(modifier = Modifier.size(10.dp))
            SourcesCard(message.sources)
        }

        if (message.status == MessageStatus.ERROR && !message.error.isNullOrBlank()) {
            Spacer(modifier = Modifier.size(8.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = message.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    if (isLastAssistant && !isGenerating) {
                        TextButton(
                            onClick = onRegenerate,
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                        ) {
                            Text("重试")
                        }
                    }
                }
            }
        }

        if (message.status == MessageStatus.STOPPED) {
            Text(
                text = "已停止生成",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (!isGenerating && message.status != MessageStatus.STREAMING) {
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (message.content.isNotBlank()) {
                    SmallAction("复制") { copyToClipboard(context, message.content) }
                    SmallAction("分享") { shareText(context, message.content) }
                }
                if (isLastAssistant) {
                    SmallAction("重新生成", onClick = onRegenerate)
                }
            }
        }

        message.usage?.let { usage ->
            if (message.status == MessageStatus.COMPLETE && usage.totalTokens > 0) {
                Text(
                    text = "输入 ${usage.promptTokens} · 输出 ${usage.completionTokens} tokens",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun ReasoningPanel(
    messageId: String,
    reasoning: String,
    reasoningMs: Long?,
    status: MessageStatus,
    contentStarted: Boolean,
    showPlaceholder: Boolean,
) {
    var userToggled by rememberSaveable(messageId) { mutableStateOf(false) }
    var expanded by remember(messageId) {
        mutableStateOf(status == MessageStatus.STREAMING && !contentStarted)
    }
    LaunchedEffect(contentStarted, status) {
        if (!userToggled && (contentStarted || status != MessageStatus.STREAMING)) {
            expanded = false
        }
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        userToggled = true
                        expanded = !expanded
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = reasoningTitle(status, reasoningMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_down),
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(if (expanded) 180f else 0f),
                )
            }
            if (expanded) {
                if (reasoning.isBlank() && showPlaceholder) {
                    Text(
                        text = "正在整理思路…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else {
                    SelectionContainer {
                        Text(
                            text = reasoning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .heightIn(max = 260.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourcesCard(sources: List<SearchSource>) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "参考来源",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            sources.forEachIndexed { index, source ->
                val host = runCatching { Uri.parse(source.url).host }
                    .getOrNull() ?: source.url.take(40)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.url)))
                            }.onFailure {
                                Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .padding(vertical = 5.dp),
                ) {
                    Text(
                        text = "${index + 1}.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.widthIn(min = 18.dp),
                    )
                    Column {
                        Text(
                            text = source.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = host,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallAction(label: String, onClick: () -> Unit) {
    // A faint translucent pill keeps these secondary actions visually separate
    // from the answer without using an expensive blur effect on low-end devices.
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = androidx.compose.foundation.BorderStroke(
            width = 0.5.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 11.sp,
            )
        }
    }
}

private fun reasoningTitle(status: MessageStatus, reasoningMs: Long?): String {
    val seconds = reasoningMs?.let { (it / 1000.0).coerceAtLeast(1.0).toInt() }
    return when (status) {
        MessageStatus.STREAMING -> "深度思考中…"
        MessageStatus.STOPPED -> "思考过程（已停止）"
        MessageStatus.ERROR -> "思考过程"
        else -> if (seconds != null) "已深度思考（用时 ${seconds} 秒）" else "已深度思考"
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("deepsleep", text))
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "分享对话"))
}