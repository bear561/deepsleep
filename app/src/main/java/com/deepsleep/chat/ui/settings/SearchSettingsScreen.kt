package com.deepsleep.chat.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.deepsleep.chat.di.AppContainer
import com.deepsleep.chat.domain.model.SearchConfig
import com.deepsleep.chat.domain.model.SearchProviderType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loaded by rememberSaveable { mutableStateOf(false) }
    var type by rememberSaveable { mutableStateOf(SearchProviderType.TAVILY) }
    var baseUrl by rememberSaveable { mutableStateOf("") }
    var maxResultsText by rememberSaveable { mutableStateOf("5") }
    var keyInput by rememberSaveable { mutableStateOf("") }
    var showKey by rememberSaveable { mutableStateOf(false) }
    var hasKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (!loaded) {
            val settings = container.settingsRepository.settings.first()
            type = settings.search.type
            baseUrl = settings.search.baseUrl
            maxResultsText = settings.search.maxResults.toString()
            hasKey = settings.searchHasKey
            loaded = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("联网搜索") },
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
            Text(
                text = "DeepSeek 官方 API 不提供内置联网搜索。开启“联网搜索”时，本 App 会用你配置的第三方搜索服务查询，再把结果交给模型总结，并在回答下方列出真实来源。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("搜索服务", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchProviderType.entries.forEach { providerType ->
                    FilterChip(
                        selected = type == providerType,
                        onClick = { type = providerType },
                        label = {
                            Text(
                                when (providerType) {
                                    SearchProviderType.TAVILY -> "Tavily"
                                    SearchProviderType.SERPER -> "Serper"
                                    SearchProviderType.SEARXNG -> "SearXNG"
                                },
                            )
                        },
                    )
                }
            }

            Text(
                text = when (type) {
                    SearchProviderType.TAVILY ->
                        "Tavily 免费额度：每月约 1000 次基础搜索，够个人日常使用（以官网为准）。"
                    SearchProviderType.SERPER ->
                        "Serper 免费额度：注册赠送约 2500 次搜索，一次性额度；用完后按量付费。"
                    SearchProviderType.SEARXNG ->
                        "SearXNG 免费：可自建，也可使用公共实例；公共实例可能限流或关闭 JSON 输出。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )

            if (type == SearchProviderType.SEARXNG) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("SearXNG 地址") },
                    placeholder = { Text("https://searx.example.com") },
                    singleLine = true,
                    supportingText = { Text("需要开启 JSON 输出（format=json）") },
                )
            } else {
                Text(
                    text = when (type) {
                        SearchProviderType.TAVILY -> "使用 Tavily Search API（api.tavily.com）"
                        SearchProviderType.SERPER -> "使用 Serper Google Search API（google.serper.dev）"
                        else -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("搜索 API Key") },
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
                            type == SearchProviderType.SEARXNG -> "自建 SearXNG 通常无需 Key，可留空"
                            hasKey -> "已保存 Key；留空则保持不变"
                            else -> "在对应搜索服务官网申请"
                        },
                    )
                },
            )

            OutlinedTextField(
                value = maxResultsText,
                onValueChange = { maxResultsText = it.filter { ch -> ch.isDigit() }.take(2) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("每次搜索结果数") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                supportingText = { Text("建议 3–5 条，最多 10 条") },
            )

            OutlinedButton(
                onClick = {
                    if (testing) return@OutlinedButton
                    testing = true
                    testMessage = null
                    scope.launch {
                        try {
                            val max = maxResultsText.toIntOrNull()?.coerceIn(1, 10) ?: 5
                            val config = SearchConfig(
                                type = type,
                                baseUrl = baseUrl.trim(),
                                maxResults = max,
                            )
                            val key = keyInput.trim().takeIf { it.isNotEmpty() }
                                ?: container.settingsRepository.searchApiKey()
                            val result = container.searchRepository.search(config, key, "DeepSeek")
                            testMessage = result.fold(
                                onSuccess = { "搜索成功，返回 ${it.size} 条结果" },
                                onFailure = { "搜索失败：${it.message ?: "未知错误"}" },
                            )
                        } finally {
                            testing = false
                        }
                    }
                },
                enabled = !testing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("测试搜索")
            }

            testMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (message.startsWith("搜索成功")) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }

            Button(
                onClick = {
                    val max = maxResultsText.toIntOrNull()?.coerceIn(1, 10) ?: 5
                    scope.launch {
                        container.settingsRepository.setSearchConfig(
                            SearchConfig(
                                type = type,
                                baseUrl = baseUrl.trim(),
                                maxResults = max,
                            ),
                        )
                        if (keyInput.isNotBlank()) {
                            container.settingsRepository.setSearchApiKey(keyInput.trim())
                        }
                        Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                        onBack()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存")
            }

            Spacer(modifier = Modifier.size(16.dp))
            Text(
                text = "提示：搜索会消耗第三方服务的额度；搜索结果仅作为参考，模型仍可能出错。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}