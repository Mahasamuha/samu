package com.mahasamuha.assistant.voice

/**
 * Collects streamed text and hands back complete sentences, so speech can start before the reply ends.
 * A sentence ends at . ! or ? followed by whitespace or by a capital letter: the model's preamble and
 * answer can arrive glued together ("...for you.Based on..."). "3.5" doesn't split.
 */
class SentenceSplitter {
    private val buffer = StringBuilder()

    fun add(delta: String): List<String> {
        buffer.append(delta)
        val sentences = mutableListOf<String>()
        var start = 0
        for (i in 0 until buffer.length - 1) {
            if (buffer[i] in TERMINATORS && (buffer[i + 1].isWhitespace() || buffer[i + 1].isUpperCase())) {
                buffer
                    .substring(start, i + 1)
                    .trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let(sentences::add)
                start = i + 1
            }
        }
        buffer.delete(0, start)
        return sentences
    }

    /** Whatever is left once the stream ends. */
    fun flush(): String? =
        buffer
            .toString()
            .trim()
            .takeIf { it.isNotEmpty() }
            .also { buffer.clear() }

    private companion object {
        val TERMINATORS = setOf('.', '!', '?')
    }
}
