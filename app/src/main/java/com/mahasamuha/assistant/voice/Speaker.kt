package com.mahasamuha.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.updateAndGet
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class SpeakerException(
    message: String,
) : Exception(message)

/** A voice the user can pick: the engine's [name] (e.g. `en-us-x-iom-local`) and a readable [label]. */
data class VoiceOption(
    val name: String,
    val label: String,
)

/** Text-to-speech. Sentences queue up and play in order. */
interface Speaker {
    suspend fun speak(sentence: String)

    /** Voices for the current language that run on the phone, in a stable order. */
    suspend fun voices(): List<VoiceOption>

    /** Uses the voice named [name], or the engine default for null or a voice that's gone. */
    suspend fun setVoice(name: String?)

    /** Suspends until everything queued has finished playing (or was stopped). */
    suspend fun awaitIdle()

    fun stop()

    fun shutdown()
}

/**
 * Android's TextToSpeech. The engine plays in its own process, so speech continues after the panel
 * closes (docs/phase1-notes.md). Other audio is ducked while speaking. [voiceName] is the user's
 * chosen voice, if any (plan §5b).
 */
class PlatformSpeaker(
    context: Context,
    private val voiceName: String? = null,
) : Speaker {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val attributes =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    private val focusRequest =
        AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()

    private val ready = CompletableDeferred<TextToSpeech>()
    private lateinit var tts: TextToSpeech
    private var retried = false

    // Utterances queued but not finished. Updated from the main thread and the engine's binder thread.
    private val pending = MutableStateFlow(0)
    private val nextId = AtomicInteger()

    private val progressListener =
        object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit

            override fun onDone(utteranceId: String) = utteranceEnded()

            override fun onStop(
                utteranceId: String,
                interrupted: Boolean,
            ) = Unit // stop() resets the count itself

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = utteranceEnded()

            override fun onError(
                utteranceId: String,
                errorCode: Int,
            ) {
                Log.w(TAG, "TTS error $errorCode")
                utteranceEnded()
            }
        }

    init {
        create()
    }

    // The engine can fail to bind on the first try (seen on a fresh emulator), so retry once.
    private fun create() {
        tts =
            TextToSpeech(appContext) { status ->
                Log.i(TAG, "TTS init status=$status")
                when {
                    status == TextToSpeech.SUCCESS -> {
                        tts.setAudioAttributes(attributes)
                        tts.setOnUtteranceProgressListener(progressListener)
                        voiceName?.let(::applyVoice)
                        ready.complete(tts)
                    }

                    !retried -> {
                        retried = true
                        tts.shutdown()
                        create()
                    }

                    else -> {
                        ready.completeExceptionally(SpeakerException("Text-to-speech unavailable"))
                    }
                }
            }
    }

    override suspend fun speak(sentence: String) {
        val engine = ready.await()
        if (pending.updateAndGet { it + 1 } == 1) audioManager.requestAudioFocus(focusRequest)
        if (engine.speak(sentence, TextToSpeech.QUEUE_ADD, null, "u${nextId.incrementAndGet()}") != TextToSpeech.SUCCESS) {
            utteranceEnded()
            throw SpeakerException("Text-to-speech rejected the sentence")
        }
    }

    // Network voices are left out: they'd send everything Samu says, read-backs included, to the engine's
    // servers. Engine voice names end in -local or -network; a voice whose data isn't downloaded is skipped.
    override suspend fun voices(): List<VoiceOption> {
        val engine = ready.await()
        val locale = Locale.getDefault()
        return engine.voices
            .orEmpty()
            .filter { it.locale.language == locale.language && it.locale.country == locale.country }
            .filter { !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
            // e.g. "en-US-language": the engine's alias for its default, which "Engine default" already covers.
            .filterNot { it.name.endsWith("-language") }
            .sortedBy { it.name }
            .mapIndexed { i, voice -> VoiceOption(voice.name, "Voice ${i + 1}") }
    }

    override suspend fun setVoice(name: String?) {
        val engine = ready.await()
        if (name == null) engine.voice = engine.defaultVoice else applyVoice(name)
    }

    private fun applyVoice(name: String) {
        // An engine update can rename or remove voices; fall back to the default rather than fail.
        val voice = tts.voices.orEmpty().firstOrNull { it.name == name }
        if (voice == null) {
            Log.w(TAG, "Voice $name not available; using the engine default")
        } else {
            tts.voice = voice
        }
    }

    override suspend fun awaitIdle() {
        pending.first { it == 0 }
    }

    override fun stop() {
        if (ready.isCompleted && !ready.isCancelled) tts.stop()
        pending.value = 0
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    override fun shutdown() {
        stop()
        if (::tts.isInitialized) tts.shutdown()
    }

    private fun utteranceEnded() {
        if (pending.updateAndGet { (it - 1).coerceAtLeast(0) } == 0) audioManager.abandonAudioFocusRequest(focusRequest)
    }
}
