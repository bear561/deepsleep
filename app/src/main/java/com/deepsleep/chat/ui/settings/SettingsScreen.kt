package com.deepsleep.chat.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepsleep.chat.BuildConfig
import com.deepsleep.chat.di.AppContainer
import com.deepsleep.chat.domain.model.AppSettings
import com.deepsleep.chat.domain.model.SearchProviderType
import com.deepsleep.chat.domain.model.ThemeMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onAddProvider: () -> Unit,
    onEditProvider: (String) -> Unit,
    onOpenSearchSettings: () -> Unit,
    onOpenBackup: () -> Unit,
) {
    val providers by container.providerRepository.providers
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by container.settingsRepository.settings
        .collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    var showThemeDialog by remember { mutableStateOf(false) }
    var showContextDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("API 配置", modifier = Modifier.weight(1f))
                    TextButton(onClick = onAddProvider) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("添加")
                    }
                }
            }
            if (providers.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("还没有 API 配置", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "添加 DeepSeek、OpenRouter 或其他 OpenAI 兼容服务后即可聊天。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                            )
                            Button(onClick = onAddProvider) { Text("添加配置") }
                        }
                    }
                }
            }
            items(providers, key = { it.id }) { provider ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEditProvider(provider.id) },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(provider.name, style = MaterialTheme.typography.bodyLarge)
                                if (provider.isDefault) {
                                    Text(
                                        text = "默认",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(start = 6.dp),
                                    )
                                }
                            }
                            Text(
                                text = provider.baseUrl,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = when {
                                    provider.hasApiKey -> "Key 已保存 · ${provider.models.size} 个模型"
                                    provider.models.isEmpty() -> "未设置 Key · 未添加模型"
                                    else -> "未设置 Key · ${provider.models.size} 个模型"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = "›",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.size(8.dp)) }
            item { SectionTitle("偏好") }
            item {
                SettingRow(
                    title = "外观",
                    value = themeLabel(settings.themeMode),
                    onClick = { showThemeDialog = true },
                )
            }
            item {
                SettingRow(
                    title = "上下文上限",
                    value = "${settings.contextTokenLimit} tokens",
                    onClick = { showContextDialog = true },
                )
            }

            item { Spacer(modifier = Modifier.size(8.dp)) }
            item { SectionTitle("联网搜索") }
            item {
                SettingRow(
                    title = "搜索服务",
                    value = searchLabel(settings),
                    onClick = onOpenSearchSettings,
                )
            }

            item { Spacer(modifier = Modifier.size(8.dp)) }
            item { SectionTitle("数据") }
            item {
                SettingRow(
                    title = "备份与恢复",
                    value = "DeepSeek 备份导入/导出",
                    onClick = onOpenBackup,
                )
            }

            item { Spacer(modifier = Modifier.size(8.dp)) }
            item { SectionTitle("关于") }
            item {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text("deepsleep ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "本地优先：对话和 API Key 只保存在这台设备上，没有账号、没有云端同步、没有统计上报。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }

    if (showThemeDialog) {
        var selected by remember { mutableStateOf(settings.themeMode) }
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("外观") },
            text = {
                Column {
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected = mode }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected == mode, onClick = { selected = mode })
                            Text(themeLabel(mode))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showThemeDialog = false
                        scope.launch { container.settingsRepository.setThemeMode(selected) }
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showThemeDialog = false }) { Text("取消") }
            },
        )
    }

    if (showContextDialog) {
        var value by remember { mutableStateOf(settings.contextTokenLimit.toString()) }
        AlertDialog(
            onDismissRequest = { showContextDialog = false },
            title = { Text("上下文上限") },
            text = {
                Column {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it.filter { ch -> ch.isDigit() }.take(7) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        label = { Text("tokens") },
                    )
                    Text(
                        text = "历史对话超过上限时会自动裁剪较早内容。默认 32000。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val limit = value.toIntOrNull() ?: settings.contextTokenLimit
                        showContextDialog = false
                        scope.launch { container.settingsRepository.setContextTokenLimit(limit) }
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showContextDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun SettingRow(
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "›",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "深色"
}

private fun searchLabel(settings: AppSettings): String {
    val type = when (settings.search.type) {
        SearchProviderType.TAVILY -> "Tavily"
        SearchProviderType.SERPER -> "Serper"
        SearchProviderType.SEARXNG -> "SearXNG"
    }
    return when {
        settings.search.type == SearchProviderType.SEARXNG &&
            settings.search.baseUrl.isNotBlank() -> "SearXNG 已配置"
        settings.searchHasKey -> "$type 已配置"
        else -> "未配置"
    }
}