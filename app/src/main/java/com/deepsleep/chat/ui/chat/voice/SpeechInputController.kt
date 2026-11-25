package com.deepsleep.chat.ui.chat.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Thin wrapper around the system SpeechRecognizer. Using the recognizer
 * service directly (instead of only launching the vendor's ASR activity)
 * works better on MIUI/HyperOS where the speech activity and the service have
 * different availability and permission behavior.
 */
class SpeechInputController(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onListeningChanged: (Boolean) -> Unit,
) {
    private val recognizer: SpeechRecognizer? = runCatching {
        SpeechRecognizer.createSpeechRecognizer(context)
    }.getOrNull()

    fun isAvailable(): Boolean =
        recognizer != null && SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        val engine = recognizer
        if (engine == null || !SpeechRecognizer.isRecognitionAvailable(context)) {
            onListeningChanged(false)
            onError("系统没有可用的语音识别服务，可使用输入法的语音键")
            return
        }
        engine.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        onListeningChanged(true)
        runCatching { engine.startListening(intent) }.onFailure { throwable ->
            onListeningChanged(false)
            onError("无法启动语音识别：${throwable.message ?: "未知错误"}")
        }
    }

    fun stop() {
        runCatching { recognizer?.stopListening() }
        onListeningChanged(false)
    }

    fun cancel() {
        runCatching { recognizer?.cancel() }
        onListeningChanged(false)
    }

    fun destroy() {
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
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
            if (text.isNotBlank()) onPartial(text)
        }

        override fun onResults(results: Bundle?) {
            onListeningChanged(false)
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotBlank()) {
                onFinal(text)
            } else {
                onError("没有听清，请再说一次")
            }
        }

        override fun onError(error: Int) {
            onListeningChanged(false)
            onError(errorMessage(error))
        }
    }

    private fun errorMessage(code: Int): String = when (code) {
        1 -> "语音服务网络超时"
        2 -> "语音服务网络错误"
        3 -> "录音出错，请检查麦克风是否被占用"
        4 -> "语音服务出错"
        5 -> "语音识别客户端出错，请重试"
        6 -> "没有检测到语音，请靠近麦克风再说一次"
        7 -> "没有听清，请再说一次"
        8 -> "语音识别正忙，请稍后再试"
        9 -> "系统语音服务缺少麦克风权限。请到 设置→应用 里找到“小米语音引擎/系统语音服务”，允许其使用麦克风后再试"
        10 -> "语音服务返回错误"
        11 -> "无法连接系统语音服务"
        12 -> "系统语音服务不支持中文"
        13 -> "中文语音包不可用，请在系统设置中下载"
        else -> "语音识别失败（错误码 $code）"
    }
}