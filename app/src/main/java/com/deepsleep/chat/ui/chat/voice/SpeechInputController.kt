package com.deepsleep.chat.ui.chat.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Thin wrapper around the system SpeechRecognizer, driven as press-and-hold:
 * call [start] when the mic is pressed and [finish] when it is released.
 *
 * The vendor recognition service is not present on every Chinese ROM (vivo,
 * some Honor/OPPO builds do not expose one), so callers must also provide a
 * recording + cloud-transcription fallback. [isAvailable] tells them which
 * path to use.
 */
class SpeechInputController(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    /** (user message, true when the system recognizer itself is unusable) */
    private val onError: (String, Boolean) -> Unit,
) {
    private val recognizer: SpeechRecognizer? = runCatching {
        SpeechRecognizer.createSpeechRecognizer(context)
    }.getOrNull()

    private val handler = Handler(Looper.getMainLooper())
    private var lastPartial = ""
    private var finished = true
    private var listening = false

    fun isAvailable(): Boolean =
        recognizer != null && SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        val engine = recognizer
        if (engine == null || !SpeechRecognizer.isRecognitionAvailable(context)) {
            onError(NO_SERVICE_MESSAGE, true)
            return
        }
        if (listening) return
        handler.removeCallbacks(timeoutRunnable)
        lastPartial = ""
        finished = false
        listening = true
        engine.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        runCatching { engine.startListening(intent) }.onFailure { throwable ->
            listening = false
            finished = true
            onError("无法启动语音识别：${throwable.message ?: "未知错误"}", true)
        }
    }

    /** The finger was lifted: stop recording but keep waiting for the final result. */
    fun finish() {
        val engine = recognizer
        if (engine == null || finished) return
        // Some vendor engines never call onResults after stopListening(); do not
        // leave the UI stuck forever if that happens.
        handler.postDelayed(timeoutRunnable, RESULT_TIMEOUT_MS)
        runCatching { engine.stopListening() }
    }

    fun cancel() {
        if (finished) return
        finished = true
        listening = false
        lastPartial = ""
        handler.removeCallbacks(timeoutRunnable)
        runCatching { recognizer?.cancel() }
    }

    fun destroy() {
        finished = true
        listening = false
        handler.removeCallbacks(timeoutRunnable)
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
    }

    private val timeoutRunnable = Runnable {
        if (!finished) conclude(lastPartial, "没有听清，请再说一次", serviceUnavailable = true)
    }

    private fun conclude(text: String, fallbackError: String? = null, serviceUnavailable: Boolean = false) {
        if (finished) return
        finished = true
        listening = false
        handler.removeCallbacks(timeoutRunnable)
        lastPartial = ""
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) {
            onFinal(trimmed)
        } else {
            onError(fallbackError ?: "没有听清，请再说一次", serviceUnavailable)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotBlank()) {
                lastPartial = text
                onPartial(text)
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            conclude(text.ifBlank { lastPartial })
        }

        override fun onError(error: Int) {
            // Keep whatever the user already saw via partial results: several
            // OEM engines report NO_MATCH after having produced usable text.
            if (lastPartial.isNotBlank()) {
                conclude(lastPartial)
            } else {
                conclude("", errorMessage(error), serviceUnavailable(error))
            }
        }
    }

    /** Errors that mean "this phone's recognizer is not usable", not "user was unclear". */
    private fun serviceUnavailable(code: Int): Boolean =
        code == 1 || code == 2 || code == 5 || code == 9 || code == 11 || code == 12 || code == 13

    private fun errorMessage(code: Int): String = when (code) {
        1 -> "语音服务网络超时"
        2 -> "语音服务网络错误"
        3 -> "录音出错，请检查麦克风是否被占用"
        4 -> "语音服务出错"
        5 -> "语音识别客户端出错，请重试"
        6 -> "没有检测到语音，请靠近麦克风再说一次"
        7 -> "没有听清，请再说一次"
        8 -> "语音识别正忙，请稍后再试"
        9 -> "系统语音服务缺少麦克风权限。请到 设置→应用 里找到系统的语音引擎，允许其使用麦克风后再试"
        10 -> "语音服务返回错误"
        11 -> "无法连接系统语音服务"
        12 -> "系统语音服务不支持中文"
        13 -> "中文语音包不可用，请在系统设置中下载"
        else -> "语音识别失败（错误码 $code）"
    }

    companion object {
        const val NO_SERVICE_MESSAGE = "系统没有内置语音识别服务"
        private const val RESULT_TIMEOUT_MS = 8_000L
    }
}