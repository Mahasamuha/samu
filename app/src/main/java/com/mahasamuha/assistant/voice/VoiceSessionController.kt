package com.mahasamuha.assistant.voice

import android.app.KeyguardManager
import android.os.SystemClock
import android.security.keystore.UserNotAuthenticatedException
import android.util.Log
import com.mahasamuha.assistant.GatewayNotSetException
import com.mahasamuha.assistant.auth.AuthManager
import com.mahasamuha.assistant.auth.NotSignedInException
import com.mahasamuha.assistant.capabilities.SessionCatalog
import com.mahasamuha.assistant.capabilities.UseCapabilityTool
import com.mahasamuha.assistant.llm.ChatClient
import com.mahasamuha.assistant.llm.ChatEvent
import com.mahasamuha.assistant.llm.ChatException
import com.mahasamuha.assistant.llm.ChatMessage
import com.mahasamuha.assistant.llm.ChatMessage.Role
import com.mahasamuha.assistant.llm.ChatOptions
import com.mahasamuha.assistant.llm.ToolCall
import com.mahasamuha.assistant.tools.Confirmation
import com.mahasamuha.assistant.tools.ConfirmationResult
import com.mahasamuha.assistant.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import org.json.JSONException
import org.json.JSONObject

data class PanelState(
    val phase: Phase = Phase.Starting,
    /** The session's conversation as shown, one entry per bubble. Kept for the whole session, like the history. */
    val transcript: List<PanelMessage> = emptyList(),
    /** Words recognized so far while the user speaks; moves into [transcript] when they finish. */
    val hearing: String = "",
    /** Input loudness 0..1 while listening, for the level indicator; 0 otherwise. */
    val level: Float = 0f,
    val error: String? = null,
    /** An action waiting on the user: shown with Confirm/Cancel (ask) or just Cancel (announce). */
    val confirming: Confirmation? = null,
) {
    enum class Phase { Starting, Listening, Thinking, Speaking, Idle }
}

/**
 * A chat bubble, or with [note] a small label between bubbles (e.g. "no reply heard"). [sources] are the
 * pages a web search behind this reply returned.
 */
data class PanelMessage(
    val fromUser: Boolean,
    val text: String,
    val note: Boolean = false,
    val sources: List<SourceLink> = emptyList(),
)

/** A web page behind a reply, shown with it (search providers expect sources alongside answers). */
data class SourceLink(
    val title: String,
    val url: String,
) {
    companion object {
        /** The pages in a `web_search` result (`{"results": [{title, url, …}]}`); empty for any other result. */
        fun fromSearchResult(result: String): List<SourceLink> {
            val results =
                try {
                    JSONObject(result).optJSONArray("results")
                } catch (e: JSONException) {
                    null
                } ?: return emptyList()
            return (0 until results.length()).mapNotNull { i ->
                val page = results.optJSONObject(i) ?: return@mapNotNull null
                val url = page.optString("url").takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return@mapNotNull null
                SourceLink(page.optString("title").ifBlank { url }, url)
            }
        }
    }
}

/** A problem the user can act on; the message is spoken as is. */
private class UserFacingException(
    message: String,
) : Exception(message)

/**
 * One panel's conversation: listen, ask the LLM (running any tools it calls), speak the reply sentence
 * by sentence. History lives as long as the session (plan §2: memory per invocation).
 */
class VoiceSessionController(
    private val scope: CoroutineScope,
    private val stt: SpeechToText,
    private val chat: ChatClient,
    private val catalogLoading: suspend () -> SessionCatalog,
    private val speaker: Speaker,
    private val locationSource: LocationSource,
    private val auth: AuthManager,
    private val keyguard: KeyguardManager,
    private val hasMicPermission: () -> Boolean,
) {
    private val _state = MutableStateFlow(PanelState())
    val state: StateFlow<PanelState> = _state.asStateFlow()

    private val history = mutableListOf<ChatMessage>()

    // Known from the first turn on.
    private lateinit var tools: ToolRegistry
    private var turn: Job? = null

    // Completed by a Confirm/Cancel tap while an action is being confirmed or announced.
    private var tap: CompletableDeferred<Boolean>? = null

    /** Starts a new turn, interrupting any speech or turn in progress. */
    fun listen() = startTurn(followUp = false)

    private fun startTurn(followUp: Boolean) {
        turn?.cancel()
        speaker.stop()
        turn = scope.launch { runTurn(followUp) }
    }

    /** A tap on the panel's Confirm (true) or Cancel (false) button. */
    fun answer(confirmed: Boolean) {
        tap?.complete(confirmed)
    }

    fun stop() {
        turn?.cancel()
        speaker.stop()
    }

    /**
     * Ends the turn but lets speech in progress finish: an action has handed over and the panel is about
     * to close, so the model's reply after it ("Your bread alarm is set…") would only repeat the report.
     */
    fun endTurn() {
        turn?.cancel()
    }

    /** A follow-up turn listens right after an answer; if the user says nothing, it just stops. */
    private suspend fun runTurn(followUp: Boolean) {
        _state.update { it.copy(phase = PanelState.Phase.Starting, hearing = "", error = null) }
        var answered = false
        try {
            checkPreconditions()
            // Look up the location while the user is talking, so it adds no delay.
            val location = scope.async { runCatching { locationSource.current() }.getOrNull() }
            val heard =
                try {
                    hear()
                } catch (e: Exception) {
                    location.cancel()
                    if (followUp && e is SttException && e.noSpeech) {
                        _state.update { it.copy(phase = PanelState.Phase.Idle, hearing = "") }
                        return
                    }
                    throw e
                }
            if (heard.isBlank()) throw UserFacingException("I didn't catch that.")
            addMessage(fromUser = true, heard)
            // Listening is over; waiting on location and the tool list counts as thinking.
            _state.update { it.copy(phase = PanelState.Phase.Thinking) }

            val place = location.await()
            if (history.isEmpty()) {
                // Resolved now, at the first model request, so a fresh index that landed while the user spoke counts.
                val catalog = catalogLoading()
                tools = catalog.registry
                Log.i(TAG, "Tools: ${tools.names.sorted().joinToString()}")
                history += ChatMessage(Role.SYSTEM, VoicePrompt.system(catalog.eagerSkills, catalog.lazyCapabilities, place))
            }
            val turnStart = history.size
            history += ChatMessage(Role.USER, heard)

            val reply =
                try {
                    respond()
                } catch (e: Exception) {
                    // Keep history to completed exchanges.
                    while (history.size > turnStart) history.removeAt(history.lastIndex)
                    throw e
                }
            speaker.awaitIdle()
            _state.update { it.copy(phase = PanelState.Phase.Idle) }
            answered = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = userMessage(e)
            Log.w(TAG, "Turn failed: $message", e)
            _state.update { it.copy(phase = PanelState.Phase.Idle, hearing = "", error = message) }
            try {
                speaker.speak(message)
            } catch (speakError: Exception) {
                Log.w(TAG, "Couldn't speak the error", speakError)
            }
        }
        // Keep the conversation going: listen for the next question (or the answer to "Which Sam?").
        if (answered) startTurn(followUp = true)
    }

    private fun checkPreconditions() {
        if (keyguard.isDeviceLocked) throw UserFacingException("Unlock your phone first.")
        if (!auth.signedIn.value) throw UserFacingException("You're signed out. Open the assistant app to sign in.")
        if (!hasMicPermission()) throw UserFacingException("I need microphone access. Open the assistant app to allow it.")
    }

    private suspend fun hear(expected: List<String> = emptyList()): String {
        _state.update { it.copy(phase = PanelState.Phase.Listening, level = 0f) }
        var final = ""
        try {
            collectHeard(expected) { final = it }
        } finally {
            _state.update { it.copy(level = 0f) }
        }
        return final
    }

    private suspend fun collectHeard(
        expected: List<String>,
        onFinal: (String) -> Unit,
    ) {
        stt.listen(expected).collect { event ->
            when (event) {
                is SttEvent.Level -> {
                    _state.update { it.copy(level = event.value) }
                }

                is SttEvent.Partial -> {
                    _state.update { it.copy(hearing = event.text) }
                }

                is SttEvent.Final -> {
                    onFinal(event.text)
                    _state.update { it.copy(hearing = event.text) }
                }
            }
        }
    }

    /**
     * Model rounds until it answers without calling tools. Each round's text is spoken as it streams,
     * including a short "Let me check" before a tool call, and shown as its own bubble. Appends to
     * history; returns all spoken text.
     */
    private suspend fun respond(): String {
        val spoken = StringBuilder()
        // Pages from this turn's searches, shown with the next reply bubble (the answer after the search).
        var sources = emptyList<SourceLink>()
        // When a round called use_capability: the clock time then, to log how long the extra round took to start.
        var bindRequestedAt: Long? = null
        repeat(MAX_ROUNDS) {
            val splitter = SentenceSplitter()
            val text = StringBuilder()
            var calls = emptyList<ToolCall>()
            var replay = emptyList<JSONObject>()
            var bubble: Int? = null // this round's reply bubble in the transcript
            // v1 calls the LLM only while unlocked (plan §2); the phone may have locked during the last round.
            if (keyguard.isDeviceLocked) throw UserFacingException("Unlock your phone first.")
            // Per round, since use_capability adds tools: those bound in this round are sent in the next.
            val options = ChatOptions(tools = tools.definitions)
            chat.stream(history.toList(), options).collect { event ->
                bindRequestedAt?.let { requested ->
                    Log.i(
                        TAG,
                        "Round after use_capability began streaming ${SystemClock.elapsedRealtime() - requested} ms after the bind request",
                    )
                    bindRequestedAt = null
                }
                when (event) {
                    is ChatEvent.Text -> {
                        val started = text.isNotBlank()
                        text.append(event.delta)
                        if (text.isNotBlank()) {
                            if (started) {
                                replaceLastMessage(text.toString().trim())
                            } else {
                                // The reply is arriving, so the panel stops showing the waiting indicator.
                                addMessage(fromUser = false, text.toString().trim())
                                bubble = _state.value.transcript.lastIndex
                                _state.update { it.copy(phase = PanelState.Phase.Speaking) }
                            }
                        }
                        for (sentence in splitter.add(event.delta)) speakSentence(sentence)
                    }

                    is ChatEvent.ToolCalls -> {
                        calls = event.calls
                    }

                    is ChatEvent.Finished -> {
                        replay = event.replay
                    }
                }
            }
            splitter.flush()?.let { speakSentence(it) }
            spoken.append(text).append(' ')

            history += ChatMessage(Role.ASSISTANT, text.toString().ifEmpty { null }, toolCalls = calls, replay = replay)
            bubble?.takeIf { sources.isNotEmpty() }?.let { index ->
                val shown = sources
                _state.update { state ->
                    state.copy(transcript = state.transcript.mapIndexed { i, m -> if (i == index) m.copy(sources = shown) else m })
                }
                sources = emptyList()
            }
            if (calls.isEmpty()) return spoken.toString().trim()

            if (calls.any { it.name == UseCapabilityTool.NAME }) bindRequestedAt = SystemClock.elapsedRealtime()
            for (call in calls) {
                _state.update { it.copy(phase = PanelState.Phase.Thinking) }
                val result = tools.execute(call, ::confirm)
                sources = (sources + SourceLink.fromSearchResult(result)).distinctBy { it.url }
                history += ChatMessage(Role.TOOL, result, toolCallId = call.id)
            }
        }
        throw IllegalStateException("No answer after $MAX_ROUNDS model rounds")
    }

    /**
     * Gets an action confirmed (plan §4b): voice or tap, whichever comes first. A tap cuts off the
     * prompt; an announcement goes ahead once it has been spoken in full. A report doesn't wait: the
     * action runs at once while the line plays.
     */
    private suspend fun confirm(confirmation: Confirmation): ConfirmationResult {
        // Nothing to cancel: the action runs at once, and the line plays while it does.
        if (confirmation is Confirmation.Report) {
            if (keyguard.isDeviceLocked) throw IllegalStateException("The phone locked, so the action was not performed")
            speakSentence(confirmation.text)
            addMessage(fromUser = false, confirmation.text)
            return ConfirmationResult.Confirmed
        }
        val tapped = CompletableDeferred<Boolean>()
        tap = tapped
        _state.update { it.copy(confirming = confirmation) }
        // The result, plus what to show as the user's side of the exchange (their words, or the button they tapped).
        val (result, userReply) =
            try {
                coroutineScope {
                    val spoken =
                        async {
                            speakSentence(confirmation.text)
                            speaker.awaitIdle()
                            when (confirmation) {
                                is Confirmation.Ask -> hearConfirmation()
                                is Confirmation.Announce, is Confirmation.Report -> ConfirmationResult.Confirmed to null
                            }
                        }
                    select<Pair<ConfirmationResult, String?>> {
                        spoken.onAwait { it }
                        tapped.onAwait { confirmed ->
                            spoken.cancel()
                            speaker.stop()
                            if (confirmed) ConfirmationResult.Confirmed to "confirm" else ConfirmationResult.Declined(null) to "cancel"
                        }
                    }
                }
            } finally {
                tap = null
                _state.update { it.copy(confirming = null, hearing = "") }
            }
        addMessage(fromUser = false, confirmation.text)
        when {
            userReply != null -> addMessage(fromUser = true, userReply)

            // Nothing recognized: say so, or a "no" that wasn't heard looks like it was ignored.
            confirmation is Confirmation.Ask -> addNote("no reply heard · cancelled")
        }
        Log.i(TAG, "Confirmation ${confirmation::class.simpleName} -> ${result::class.simpleName}")
        // v1 acts only while unlocked (plan §2); the phone may have locked while waiting.
        if (result == ConfirmationResult.Confirmed && keyguard.isDeviceLocked) {
            throw IllegalStateException("The phone locked, so the action was not performed")
        }
        return result
    }

    private suspend fun hearConfirmation(): Pair<ConfirmationResult, String?> {
        val reply =
            try {
                hear(ConfirmationReply.EXPECTED)
            } catch (e: SttException) {
                if (!e.noSpeech) throw e
                ""
            }.ifBlank { null }
        val result =
            if (reply != null && ConfirmationReply.isYes(reply)) {
                ConfirmationResult.Confirmed
            } else {
                ConfirmationResult.Declined(reply)
            }
        return result to reply
    }

    /** Adds a bubble and clears the in-progress words. */
    private fun addMessage(
        fromUser: Boolean,
        text: String,
    ) {
        _state.update { it.copy(transcript = it.transcript + PanelMessage(fromUser, text), hearing = "") }
    }

    private fun addNote(text: String) {
        _state.update { it.copy(transcript = it.transcript + PanelMessage(fromUser = false, text, note = true), hearing = "") }
    }

    /** Updates the bubble that is still streaming. */
    private fun replaceLastMessage(text: String) {
        _state.update { it.copy(transcript = it.transcript.dropLast(1) + it.transcript.last().copy(text = text)) }
    }

    private suspend fun speakSentence(sentence: String) {
        _state.update { it.copy(phase = PanelState.Phase.Speaking) }
        speaker.speak(sentence)
    }

    private companion object {
        /** Model calls per turn: enough for a contact lookup, a text, a revised text, and the answer. */
        const val MAX_ROUNDS = 5
    }

    private fun userMessage(e: Exception): String =
        when (e) {
            is UserFacingException -> {
                e.message.orEmpty()
            }

            is GatewayNotSetException -> {
                "No server is set up. Open the assistant app and enter the gateway address."
            }

            is NotSignedInException -> {
                "You're signed out. Open the assistant app to sign in."
            }

            // The screen can be unlocked while the Keystore still counts the phone as locked (e.g. woken
            // within the lock grace period, no fingerprint or PIN), which keeps the token key closed.
            is UserNotAuthenticatedException -> {
                if (keyguard.isDeviceLocked) {
                    "Unlock your phone first."
                } else {
                    "Lock your phone and unlock it with your fingerprint or PIN, then try again."
                }
            }

            is ChatException.BudgetExceeded -> {
                "The assistant's monthly budget is used up."
            }

            is ChatException.RateLimited -> {
                "That's too many requests. Try again in a moment."
            }

            is ChatException.Unauthorized -> {
                "The server didn't accept the sign-in. Open the assistant app and sign in again."
            }

            is ChatException.Unavailable -> {
                "I can't reach the assistant server right now."
            }

            is ChatException.Rejected -> {
                "The assistant server rejected that request."
            }

            is SttException -> {
                if (e.noSpeech) "I didn't catch that." else "Speech recognition failed."
            }

            is OnDeviceSttUnavailableException -> {
                "On-device speech recognition isn't available. Check that the speech model is downloaded."
            }

            is SpeakerException -> {
                "Text to speech isn't working."
            }

            else -> {
                "Something went wrong."
            }
        }
}
