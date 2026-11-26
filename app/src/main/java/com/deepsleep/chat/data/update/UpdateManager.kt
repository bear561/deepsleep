package com.deepsleep.chat.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.deepsleep.chat.BuildConfig
import com.deepsleep.chat.data.repository.SettingsRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float?) : UpdateState
    data class ReadyToInstall(val info: UpdateInfo, val file: File) : UpdateState
    data class Error(val message: String) : UpdateState
}

/**
 * App-scoped coordinator for update checks, downloads and the install intent.
 * The dialog UI lives in [com.deepsleep.chat.ui.update.UpdateHost].
 */
class UpdateManager(
    private val context: Context,
    private val updateRepository: UpdateRepository,
    private val settingsRepository: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var job: Job? = null

    fun autoCheckIfDue() {
        if (job?.isActive == true) return
        job = scope.launch {
            val settings = settingsRepository.settings.first()
            if (!settings.autoCheckUpdates) return@launch
            val now = System.currentTimeMillis()
            if (settings.lastUpdateCheckAt > 0L &&
                now - settings.lastUpdateCheckAt < AUTO_CHECK_INTERVAL_MS
            ) {
                return@launch
            }
            runCheck(settings.updateRepo, settings.updateManifestUrl, manual = false)
        }
    }

    fun check(manual: Boolean = true) {
        if (job?.isActive == true) return
        job = scope.launch {
            val settings = settingsRepository.settings.first()
            runCheck(settings.updateRepo, settings.updateManifestUrl, manual)
        }
    }

    private suspend fun runCheck(repo: String, manifestUrl: String, manual: Boolean) {
        if (manual) _state.value = UpdateState.Checking
        try {
            val info = updateRepository.check(
                repo = repo,
                manifestUrl = manifestUrl,
                currentVersion = BuildConfig.VERSION_NAME,
                currentVersionCode = BuildConfig.VERSION_CODE,
            )
            settingsRepository.setLastUpdateCheckAt(System.currentTimeMillis())
            _state.value = when {
                info != null -> UpdateState.Available(info)
                manual -> UpdateState.UpToDate
                else -> UpdateState.Idle
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            if (manual) {
                _state.value = UpdateState.Error(t.message ?: "检查更新失败，请稍后重试")
            } else {
                _state.value = UpdateState.Idle
            }
        }
    }

    fun downloadAndPrepare() {
        val available = _state.value as? UpdateState.Available ?: return
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = UpdateState.Downloading(available.info, 0f)
            try {
                val file = updateRepository.download(available.info) { progress ->
                    _state.value = UpdateState.Downloading(available.info, progress)
                }
                _state.value = UpdateState.ReadyToInstall(available.info, file)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _state.value = UpdateState.Error(t.message ?: "下载更新失败，请稍后重试")
            }
        }
    }

    fun cancelDownload() {
        job?.cancel()
        job = null
        _state.value = UpdateState.Idle
    }

    fun dismiss() {
        if (_state.value is UpdateState.Downloading) return
        job?.cancel()
        job = null
        _state.value = UpdateState.Idle
    }

    fun installPending() {
        val ready = _state.value as? UpdateState.ReadyToInstall ?: return
        install(ready.file)
    }

    fun install(file: File) {
        if (!file.isFile) {
            _state.value = UpdateState.Error("安装包文件不存在，请重新下载")
            return
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            openInstallPermissionSettings()
            return
        }
        val uri = runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
        }.getOrElse {
            _state.value = UpdateState.Error("无法读取已下载的安装包：${it.message ?: "未知错误"}")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onFailure { throwable ->
            _state.value = UpdateState.Error(
                "无法启动系统安装器：${throwable.message ?: "未知错误"}",
            )
        }
    }

    fun installPermissionGranted(): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            _state.value = UpdateState.Error(
                "请到系统设置中允许本应用安装应用后重试",
            )
        }
    }

    private companion object {
        const val AUTO_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
    }
}