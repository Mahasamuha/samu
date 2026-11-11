package com.mahasamuha.assistant.voice

import android.content.Context
import androidx.core.content.edit

/** Debug-only experiment switch (setup screen): feed the recognizer our own microphone audio. Off by default. */
class SttPreference(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("stt", Context.MODE_PRIVATE)

    var pipedAudio: Boolean
        get() = prefs.getBoolean(PIPED_AUDIO, false)
        set(value) = prefs.edit { putBoolean(PIPED_AUDIO, value) }

    private companion object {
        const val PIPED_AUDIO = "pipedAudio"
    }
}
