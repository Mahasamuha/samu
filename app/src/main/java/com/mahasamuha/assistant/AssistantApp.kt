package com.mahasamuha.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.net.toUri
import com.mahasamuha.assistant.auth.AuthManager
import com.mahasamuha.assistant.auth.TokenStore
import com.mahasamuha.assistant.calls.PlaceCallTool
import com.mahasamuha.assistant.capabilities.BundledCapabilities
import com.mahasamuha.assistant.capabilities.CachedIndex
import com.mahasamuha.assistant.capabilities.CapabilityCache
import com.mahasamuha.assistant.capabilities.CapabilityCatalog
import com.mahasamuha.assistant.capabilities.SessionCatalog
import com.mahasamuha.assistant.clock.SetAlarmTool
import com.mahasamuha.assistant.clock.SetTimerTool
import com.mahasamuha.assistant.contacts.ContactDirectory
import com.mahasamuha.assistant.contacts.FindContactTool
import com.mahasamuha.assistant.llm.ChatClient
import com.mahasamuha.assistant.llm.GatewayChatClient
import com.mahasamuha.assistant.music.MusicControlTool
import com.mahasamuha.assistant.music.PlayMusicTool
import com.mahasamuha.assistant.music.SpeakerDirectory
import com.mahasamuha.assistant.music.Symfonium
import com.mahasamuha.assistant.navigation.NavigateTool
import com.mahasamuha.assistant.navigation.SavedPlaces
import com.mahasamuha.assistant.remote.McpClient
import com.mahasamuha.assistant.remote.remoteTool
import com.mahasamuha.assistant.skills.BundledSkills
import com.mahasamuha.assistant.skills.mergeSkills
import com.mahasamuha.assistant.sms.SendSmsTool
import com.mahasamuha.assistant.sms.SmsSender
import com.mahasamuha.assistant.voice.LocationSource
import com.mahasamuha.assistant.voice.PlatformLocationSource
import com.mahasamuha.assistant.voice.SttPreference
import com.mahasamuha.assistant.voice.TAG
import com.mahasamuha.assistant.voice.VoicePreference
import com.mahasamuha.assistant.weather.OpenMeteoWeather
import com.mahasamuha.assistant.weather.WeatherTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Holds the app-wide singletons; constructed by hand (no DI framework at this size). */
class AssistantApp : Application() {
    val config by lazy { AppConfig.fromBuildConfig() }
    val gatewayPreference by lazy { GatewayPreference(this) }

    /** The gateways in effect (plan §5b): the URL saved on the setup screen. Throws [GatewayNotSetException] before one is. */
    val gateways: () -> List<Uri> = { gatewayUrls(gatewayPreference.url).map { it.toUri() } }
    val auth: AuthManager by lazy { AuthManager(this, config, TokenStore(this), onSignedOut = { capabilityCache.clear() }) }
    val chatClient: ChatClient by lazy { GatewayChatClient(gateways, auth) }
    val locationSource: LocationSource by lazy { PlatformLocationSource(this) }
    val savedPlaces by lazy { SavedPlaces(this) }
    val voicePreference by lazy { VoicePreference(this) }
    val sttPreference by lazy { SttPreference(this) }

    private val weatherTool by lazy { WeatherTool(OpenMeteoWeather()::forecast, locationSource) }
    private val contacts by lazy { ContactDirectory(contentResolver, resources) }
    private val smsSender by lazy { SmsSender(this) }
    private val mcpClient by lazy { McpClient(gateways, auth) }
    val bundledCapabilities by lazy { BundledCapabilities(assets) }
    val capabilityCache: CapabilityCache by lazy { CapabilityCache(noBackupFilesDir, auth, mcpClient) }
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val staleRefreshRunning = AtomicBoolean(false)
    private val symfonium by lazy { Symfonium(this) }
    private val speakerDirectory by lazy { SpeakerDirectory(this) }

    /**
     * The capabilities and tools for one session (plan §4b, phase 3.5), resolved at the first model
     * request. The index is the fresh one if [indexRefresh] (started when the panel opened) has already
     * completed, else the cached one, else only the bundled manifest; it never waits. A local tool is
     * available when its permission is granted. [launch] starts an activity from the session, which must
     * happen while it's visible; [closePanel] dismisses the session (after a call is placed).
     */
    suspend fun sessionCatalog(
        indexRefresh: Deferred<Result<CachedIndex>>,
        launch: (Intent) -> Unit,
        closePanel: () -> Unit,
        keepPaused: () -> Unit,
    ): SessionCatalog {
        val fresh = if (indexRefresh.isCompleted) indexRefresh.await().getOrNull() else null
        val index = fresh ?: capabilityCache.current()
        val source =
            when {
                fresh != null -> "fresh"
                index != null -> "cache (${index.ageMillis() / 60_000} min old)"
                else -> "bundled"
            }
        val contactsAllowed = granted(Manifest.permission.READ_CONTACTS)
        val telecom = getSystemService(TelecomManager::class.java)
        // Every local tool is built, with whether its permission is granted, so a local tool's name stays
        // reserved even in a session that leaves the tool out.
        val candidates =
            listOf(
                weatherTool to true,
                FindContactTool(contacts) to contactsAllowed,
                SendSmsTool(smsSender::send, contacts.takeIf { contactsAllowed }) to granted(Manifest.permission.SEND_SMS),
                PlaceCallTool(
                    contacts.takeIf { contactsAllowed },
                    launch,
                    closePanel,
                    isEmergencyNumber = getSystemService(TelephonyManager::class.java)::isEmergencyNumber,
                    defaultDialerPackage = { telecom.defaultDialerPackage },
                    placeCall = { number -> placeCall(telecom, Uri.fromParts("tel", number, null)) },
                ) to granted(Manifest.permission.CALL_PHONE),
                NavigateTool(this, savedPlaces, contacts.takeIf { contactsAllowed }, launch, closePanel) to true,
                // SET_ALARM is a normal permission, granted at install.
                SetTimerTool(this, launch, closePanel) to true,
                SetAlarmTool(this, launch, closePanel) to true,
                // Symfonium takes plain broadcasts and the speaker scan needs only network state: no runtime permission.
                PlayMusicTool(symfonium, speakerDirectory, mcpClient, closePanel) to true,
                MusicControlTool(this, symfonium, speakerDirectory, closePanel, keepPaused) to true,
            )
        // A server skill with the same name as a bundled one overrides it (plan §4b).
        val skills = mergeSkills(this.skills, index?.skills.orEmpty())
        return SessionCatalog.build(
            candidates = candidates,
            merged = CapabilityCatalog.merge(bundledCapabilities.capabilities, index),
            skills = skills,
            remoteTool = { definition -> remoteTool(definition, mcpClient::callTool, ::refreshIndexSoon) },
            source = source,
        )
    }

    /**
     * Refreshes the capability index for a session that's opening. Runs in the app's scope, so closing the
     * panel early doesn't cancel it: a refresh that lands too late still serves the next session.
     */
    fun startIndexRefresh(): Deferred<Result<CachedIndex>> = appScope.async { capabilityCache.refresh() }

    /** A remote tool the server no longer has means the cached index is stale: refresh it for the next session. */
    private fun refreshIndexSoon() {
        if (!staleRefreshRunning.compareAndSet(false, true)) return
        appScope.launch {
            try {
                capabilityCache.refresh()
            } finally {
                staleRefreshRunning.set(false)
            }
        }
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    val skills by lazy { BundledSkills(assets).load() }

    // Only registered while CALL_PHONE is granted (sessionCatalog).
    @SuppressLint("MissingPermission")
    private fun placeCall(
        telecom: TelecomManager,
        uri: Uri,
    ) = telecom.placeCall(uri, Bundle())
}
