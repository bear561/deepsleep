package com.deepsleep.chat.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deepsleep.chat.di.AppContainer
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val viewModel: BackupViewModel = viewModel(factory = BackupViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
    val deepseekImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::inspectDeepSeek)
    }
    val ownImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::inspectOwn)
    }
    val deepseekExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let(viewModel::exportDeepSeek)
    }
    val ownExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let(viewModel::exportOwn)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("备份与恢复") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.working) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = state.status.ifBlank { "处理中…" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.error?.let { error ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::clearError) { Text("知道了") }
                    }
                }
            }

            state.result?.let { result ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = result,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = viewModel::clearResult) { Text("关闭") }
                    }
                }
            }

            SectionLabel("DeepSeek 官方备份")
            Text(
                text = "在 DeepSeek App/网页端进入 设置 → 数据管理 → 导出所有历史对话，把下载链接在浏览器中打开得到 ZIP，然后在这里导入。只支持格式 v3。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { deepseekImport.launch(arrayOf("*/*")) },
                enabled = !state.working,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("导入 DeepSeek 备份（ZIP）")
            }
            OutlinedButton(
                onClick = { deepseekExport.launch("deepsleep-deepseek-backup-$today.zip") },
                enabled = !state.working,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("导出为 DeepSeek 格式")
            }
            Text(
                text = "导入只会保留每个对话的当前分支；官方格式不含图片正文，图片文件名会保留但不能查看。导出时附件也只写元数据。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.size(10.dp))
            SectionLabel("本 App 备份")
            Text(
                text = "包含所有对话、附件文本、来源、设置和 API 配置（不包含 API Key），换机后可无损恢复。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { ownImport.launch(arrayOf("*/*")) },
                enabled = !state.working,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("导入本 App 备份")
            }
            OutlinedButton(
                onClick = { ownExport.launch("deepsleep-backup-$today.zip") },
                enabled = !state.working,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("导出本 App 备份")
            }

            Spacer(modifier = Modifier.size(10.dp))
            Text(
                text = "隐私提示：备份文件包含聊天内容，请妥善保管。API Key 不会写入任何备份，导入后需要在设置中重新填写。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    state.inspection?.let { inspection ->
        val kind = state.pendingKind
        AlertDialog(
            onDismissRequest = viewModel::dismissInspection,
            title = { Text("确认导入") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = when (kind) {
                            BackupImportKind.DEEPSEEK -> "DeepSeek 备份 · 格式 v${inspection.formatVersion}"
                            else -> "deepsleep 备份 · 格式 v${inspection.formatVersion}"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = buildString {
                            append("发现 ${inspection.sessionCount} 个对话")
                            if (inspection.messageCount > 0) {
                                append("，约 ${inspection.messageCount} 条消息")
                            }
                            append("。")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (inspection.duplicateCount > 0) {
                        Text(
                            text = "其中 ${inspection.duplicateCount} 个对话已经导入过。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = state.skipDuplicates,
                            onCheckedChange = viewModel::setSkipDuplicates,
                        )
                        Text("跳过已存在的对话", style = MaterialTheme.typography.bodySmall)
                    }
                    if (kind == BackupImportKind.DEEPSEEK) {
                        Text(
                            text = "只会导入每个对话的当前分支；图片文件不会导入。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmImport) { Text("开始导入") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissInspection) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp),
    )
}