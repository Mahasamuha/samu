package com.mahasamuha.assistant.voice

import kotlin.math.log10
import kotlin.math.sqrt

/** Maps microphone loudness onto 0..1 for the panel's level indicator; both sources share one scale. */
object InputLevel {
    private const val FLOOR_DBFS = -60.0
    private const val CEILING_DBFS = -25.0
    const val SILENCE_DBFS = -120.0
    private const val FULL_SCALE = 32_768.0

    // The recognizer's rmsdB is roughly -2 (silence) to 10 (loud).
    private const val RMS_DB_FLOOR = -2f
    private const val RMS_DB_CEILING = 10f

    /** RMS of 16-bit PCM [samples] (first [count] of them) in dBFS; [SILENCE_DBFS] for silence or no samples. */
    fun rmsDbfs(
        samples: ShortArray,
        count: Int = samples.size,
    ): Double {
        if (count <= 0) return SILENCE_DBFS
        var sum = 0.0
        for (i in 0 until count) sum += samples[i].toDouble() * samples[i]
        val rms = sqrt(sum / count)
        if (rms <= 0.0) return SILENCE_DBFS
        return maxOf(20 * log10(rms / FULL_SCALE), SILENCE_DBFS)
    }

    /** Level of 16-bit PCM [samples] (first [count] of them): RMS in dBFS mapped to 0..1. Silence is 0. */
    fun fromPcm(
        samples: ShortArray,
        count: Int = samples.size,
    ): Float = fromDbfs(rmsDbfs(samples, count))

    /** Maps a dBFS value onto 0..1 over the floor..ceiling range. */
    fun fromDbfs(dbfs: Double): Float = ((dbfs - FLOOR_DBFS) / (CEILING_DBFS - FLOOR_DBFS)).toFloat().coerceIn(0f, 1f)

    /** Level from `RecognitionListener.onRmsChanged`, on the same 0..1 scale. */
    fun fromRecognizerDb(rmsdB: Float): Float = ((rmsdB - RMS_DB_FLOOR) / (RMS_DB_CEILING - RMS_DB_FLOOR)).coerceIn(0f, 1f)
}
