package com.deepsleep.chat.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deepsleep.chat.R
import com.deepsleep.chat.data.remote.openai.UrlNormalizer
import com.deepsleep.chat.di.AppContainer
import com.deepsleep.chat.domain.model.ModelConfig
import com.deepsleep.chat.domain.model.ProviderPresets
import com.deepsleep.chat.util.ModelConfigs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderEditScreen(
    container: AppContainer,
    providerId: String,
    onBack: () -> Unit,
) {
    val viewModel: ProviderEditViewModel = viewModel(
        key = "provider-$providerId",
        factory = ProviderEditViewModel.factory(container, providerId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showKey by rememberSaveable { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val parsedModels = remember(state.modelsText) { ModelConfigs.parse(state.modelsText) }

    LaunchedEffect(state.saved) {
        if (state.saved) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "添加 API 配置" else "编辑 API 配置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (state.loading) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                SectionLabel("快速预设")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ProviderPresets.all.forEach { preset ->
                        AssistChip(
                            onClick = { viewModel.applyPreset(preset) },
                            label = { Text(preset.label) },
                        )
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = { value -> viewModel.update { it.copy(name = value) } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("名称") },
                    placeholder = { Text("例如：DeepSeek 官方") },
                    singleLine = true,
                )
            }

            item {
                OutlinedTextField(
                    value = state.baseUrl,
                    onValueChange = { value -> viewModel.update { it.copy(baseUrl = value) } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Base URL") },
                    placeholder = { Text("https://api.deepseek.com/v1") },
                    singleLine = true,
                    supportingText = {
                        if (state.baseUrl.isNotBlank()) {
                            Text("请求地址：${UrlNormalizer.previewChatUrl(state.baseUrl)}")
                        } else {
                            Text("OpenAI 兼容地址，通常以 /v1 结尾")
                        }
                    },
                )
            }

            item {
                OutlinedTextField(
                    value = state.apiKeyInput,
                    onValueChange = { value -> viewModel.update { it.copy(apiKeyInput = value) } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showKey = !showKey }) {
                            Text(if (showKey) "隐藏" else "显示")
                        }
                    },
                    supportingText = {
                        Text(
                            when {
                                state.hasStoredKey -> "已保存 Key；留空则保持原 Key 不变"
                                else -> "本地加密保存，不会上传到任何服务器"
                            },
                        )
                    },
                )
            }

            item {
                SectionLabel("模型")
                OutlinedTextField(
                    value = state.modelsText,
                    onValueChange = { value -> viewModel.update { it.copy(modelsText = value) } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("模型列表") },
                    minLines = 4,
                    maxLines = 10,
                    supportingText = {
                        Text("每行一个：模型ID | 显示名 | 是否支持深度思考（可选 true）")
                    },
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = viewModel::testConnection,
                        enabled = !state.testing,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (state.testing) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text("测试连接")
                    }
                    OutlinedButton(
                        onClick = viewModel::fetchModels,
                        enabled = !state.testing,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("拉取模型")
                    }
                }
            }

            if (state.fetchedModels.isNotEmpty()) {
                item {
                    TextButton(onClick = viewModel::useFetchedModels) {
                        Text("使用检测到的 ${state.fetchedModels.size} 个模型")
                    }
                }
            }

            item {
                ModelDropdown(
                    label = "默认模型",
                    selectedId = state.chatModelId,
                    models = parsedModels,
                    allowNone = false,
                    onSelect = { id -> viewModel.update { it.copy(chatModelId = id) } },
                )
            }

            item {
                ModelDropdown(
                    label = "深度思考模型（可选）",
                    selectedId = state.reasoningModelId,
                    models = parsedModels,
                    allowNone = true,
                    onSelect = { id -> viewModel.update { it.copy(reasoningModelId = id) } },
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("设为默认配置", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "新对话默认使用这套配置",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.isDefault,
                        onCheckedChange = { checked -> viewModel.update { it.copy(isDefault = checked) } },
                    )
                }
            }

            item {
                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Text(if (showAdvanced) "收起高级选项" else "高级选项")
                }
            }

            if (showAdvanced) {
                item {
                    OutlinedTextField(
                        value = state.systemPrompt,
                        onValueChange = { value -> viewModel.update { it.copy(systemPrompt = value) } },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("系统提示词（可选）") },
                        minLines = 2,
                        maxLines = 5,
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = state.temperature,
                            onValueChange = { value -> viewModel.update { it.copy(temperature = value) } },
                            modifier = Modifier.weight(1f),
                            label = { Text("Temperature") },
                            placeholder = { Text("默认") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                        OutlinedTextField(
                            value = state.maxTokens,
                            onValueChange = { value -> viewModel.update { it.copy(maxTokens = value) } },
                            modifier = Modifier.weight(1f),
                            label = { Text("最大输出 Token") },
                            placeholder = { Text("默认") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                }
                item {
                    OutlinedTextField(
                        value = state.extraHeadersText,
                        onValueChange = { value -> viewModel.update { it.copy(extraHeadersText = value) } },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("额外请求头（可选）") },
                        placeholder = { Text("HTTP-Referer: https://example.com") },
                        minLines = 2,
                        maxLines = 5,
                        supportingText = { Text("每行 Key: Value，用于 OpenRouter 等特殊要求") },
                    )
                }
            }

            state.message?.let { message ->
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (state.messageIsError) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.messageIsError) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }

            item {
                Button(
                    onClick = viewModel::save,
                    enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("保存")
                }
            }

            if (!state.isNew) {
                item {
                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("删除这套配置", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            item { Spacer(modifier = Modifier.size(24.dp)) }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除配置") },
            text = { Text("确定删除这套 API 配置吗？相关对话不会被删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.delete()
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ModelDropdown(
    label: String,
    selectedId: String,
    models: List<ModelConfig>,
    allowNone: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = models.firstOrNull { it.id == selectedId }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = selected?.displayName
                        ?: if (allowNone || selectedId.isBlank()) "不启用" else "请选择模型",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_arrow_down),
                contentDescription = null,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (allowNone) {
                DropdownMenuItem(
                    text = { Text("不启用") },
                    onClick = {
                        onSelect("")
                        expanded = false
                    },
                )
            }
            models.forEach { model ->
                DropdownMenuItem(
                    text = {
                        Text(
                            if (model.supportsReasoning) "${model.displayName} · 深度思考" else model.displayName,
                        )
                    },
                    onClick = {
                        onSelect(model.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}