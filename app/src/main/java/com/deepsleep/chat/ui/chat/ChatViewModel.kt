package com.deepsleep.chat.ui.chat

import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.deepsleep.chat.data.remote.openai.ChatFailure
import com.deepsleep.chat.data.remote.openai.ErrorMapper
import com.deepsleep.chat.data.remote.openai.StreamEvent
import com.deepsleep.chat.di.AppContainer
import com.deepsleep.chat.domain.model.Attachment
import com.deepsleep.chat.domain.model.ChatMessage
import com.deepsleep.chat.domain.model.Conversation
import com.deepsleep.chat.domain.model.MessageRole
import com.deepsleep.chat.domain.model.MessageStatus
import com.deepsleep.chat.domain.model.ModelConfig
import com.deepsleep.chat.domain.model.Provider
import com.deepsleep.chat.domain.model.SearchProviderType
import com.deepsleep.chat.domain.model.SearchSource
import com.deepsleep.chat.domain.model.TextLimits
import com.deepsleep.chat.ui.chat.voice.SpeechInputController
import com.deepsleep.chat.ui.chat.voice.VoicePhase
import com.deepsleep.chat.ui.chat.voice.VoiceRecorder
import com.deepsleep.chat.util.PromptBuilder
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatUiState(
    val conversationId: String? = null,
    val conversation: Conversation? = null,
    val messages: List<ChatMessage> = emptyList(),
    val conversations: List<Conversation> = emptyList(),
    val conversationQuery: String = "",
    val input: String = "",
    val attachments: List<Attachment> = emptyList(),
    val attachmentLoading: Boolean = false,
    val isStreaming: Boolean = false,
    val searching: Boolean = false,
    val thinkingEnabled: Boolean = false,
    val webSearchEnabled: Boolean = false,
    val providers: List<Provider> = emptyList(),
    val providerId: String? = null,
    val selectedModelId: String? = null,
    val contextLimit: Int = 32_000,
    val noProvider: Boolean = false,
    val isLoadingConversation: Boolean = false,
    val error: String? = null,
    val warning: String? = null,
    val searchConfigured: Boolean = false,
    val reasoningPickerVisible: Boolean = false,
    val voicePhase: VoicePhase = VoicePhase.IDLE,
    val voicePartial: String = "",
) {
    val activeProvider: Provider?
        get() = providers.firstOrNull { it.id == providerId }
            ?: providers.firstOrNull { it.isDefault }
            ?: providers.firstOrNull()

    val activeModel: ModelConfig?
        get() = activeProvider?.let { provider ->
            provider.models.firstOrNull { it.id == selectedModelId }
                ?: provider.models.firstOrNull { it.id == provider.chatModelId }
                ?: provider.models.firstOrNull()
        }

    val activeModelDisplay: String
        get() = activeModel?.displayName ?: selectedModelId ?: "选择模型"
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val container: AppContainer,
    private val initialConversationId: String?,
) : ViewModel() {

    private val providerRepository = container.providerRepository
    private val settingsRepository = container.settingsRepository
    private val conversationRepository = container.conversationRepository
    private val chatRepository = container.chatRepository
    private val searchRepository = container.searchRepository
    private val attachmentRepository = container.attachmentRepository

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val conversationQuery = MutableStateFlow("")
    private var sendJob: Job? = null
    private var transcribeJob: Job? = null
    private var recordingFile: File? = null
    private var voiceStartedAt = 0L

    /**
     * Some ROMs report a system recognizer but every attempt fails (or the
     * service is missing its own mic permission). Once that happens, prefer
     * recording + cloud transcription for the rest of the session.
     */
    private var preferRecording = false

    private var speechController: SpeechInputController? = null

    /**
     * Created on first use: every view-model on the back stack would otherwise
     * bind a SpeechRecognizer service even if voice is never touched.
     */
    private fun speech(): SpeechInputController = speechController ?: SpeechInputController(
        context = container.context,
        onPartial = { text ->
            _state.update { it.copy(voicePartial = text) }
        },
        onFinal = { text ->
            _state.update { it.copy(voicePhase = VoicePhase.IDLE, voicePartial = "") }
            appendVoiceText(text)
        },
        onError = { message, serviceUnavailable ->
            _state.update { it.copy(voicePhase = VoicePhase.IDLE, voicePartial = "") }
            if (serviceUnavailable && canTranscribe()) {
                preferRecording = true
                showWarning("$message。下次按住麦克风将改用录音转写。")
            } else {
                showError(message)
            }
        },
    ).also { speechController = it }

    private val voiceRecorder = VoiceRecorder(container.context)

    init {
        observeConfiguration()
        observeConversations()
        bootstrap()
    }

    fun setInput(text: String) {
        _state.update { it.copy(input = text) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun dismissWarning() {
        _state.update { it.copy(warning = null) }
    }

    fun showError(message: String) {
        _state.update { it.copy(error = message) }
    }

    fun showWarning(message: String) {
        _state.update { it.copy(warning = message) }
    }

    fun setConversationQuery(query: String) {
        conversationQuery.value = query
        _state.update { it.copy(conversationQuery = query) }
    }

    fun newChat() {
        if (sendJob?.isActive == true) stopGeneration()
        cancelVoiceInput()
        val pending = _state.value.attachments
        attachmentRepository.deleteImages(pending)
        _state.update { current ->
            current.copy(
                conversationId = null,
                conversation = null,
                messages = emptyList(),
                input = "",
                attachments = emptyList(),
                error = null,
                warning = null,
                isStreaming = false,
                searching = false,
                isLoadingConversation = false,
                voicePhase = VoicePhase.IDLE,
                voicePartial = "",
            )
        }
    }

    fun sendMessage() {
        val current = _state.value
        if (current.isStreaming || sendJob?.isActive == true) return
        val text = current.input.trim()
        if (text.isEmpty() && current.attachments.isEmpty()) return
        val provider = current.activeProvider
        if (provider == null) {
            _state.update { it.copy(error = "请先在设置中添加 API 配置") }
            return
        }
        val modelId = current.selectedModelId ?: provider.chatModelId
        if (modelId.isBlank()) {
            _state.update { it.copy(error = "当前配置没有可用模型，请先在设置中添加模型") }
            return
        }
        val attachments = current.attachments
        val displayText = text.ifBlank {
            attachments.firstOrNull()?.let { attachment ->
                if (attachment.isImage) "[图片] ${attachment.name}" else "[附件] ${attachment.name}"
            }.orEmpty()
        }
        _state.update {
            it.copy(input = "", attachments = emptyList(), error = null, warning = null)
        }
        startJob {
            var conversation = _state.value.conversation
            if (conversation == null) {
                conversation = conversationRepository.createConversation(
                    providerId = provider.id,
                    chatModelId = modelId,
                    reasoningModelId = provider.reasoningModelId,
                    thinkingEnabled = _state.value.thinkingEnabled,
                    webSearchEnabled = _state.value.webSearchEnabled,
                )
                val title = deriveTitle(displayText)
                conversationRepository.renameConversation(conversation.id, title)
                conversation = conversation.copy(title = title)
                _state.update { it.copy(conversationId = conversation.id, conversation = conversation) }
            } else {
                val updated = conversation.copy(
                    providerId = provider.id,
                    chatModelId = modelId,
                    reasoningModelId = provider.reasoningModelId,
                    thinkingEnabled = _state.value.thinkingEnabled,
                    webSearchEnabled = _state.value.webSearchEnabled,
                    updatedAt = System.currentTimeMillis(),
                )
                conversationRepository.saveConversation(updated)
                conversation = updated
                _state.update { it.copy(conversation = updated) }
            }

            val userMessage = conversationRepository.appendMessage(
                conversationId = conversation.id,
                role = MessageRole.USER,
                content = text,
                status = MessageStatus.COMPLETE,
                attachments = attachments,
            )
            conversationRepository.touchConversation(conversation.id, displayText)
            _state.update {
                it.copy(
                    messages = it.messages + userMessage,
                    conversation = it.conversation?.copy(
                        updatedAt = System.currentTimeMillis(),
                        lastPreview = displayText.take(120),
                    ),
                )
            }
            generateReply(
                conversation = conversation,
                provider = provider,
                modelId = modelId,
                thinkingEnabled = _state.value.thinkingEnabled,
                webSearchEnabled = _state.value.webSearchEnabled,
                lastUserText = text,
            )
        }
    }

    fun stopGeneration() {
        sendJob?.cancel()
        _state.update { it.copy(isStreaming = false, searching = false) }
    }

    fun regenerate() {
        val current = _state.value
        if (current.isStreaming || sendJob?.isActive == true) return
        val conversation = current.conversation ?: return
        val last = current.messages.lastOrNull() ?: return
        if (last.role != MessageRole.ASSISTANT) return
        val provider = current.activeProvider ?: return
        val modelId = current.selectedModelId ?: provider.chatModelId
        startJob {
            conversationRepository.deleteMessage(last.id)
            _state.update { it.copy(messages = it.messages.filterNot { message -> message.id == last.id }) }
            val lastUser = conversationRepository.getMessages(conversation.id)
                .lastOrNull { it.role == MessageRole.USER }?.content.orEmpty()
            generateReply(
                conversation = conversation,
                provider = provider,
                modelId = modelId,
                thinkingEnabled = _state.value.thinkingEnabled,
                webSearchEnabled = _state.value.webSearchEnabled,
                lastUserText = lastUser,
            )
        }
    }

    fun editUserMessage(messageId: String, newText: String) {
        val current = _state.value
        if (current.isStreaming || sendJob?.isActive == true) return
        if (newText.isBlank()) return
        val conversation = current.conversation ?: return
        val provider = current.activeProvider ?: return
        val modelId = current.selectedModelId ?: provider.chatModelId
        startJob {
            val message = conversationRepository.getMessages(conversation.id)
                .firstOrNull { it.id == messageId && it.role == MessageRole.USER }
                ?: return@startJob
            conversationRepository.updateMessage(message.copy(content = newText.trim()))
            val removed = conversationRepository.getMessages(conversation.id)
                .filter { it.seq > message.seq }
            conversationRepository.deleteMessagesAfter(conversation.id, message.seq)
            attachmentRepository.deleteImages(removed.flatMap { it.attachments })
            val messages = conversationRepository.getMessages(conversation.id)
            _state.update { it.copy(messages = messages, error = null) }
            generateReply(
                conversation = conversation,
                provider = provider,
                modelId = modelId,
                thinkingEnabled = _state.value.thinkingEnabled,
                webSearchEnabled = _state.value.webSearchEnabled,
                lastUserText = newText.trim(),
            )
        }
    }

    fun selectModel(provider: Provider, model: ModelConfig) {
        if (_state.value.isStreaming) return
        val thinking = model.supportsReasoning
        _state.update {
            it.copy(
                providerId = provider.id,
                selectedModelId = model.id,
                thinkingEnabled = thinking,
            )
        }
        persistConversationConfig(provider, model.id, thinking, _state.value.webSearchEnabled)
    }

    fun toggleThinking() {
        val current = _state.value
        if (current.isStreaming) return
        val provider = current.activeProvider ?: return

        // Turning thinking off must always work, even if the provider has no
        // configured reasoning model (e.g. the user picked a thinking model manually).
        if (current.thinkingEnabled) {
            val modelId = provider.chatModelId.ifBlank { current.selectedModelId.orEmpty() }
            _state.update {
                it.copy(
                    thinkingEnabled = false,
                    selectedModelId = modelId.ifBlank { it.selectedModelId },
                )
            }
            persistConversationConfig(provider, modelId, false, current.webSearchEnabled)
            return
        }

        val configuredReasoningModel = provider.reasoningModelId?.takeIf { it.isNotBlank() }
        if (configuredReasoningModel != null) {
            _state.update {
                it.copy(thinkingEnabled = true, selectedModelId = configuredReasoningModel)
            }
            persistConversationConfig(
                provider,
                configuredReasoningModel,
                true,
                current.webSearchEnabled,
            )
            return
        }

        // Models fetched with a "reasoning" flag can be promoted automatically.
        val flagged = provider.models.firstOrNull { it.supportsReasoning }
        if (flagged != null) {
            setReasoningModel(flagged.id)
            return
        }

        _state.update { it.copy(reasoningPickerVisible = true) }
    }

    fun setReasoningModel(modelId: String) {
        val current = _state.value
        val provider = current.activeProvider ?: return
        val model = provider.models.firstOrNull { it.id == modelId } ?: return
        val updatedProvider = provider.copy(
            reasoningModelId = modelId,
            models = provider.models.map {
                if (it.id == modelId) it.copy(supportsReasoning = true) else it
            },
        )
        _state.update {
            it.copy(
                providers = it.providers.map { existing ->
                    if (existing.id == updatedProvider.id) updatedProvider else existing
                },
                providerId = updatedProvider.id,
                thinkingEnabled = true,
                selectedModelId = modelId,
                reasoningPickerVisible = false,
                warning = null,
            )
        }
        viewModelScope.launch {
            runCatching { providerRepository.upsert(updatedProvider, null) }
        }
        persistConversationConfig(updatedProvider, modelId, true, current.webSearchEnabled)
    }

    fun dismissReasoningPicker() {
        _state.update { it.copy(reasoningPickerVisible = false) }
    }

    fun appendVoiceText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        _state.update { current ->
            val prefix = current.input.trim()
            current.copy(input = if (prefix.isEmpty()) trimmed else "$prefix $trimmed")
        }
    }

    fun hasSystemSpeechService(): Boolean = speech().isAvailable()

    /** Called when the mic is pressed. Recording stops in [stopVoiceInput]. */
    fun startVoiceInput() {
        val current = _state.value
        if (current.isStreaming || current.voicePhase != VoicePhase.IDLE) return
        if (current.noProvider) {
            showError("请先添加 API 配置")
            return
        }
        voiceStartedAt = SystemClock.elapsedRealtime()
        if (!preferRecording && speech().isAvailable()) {
            _state.update { it.copy(voicePhase = VoicePhase.LISTENING, voicePartial = "", error = null) }
            speech().start()
            return
        }

        val provider = current.activeProvider
        if (provider == null) {
            showError("请先添加 API 配置")
            return
        }
        if (provider.transcriptionModelId.isNullOrBlank()) {
            showError(VOICE_SETUP_HINT)
            return
        }
        val file = voiceRecorder.start()
        if (file == null) {
            showError("无法启动录音，请检查麦克风是否被其他应用占用")
            return
        }
        recordingFile = file
        _state.update { it.copy(voicePhase = VoicePhase.RECORDING, voicePartial = "", error = null) }
    }

    /** Called when the finger is lifted. */
    fun stopVoiceInput() {
        val heldMs = SystemClock.elapsedRealtime() - voiceStartedAt
        when (_state.value.voicePhase) {
            VoicePhase.LISTENING -> {
                if (heldMs < MIN_HOLD_MS) {
                    speechController?.cancel()
                    resetVoiceState()
                    showWarning("按住麦克风说话，松开结束")
                } else {
                    _state.update { it.copy(voicePhase = VoicePhase.TRANSCRIBING) }
                    speechController?.finish()
                }
            }
            VoicePhase.RECORDING -> {
                val file = voiceRecorder.stop()
                recordingFile = null
                _state.update { it.copy(voicePhase = VoicePhase.TRANSCRIBING, voicePartial = "") }
                when {
                    file == null -> {
                        resetVoiceState()
                        showError("没有录到声音，请按住麦克风再说一次")
                    }
                    heldMs < MIN_HOLD_MS || file.length() < MIN_RECORDING_BYTES -> {
                        file.delete()
                        resetVoiceState()
                        showWarning("按住麦克风说话，松开结束")
                    }
                    else -> transcribeVoice(file)
                }
            }
            else -> Unit
        }
    }

    fun cancelVoiceInput() {
        speechController?.cancel()
        voiceRecorder.cancel()
        recordingFile?.delete()
        recordingFile = null
        transcribeJob?.cancel()
        transcribeJob = null
        resetVoiceState()
    }

    private fun resetVoiceState() {
        _state.update { it.copy(voicePhase = VoicePhase.IDLE, voicePartial = "") }
    }

    private fun transcribeVoice(file: File) {
        val provider = _state.value.activeProvider
        val modelId = provider?.transcriptionModelId?.trim().orEmpty()
        if (provider == null || modelId.isEmpty()) {
            file.delete()
            resetVoiceState()
            showError(VOICE_SETUP_HINT)
            return
        }
        transcribeJob?.cancel()
        transcribeJob = viewModelScope.launch {
            try {
                val key = providerRepository.apiKey(provider.id)
                val text = chatRepository.transcribe(provider, key, modelId, file)
                appendVoiceText(text)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val message = (t as? ChatFailure)?.userMessage
                    ?: t.message
                    ?: "语音转写失败，请稍后重试"
                showError(message)
            } finally {
                withContext(NonCancellable) {
                    file.delete()
                    resetVoiceState()
                }
            }
        }
    }

    private fun canTranscribe(): Boolean =
        _state.value.activeProvider?.transcriptionModelId?.isNotBlank() == true

    fun toggleWebSearch() {
        val current = _state.value
        val enabled = !current.webSearchEnabled
        _state.update { it.copy(webSearchEnabled = enabled) }
        current.activeProvider?.let { provider ->
            persistConversationConfig(
                provider = provider,
                modelId = current.selectedModelId ?: provider.chatModelId,
                thinking = current.thinkingEnabled,
                webSearch = enabled,
            )
        }
    }

    fun addAttachment(uri: Uri) {
        if (_state.value.attachmentLoading || _state.value.isStreaming) return
        _state.update { it.copy(attachmentLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val attachment = attachmentRepository.importText(uri)
                val existingChars = _state.value.attachments.sumOf { it.text.length }
                if (existingChars + attachment.text.length > TextLimits.MAX_CHARS_TOTAL_ATTACHMENTS) {
                    _state.update { it.copy(error = "附件总长度超过 4 万字，请减少文件") }
                } else {
                    _state.update { it.copy(attachments = it.attachments + attachment) }
                }
            } catch (t: Throwable) {
                _state.update { it.copy(error = t.message ?: "读取附件失败") }
            } finally {
                _state.update { it.copy(attachmentLoading = false) }
            }
        }
    }

    fun addImageAttachments(uris: List<Uri>) {
        if (uris.isEmpty() || _state.value.attachmentLoading || _state.value.isStreaming) return
        val existingImages = _state.value.attachments.count { it.isImage }
        val room = TextLimits.MAX_IMAGES_PER_MESSAGE - existingImages
        if (room <= 0) {
            showError("每条消息最多添加 ${TextLimits.MAX_IMAGES_PER_MESSAGE} 张图片")
            return
        }
        val selected = uris.take(room)
        _state.update { it.copy(attachmentLoading = true, error = null) }
        viewModelScope.launch {
            val imported = mutableListOf<Attachment>()
            var lastError: String? = null
            try {
                selected.forEach { uri ->
                    runCatching { attachmentRepository.importImage(uri) }
                        .onSuccess { imported += it }
                        .onFailure { throwable -> lastError = throwable.message ?: "读取图片失败" }
                }
            } finally {
                val failure = lastError
                _state.update { state ->
                    val providerUrl = state.activeProvider?.baseUrl.orEmpty()
                    val unsupportedVision = imported.isNotEmpty() &&
                        providerUrl.contains("api.deepseek.com", ignoreCase = true)
                    state.copy(
                        attachments = state.attachments + imported,
                        attachmentLoading = false,
                        error = when {
                            uris.size > room ->
                                "每条消息最多 ${TextLimits.MAX_IMAGES_PER_MESSAGE} 张图片，已添加前 $room 张"
                            failure != null && imported.isEmpty() -> failure
                            failure != null -> "部分图片未能添加：$failure"
                            else -> state.error
                        },
                        warning = if (unsupportedVision) {
                            "DeepSeek 官方 API 不支持图片输入，请切换到支持视觉的 OpenAI 兼容模型后再发送。"
                        } else {
                            state.warning
                        },
                    )
                }
            }
        }
    }

    fun removeAttachment(id: String) {
        val attachment = _state.value.attachments.firstOrNull { it.id == id } ?: return
        if (attachment.isImage) attachmentRepository.deleteImages(listOf(attachment))
        _state.update { it.copy(attachments = it.attachments.filterNot { existing -> existing.id == id }) }
    }

    fun renameConversation(id: String, title: String) {
        viewModelScope.launch {
            conversationRepository.renameConversation(id, title)
            if (_state.value.conversationId == id) {
                _state.update {
                    it.copy(conversation = it.conversation?.copy(title = title.trim().ifBlank { "未命名对话" }))
                }
            }
        }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            val removed = conversationRepository.getMessages(id)
            conversationRepository.deleteConversation(id)
            attachmentRepository.deleteImages(removed.flatMap { it.attachments })
            if (_state.value.conversationId == id) {
                _state.update {
                    it.copy(
                        conversationId = null,
                        conversation = null,
                        messages = emptyList(),
                        error = null,
                        warning = null,
                    )
                }
            }
        }
    }

    fun setConversationPinned(id: String, pinned: Boolean) {
        viewModelScope.launch {
            conversationRepository.setPinned(id, pinned)
        }
    }

    override fun onCleared() {
        sendJob?.cancel()
        transcribeJob?.cancel()
        speechController?.destroy()
        voiceRecorder.cancel()
        attachmentRepository.deleteImages(_state.value.attachments)
        super.onCleared()
    }

    private fun observeConfiguration() {
        viewModelScope.launch {
            combine(
                providerRepository.providers,
                settingsRepository.settings,
            ) { providers, settings -> providers to settings }
                .collect { (providers, settings) ->
                    _state.update { current ->
                        val providerId = current.providerId?.takeIf { id -> providers.any { it.id == id } }
                            ?: providers.firstOrNull { it.id == settings.defaultProviderId }?.id
                            ?: providers.firstOrNull { it.isDefault }?.id
                            ?: providers.firstOrNull()?.id
                        val provider = providers.firstOrNull { it.id == providerId }
                        val selected = current.selectedModelId
                            ?.takeIf { id -> provider?.models?.any { it.id == id } == true }
                            ?: if (current.thinkingEnabled) {
                                provider?.reasoningModelId ?: provider?.chatModelId
                            } else {
                                provider?.chatModelId
                            } ?: provider?.models?.firstOrNull()?.id
                        val selectedModelSupportsReasoning = provider?.models?.any {
                            it.id == current.selectedModelId && it.supportsReasoning
                        } == true
                        val thinking = current.thinkingEnabled &&
                            (provider?.supportsReasoning == true || selectedModelSupportsReasoning)
                        val searchConfigured = settings.searchHasKey ||
                            (settings.search.type == SearchProviderType.SEARXNG &&
                                settings.search.baseUrl.isNotBlank())
                        current.copy(
                            providers = providers,
                            providerId = providerId,
                            selectedModelId = selected,
                            thinkingEnabled = thinking,
                            noProvider = providers.isEmpty(),
                            contextLimit = settings.contextTokenLimit,
                            searchConfigured = searchConfigured,
                        )
                    }
                }
        }
    }

    private fun observeConversations() {
        viewModelScope.launch {
            conversationQuery
                .flatMapLatest { query -> conversationRepository.observeConversations(query) }
                .collect { conversations ->
                    _state.update { it.copy(conversations = conversations) }
                }
        }
    }

    private fun bootstrap() {
        viewModelScope.launch {
            conversationRepository.markStaleStreamingStopped()
            val id = initialConversationId?.takeUnless { it.isBlank() || it == "new" } ?: return@launch
            _state.update { it.copy(conversationId = id, isLoadingConversation = true) }
            val snapshot = conversationRepository.loadConversation(id)
            if (snapshot == null) {
                _state.update { it.copy(isLoadingConversation = false, error = "对话不存在或已被删除") }
                return@launch
            }
            val conversation = snapshot.conversation
            val thinking = conversation.thinkingEnabled
            val modelId = if (thinking) {
                conversation.reasoningModelId ?: conversation.chatModelId
            } else {
                conversation.chatModelId
            }
            _state.update { current ->
                current.copy(
                    conversationId = conversation.id,
                    conversation = conversation,
                    messages = snapshot.messages,
                    providerId = conversation.providerId ?: current.providerId,
                    selectedModelId = modelId ?: current.selectedModelId,
                    thinkingEnabled = thinking,
                    webSearchEnabled = conversation.webSearchEnabled,
                    isLoadingConversation = false,
                )
            }
        }
    }

    private fun startJob(block: suspend () -> Unit) {
        if (sendJob?.isActive == true) return
        _state.update { it.copy(isStreaming = true, searching = false, error = null) }
        sendJob = viewModelScope.launch {
            try {
                block()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val message = (t as? ChatFailure)?.userMessage
                    ?: t.message
                    ?: "发送失败，请稍后重试"
                _state.update { it.copy(error = message) }
            } finally {
                _state.update { it.copy(isStreaming = false, searching = false) }
                sendJob = null
            }
        }
    }

    private suspend fun generateReply(
        conversation: Conversation,
        provider: Provider,
        modelId: String,
        thinkingEnabled: Boolean,
        webSearchEnabled: Boolean,
        lastUserText: String,
    ) {
        var sources: List<SearchSource> = emptyList()
        // An image-only message has no text to search for.
        if (webSearchEnabled && lastUserText.isNotBlank()) {
            _state.update { it.copy(searching = true) }
            try {
                val settings = settingsRepository.settings.first()
                val key = settingsRepository.searchApiKey()
                val result = searchRepository.search(
                    config = settings.search,
                    apiKey = key,
                    query = lastUserText,
                )
                sources = result.getOrElse { throwable ->
                    _state.update {
                        it.copy(warning = "联网搜索失败，已直接回答：${throwable.message ?: "未知错误"}")
                    }
                    emptyList()
                }
            } finally {
                _state.update { it.copy(searching = false) }
            }
        }

        val settings = settingsRepository.settings.first()
        val history = conversationRepository.getMessages(conversation.id)
        val built = PromptBuilder.build(
            provider = provider,
            conversation = conversation,
            history = history,
            sources = sources,
            contextLimit = settings.contextTokenLimit,
            imageDir = attachmentRepository.imageDir,
        )
        if (built.trimmedHistory) {
            _state.update { it.copy(warning = "历史对话过长，已自动裁剪较早内容") }
        }

        val placeholder = conversationRepository.appendMessage(
            conversationId = conversation.id,
            role = MessageRole.ASSISTANT,
            content = "",
            status = MessageStatus.STREAMING,
            thinkingEnabled = thinkingEnabled,
            modelId = modelId,
            providerId = provider.id,
        ).copy(sources = sources)
        conversationRepository.updateMessage(placeholder)
        _state.update { it.copy(messages = it.messages + placeholder) }

        streamInto(
            initial = placeholder,
            provider = provider,
            modelId = modelId,
            promptMessages = built.messages,
            conversation = conversation,
            lastUserText = lastUserText,
            thinkingEnabled = thinkingEnabled,
        )
    }

    private suspend fun streamInto(
        initial: ChatMessage,
        provider: Provider,
        modelId: String,
        promptMessages: List<com.deepsleep.chat.domain.model.PromptMessage>,
        conversation: Conversation,
        lastUserText: String,
        thinkingEnabled: Boolean,
    ) {
        val apiKey = providerRepository.apiKey(provider.id)
        if (provider.hasApiKey && apiKey == null) {
            val message = "API Key 无法解密，请到设置中重新填写"
            val errored = initial.copy(status = MessageStatus.ERROR, error = message)
            conversationRepository.updateMessage(errored)
            replaceMessage(errored)
            _state.update { it.copy(error = message) }
            return
        }
        val content = StringBuilder()
        val reasoning = StringBuilder()
        var live = initial
        var lastUiFlush = 0L
        var lastDbWrite = System.currentTimeMillis()
        var reasoningStartedAt = 0L
        var reasoningMs: Long? = null
        var failure: ChatFailure? = null
        var finishReason: String? = null

        suspend fun persist(force: Boolean, status: MessageStatus = MessageStatus.STREAMING, error: String? = null) {
            val now = System.currentTimeMillis()
            if (!force && now - lastDbWrite < 800) return
            val snapshot = live.copy(
                content = content.toString(),
                reasoning = reasoning.toString(),
                status = status,
                error = error,
                reasoningMs = reasoningMs ?: live.reasoningMs,
            )
            conversationRepository.updateMessage(snapshot)
            live = snapshot
            lastDbWrite = now
        }

        fun publish(force: Boolean) {
            val now = System.currentTimeMillis()
            if (!force && now - lastUiFlush < 120) return
            lastUiFlush = now
            val snapshot = live.copy(
                content = content.toString(),
                reasoning = reasoning.toString(),
                reasoningMs = reasoningMs ?: live.reasoningMs,
            )
            live = snapshot
            replaceMessage(snapshot)
        }

        try {
            chatRepository.streamReply(provider, apiKey, modelId, promptMessages).collect { event ->
                when (event) {
                    is StreamEvent.ReasoningDelta -> {
                        // A provider may return reasoning_content even when the
                        // user did not enable deep thinking. Honor the toggle:
                        // ignore and hide it in that case.
                        if (thinkingEnabled) {
                            if (reasoningStartedAt == 0L) {
                                reasoningStartedAt = System.currentTimeMillis()
                            }
                            reasoning.append(event.text)
                            publish(false)
                        }
                    }
                    is StreamEvent.ContentDelta -> {
                        if (reasoningStartedAt > 0L && reasoningMs == null) {
                            reasoningMs = System.currentTimeMillis() - reasoningStartedAt
                        }
                        content.append(event.text)
                        publish(false)
                    }
                    is StreamEvent.UsageUpdated -> {
                        live = live.copy(usage = event.usage)
                        publish(true)
                    }
                    is StreamEvent.Finished -> {
                        finishReason = event.finishReason
                    }
                    is StreamEvent.Failed -> {
                        failure = event.error
                    }
                }
                persist(false)
            }

            failure?.let { throw it }
            if (reasoningStartedAt > 0L && reasoningMs == null) {
                reasoningMs = System.currentTimeMillis() - reasoningStartedAt
            }
            val empty = content.isBlank() && reasoning.isBlank()
            val finalStatus = if (empty) MessageStatus.ERROR else MessageStatus.COMPLETE
            val errorText = if (empty) "模型没有返回内容" else null
            val finalMessage = live.copy(
                content = content.toString(),
                reasoning = reasoning.toString(),
                status = finalStatus,
                reasoningMs = reasoningMs ?: live.reasoningMs,
                error = errorText,
            )
            conversationRepository.updateMessage(finalMessage)
            replaceMessage(finalMessage)
            live = finalMessage
            if (finalStatus == MessageStatus.COMPLETE) {
                conversationRepository.touchConversation(conversation.id, finalMessage.content.take(120))
                _state.update { current ->
                    current.copy(
                        conversation = current.conversation?.copy(
                            updatedAt = System.currentTimeMillis(),
                            lastPreview = finalMessage.content.take(120),
                        ),
                    )
                }
                if (finishReason == "length") {
                    _state.update { it.copy(warning = "回答达到长度上限，可发送“继续”让模型接着写") }
                }
            } else {
                _state.update { it.copy(error = errorText) }
            }
        } catch (ce: CancellationException) {
            withContext(NonCancellable) {
                if (reasoningStartedAt > 0L && reasoningMs == null) {
                    reasoningMs = System.currentTimeMillis() - reasoningStartedAt
                }
                val stopped = live.copy(
                    content = content.toString(),
                    reasoning = reasoning.toString(),
                    status = MessageStatus.STOPPED,
                    reasoningMs = reasoningMs ?: live.reasoningMs,
                )
                conversationRepository.updateMessage(stopped)
                replaceMessage(stopped)
            }
            throw ce
        } catch (t: Throwable) {
            val chatFailure = t as? ChatFailure ?: ErrorMapper.fromException(t)
            withContext(NonCancellable) {
                if (reasoningStartedAt > 0L && reasoningMs == null) {
                    reasoningMs = System.currentTimeMillis() - reasoningStartedAt
                }
                val errored = live.copy(
                    content = content.toString(),
                    reasoning = reasoning.toString(),
                    status = MessageStatus.ERROR,
                    error = chatFailure.userMessage,
                    reasoningMs = reasoningMs ?: live.reasoningMs,
                )
                conversationRepository.updateMessage(errored)
                replaceMessage(errored)
                _state.update { it.copy(error = chatFailure.userMessage) }
            }
        }
    }

    private fun replaceMessage(message: ChatMessage) {
        _state.update { current ->
            current.copy(
                messages = current.messages.map { existing ->
                    if (existing.id == message.id) message else existing
                },
            )
        }
    }

    private fun persistConversationConfig(
        provider: Provider,
        modelId: String,
        thinking: Boolean,
        webSearch: Boolean,
    ) {
        val conversation = _state.value.conversation ?: return
        val updated = conversation.copy(
            providerId = provider.id,
            chatModelId = modelId,
            reasoningModelId = provider.reasoningModelId,
            thinkingEnabled = thinking,
            webSearchEnabled = webSearch,
            updatedAt = System.currentTimeMillis(),
        )
        _state.update { it.copy(conversation = updated) }
        viewModelScope.launch { conversationRepository.saveConversation(updated) }
    }

    private fun deriveTitle(text: String): String {
        val firstLine = text.trim().lineSequence().firstOrNull().orEmpty().trim()
        val title = firstLine.ifBlank { "未命名对话" }
        return title.take(24)
    }

    companion object {
        private const val MIN_HOLD_MS = 350L
        private const val MIN_RECORDING_BYTES = 2_000L
        private const val VOICE_SETUP_HINT =
            "系统没有内置语音识别服务（vivo 等机型常见）。请到「设置 → API 配置 → 高级选项」填写「语音转写模型」，" +
                "例如 whisper-1（OpenAI）或 FunAudioLLM/SenseVoiceSmall（硅基流动），再按住麦克风重试。"

        fun factory(container: AppContainer, conversationId: String?): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { ChatViewModel(container, conversationId) }
            }
    }
}