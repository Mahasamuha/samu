package com.mahasamuha.assistant.contacts

// Set from on-device lookups (docs/phase3-notes.md): the right name scored 0.96–1.00 and the
// next-best wrong one 0.08–0.12 below it.
internal const val SOUND_ALIKE_MIN = 0.80
internal const val SOUND_ALIKE_MARGIN = 0.06

/**
 * The [names] that sound like [heard], best first: at least [SOUND_ALIKE_MIN] and within
 * [SOUND_ALIKE_MARGIN] of the best score, since the right name tends to stand clear of the rest. At most [limit].
 */
internal fun soundAlikeNames(
    heard: String,
    names: Collection<String>,
    limit: Int,
): List<String> {
    val scored = names.map { it to soundAlikeScore(heard, it) }.sortedByDescending { (_, score) -> score }
    val best = scored.firstOrNull()?.second ?: return emptyList()
    return scored
        .filter { (_, score) -> score >= SOUND_ALIKE_MIN && score >= best - SOUND_ALIKE_MARGIN }
        .take(limit)
        .map { (name, _) -> name }
}

/**
 * How much a heard name sounds like a contact's name, 0 to 1. Speech recognition writes uncommon names
 * as something familiar ("Shivon" for Siobhan, "Kira" for Ciara), so each word of [heard] is compared
 * with the closest word of [name], by spelling and by a rough phonetic key, and the scores averaged.
 * A heuristic for picking candidates to offer the user, never for acting on its own.
 */
internal fun soundAlikeScore(
    heard: String,
    name: String,
): Double {
    val heardWords = words(heard)
    val nameWords = words(name)
    if (heardWords.isEmpty() || nameWords.isEmpty()) return 0.0
    return heardWords.map { h -> nameWords.maxOf { n -> wordScore(h, n) } }.average()
}

private fun words(text: String): List<String> = text.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }

private fun wordScore(
    a: String,
    b: String,
): Double = maxOf(jaroWinkler(a, b), jaroWinkler(phoneticKey(a), phoneticKey(b)))

/**
 * A rough sound key in the spirit of Metaphone: spellings that sound alike reduce to the same consonant
 * skeleton. Covers common English spellings plus a few seen in names (Irish "bh"/"mh", "ph", soft c and g).
 */
internal fun phoneticKey(word: String): String {
    var w = word.lowercase().filter { it in 'a'..'z' }
    if (w.isEmpty()) return w
    for ((from, to) in DIGRAPHS) w = w.replace(from, to)
    val mapped =
        buildString {
            w.forEachIndexed { i, c ->
                val next = w.getOrNull(i + 1)
                append(
                    when (c) {
                        'c' -> if (next in SOFTENING) 's' else 'k'
                        'g' -> if (next in SOFTENING) 'j' else 'g'
                        'q' -> 'k'
                        'z' -> 's'
                        'v' -> 'f'
                        'y' -> 'i'
                        else -> c
                    },
                )
            }
        }
    // Vowels carry little of a misspelled name; keep only a leading one.
    val skeleton = mapped.first() + mapped.drop(1).filter { it !in VOWELS }
    return skeleton
        .fold(StringBuilder()) { out, c ->
            if (out.lastOrNull() != c) out.append(c)
            out
        }.toString()
}

private val DIGRAPHS =
    listOf(
        "sch" to "sk",
        "ph" to "f",
        "sh" to "x",
        "ch" to "x",
        "ck" to "k",
        "th" to "t",
        "bh" to "v",
        "mh" to "v",
        "gh" to "g",
        "kn" to "n",
        "wh" to "w",
        "dg" to "j",
    )
private val SOFTENING = setOf('e', 'i', 'y')
private const val VOWELS = "aeiouhw"

/** Jaro-Winkler similarity: 1 for identical strings, weighting a shared prefix. */
internal fun jaroWinkler(
    a: String,
    b: String,
): Double {
    if (a == b) return 1.0
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val window = (maxOf(a.length, b.length) / 2 - 1).coerceAtLeast(0)
    val aMatched = BooleanArray(a.length)
    val bMatched = BooleanArray(b.length)
    var matches = 0
    for (i in a.indices) {
        for (j in maxOf(0, i - window)..minOf(b.length - 1, i + window)) {
            if (!bMatched[j] && a[i] == b[j]) {
                aMatched[i] = true
                bMatched[j] = true
                matches++
                break
            }
        }
    }
    if (matches == 0) return 0.0
    var transpositions = 0
    var j = 0
    for (i in a.indices) {
        if (!aMatched[i]) continue
        while (!bMatched[j]) j++
        if (a[i] != b[j]) transpositions++
        j++
    }
    val m = matches.toDouble()
    val jaro = (m / a.length + m / b.length + (m - transpositions / 2.0) / m) / 3
    val prefix =
        a
            .zip(b)
            .takeWhile { (x, y) -> x == y }
            .size
            .coerceAtMost(4)
    return jaro + prefix * 0.1 * (1 - jaro)
}
