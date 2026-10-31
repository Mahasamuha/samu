package com.mahasamuha.assistant.voice

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.mahasamuha.assistant.AssistantApp
import com.mahasamuha.assistant.ui.AssistantTheme

/**
 * One assistant invocation: the panel and its conversation. The session isn't an Activity, so it
 * supplies the lifecycle and saved-state owners that ComposeView needs.
 */
class AssistantSession(context: Context) :
    VoiceInteractionSession(context), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val audioManager by lazy { context.getSystemService(AudioManager::class.java) }

    /**
     * Held for the whole time the panel is up, so music pauses while the user talks (it hurts
     * recognition) and resumes when this is abandoned, as with other assistants. Separate from the
     * Speaker's ducking request: the no-op listener gives this request its own focus client.
     */
    private val sessionFocus by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { }
            .build()
    }

    /** Set when the user paused music, so the session ends with a permanent focus loss for the player. */
    private var keepOthersPaused = false

    private lateinit var speaker: Speaker
    private lateinit var controller: VoiceSessionController

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Session.onCreate")
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        val app = context.applicationContext as AssistantApp
        speaker = PlatformSpeaker(context, app.voicePreference.name)
        // Races speech recognition: the session uses the fresh index only if it lands first.
        val indexRefresh = app.startIndexRefresh()
        controller = VoiceSessionController(
            scope = lifecycleScope,
            stt = PlatformSpeechToText(context),
            chat = app.chatClient,
            // From the session's own context while the panel is up; Android 17 blocks background launches.
            // Resolved at the first model request; the index refresh runs while the user speaks.
            catalogLoading = {
                app.sessionCatalog(
                    indexRefresh = indexRefresh,
                    launch = { intent -> context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    // No more model rounds, but after any speech in progress, so a reported action's line
                    // ("Timer, 10 minutes") isn't cut off.
                    closePanel = {
                        controller.endTurn()
                        lifecycleScope.launch { speaker.awaitIdle(); finish() }
                    },
                    keepPaused = { keepOthersPaused = true },
                )
            },
            speaker = speaker,
            locationSource = app.locationSource,
            auth = app.auth,
            keyguard = context.getSystemService(KeyguardManager::class.java),
            hasMicPermission = { context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
        )
        keepScreenOnWhileActive()
    }

    /**
     * Keeps the screen on while the conversation is active (listening, thinking, speaking, confirming)
     * and for one screen-timeout length after, so talking counts like touching: the screen goes off only
     * after the usual timeout with nothing happening. Apps can't reset the system's own timer
     * (`PowerManager.userActivity` is system-only), so the window flag stands in for it. A turn also
     * fails if the phone locks mid-way (plan §2), which this avoids.
     */
    private fun keepScreenOnWhileActive() {
        lifecycleScope.launch {
            controller.state
                .map { it.phase != PanelState.Phase.Idle || it.confirming != null }
                .distinctUntilChanged()
                .collectLatest { active ->
                    val dialogWindow = window.window ?: return@collectLatest
                    if (active) {
                        dialogWindow.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        delay(Settings.System.getLong(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, DEFAULT_TIMEOUT_MS))
                        dialogWindow.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
        }
    }

    override fun onCreateContentView(): View = ComposeView(context).apply {
        setViewTreeLifecycleOwner(this@AssistantSession)
        setViewTreeSavedStateRegistryOwner(this@AssistantSession)
        setContent {
            val state by controller.state.collectAsState()
            AssistantTheme {
                SessionPanel(
                    state = state,
                    onListen = controller::listen,
                    onAnswer = controller::answer,
                    onOpenSource = { url ->
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    },
                    onClose = { finish() },
                )
            }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.i(TAG, "Session.onShow flags=${describeShowFlags(showFlags)} args=${describeBundle(args)}")
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        controller.listen()
        audioManager.requestAudioFocus(sessionFocus)
    }

    override fun onHandleAssist(state: AssistState) {
        Log.i(
            TAG,
            "Session.onHandleAssist index=${state.index}/${state.count} " +
                "structure=${state.assistStructure?.activityComponent} " +
                "content=${state.assistContent != null} data=${describeBundle(state.assistData)}",
        )
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        Log.i(TAG, "Session.onHandleScreenshot ${screenshot?.let { "${it.width}x${it.height}" }}")
    }

    override fun onHide() {
        Log.i(TAG, "Session.onHide")
        // Dismissing the panel means "stop talking". A call's announcement has already finished by
        // the time the call is placed (the confirmation waits for it), and closePanel waits for
        // speech to finish, so neither is cut off by this.
        controller.stop()
        if (keepOthersPaused) {
            keepOthersPaused = false
            // Abandoning a transient request makes the player's focus return as a gain, and it resumes.
            // A permanent loss is the platform's "stopped by the user" signal, which players don't resume from.
            val permanent = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setOnAudioFocusChangeListener { }
                .build()
            audioManager.requestAudioFocus(permanent)
            audioManager.abandonAudioFocusRequest(permanent)
        }
        audioManager.abandonAudioFocusRequest(sessionFocus)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        super.onHide()
    }

    override fun onDestroy() {
        Log.i(TAG, "Session.onDestroy")
        speaker.shutdown()
        audioManager.abandonAudioFocusRequest(sessionFocus)
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
        val SHOW_FLAG_NAMES = mapOf(
            SHOW_WITH_ASSIST to "WITH_ASSIST",
            SHOW_WITH_SCREENSHOT to "WITH_SCREENSHOT",
            SHOW_SOURCE_ASSIST_GESTURE to "SOURCE_ASSIST_GESTURE",
            SHOW_SOURCE_APPLICATION to "SOURCE_APPLICATION",
            SHOW_SOURCE_ACTIVITY to "SOURCE_ACTIVITY",
            SHOW_SOURCE_PUSH_TO_TALK to "SOURCE_PUSH_TO_TALK",
            SHOW_SOURCE_NOTIFICATION to "SOURCE_NOTIFICATION",
            SHOW_SOURCE_AUTOMOTIVE_SYSTEM_UI to "SOURCE_AUTOMOTIVE_SYSTEM_UI",
        )

        fun describeShowFlags(flags: Int): String {
            val known = SHOW_FLAG_NAMES.filterKeys { flags and it != 0 }.values
            val unknown = flags and SHOW_FLAG_NAMES.keys.fold(0) { acc, bit -> acc or bit }.inv()
            return (known + if (unknown != 0) listOf("0x%x".format(unknown)) else emptyList())
                .joinToString("|").ifEmpty { "0" }
        }

        @Suppress("DEPRECATION")
        fun describeBundle(bundle: Bundle?): String =
            bundle?.keySet()?.joinToString(prefix = "{", postfix = "}") { "$it=${bundle.get(it)}" } ?: "null"
    }
}
