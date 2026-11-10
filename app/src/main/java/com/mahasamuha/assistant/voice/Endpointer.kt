package com.mahasamuha.assistant.voice

/** Why an utterance was judged over. */
enum class EndReason { Silence, NoSpeech }

/**
 * Decides in audio time (not wall-clock) when the user is done talking, from one speech/non-speech verdict per
 * frame. Ends after [endSilenceMs] of continuous non-speech once speech was seen, or when no speech arrived within
 * [noSpeechMs] of audio. Reports the end once; later frames return null. No Android or library types.
 */
class Endpointer(
    private val frameMs: Int,
    private val endSilenceMs: Int = END_SILENCE_MS,
    private val noSpeechMs: Int = NO_SPEECH_MS,
) {
    private var speechSeen = false
    private var silenceRunMs = 0
    private var ended = false

    /** Audio seen so far, in milliseconds. */
    var elapsedMs = 0
        private set

    fun onFrame(isSpeech: Boolean): EndReason? {
        if (ended) return null
        elapsedMs += frameMs
        if (isSpeech) {
            speechSeen = true
            silenceRunMs = 0
            return null
        }
        silenceRunMs += frameMs
        val reason =
            when {
                speechSeen && silenceRunMs >= endSilenceMs -> EndReason.Silence
                !speechSeen && elapsedMs >= noSpeechMs -> EndReason.NoSpeech
                else -> null
            }
        if (reason != null) ended = true
        return reason
    }

    companion object {
        const val END_SILENCE_MS = 800
        const val NO_SPEECH_MS = 6_000
    }
}
