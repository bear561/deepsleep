package com.deepsleep.chat.ui.chat.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.util.UUID

/**
 * Records a short mono AAC clip while the mic button is held. The clip is
 * uploaded to the user's OpenAI-compatible /audio/transcriptions endpoint,
 * which is the only cross-ROM alternative when no system speech service exists.
 */
class VoiceRecorder(
    private val context: Context,
) {
    private var recorder: MediaRecorder? = null
    private var output: File? = null

    fun start(): File? {
        cancel()
        val file = File(context.cacheDir, "voice-${UUID.randomUUID()}.m4a")
        val engine = createRecorder()
        return try {
            engine.setAudioSource(MediaRecorder.AudioSource.MIC)
            engine.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            engine.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            engine.setAudioChannels(1)
            // Some low-end encoders reject non-default rates; the defaults are
            // still perfectly fine for speech recognition.
            runCatching { engine.setAudioSamplingRate(16_000) }
            runCatching { engine.setAudioEncodingBitRate(32_000) }
            engine.setOutputFile(file.absolutePath)
            engine.prepare()
            engine.start()
            recorder = engine
            output = file
            file
        } catch (t: Throwable) {
            runCatching { engine.release() }
            file.delete()
            null
        }
    }

    fun stop(): File? {
        val engine = recorder ?: return null
        val file = output
        recorder = null
        output = null
        val stopped = runCatching { engine.stop() }.isSuccess
        runCatching { engine.release() }
        if (!stopped || file == null || !file.isFile || file.length() == 0L) {
            file?.delete()
            return null
        }
        return file
    }

    fun cancel() {
        val engine = recorder
        recorder = null
        output?.delete()
        output = null
        if (engine != null) {
            runCatching { engine.stop() }
            runCatching { engine.release() }
        }
    }

    @Suppress("DEPRECATION")
    private fun createRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
}