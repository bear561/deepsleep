package com.deepsleep.chat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deepsleep.chat.R
import com.deepsleep.chat.ui.chat.voice.VoicePhase

@Composable
fun Composer(
    state: ChatUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onToggleThinking: () -> Unit,
    onToggleWebSearch: () -> Unit,
    onPickImages: () -> Unit,
    onPickFile: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onVoicePress: () -> Unit,
    onVoiceRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canSend = (state.input.isNotBlank() || state.attachments.isNotEmpty()) && !state.isStreaming
    val voiceActive = state.voicePhase != VoicePhase.IDLE
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (state.attachments.isNotEmpty() || state.attachmentLoading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    state.attachments.forEach { attachment ->
                        if (attachment.isImage) {
                            ImageAttachmentChip(
                                attachment = attachment,
                                onRemove = { onRemoveAttachment(attachment.id) },
                            )
                        } else {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = attachment.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.width(120.dp),
                                    )
                                    IconButton(
                                        onClick = { onRemoveAttachment(attachment.id) },
                                        modifier = Modifier.size(26.dp),
                                    ) {
                                        Text("×", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                    if (state.attachmentLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 1.5.dp)
                        Text(
                            text = "处理中…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                var attachMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(
                        onClick = { attachMenu = true },
                        enabled = !state.isStreaming && !state.attachmentLoading,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_attach),
                            contentDescription = "添加附件",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("从相册选择图片") },
                            onClick = {
                                attachMenu = false
                                onPickImages()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("选择文本文件") },
                            onClick = {
                                attachMenu = false
                                onPickFile()
                            },
                        )
                    }
                }
                OutlinedTextField(
                    value = state.input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("给 deepsleep 发送消息") },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 6,
                    minLines = 1,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                )
                // Press-and-hold: voice recording starts on touch down and stops
                // on release, matching the official app's interaction.
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (voiceActive) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            CircleShape,
                        )
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    onVoicePress()
                                    tryAwaitRelease()
                                    onVoiceRelease()
                                },
                            )
                        }
                        .semantics {
                            contentDescription = "按住说话"
                            role = Role.Button
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_mic),
                        contentDescription = null,
                        tint = if (voiceActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (state.isStreaming) {
                    FilledIconButton(
                        onClick = onStop,
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_stop),
                            contentDescription = "停止生成",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else {
                    FilledIconButton(
                        onClick = onSend,
                        enabled = canSend,
                        shape = CircleShape,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_send),
                            contentDescription = "发送",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = state.thinkingEnabled,
                    onClick = onToggleThinking,
                    enabled = !state.isStreaming && state.activeProvider != null,
                    label = { Text("深度思考") },
                )
                FilterChip(
                    selected = state.webSearchEnabled,
                    onClick = onToggleWebSearch,
                    enabled = !state.isStreaming,
                    label = { Text("联网搜索") },
                )
                Spacer(modifier = Modifier.weight(1f))
                if (state.isStreaming) {
                    Text(
                        text = if (state.searching) "正在搜索…" else "生成中",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ImageAttachmentChip(
    attachment: com.deepsleep.chat.domain.model.Attachment,
    onRemove: () -> Unit,
) {
    Box(modifier = Modifier.size(56.dp)) {
        LocalAttachmentImage(
            attachment = attachment,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(18.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.65f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "×",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
        // The remove target covers the badge and a little extra for fingers.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(28.dp)
                .clip(CircleShape)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onRemove() })
                },
        )
    }
}