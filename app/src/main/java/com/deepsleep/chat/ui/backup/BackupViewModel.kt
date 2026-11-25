package com.deepsleep.chat.ui.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.deepsleep.chat.data.backup.BackupExportResult
import com.deepsleep.chat.data.backup.BackupImportResult
import com.deepsleep.chat.data.backup.BackupInspection
import com.deepsleep.chat.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class BackupImportKind { DEEPSEEK, OWN }

data class BackupUiState(
    val working: Boolean = false,
    val status: String = "",
    val error: String? = null,
    val inspection: BackupInspection? = null,
    val pendingUri: Uri? = null,
    val pendingKind: BackupImportKind? = null,
    val skipDuplicates: Boolean = true,
    val result: String? = null,
)

class BackupViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val repository = container.backupRepository
    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    fun inspectDeepSeek(uri: Uri) {
        if (_state.value.working) return
        _state.update {
            it.copy(
                working = true,
                status = "正在检查 DeepSeek 备份…",
                error = null,
                result = null,
            )
        }
        viewModelScope.launch {
            try {
                val inspection = repository.inspectDeepSeek(uri)
                _state.update {
                    it.copy(
                        working = false,
                        status = "",
                        inspection = inspection,
                        pendingUri = uri,
                        pendingKind = BackupImportKind.DEEPSEEK,
                        skipDuplicates = true,
                    )
                }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    fun inspectOwn(uri: Uri) {
        if (_state.value.working) return
        _state.update {
            it.copy(
                working = true,
                status = "正在检查备份…",
                error = null,
                result = null,
            )
        }
        viewModelScope.launch {
            try {
                val inspection = repository.inspectOwn(uri)
                _state.update {
                    it.copy(
                        working = false,
                        status = "",
                        inspection = inspection,
                        pendingUri = uri,
                        pendingKind = BackupImportKind.OWN,
                        skipDuplicates = true,
                    )
                }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    fun setSkipDuplicates(skip: Boolean) {
        _state.update { it.copy(skipDuplicates = skip) }
    }

    fun confirmImport() {
        val current = _state.value
        val uri = current.pendingUri ?: return
        val kind = current.pendingKind ?: return
        if (current.working) return
        _state.update { it.copy(working = true, status = "准备导入…", error = null) }
        viewModelScope.launch {
            try {
                val result = when (kind) {
                    BackupImportKind.DEEPSEEK -> repository.importDeepSeek(
                        uri = uri,
                        skipDuplicates = current.skipDuplicates,
                        onProgress = { done, total ->
                            _state.update { it.copy(status = "正在导入 $done / $total…") }
                        },
                    )
                    BackupImportKind.OWN -> repository.importOwn(
                        uri = uri,
                        skipDuplicates = current.skipDuplicates,
                        onProgress = { done, total ->
                            _state.update { it.copy(status = "正在导入 $done / $total…") }
                        },
                    )
                }
                _state.update {
                    it.copy(
                        working = false,
                        status = "",
                        inspection = null,
                        pendingUri = null,
                        pendingKind = null,
                        result = formatImportResult(result),
                    )
                }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    fun exportDeepSeek(uri: Uri) {
        if (_state.value.working) return
        _state.update {
            it.copy(working = true, status = "正在导出 DeepSeek 格式…", error = null, result = null)
        }
        viewModelScope.launch {
            try {
                val result = repository.exportDeepSeek(uri) { done, total ->
                    _state.update { it.copy(status = "正在导出 $done / $total…") }
                }
                _state.update {
                    it.copy(
                        working = false,
                        status = "",
                        result = formatExportResult(result),
                    )
                }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    fun exportOwn(uri: Uri) {
        if (_state.value.working) return
        _state.update {
            it.copy(working = true, status = "正在导出本 App 备份…", error = null, result = null)
        }
        viewModelScope.launch {
            try {
                val result = repository.exportOwn(uri)
                _state.update {
                    it.copy(working = false, status = "", result = formatExportResult(result))
                }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    fun dismissInspection() {
        if (_state.value.working) return
        _state.update { it.copy(inspection = null, pendingUri = null, pendingKind = null) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun clearResult() {
        _state.update { it.copy(result = null) }
    }

    private fun fail(t: Throwable) {
        _state.update {
            it.copy(
                working = false,
                status = "",
                inspection = null,
                pendingUri = null,
                pendingKind = null,
                error = t.message ?: "操作失败",
            )
        }
    }

    private fun formatImportResult(result: BackupImportResult): String = buildString {
        append("导入完成：${result.conversations} 个对话，${result.messages} 条消息")
        if (result.providers > 0) append("，${result.providers} 套 API 配置")
        append("。")
        if (result.skipped > 0) append("\n跳过 ${result.skipped} 个已存在或无效的对话。")
        result.warnings.take(8).forEach { append("\n• ").append(it) }
        if (result.warnings.size > 8) append("\n• …")
    }

    private fun formatExportResult(result: BackupExportResult): String = buildString {
        append("导出完成：${result.conversations} 个对话，${result.messages} 条消息。")
        if (result.skipped > 0) append("\n跳过 ${result.skipped} 个空对话。")
        result.warnings.take(8).forEach { append("\n• ").append(it) }
        if (result.warnings.size > 8) append("\n• …")
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { BackupViewModel(container) }
        }
    }
}