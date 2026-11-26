package com.deepsleep.chat.ui.chat

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deepsleep.chat.R
import com.deepsleep.chat.di.AppContainer
import com.deepsleep.chat.domain.model.ChatMessage
import com.deepsleep.chat.domain.model.MessageRole
import com.deepsleep.chat.domain.model.TextLimits
import com.deepsleep.chat.ui.chat.voice.VoicePhase
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    container: AppContainer,
    conversationId: String?,
    onOpenConversation: (String) -> Unit,
    onNewChatRoute: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddProvider: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenSearchSettings: () -> Unit,
) {
    val viewModel: ChatViewModel = viewModel(
        key = "chat-${conversationId ?: "new"}",
        factory = ChatViewModel.factory(container, conversationId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    var showSearchSetup by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<ChatMessage?>(null) }

    // The message list uses reverseLayout: index 0 is the newest item at the bottom.
// While a message grows, the bottom edge stays anchored, so we do NOT scroll on
// every token (that was the source of the jump/flicker).
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            info.totalItemsCount == 0 ||
                (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 24)
        }
    }

    LaunchedEffect(state.messages.size, state.searching) {
        if (state.messages.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::addAttachment)
    }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(TextLimits.MAX_IMAGES_PER_MESSAGE),
    ) { uris ->
        viewModel.addImageAttachments(uris)
    }

    // Some ROMs expose only a recognition activity, not a RecognitionService.
    // Keep it as the last-resort fallback before telling the user to configure
    // cloud transcription.
    val voiceIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "请说话")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
    }
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val text = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (text.isNotEmpty()) viewModel.appendVoiceText(text)
        }
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.showWarning("已获得麦克风权限，请按住麦克风说话")
        } else {
            viewModel.showError("需要麦克风权限才能语音输入")
        }
    }
    val canTranscribe = !state.activeProvider?.transcriptionModelId.isNullOrBlank()
    val pressVoice: () -> Unit = {
        if (state.isStreaming) {
            Unit
        } else {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            when {
                !granted -> micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                viewModel.hasSystemSpeechService() || canTranscribe -> viewModel.startVoiceInput()
                voiceIntent.resolveActivity(context.packageManager) != null -> {
                    runCatching { voiceLauncher.launch(voiceIntent) }
                        .onFailure { viewModel.showError("无法启动语音识别") }
                }
                else -> viewModel.startVoiceInput()
            }
        }
    }
    val releaseVoice: () -> Unit = { viewModel.stopVoiceInput() }

    val closeDrawerThen: (() -> Unit) -> Unit = { action ->
        scope.launch {
            drawerState.close()
            action()
        }
    }
    val closeDrawer = { closeDrawerThen {} }
    val handleNewChat: () -> Unit = {
        closeDrawerThen {
            if (conversationId == null) viewModel.newChat() else onNewChatRoute()
        }
    }
    val handleSelectConversation: (String) -> Unit = { id ->
        closeDrawerThen {
            when {
                id == state.conversationId -> Unit
                id == conversationId -> Unit
                else -> onOpenConversation(id)
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ChatDrawer(
                state = state,
                onQueryChange = viewModel::setConversationQuery,
                onSelectConversation = handleSelectConversation,
                onNewChat = handleNewChat,
                onRename = viewModel::renameConversation,
                onDelete = viewModel::deleteConversation,
                onTogglePin = { viewModel.setConversationPinned(it.id, !it.pinned) },
                onOpenSettings = {
                    closeDrawerThen { onOpenSettings() }
                },
                onOpenBackup = {
                    closeDrawerThen { onOpenBackup() }
                },
            )
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Column(
                            modifier = Modifier
                                .clickable { showModelPicker = true }
                                .padding(vertical = 2.dp),
                        ) {
                            Text(
                                text = state.conversation?.title ?: "新对话",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = state.activeModelDisplay,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 220.dp),
                                )
                                Icon(
                                    painter = painterResource(R.drawable.ic_arrow_down),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "历史对话")
                        }
                    },
                    actions = {
                        IconButton(onClick = handleNewChat) {
                            Icon(Icons.Default.Add, contentDescription = "新对话")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            },
            bottomBar = {
                if (!state.noProvider) {
                    Column(
                        modifier = Modifier
                            .navigationBarsPadding()
                            .imePadding(),
                    ) {
                        if (state.voicePhase != VoicePhase.IDLE) {
                            VoiceBar(
                                phase = state.voicePhase,
                                partial = state.voicePartial,
                            )
                        }
                        Composer(
                            state = state,
                            onInputChange = viewModel::setInput,
                            onSend = viewModel::sendMessage,
                            onStop = viewModel::stopGeneration,
                            onToggleThinking = viewModel::toggleThinking,
                            onToggleWebSearch = {
                                if (!state.webSearchEnabled && !state.searchConfigured) {
                                    showSearchSetup = true
                                } else {
                                    viewModel.toggleWebSearch()
                                }
                            },
                            onPickImages = {
                                imagePicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            onPickFile = { filePicker.launch(arrayOf("text/*")) },
                            onRemoveAttachment = viewModel::removeAttachment,
                            onVoicePress = pressVoice,
                            onVoiceRelease = releaseVoice,
                        )
                    }
                }
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                state.warning?.let { warning ->
                    NoticeBar(
                        text = warning,
                        isError = false,
                        onDismiss = viewModel::dismissWarning,
                    )
                }
                state.error?.let { error ->
                    NoticeBar(
                        text = error,
                        isError = true,
                        onDismiss = viewModel::dismissError,
                    )
                }

                Box(modifier = Modifier.weight(1f)) {
                    when {
                        state.isLoadingConversation -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        state.messages.isEmpty() -> {
                            if (state.noProvider) {
                                NoProviderState(
                                    onAddProvider = onAddProvider,
                                    onOpenBackup = onOpenBackup,
                                )
                            } else {
                                WelcomeState(onSuggestion = viewModel::setInput)
                            }
                        }
                        else -> {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                reverseLayout = true,
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(18.dp),
                            ) {
                                val lastAssistantId = state.messages.lastOrNull {
                                    it.role == MessageRole.ASSISTANT
                                }?.id
                                if (state.searching) {
                                    item(key = "searching") {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(14.dp),
                                                strokeWidth = 1.5.dp,
                                            )
                                            Spacer(modifier = Modifier.size(8.dp))
                                            Text(
                                                text = "正在联网搜索…",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                items(
                                    items = state.messages.asReversed(),
                                    key = { it.id },
                                    contentType = { it.role.name },
                                ) { message ->
                                    val isLastAssistant = message.id == lastAssistantId
                                    MessageItem(
                                        message = message,
                                        isLastAssistant = isLastAssistant,
                                        isGenerating = state.isStreaming,
                                        thinkingExpected = isLastAssistant &&
                                            message.thinkingEnabled &&
                                            message.status == com.deepsleep.chat.domain.model.MessageStatus.STREAMING,
                                        onRegenerate = viewModel::regenerate,
                                        onEditUser = { editTarget = it },
                                    )
                                }
                            }

                            if (!isAtBottom) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.BottomEnd,
                                ) {
                                    FilledTonalIconButton(
                                        onClick = {
                                            scope.launch { listState.animateScrollToItem(0) }
                                        },
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_arrow_down),
                                            contentDescription = "回到底部",
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showModelPicker) {
        ModelPickerSheet(
            state = state,
            onDismiss = { showModelPicker = false },
            onSelect = { provider, model ->
                viewModel.selectModel(provider, model)
                showModelPicker = false
            },
            onManageProviders = {
                showModelPicker = false
                onOpenSettings()
            },
        )
    }

    if (showSearchSetup) {
        AlertDialog(
            onDismissRequest = { showSearchSetup = false },
            title = { Text("联网搜索尚未配置") },
            text = {
                Text(
                    "DeepSeek 官方 API 不提供搜索。需要先在设置里配置 Tavily、Serper 或 SearXNG 的 Key/地址。配置后开启开关，本轮问题会先搜索，并在回答下方展示真实来源。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSearchSetup = false
                        onOpenSearchSettings()
                    },
                ) { Text("去配置") }
            },
            dismissButton = {
                TextButton(onClick = { showSearchSetup = false }) { Text("取消") }
            },
        )
    }

    if (state.reasoningPickerVisible) {
        val provider = state.activeProvider
        val models = provider?.models.orEmpty()
        AlertDialog(
            onDismissRequest = viewModel::dismissReasoningPicker,
            title = { Text("选择深度思考模型") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "当前配置没有指定推理模型。选择一个模型后，会把它保存为这套配置的深度思考模型。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (models.isEmpty()) {
                        Text(
                            text = "当前配置没有模型，请先到 API 配置中添加或拉取模型。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    } else {
                        models.forEach { model ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.setReasoningModel(model.id) }
                                    .padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = state.thinkingEnabled &&
                                        model.id == state.selectedModelId,
                                    onClick = { viewModel.setReasoningModel(model.id) },
                                )
                                Column {
                                    Text(
                                        text = model.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    if (model.id != model.displayName) {
                                        Text(
                                            text = model.id,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (!model.supportsReasoning) {
                                        Text(
                                            text = "未标记支持思考，仍可尝试",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.dismissReasoningPicker()
                        onOpenSettings()
                    },
                ) {
                    Text(if (models.isEmpty()) "去配置" else "管理配置")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissReasoningPicker) { Text("取消") }
            },
        )
    }

    editTarget?.let { target ->
        var text by remember(target.id) { mutableStateOf(target.content) }
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text("编辑消息") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.editUserMessage(target.id, text)
                        editTarget = null
                    },
                ) { Text("保存并重新生成") }
            },
            dismissButton = {
                TextButton(onClick = { editTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun VoiceBar(
    phase: VoicePhase,
    partial: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.size(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (phase) {
                        VoicePhase.LISTENING -> "正在听…松开结束"
                        VoicePhase.RECORDING -> "正在录音…松开结束"
                        VoicePhase.TRANSCRIBING -> "正在识别…"
                        VoicePhase.IDLE -> ""
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                val detail = when {
                    partial.isNotBlank() -> partial
                    phase == VoicePhase.LISTENING -> "靠近麦克风说话，松开手指完成"
                    phase == VoicePhase.RECORDING -> "录音会上传到当前 API 配置的语音转写模型"
                    phase == VoicePhase.TRANSCRIBING -> "请稍候"
                    else -> ""
                }
                if (detail.isNotBlank()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun NoticeBar(
    text: String,
    isError: Boolean,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "关闭提示",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun NoProviderState(
    onAddProvider: () -> Unit,
    onOpenBackup: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_brand_mark),
            contentDescription = null,
            modifier = Modifier
                .size(72.dp)
                .padding(bottom = 12.dp),
        )
        Text(
            text = "嗨，我是 deepsleep",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "先添加一个 API 配置就能开始聊天。支持 DeepSeek 官方、OpenRouter、硅基流动、Ollama 或任意 OpenAI 兼容接口。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp, bottom = 18.dp),
        )
        Button(
            onClick = onAddProvider,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("添加 API 配置")
        }
        TextButton(onClick = onOpenBackup, modifier = Modifier.padding(top = 6.dp)) {
            Text("从 DeepSeek 备份恢复")
        }
    }
}

@Composable
private fun WelcomeState(onSuggestion: (String) -> Unit) {
    val suggestions = listOf(
        "帮我写一份一周的学习计划",
        "用简单的话解释什么是大语言模型",
        "给我讲一个短笑话",
        "把这段话翻译成英文并润色：",
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_brand_mark),
            contentDescription = null,
            modifier = Modifier
                .size(72.dp)
                .padding(bottom = 12.dp),
        )
        Text(
            text = "嗨，我是 deepsleep",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "有什么可以帮你的？",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 18.dp),
        )
        suggestions.forEach { suggestion ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onSuggestion(suggestion) },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = suggestion,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                )
            }
        }
    }
}