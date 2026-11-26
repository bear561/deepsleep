package com.deepsleep.chat.ui.chat.voice

enum class VoicePhase {
    IDLE,

    /** Holding the mic and the system recognizer is listening. */
    LISTENING,

    /** Holding the mic and recording locally (no system speech service). */
    RECORDING,

    /** Finger lifted; waiting for the recognizer or the transcription API. */
    TRANSCRIBING,
}