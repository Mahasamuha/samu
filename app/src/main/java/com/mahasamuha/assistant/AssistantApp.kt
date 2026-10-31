package com.mahasamuha.assistant

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import com.mahasamuha.assistant.auth.AuthManager
import com.mahasamuha.assistant.auth.TokenStore
import com.mahasamuha.assistant.calls.PlaceCallTool
import com.mahasamuha.assistant.capabilities.BundledCapabilities
import com.mahasamuha.assistant.capabilities.CapabilityCache
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
import com.mahasamuha.assistant.remote.FallbackTool
import com.mahasamuha.assistant.remote.McpClient
import com.mahasamuha.assistant.remote.RemoteTools
import com.mahasamuha.assistant.skills.BundledSkills
import com.mahasamuha.assistant.sms.SendSmsTool
import com.mahasamuha.assistant.sms.SmsSender
import com.mahasamuha.assistant.tools.ReadOnlyTool
import com.mahasamuha.assistant.tools.ToolRegistry
import com.mahasamuha.assistant.voice.TAG
import com.mahasamuha.assistant.voice.LocationSource
import com.mahasamuha.assistant.voice.PlatformLocationSource
import com.mahasamuha.assistant.voice.VoicePreference
import com.mahasamuha.assistant.weather.OpenMeteoWeather
import com.mahasamuha.assistant.weather.WeatherTool

/** Holds the app-wide singletons; constructed by hand (no DI framework at this size). */
class AssistantApp : Application() {
    val config by lazy { AppConfig.fromBuildConfig() }
    val auth: AuthManager by lazy { AuthManager(this, config, TokenStore(this), onSignedOut = { capabilityCache.clear() }) }
    val chatClient: ChatClient by lazy { GatewayChatClient(config.gatewayUrls, auth) }
    val locationSource: LocationSource by lazy { PlatformLocationSource(this) }
    val savedPlaces by lazy { SavedPlaces(this) }
    val voicePreference by lazy { VoicePreference(this) }

    private val weatherTool by lazy { WeatherTool(OpenMeteoWeather(), locationSource) }
    private val contacts by lazy { ContactDirectory(contentResolver, resources) }
    private val smsSender by lazy { SmsSender(this) }
    private val mcpClient by lazy { McpClient(config.gatewayUrls, auth) }
    val bundledCapabilities by lazy { BundledCapabilities(assets) }
    val capabilityCache: CapabilityCache by lazy { CapabilityCache(noBackupFilesDir, auth, mcpClient) }
    private val remoteTools by lazy { RemoteTools(mcpClient) }
    private val symfonium by lazy { Symfonium(this) }
    private val speakerDirectory by lazy { SpeakerDirectory(this) }

    /**
     * Tools for one session: those whose permissions are granted right now, so a skill that needs a
     * missing one is left out (plan §4b). A remote tool with the same name replaces a local one only if
     * the local one opts in with [allowRemote][com.mahasamuha.assistant.tools.Tool.allowRemote].
     * [launch] starts an activity from the session, which must happen while it's visible; [closePanel]
     * dismisses the session (after a call is placed). Remote tools come from the MCP server (plan §4b).
     */
    suspend fun sessionTools(launch: (Intent) -> Unit, closePanel: () -> Unit, keepPaused: () -> Unit): ToolRegistry {
        val contactsAllowed = granted(Manifest.permission.READ_CONTACTS)
        // Every local tool is built, with whether its permission is granted, so a local tool's name stays
        // reserved even in a session that leaves the tool out.
        val candidates = listOf(
            weatherTool to true,
            FindContactTool(contacts) to contactsAllowed,
            SendSmsTool(smsSender, contacts.takeIf { contactsAllowed }) to granted(Manifest.permission.SEND_SMS),
            PlaceCallTool(
                contacts.takeIf { contactsAllowed },
                getSystemService(TelephonyManager::class.java),
                getSystemService(TelecomManager::class.java),
                launch,
                closePanel,
            ) to granted(Manifest.permission.CALL_PHONE),
            NavigateTool(this, savedPlaces, contacts.takeIf { contactsAllowed }, launch, closePanel) to true,
            // SET_ALARM is a normal permission, granted at install.
            SetTimerTool(this, launch, closePanel) to true,
            SetAlarmTool(this, launch, closePanel) to true,
            // Symfonium takes plain broadcasts and the speaker scan needs only network state: no runtime permission.
            PlayMusicTool(symfonium, speakerDirectory, mcpClient, closePanel) to true,
            MusicControlTool(this, symfonium, speakerDirectory, closePanel, keepPaused) to true,
        )
        val local = candidates.filter { (_, allowed) -> allowed }.map { (tool, _) -> tool }
        val reserved = candidates.map { (tool, _) -> tool }.filterNot { it.allowRemote }.map { it.definition.name }.toSet()

        // A remote tool replaces a local one of the same name; a read-only local one stays behind it as the fallback.
        val localByName = local.associateBy { it.definition.name }
        // Remote tools that a local tool calls itself never reach the model.
        val privateNames = candidates.flatMap { (tool, _) -> tool.privateRemoteTools }.toSet()
        val loaded = remoteTools.load()
        val remote = loaded
            .filter { it.definition.name !in privateNames }
            .also { Log.i(TAG, "Kept ${loaded.size - it.size} remote tools private") }
            .filter { tool ->
                (tool.definition.name !in reserved).also { kept ->
                    if (!kept) Log.w(TAG, "Ignoring remote ${tool.definition.name}: the local one doesn't allow a remote override")
                }
            }
            .map { tool ->
                val shadowed = localByName[tool.definition.name]
                if (tool is ReadOnlyTool && shadowed is ReadOnlyTool) FallbackTool(tool, shadowed) else tool
            }
        val remoteNames = remote.map { it.definition.name }.toSet()
        return ToolRegistry(local.filter { it.definition.name !in remoteNames } + remote)
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    val skills by lazy { BundledSkills(assets).load() }
}
