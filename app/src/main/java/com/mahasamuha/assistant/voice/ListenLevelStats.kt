package com.mahasamuha.assistant.voice

import kotlin.math.ceil

/**
 * Calibration diagnostics, not used for any decision: level statistics for one listen, for tuning the indicator: our own PCM chunk levels in dBFS (peak and a
 * percentile) and the recognizer's own `onRmsChanged` range. A listen is a few hundred chunks, so values are kept.
 * Chunk levels come from the capture thread and recognizer levels from main; [summary] is called after both stop.
 */
class ListenLevelStats {
    private val chunkDbfs = ArrayList<Double>()
    private var recognizerMin = Float.POSITIVE_INFINITY
    private var recognizerMax = Float.NEGATIVE_INFINITY

    @Synchronized
    fun onChunk(dbfs: Double) {
        chunkDbfs.add(dbfs)
    }

    @Synchronized
    fun onRecognizerDb(db: Float) {
        if (db < recognizerMin) recognizerMin = db
        if (db > recognizerMax) recognizerMax = db
    }

    /** Peak chunk level, or null with no chunks. */
    @Synchronized
    fun peakDbfs(): Double? = chunkDbfs.maxOrNull()

    /** Nearest-rank percentile ([percent] in 1..100) of chunk levels, or null with no chunks. */
    @Synchronized
    fun percentileDbfs(percent: Int): Double? {
        if (chunkDbfs.isEmpty()) return null
        val sorted = chunkDbfs.sorted()
        val rank = ceil(percent / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    @Synchronized
    fun summary(): String {
        val ours = "ours dBFS peak ${format(peakDbfs())}, p90 ${format(percentileDbfs(P90))} over ${chunkDbfs.size} chunks"
        val theirs =
            if (recognizerMin > recognizerMax) {
                "none"
            } else {
                "min ${format(recognizerMin.toDouble())}, max ${format(recognizerMax.toDouble())}"
            }
        return "$ours; recognizer dB $theirs"
    }

    private fun format(value: Double?): String = value?.let { "%.1f".format(java.util.Locale.ROOT, it) } ?: "n/a"

    private companion object {
        const val P90 = 90
    }
}
