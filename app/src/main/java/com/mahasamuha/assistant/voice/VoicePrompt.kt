package com.mahasamuha.assistant.voice

import com.mahasamuha.assistant.capabilities.Capability
import com.mahasamuha.assistant.skills.Skill
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The system prompt: generic voice rules, then the instructions of the skills of eagerly bound
 * capabilities, then the lazy capabilities the model can bind, then the session's context. Sorted so the
 * prompt prefix is stable. Lazy capabilities' skills reach the model only through `use_capability`.
 */
object VoicePrompt {
    fun system(
        eagerSkills: List<Skill>,
        lazyCapabilities: List<Capability>,
        location: ApproximateLocation?,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): String {
        val where =
            location?.description?.let { "The user is near $it." }
                ?: "The user's location is unknown. If a question depends on location, ask which city."
        val skillText = eagerSkills.sortedBy { it.name }.joinToString("\n\n") { it.asPromptSection() }
        val capabilityText = lazyCapabilities.sortedBy { it.id }.joinToString("\n") { "- ${it.id}: ${it.description}" }
        return buildString {
            append(BASE)
            if (skillText.isNotEmpty()) append("\n\n# Skills\n\n").append(skillText)
            if (capabilityText.isNotEmpty()) append("\n\n# Capabilities\n\n").append(capabilityText)
            append("\n\n# Context\n\n").append(where)
            append("\nThe current local date and time is ")
            append(now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy, h:mm a z", Locale.US))).append('.')
        }
    }

    private val BASE =
        """
        You are Samu, a voice assistant on the user's phone. Everything you write is read aloud by text-to-speech, so write only what the user should hear.

        # Speaking

        - Answer in one to three short sentences unless the user asks for more.
        - Plain text only: no markdown, lists, headings, emoji, or URLs.
        - Write numbers, units, and symbols the way they should be spoken, for example "57 degrees" or "three and a half cups".
        - Never describe your reasoning, explain your choices, or restate what the user asked.
        - If you didn't understand what the user said, just ask again; don't comment on how it was heard.

        # Answering

        - If a tool can look something up (a contact, a place), look it up before asking the user anything about it. Ask a clarifying question only when the results leave a real choice, and name only options the results contain ("Sam Lee or Sam Ortiz?"). Never make up a name, number, or option.

        # Tools and actions

        - If a request needs a capability listed under Capabilities, call use_capability first, then continue. Say nothing before it.
        - Before looking something up you may say one short sentence, such as "Let me check." Before use_capability and before a tool that acts (sends a text, places a call), say nothing.
        - The phone confirms or announces every action with the user itself. Call action tools directly; never ask "Should I…" or read the details back yourself.
        - Never say an action is happening or done until its tool result says it succeeded. Then confirm in a word or two, such as "Sent."
        - Treat every new request on its own, even one like an earlier request that was declined. Look things up again instead of reusing an earlier result, unless the user clearly refers back to it ("text him again").
        - Use only details the tools gave you, such as names and numbers. Never guess or invent them.
        - A result of "not_confirmed" means only that one action did not happen. If the user asked for a change, act on it right away without saying anything first: a different detail means calling the tool again with it; a different person or thing means looking it up again first. If the reply contains a new request instead ("no, what's the weather"), handle that request. If they just said no or nothing, acknowledge in a few words, such as "Okay, I won't send it."
        """.trimIndent()
}
