package com.mahasamuha.assistant.voice

/** Why an utterance was judged over: [Silence] after the recognizer produced text, [NoSpeech] when it never did. */
enum class EndReason { Silence, NoSpeech }

/**
 * Decides in audio time (not wall-clock) when the user is done talking, from one VAD speech/non-speech verdict per
 * frame and whether the recognizer has produced text yet ([onTextHeard]). "Speech heard" means recognized text: the
 * VAD also flags noise, so it only measures silence.
 * - Before any text: ends after [waitSilenceMs] of continuous non-speech (reset by VAD speech). A cold engine may
 *   need that long to produce its first words, and closing the pipe is safe: the recognizer finishes queued audio.
 * - After text: ends after [endSilenceMs] of continuous non-speech.
 * Reports the end once; later frames return null. No Android or library types.
 */
class Endpointer(
    private val frameMs: Int,
    private val waitSilenceMs: Int,
    private val endSilenceMs: Int = END_SILENCE_MS,
) {
    @Volatile private var textHeard = false
    private var silenceRunMs = 0
    private var ended = false

    /** Audio seen so far, in milliseconds. */
    var elapsedMs = 0
        private set

    /** Whether the recognizer has produced non-blank text. */
    val hasHeardText get() = textHeard

    /** The recognizer produced its first non-blank text. Safe to call from any thread. */
    fun onTextHeard() {
        textHeard = true
    }

    /** Call from the capture thread only. */
    fun onFrame(isSpeech: Boolean): EndReason? {
        if (ended) return null
        elapsedMs += frameMs
        if (isSpeech) {
            silenceRunMs = 0
            return null
        }
        silenceRunMs += frameMs
        val reason =
            if (textHeard) {
                EndReason.Silence.takeIf { silenceRunMs >= endSilenceMs }
            } else {
                EndReason.NoSpeech.takeIf { silenceRunMs >= waitSilenceMs }
            }
        if (reason != null) ended = true
        return reason
    }

    companion object {
        const val END_SILENCE_MS = 800

        /** Wait for text on a reply listen: Samu has spoken, so the user is expected to answer promptly. */
        const val REPLY_WAIT_MS = 4_000

        /** Wait for text on the session's first listen, when a cold engine may be slow to produce any. */
        const val FIRST_WAIT_MS = 6_000

        /** Silence after text on a confirmation reply: a lone "yes" or "no" is often followed by a second word. */
        const val CONFIRMATION_END_SILENCE_MS = 1_200

        fun waitSilenceMs(kind: ListenKind) = if (kind == ListenKind.First) FIRST_WAIT_MS else REPLY_WAIT_MS

        fun endSilenceMs(kind: ListenKind) = if (kind == ListenKind.Confirmation) CONFIRMATION_END_SILENCE_MS else END_SILENCE_MS
    }
}
