package com.mahasamuha.assistant

import android.Manifest
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.mahasamuha.assistant.auth.NotSignedInException
import com.mahasamuha.assistant.ui.AppTheme
import com.mahasamuha.assistant.ui.AppType
import com.mahasamuha.assistant.ui.AssistantTheme
import com.mahasamuha.assistant.ui.GhostButton
import com.mahasamuha.assistant.ui.Hairline
import com.mahasamuha.assistant.ui.Mark
import com.mahasamuha.assistant.ui.MonoLabel
import com.mahasamuha.assistant.ui.PrimaryButton
import com.mahasamuha.assistant.ui.SecondaryButton
import com.mahasamuha.assistant.ui.StatusDot
import com.mahasamuha.assistant.voice.PlatformSpeaker
import com.mahasamuha.assistant.voice.TAG
import com.mahasamuha.assistant.voice.VoiceOption
import kotlinx.coroutines.launch

/** Explains setup, links to the assistant settings screen, and handles sign-in. */
class MainActivity : ComponentActivity() {
    private val app get() = application as AssistantApp

    private var roleHeld by mutableStateOf(false)
    private var micAllowed by mutableStateOf(false)
    private var locationAllowed by mutableStateOf(false)
    private var contactsAllowed by mutableStateOf(false)
    private var smsAllowed by mutableStateOf(false)
    private var callsAllowed by mutableStateOf(false)
    private var localNetworkAllowed by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)
    private var home by mutableStateOf("")
    private var voices by mutableStateOf<List<VoiceOption>>(emptyList())
    private var voice by mutableStateOf<String?>(null)
    private var pipedAudio by mutableStateOf(false)

    // Plays the samples; the activity is visible, so its own speech is allowed.
    private lateinit var speaker: PlatformSpeaker
    private var work by mutableStateOf("")
    private var gatewayInput by mutableStateOf("")
    private var gatewayError by mutableStateOf<String?>(null)
    private var gatewayInUse by mutableStateOf<String?>(null)
    private var capabilityStatus by mutableStateOf("")
    private var refreshingCapabilities by mutableStateOf(false)

    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            micAllowed = granted
        }

    private val requestLocation =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            locationAllowed = granted
        }

    private val requestContacts =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            contactsAllowed = granted
        }

    private val requestSms =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            smsAllowed = granted
        }

    private val requestCalls =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            callsAllowed = granted
        }

    private val requestLocalNetwork =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            localNetworkAllowed = granted
        }

    private val signIn =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            runReporting("Sign-in") {
                app.auth.completeSignIn(result.data)
                "Signed in (token key: ${app.auth.keySecurityLevel()})"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        home = app.savedPlaces.home
        work = app.savedPlaces.work
        voice = app.voicePreference.name
        pipedAudio = app.sttPreference.pipedAudio
        gatewayInput = app.gatewayPreference.url.orEmpty()
        showGatewayInUse()
        speaker = PlatformSpeaker(this)
        runReporting("Voices") {
            voices = speaker.voices()
            null
        }
        runReporting("Capabilities") {
            capabilityStatus = describeCapabilityCache()
            null
        }
        setContent {
            val signedIn by app.auth.signedIn.collectAsState()
            AssistantTheme {
                SetupScreen(
                    version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(),
                    roleHeld = roleHeld,
                    signedIn = signedIn,
                    micAllowed = micAllowed,
                    locationAllowed = locationAllowed,
                    contactsAllowed = contactsAllowed,
                    smsAllowed = smsAllowed,
                    callsAllowed = callsAllowed,
                    localNetworkAllowed = localNetworkAllowed,
                    message = message,
                    home = home,
                    work = work,
                    onHomeChange = {
                        home = it
                        app.savedPlaces.home = it
                    },
                    onWorkChange = {
                        work = it
                        app.savedPlaces.work = it
                    },
                    gatewayInput = gatewayInput,
                    gatewayError = gatewayError,
                    gatewayInUse = gatewayInUse,
                    onGatewayInputChange = {
                        gatewayInput = it
                        gatewayError = null
                    },
                    onSaveGateway = ::saveGateway,
                    voices = voices,
                    voice = voice,
                    onPickVoice = ::pickVoice,
                    pipedAudio = pipedAudio,
                    onPipedAudioChange = {
                        pipedAudio = it
                        app.sttPreference.pipedAudio = it
                    },
                    onOpenAssistantSettings = { open(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) },
                    onSignIn = {
                        runReporting("Sign-in") {
                            signIn.launch(app.auth.signInIntent())
                            null
                        }
                    },
                    onSignOut = {
                        runReporting("Sign-out") {
                            app.auth.signOut()
                            capabilityStatus = describeCapabilityCache()
                            "Signed out"
                        }
                    },
                    capabilityStatus = capabilityStatus,
                    refreshingCapabilities = refreshingCapabilities,
                    onRefreshCapabilities = ::refreshCapabilities,
                    onAllowMic = { requestMic.launch(Manifest.permission.RECORD_AUDIO) },
                    onAllowLocation = { requestLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
                    onAllowContacts = { requestContacts.launch(Manifest.permission.READ_CONTACTS) },
                    onAllowSms = { requestSms.launch(Manifest.permission.SEND_SMS) },
                    onAllowCalls = { requestCalls.launch(Manifest.permission.CALL_PHONE) },
                    onAllowLocalNetwork = { requestLocalNetwork.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        roleHeld = getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
        micAllowed = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        locationAllowed = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        contactsAllowed = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        smsAllowed = checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        callsAllowed = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        localNetworkAllowed = checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        speaker.shutdown()
        super.onDestroy()
    }

    private fun showGatewayInUse() {
        gatewayInUse = app.gatewayPreference.url
    }

    /**
     * Saves the typed gateway URL, which is required. A different backend signs the user out, since the
     * session belongs to the old one; the same URL changes nothing.
     */
    private fun saveGateway() {
        val newUrl =
            try {
                parseGatewayUrl(gatewayInput)
            } catch (e: IllegalArgumentException) {
                gatewayError = e.message
                return
            }
        gatewayInput = newUrl
        val saved = app.gatewayPreference.url
        if (!changesBackend(saved, newUrl)) {
            message = "Gateway unchanged."
            return
        }
        runReporting("Gateway") {
            app.gatewayPreference.url = newUrl
            Log.i(TAG, "Gateway ${if (saved == null) "set" else "switched"}: $newUrl")
            // A phone upgraded from a build-time gateway can be signed in with no URL saved yet.
            val wasSignedIn = app.auth.signedIn.value
            app.auth.signOut()
            showGatewayInUse()
            capabilityStatus = describeCapabilityCache()
            if (wasSignedIn) "Gateway set. You're signed out; sign in again." else "Gateway saved."
        }
    }

    /** Saves [name] (null: the engine default) for the next session and plays a sample in it. */
    private fun pickVoice(name: String?) {
        voice = name
        app.voicePreference.name = name
        runReporting("Voice") {
            speaker.stop()
            speaker.setVoice(name)
            speaker.speak(VOICE_SAMPLE)
            null
        }
    }

    /** Fetches the capability index now and shows the outcome. */
    private fun refreshCapabilities() {
        if (refreshingCapabilities) return
        refreshingCapabilities = true
        runReporting("Capabilities") {
            try {
                val result = app.capabilityCache.refresh()
                capabilityStatus = describeCapabilityCache()
                result.fold(
                    onSuccess = { "Capabilities updated (${it.capabilities.size} from the server)" },
                    onFailure = { "Refresh failed: ${it.message ?: it.javaClass.simpleName}" },
                )
            } finally {
                refreshingCapabilities = false
            }
        }
    }

    private suspend fun describeCapabilityCache(): String {
        val index =
            app.capabilityCache.current()
                ?: return "Not fetched yet. Using the built-in capabilities."
        val minutes = index.ageMillis() / 60_000
        val age =
            when {
                minutes < 1 -> "just now"
                minutes < 60 -> "$minutes min ago"
                minutes < 24 * 60 -> "${minutes / 60} h ago"
                else -> "${minutes / (24 * 60)} d ago"
            }
        return "Cached index, updated $age (${index.capabilities.size} remote or hybrid capabilities)."
    }

    /** UI boundary: runs [block] and shows its result, or the error, as the status message. */
    private fun runReporting(
        label: String,
        block: suspend () -> String?,
    ) {
        lifecycleScope.launch {
            try {
                block()?.let { message = it }
            } catch (e: NotSignedInException) {
                message = "$label: not signed in"
            } catch (e: Exception) {
                Log.w(TAG, "$label failed", e)
                message = "$label failed: ${e.message}"
            }
        }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No activity for ${intent.action}", e)
            message = "This device has no screen for ${intent.action}"
        }
    }
}

/** The next thing to set up gets the one primary button; everything else is a ghost button. */
private enum class Step { Gateway, Role, SignIn, Mic, Location, Contacts, Sms, Calls, LocalNetwork, None }

@Composable
private fun SetupScreen(
    version: String,
    roleHeld: Boolean,
    signedIn: Boolean,
    micAllowed: Boolean,
    locationAllowed: Boolean,
    contactsAllowed: Boolean,
    smsAllowed: Boolean,
    callsAllowed: Boolean,
    localNetworkAllowed: Boolean,
    message: String?,
    home: String,
    work: String,
    gatewayInput: String,
    gatewayError: String?,
    gatewayInUse: String?,
    onGatewayInputChange: (String) -> Unit,
    onSaveGateway: () -> Unit,
    onHomeChange: (String) -> Unit,
    onWorkChange: (String) -> Unit,
    voices: List<VoiceOption>,
    voice: String?,
    onPickVoice: (String?) -> Unit,
    pipedAudio: Boolean,
    onPipedAudioChange: (Boolean) -> Unit,
    onOpenAssistantSettings: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    capabilityStatus: String,
    refreshingCapabilities: Boolean,
    onRefreshCapabilities: () -> Unit,
    onAllowMic: () -> Unit,
    onAllowLocation: () -> Unit,
    onAllowContacts: () -> Unit,
    onAllowSms: () -> Unit,
    onAllowCalls: () -> Unit,
    onAllowLocalNetwork: () -> Unit,
) {
    val colors = AppTheme.colors
    val next =
        when {
            gatewayInUse == null -> Step.Gateway
            !roleHeld -> Step.Role
            !signedIn -> Step.SignIn
            !micAllowed -> Step.Mic
            !locationAllowed -> Step.Location
            !contactsAllowed -> Step.Contacts
            !smsAllowed -> Step.Sms
            !callsAllowed -> Step.Calls
            !localNetworkAllowed -> Step.LocalNetwork
            else -> Step.None
        }

    // The next missing step gets the one filled button; the rest are outlined so they still read as buttons.
    @Composable
    fun Action(
        step: Step,
        label: String,
        onClick: () -> Unit,
    ) = if (step == next) PrimaryButton(label, onClick) else SecondaryButton(label, onClick)

    @Composable
    fun Status(
        done: Boolean,
        text: String,
    ) = Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(done)
        Text(text, style = AppType.body, color = colors.text)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = colors.surface) {
        Column(
            modifier =
                Modifier
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // The byline wherever the app describes itself; the app's name alone is "Samu".
                    MonoLabel("samu by mahasamuha")
                    Text("Setup", style = AppType.h3, color = colors.text)
                    Text("v$version", style = AppType.eyebrow, color = colors.textMuted)
                }
                Mark(size = 40.dp)
            }

            Hairline()
            MonoLabel("gateway")
            Text(
                if (gatewayInUse == null) {
                    "Required first: enter the https:// address of the Samu gateway. Samu can't sign in or answer without it."
                } else {
                    "In use: $gatewayInUse. Changing it signs you out. The token is sent to whatever address you enter."
                },
                style = AppType.body,
                color = colors.textBody,
            )
            OutlinedTextField(
                value = gatewayInput,
                onValueChange = onGatewayInputChange,
                label = { Text("gateway URL") },
                placeholder = { Text("https://<gateway-host>") },
                isError = gatewayError != null,
                supportingText = gatewayError?.let { { Text(it) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                textStyle = AppType.body,
                modifier = Modifier.fillMaxWidth(),
            )
            Action(Step.Gateway, "save gateway", onSaveGateway)

            Hairline()
            MonoLabel("digital assistant")
            Status(roleHeld, if (roleHeld) "This app is the default digital assistant." else "Not the default digital assistant yet.")
            Text(
                "1. Open the digital assistant settings and choose this app.\n" +
                    "2. In Settings → System → Gestures → Press & hold power button, choose Digital assistant.\n" +
                    "3. Long-press the power button to open the panel.",
                style = AppType.body,
                color = colors.textBody,
            )
            Action(Step.Role, "open assistant settings ↗", onOpenAssistantSettings)

            Hairline()
            MonoLabel("account")
            Status(signedIn, if (signedIn) "Signed in." else "Not signed in.")
            when {
                signedIn -> GhostButton("sign out", onSignOut)
                gatewayInUse == null -> Text("Set the gateway first.", style = AppType.caption, color = colors.textMuted)
                else -> Action(Step.SignIn, "sign in →", onSignIn)
            }

            Hairline()
            MonoLabel("capabilities")
            Text(
                "What Samu can do beyond the built-in tools comes from the server, and is kept on this phone. " +
                    "Refresh it here after changing the server.",
                style = AppType.body,
                color = colors.textBody,
            )
            Text(capabilityStatus, style = AppType.caption, color = colors.textMuted)
            if (signedIn) {
                GhostButton(if (refreshingCapabilities) "refreshing…" else "refresh capabilities", onRefreshCapabilities)
            }

            Hairline()
            MonoLabel("permissions")
            Status(micAllowed, if (micAllowed) "Microphone allowed." else "The assistant needs the microphone.")
            if (!micAllowed) Action(Step.Mic, "allow microphone", onAllowMic)
            Status(
                locationAllowed,
                if (locationAllowed) {
                    "Approximate location allowed (city level, for local answers)."
                } else {
                    "Optional: approximate location, so questions like the weather know your city."
                },
            )
            if (!locationAllowed) Action(Step.Location, "allow approximate location", onAllowLocation)
            Status(
                contactsAllowed,
                if (contactsAllowed) {
                    "Contacts allowed (only matched names and numbers leave the phone)."
                } else {
                    "Optional: contacts, so you can text or call people by name."
                },
            )
            if (!contactsAllowed) Action(Step.Contacts, "allow contacts", onAllowContacts)
            Status(
                smsAllowed,
                if (smsAllowed) {
                    "Texting allowed. Every text is read back for your confirmation first."
                } else {
                    "Optional: sending texts, always after you confirm."
                },
            )
            if (!smsAllowed) Action(Step.Sms, "allow texting", onAllowSms)
            Status(
                callsAllowed,
                if (callsAllowed) {
                    "Calling allowed. Calls are announced, or confirmed first when the contact isn't certain or after a search."
                } else {
                    "Optional: placing calls."
                },
            )
            if (!callsAllowed) Action(Step.Calls, "allow calling", onAllowCalls)
            Status(
                localNetworkAllowed,
                if (localNetworkAllowed) {
                    "Speakers allowed (finds Sonos and Cast speakers on home Wi-Fi)."
                } else {
                    "Optional: local network, to find Sonos and Cast speakers on home Wi-Fi to play music on."
                },
            )
            if (!localNetworkAllowed) Action(Step.LocalNetwork, "allow speakers", onAllowLocalNetwork)

            Hairline()
            MonoLabel("places")
            Text(
                "For \"navigate home\" and \"navigate to work\". They stay on this phone: Samu says \"home\" or " +
                    "\"work\", and only the maps app gets the address.",
                style = AppType.body,
                color = colors.textBody,
            )
            OutlinedTextField(
                value = home,
                onValueChange = onHomeChange,
                label = { Text("home address") },
                singleLine = true,
                textStyle = AppType.body,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = work,
                onValueChange = onWorkChange,
                label = { Text("work address") },
                singleLine = true,
                textStyle = AppType.body,
                modifier = Modifier.fillMaxWidth(),
            )

            Hairline()
            MonoLabel("voice")
            Text(
                "Tap a voice to hear it; Samu uses it from the next time you open the panel. Only voices that run " +
                    "on the phone are listed.",
                style = AppType.body,
                color = colors.textBody,
            )

            @Composable
            fun VoiceRow(
                name: String?,
                label: String,
            ) = Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { onPickVoice(name) },
            ) {
                StatusDot(name == voice)
                Column {
                    Text(label, style = AppType.body, color = colors.text)
                    name?.let { Text(it, style = AppType.eyebrow, color = colors.textMuted) }
                }
            }
            VoiceRow(null, "Engine default")
            voices.forEach { VoiceRow(it.name, it.label) }
            if (BuildConfig.DEBUG) {
                Hairline()
                MonoLabel("experiment")
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { onPipedAudioChange(!pipedAudio) },
                ) {
                    StatusDot(pipedAudio)
                    Text("Experimental: feed recognizer our own audio", style = AppType.body, color = colors.text)
                }
            }
            message?.let { Text(it, style = AppType.caption, color = colors.textMuted) }
        }
    }
}

private const val VOICE_SAMPLE = "Hi, I'm Samu. Calling Sam Lee, mobile."
