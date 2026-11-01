package com.mahasamuha.assistant.voice

/**
 * Decides on the phone, not by the model, whether a spoken reply confirms an action. Deliberately
 * strict: only a whole-utterance yes counts, so "yes but say ten minutes" is not a yes.
 */
object ConfirmationReply {
    private val YES =
        setOf(
            "yes",
            "yeah",
            "yep",
            "yup",
            "sure",
            "ok",
            "okay",
            "correct",
            "confirm",
            "right",
            "thats right",
            "send",
            "send it",
            "call",
            "do it",
            "go",
            "go ahead",
            "sounds good",
        )

    // A leading yes before another yes phrase ("yes send it") is still a yes.
    private val PREFIXES = listOf("yes ", "yeah ", "ok ", "okay ", "sure ")

    /** Words to favor in speech recognition while waiting for a confirmation. */
    val EXPECTED: List<String> = YES.toList() + listOf("no", "nope", "cancel", "stop", "don't", "wait")

    fun isYes(reply: String): Boolean {
        var text =
            reply
                .lowercase()
                .replace(Regex("[^a-z ]"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
        text = text.removeSuffix(" please").trim()
        if (text in YES) return true
        val prefix = PREFIXES.firstOrNull { text.startsWith(it) } ?: return false
        return text.removePrefix(prefix) in YES
    }
}
