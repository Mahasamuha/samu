package com.mahasamuha.assistant.voice

/** What to do with a recognizer error: [rescuedText] is the last partial to use as the final result, if any. */
data class ListenErrorOutcome(
    val offsetMs: Long,
    val rescuedText: String?,
)

/**
 * Timing and fallback state for one listen; no Android dependencies. Created when listening starts.
 * Offsets are milliseconds since creation, read from [clock].
 * A cold on-device recognizer can deliver partials and then end with "no match"; the last partial is kept for that case.
 */
class ListenTracker(
    private val clock: () -> Long,
) {
    private val startMs = clock()
    private var firstPartialSeen = false
    private var lastPartial: String? = null

    fun offsetMs(): Long = clock() - startMs

    /** Returns the offset of the first non-blank partial, or null for blank and later ones. */
    fun onPartial(text: String): Long? {
        if (text.isBlank()) return null
        lastPartial = text
        if (firstPartialSeen) return null
        firstPartialSeen = true
        return offsetMs()
    }

    fun onError(code: Int): ListenErrorOutcome = ListenErrorOutcome(offsetMs(), lastPartial.takeIf { code == ERROR_NO_MATCH })

    /** Mirrors `SpeechRecognizer.ERROR_NO_MATCH`, kept here so this class stays off Android. */
    companion object {
        const val ERROR_NO_MATCH = 7
    }
}
