package com.mahasamuha.assistant.voice

import java.util.concurrent.ConcurrentHashMap

/**
 * Clock for one listen's log lines; no Android dependencies. Created when listening starts.
 * Offsets are milliseconds since creation, read from [clock]; [label] prefixes every line.
 * [isFirst] backs the "first partial", "first level" lines (it may be called from any thread).
 */
class ListenTracker(
    private val label: String,
    private val clock: () -> Long,
) {
    private val startMs = clock()
    private val seen = ConcurrentHashMap.newKeySet<String>()

    fun offsetMs(): Long = clock() - startMs

    /** [message] prefixed with the label and the offset, e.g. `STT +340ms ready`. */
    fun stamp(message: String): String = "$label +${offsetMs()}ms $message"

    /** True the first time it is called for [event], false after that. */
    fun isFirst(event: String): Boolean = seen.add(event)
}
