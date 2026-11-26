package com.deepsleep.chat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.deepsleep.chat.data.remote.openai.ChatFailure
import com.deepsleep.chat.data.remote.openai.ErrorMapper
import com.deepsleep.chat.di.AppContainer
import com.deepsleep.chat.domain.model.ModelConfig
import com.deepsleep.chat.domain.model.Provider
import com.deepsleep.chat.domain.model.ProviderPresets
import com.deepsleep.chat.util.ModelConfigs
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProviderEditState(
    val providerId: String,
    val isNew: Boolean,
    val name: String = "",
    val baseUrl: String = "",
    val apiKeyInput: String = "",
    val hasStoredKey: Boolean = false,
    val modelsText: String = "",
    val chatModelId: String = "",
    val reasoningModelId: String = "",
    val transcriptionModelId: String = "",
    val temperature: String = "",
    val maxTokens: String = "",
    val systemPrompt: String = "",
    val extraHeadersText: String = "",
    val isDefault: Boolean = false,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val testing: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
    val fetchedModels: List<ModelConfig> = emptyList(),
    val saved: Boolean = false,
)

class ProviderEditViewModel(
    private val container: AppContainer,
    private val providerId: String,
) : ViewModel() {

    private val providerRepository = container.providerRepository
    private val settingsRepository = container.settingsRepository
    private val chatRepository = container.chatRepository

    private val isNew = providerId == "new"
    private val _state = MutableStateFlow(
        ProviderEditState(
            providerId = if (isNew) UUID.randomUUID().toString() else providerId,
            isNew = isNew,
            loading = !isNew,
        ),
    )
    val state: StateFlow<ProviderEditState> = _state.asStateFlow()

    init {
        if (!isNew) {
            viewModelScope.launch {
                val provider = providerRepository.providers.first().firstOrNull { it.id == providerId }
                if (provider == null) {
                    _state.update {
                        it.copy(loading = false, message = "配置不存在或已被删除", messageIsError = true)
                    }
                } else {
                    _state.update {
                        it.copy(
                            loading = false,
                            name = provider.name,
                            baseUrl = provider.baseUrl,
                            hasStoredKey = provider.hasApiKey,
                            modelsText = ModelConfigs.format(provider.models),
                            chatModelId = provider.chatModelId,
                            reasoningModelId = provider.reasoningModelId.orEmpty(),
                            transcriptionModelId = provider.transcriptionModelId.orEmpty(),
                            temperature = provider.temperature?.toString().orEmpty(),
                            maxTokens = provider.maxTokens?.toString().orEmpty(),
                            systemPrompt = provider.systemPrompt,
                            extraHeadersText = ModelConfigs.formatHeaders(provider.extraHeaders),
                            isDefault = provider.isDefault,
                        )
                    }
                }
            }
        }
    }

    fun update(transform: (ProviderEditState) -> ProviderEditState) {
        _state.update(transform)
    }

    fun applyPreset(preset: ProviderPresets.Preset) {
        _state.update { current ->
            val models = preset.models
            current.copy(
                name = current.name.ifBlank { preset.label },
                baseUrl = preset.baseUrl,
                modelsText = ModelConfigs.format(models),
                chatModelId = models.firstOrNull { !it.supportsReasoning }?.id
                    ?: models.firstOrNull()?.id.orEmpty(),
                reasoningModelId = models.firstOrNull { it.supportsReasoning }?.id.orEmpty(),
                transcriptionModelId = preset.transcriptionModelId
                    .ifBlank { current.transcriptionModelId },
                message = null,
            )
        }
    }

    fun testConnection() {
        val current = _state.value
        if (current.testing) return
        val provider = buildProvider(current, requireModels = false) ?: return
        _state.update { it.copy(testing = true, message = null) }
        viewModelScope.launch {
            try {
                val key = resolveKey(current)
                val result = chatRepository.testConnection(provider, key)
                _state.update {
                    it.copy(
                        testing = false,
                        message = result.message,
                        messageIsError = !result.ok,
                        fetchedModels = result.models,
                    )
                }
            } catch (t: Throwable) {
                val failure = t as? ChatFailure ?: ErrorMapper.fromException(t)
                _state.update {
                    it.copy(testing = false, message = failure.userMessage, messageIsError = true)
                }
            }
        }
    }

    fun fetchModels() {
        val current = _state.value
        if (current.testing) return
        val provider = buildProvider(current, requireModels = false) ?: return
        _state.update { it.copy(testing = true, message = null) }
        viewModelScope.launch {
            try {
                val models = chatRepository.listModels(provider, resolveKey(current))
                if (models.isEmpty()) {
                    _state.update {
                        it.copy(testing = false, message = "服务没有返回模型", messageIsError = true)
                    }
                } else {
                    applyFetchedModels(models)
                    _state.update {
                        it.copy(
                            testing = false,
                            message = "已获取 ${models.size} 个模型，请确认后保存",
                            messageIsError = false,
                            fetchedModels = models,
                        )
                    }
                }
            } catch (t: Throwable) {
                val failure = t as? ChatFailure ?: ErrorMapper.fromException(t)
                _state.update {
                    it.copy(testing = false, message = failure.userMessage, messageIsError = true)
                }
            }
        }
    }

    fun useFetchedModels() {
        val models = _state.value.fetchedModels
        if (models.isEmpty()) return
        applyFetchedModels(models)
    }

    fun save() {
        val current = _state.value
        if (current.saving) return
        val provider = buildProvider(current, requireModels = true) ?: return
        _state.update { it.copy(saving = true, message = null) }
        viewModelScope.launch {
            try {
                val key = current.apiKeyInput.takeIf { it.isNotBlank() }
                providerRepository.upsert(provider, key)
                val allProviders = providerRepository.providers.first()
                settingsRepository.setDefaultProvider(
                    allProviders.firstOrNull { it.isDefault }?.id,
                )
                _state.update { it.copy(saving = false, saved = true) }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        saving = false,
                        message = t.message ?: "保存失败",
                        messageIsError = true,
                    )
                }
            }
        }
    }

    fun delete() {
        if (isNew) return
        viewModelScope.launch {
            try {
                providerRepository.delete(providerId)
                val settings = settingsRepository.settings.first()
                if (settings.defaultProviderId == providerId) {
                    settingsRepository.setDefaultProvider(null)
                }
                _state.update { it.copy(saved = true) }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(message = t.message ?: "删除失败", messageIsError = true)
                }
            }
        }
    }

    private fun applyFetchedModels(models: List<ModelConfig>) {
        _state.update { current ->
            val chat = current.chatModelId.takeIf { id ->
                models.any { it.id == id && !it.supportsReasoning }
            } ?: models.firstOrNull { !it.supportsReasoning }?.id
                ?: models.firstOrNull()?.id.orEmpty()
            val reasoning = current.reasoningModelId.takeIf { id ->
                models.any { it.id == id }
            } ?: models.firstOrNull { it.supportsReasoning }?.id.orEmpty()
            current.copy(
                modelsText = ModelConfigs.format(models),
                chatModelId = chat,
                reasoningModelId = reasoning,
            )
        }
    }

    private fun buildProvider(current: ProviderEditState, requireModels: Boolean): Provider? {
        val name = current.name.trim()
        if (name.isEmpty()) {
            failValidation("请填写配置名称")
            return null
        }
        val baseUrl = current.baseUrl.trim()
        if (baseUrl.isEmpty() || !(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))) {
            failValidation("Base URL 必须以 http:// 或 https:// 开头")
            return null
        }
        val models = ModelConfigs.parse(current.modelsText)
        if (requireModels && models.isEmpty()) {
            failValidation("请至少填写一个模型")
            return null
        }
        val chatModel = current.chatModelId.trim().ifBlank {
            models.firstOrNull { !it.supportsReasoning }?.id ?: models.firstOrNull()?.id.orEmpty()
        }
        if (requireModels && models.none { it.id == chatModel }) {
            failValidation("默认模型必须在模型列表中")
            return null
        }
        val reasoningModel = current.reasoningModelId.trim().ifBlank { null }
        if (requireModels && reasoningModel != null && models.none { it.id == reasoningModel }) {
            failValidation("推理模型必须在模型列表中")
            return null
        }
        val temperature = current.temperature.trim().takeIf { it.isNotEmpty() }?.let {
            it.toDoubleOrNull() ?: run {
                failValidation("Temperature 必须是数字")
                return null
            }
        }
        val maxTokens = current.maxTokens.trim().takeIf { it.isNotEmpty() }?.let {
            it.toIntOrNull() ?: run {
                failValidation("最大输出 Token 必须是整数")
                return null
            }
        }
        val headers = ModelConfigs.parseHeaders(current.extraHeadersText) ?: run {
            failValidation("请求头格式应为每行 Key: Value")
            return null
        }
        return Provider(
            id = current.providerId,
            name = name,
            baseUrl = baseUrl,
            models = models,
            chatModelId = chatModel,
            reasoningModelId = reasoningModel,
            transcriptionModelId = current.transcriptionModelId.trim().ifBlank { null },
            systemPrompt = current.systemPrompt.trim(),
            temperature = temperature,
            maxTokens = maxTokens,
            extraHeaders = headers,
            isDefault = current.isDefault,
            hasApiKey = current.hasStoredKey || current.apiKeyInput.isNotBlank(),
        )
    }

    private suspend fun resolveKey(current: ProviderEditState): String? {
        if (current.apiKeyInput.isNotBlank()) return current.apiKeyInput.trim()
        if (!isNew) return providerRepository.apiKey(current.providerId)
        return null
    }

    private fun failValidation(message: String) {
        _state.update { it.copy(message = message, messageIsError = true) }
    }

    companion object {
        fun factory(container: AppContainer, providerId: String): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { ProviderEditViewModel(container, providerId) }
            }
    }
}