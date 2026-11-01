package com.mahasamuha.assistant.voice

import android.content.Context
import androidx.core.content.edit

/** The TTS voice picked on the setup screen (plan §5b); null means the engine default. */
class VoicePreference(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("voice", Context.MODE_PRIVATE)

    var name: String?
        get() = prefs.getString(NAME, null)
        set(value) = prefs.edit { putString(NAME, value) }

    private companion object {
        const val NAME = "name"
    }
}
