package com.deepsleep.chat.ui.update

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepsleep.chat.BuildConfig
import com.deepsleep.chat.R
import com.deepsleep.chat.data.update.UpdateInfo
import com.deepsleep.chat.data.update.UpdateManager
import com.deepsleep.chat.data.update.UpdateState
import com.deepsleep.chat.di.AppContainer
import java.util.Locale

private val UpdateGradient = listOf(Color(0xFF5B77FF), Color(0xFF4D6BFE))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateHost(container: AppContainer) {
    val manager = container.updateManager
    val state by manager.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        manager.autoCheckIfDue()
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetVisible = state is UpdateState.Available ||
        state is UpdateState.Downloading ||
        state is UpdateState.ReadyToInstall

    if (sheetVisible) {
        ModalBottomSheet(
            onDismissRequest = {
                if (state is UpdateState.Downloading) {
                    manager.cancelDownload()
                } else {
                    manager.dismiss()
                }
            },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            dragHandle = { BottomSheetDefaults.DragHandle() },
        ) {
            when (val current = state) {
                is UpdateState.Available -> AvailableContent(
                    info = current.info,
                    onUpdate = manager::downloadAndPrepare,
                    onLater = manager::dismiss,
                )

                is UpdateState.Downloading -> DownloadingContent(
                    info = current.info,
                    progress = current.progress,
                    onCancel = manager::cancelDownload,
                )

                is UpdateState.ReadyToInstall -> ReadyContent(
                    info = current.info,
                    manager = manager,
                    onLater = manager::dismiss,
                )

                else -> Unit
            }
        }
    }

    when (val current = state) {
        UpdateState.Idle,
        is UpdateState.Available,
        is UpdateState.Downloading,
        is UpdateState.ReadyToInstall,
        -> Unit

        UpdateState.Checking -> AlertDialog(
            onDismissRequest = {},
            title = { Text("检查更新") },
            text = { Text("正在向 GitHub 查询最新版本…") },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = manager::dismiss) { Text("取消") }
            },
        )

        UpdateState.UpToDate -> AlertDialog(
            onDismissRequest = manager::dismiss,
            title = { Text("已是最新版本") },
            text = { Text("当前版本 ${BuildConfig.VERSION_NAME}，没有发现更新的版本。") },
            confirmButton = {
                TextButton(onClick = manager::dismiss) { Text("好") }
            },
        )

        is UpdateState.Error -> AlertDialog(
            onDismissRequest = manager::dismiss,
            title = { Text("更新失败") },
            text = {
                Text(
                    text = current.message,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    manager.dismiss()
                    manager.check(manual = true)
                }) { Text("重新检查") }
            },
            dismissButton = {
                TextButton(onClick = manager::dismiss) { Text("关闭") }
            },
        )
    }
}

@Composable
private fun AvailableContent(
    info: UpdateInfo,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 18.dp),
    ) {
        SheetHeader(
            eyebrow = "发现新版本",
            version = info.versionName,
            meta = "${formatSize(info.sizeBytes)} · 今天发布",
            onClose = onLater,
        )
        NotesCard(info.notes)
        GradientButton(text = "立即更新", onClick = onUpdate)
        GhostButton(text = "稍后提醒", onClick = onLater)
    }
}

@Composable
private fun DownloadingContent(
    info: UpdateInfo,
    progress: Float?,
    onCancel: () -> Unit,
) {
    val target = progress?.coerceIn(0f, 1f) ?: 0f
    val animated by animateFloatAsState(targetValue = target, animationSpec = tween(260))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 14.dp),
    ) {
        SheetHeader(
            eyebrow = "正在下载更新",
            version = info.versionName,
            meta = "${formatSize(info.sizeBytes)} · 请保持网络连接",
            accent = progress?.let { "${(it * 100).toInt()}%" },
            onClose = onCancel,
        )
        Spacer(modifier = Modifier.height(18.dp))
        if (progress == null) {
            androidx.compose.material3.LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animated)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            Brush.horizontalGradient(listOf(Color(0xFF6C86FF), Color(0xFF4D6BFE))),
                        ),
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "正在下载…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = if (progress == null) "请稍候" else "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp),
        ) {
            Text(
                text = "取消下载",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ReadyContent(
    info: UpdateInfo,
    manager: UpdateManager,
    onLater: () -> Unit,
) {
    var permissionTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permissionTick++
    }
    val canInstall = remember(permissionTick) { manager.installPermissionGranted() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 18.dp),
    ) {
        SheetHeader(
            eyebrow = "下载完成",
            version = info.versionName,
            meta = "${formatSize(info.sizeBytes)} · 安装后聊天记录会保留",
            onClose = onLater,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 15.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(21.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "安装包已就绪",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "点击安装后由系统校验签名并覆盖安装。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (!canInstall) {
            Text(
                text = "首次安装需要在系统设置中允许本应用安装应用，授权后回到这里再次点击安装。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            )
        }
        GradientButton(text = "安装", onClick = manager::installPending)
        GhostButton(text = "稍后提醒", onClick = onLater)
    }
}

@Composable
private fun SheetHeader(
    eyebrow: String,
    version: String,
    meta: String,
    onClose: () -> Unit,
    accent: String? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.ic_brand_mark),
            contentDescription = null,
            modifier = Modifier.size(46.dp),
        )
        Spacer(modifier = Modifier.width(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = eyebrow,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = version,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.5).sp,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = accent ?: "当前 ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (accent != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = if (accent != null) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(role = Role.Button, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "关闭",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

@Composable
private fun NotesCard(notes: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 15.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = "更新内容",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 0.5.sp,
        )
        Spacer(modifier = Modifier.height(5.dp))
        val lines = remember(notes) {
            notes.lines()
                .map { stripMarkdown(it) }
                .filter { it.isNotBlank() }
                .take(12)
        }
        if (lines.isEmpty()) {
            Text(
                text = "该版本没有填写更新说明。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(
                modifier = Modifier
                    .heightIn(max = 140.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                lines.forEach { line ->
                    Row {
                        Box(
                            modifier = Modifier
                                .padding(top = 7.dp)
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GradientButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(Brush.linearGradient(UpdateGradient))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.3.sp,
        )
    }
}

@Composable
private fun GhostButton(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp),
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
    }
}

private fun stripMarkdown(line: String): String {
    var text = line.trim()
    while (text.startsWith("#")) text = text.removePrefix("#").trim()
    text = text
        .removePrefix("- ")
        .removePrefix("* ")
        .removePrefix("• ")
        .trim()
    return text
        .replace("**", "")
        .replace("`", "")
}

private fun formatSize(bytes: Long): String =
    if (bytes > 0) {
        String.format(Locale.CHINA, "%.1f MB", bytes / 1024f / 1024f)
    } else {
        "安装包"
    }