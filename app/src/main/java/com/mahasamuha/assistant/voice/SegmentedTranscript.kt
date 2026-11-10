package com.mahasamuha.assistant.voice

/**
 * Text of a segmented recognition session: finished segments in order, plus the segment still being recognized.
 * The recognizer's partials cover only the current segment, so the UI text is previous segments + current partial.
 * Blank segments are dropped. No Android dependencies.
 */
class SegmentedTranscript {
    private val segments = mutableListOf<String>()
    private var partial = ""

    val segmentCount get() = segments.size

    /** The current segment's latest partial; returns the text to show: all finished segments plus it. */
    fun onPartial(text: String): String {
        partial = text.trim()
        return pendingText()
    }

    /** A segment finished with [text] (blank is ignored); the current partial is superseded. */
    fun onSegment(text: String) {
        partial = ""
        text.trim().takeIf { it.isNotEmpty() }?.let(segments::add)
    }

    /** Finished segments only. */
    fun joined(): String = segments.joinToString(" ")

    /** Finished segments plus the unfinished partial: the best text when the recognizer ends without a result. */
    fun pendingText(): String = (segments + partial).filter { it.isNotEmpty() }.joinToString(" ")
}
