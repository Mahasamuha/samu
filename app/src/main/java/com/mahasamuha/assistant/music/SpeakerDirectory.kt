package com.mahasamuha.assistant.music

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.mahasamuha.assistant.voice.TAG
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A speaker Symfonium can play to: [type] and [identifier] are its renderer type and ID for SELECT_RENDERER.
 * [aliases] are other names that also select it (the other members of a Sonos group).
 */
data class CastSpeaker(
    val name: String,
    val type: Int,
    val identifier: String,
    val aliases: List<String> = emptyList(),
)

/**
 * Finds the cast speakers on the current Wi-Fi network (Sonos groups and Google Cast devices) with
 * the identifiers Symfonium's API needs to select them as output. Needs ACCESS_NETWORK_STATE.
 *
 * Nothing about the speakers (names, identifiers, addresses) is logged.
 */
class SpeakerDirectory(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val nsd = appContext.getSystemService(NsdManager::class.java)

    /** A resolved mDNS service: first IPv4 address (if any) and its TXT records as strings. */
    private class Resolved(
        val ip: String?,
        val txt: Map<String, String>,
    )

    /**
     * Speakers on the current Wi-Fi network, sorted by name.
     *
     * @throws IllegalStateException when the local network permission is missing or the phone is not on Wi-Fi.
     */
    suspend fun speakers(): List<CastSpeaker> {
        // Without it, NsdManager shows a system device picker instead of discovering.
        check(appContext.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED) {
            "Speakers need the local network permission. The user can allow it in the Samu app."
        }
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        check(caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) { "Speakers can only be reached on home Wi-Fi" }

        val found = withTimeoutOrNull(OVERALL_TIMEOUT_MS) { discover() }.orEmpty()
        val sonosPlayers = found.filter { it.first == SONOS_SERVICE }.mapNotNull { it.second }
        val casts = found.filter { it.first == CAST_SERVICE }.mapNotNull { it.second }

        val result = mutableListOf<CastSpeaker>()

        val castSpeakers =
            casts
                .mapNotNull { r ->
                    val id = r.txt["id"]?.takeIf { it.isNotBlank() }
                    val name = r.txt["fn"]?.takeIf { it.isNotBlank() }
                    if (id != null && name != null) CastSpeaker(name, CHROMECAST, id) else null
                }.distinctBy { it.identifier }
        result += castSpeakers

        val sonosIp = sonosPlayers.firstNotNullOfOrNull { it.ip }
        val sonosGroups =
            if (sonosIp == null) {
                emptyList()
            } else {
                try {
                    sonosGroups(sonosIp)
                } catch (e: Exception) {
                    Log.i(TAG, "Sonos group lookup failed: ${e.javaClass.simpleName}")
                    emptyList()
                }
            }
        result += sonosGroups

        Log.i(TAG, "Speakers: ${sonosGroups.size} Sonos groups, ${castSpeakers.size} Cast devices")
        return result.sortedBy { it.name.lowercase() }
    }

    /** Browses both service types for a short window, then resolves what was found. Pairs are (service type, resolved info). */
    private suspend fun discover(): List<Pair<String, Resolved?>> =
        coroutineScope {
            val sonos = async { browse(SONOS_SERVICE) }
            val cast = async { browse(CAST_SERVICE) }
            val services = sonos.await().map { SONOS_SERVICE to it } + cast.await().map { CAST_SERVICE to it }
            services.map { (type, info) -> async { type to withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { resolve(info) } } }.awaitAll()
        }

    /** Services of [type] seen during the discovery window. */
    private suspend fun browse(type: String): List<NsdServiceInfo> {
        val services = java.util.Collections.synchronizedList(mutableListOf<NsdServiceInfo>())
        val listener =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    services += serviceInfo
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}

                override fun onDiscoveryStopped(serviceType: String) {}

                override fun onStartDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) {
                    Log.i(TAG, "Discovery failed: $errorCode")
                }

                override fun onStopDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) {}
            }
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        try {
            delay(DISCOVERY_WINDOW_MS)
        } finally {
            try {
                nsd.stopServiceDiscovery(listener)
            } catch (_: IllegalArgumentException) {
                // discovery never started
            }
        }
        return synchronized(services) { services.toList() }
    }

    /** Resolves [service] to its addresses and TXT records via the API 34+ service-info callback. */
    private suspend fun resolve(service: NsdServiceInfo): Resolved? {
        val result = CompletableDeferred<Resolved?>()
        val callback =
            object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    result.complete(null)
                }

                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                    val ip =
                        serviceInfo.hostAddresses
                            .filterIsInstance<Inet4Address>()
                            .firstOrNull()
                            ?.hostAddress
                    val txt = serviceInfo.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8).orEmpty() }
                    result.complete(Resolved(ip, txt))
                }

                override fun onServiceLost() {
                    result.complete(null)
                }

                override fun onServiceInfoCallbackUnregistered() {}
            }
        nsd.registerServiceInfoCallback(service, appContext.mainExecutor, callback)
        try {
            return result.await()
        } finally {
            unregister(callback)
        }
    }

    private fun unregister(callback: NsdManager.ServiceInfoCallback) {
        try {
            nsd.unregisterServiceInfoCallback(callback)
        } catch (_: IllegalArgumentException) {
            // already unregistered
        }
    }

    /**
     * Sonos groups from the ZoneGroupTopology service of any one player, as Symfonium UPnP renderers
     * (identifier is the group coordinator's UUID plus "_MR").
     *
     * The request goes over a raw [Socket] rather than HttpURLConnection because Android's default
     * network security policy blocks cleartext HTTP to ordinary hosts for library HTTP stacks. The
     * policy does not govern raw sockets, and this is one fixed request to a LAN device, so a
     * minimal hand-written HTTP/1.1 exchange is simpler than weakening the app's policy.
     */
    private suspend fun sonosGroups(ip: String): List<CastSpeaker> =
        withContext(Dispatchers.IO) {
            val body =
                """<?xml version="1.0"?>""" +
                    """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """ +
                    """s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">""" +
                    """<s:Body><u:GetZoneGroupState xmlns:u="urn:schemas-upnp-org:service:ZoneGroupTopology:1"/></s:Body>""" +
                    """</s:Envelope>"""
            val payload = body.toByteArray(Charsets.UTF_8)
            val request =
                "POST /ZoneGroupTopology/Control HTTP/1.1\r\n" +
                    "Host: $ip:1400\r\n" +
                    "Content-Type: text/xml; charset=\"utf-8\"\r\n" +
                    "SOAPACTION: \"urn:schemas-upnp-org:service:ZoneGroupTopology:1#GetZoneGroupState\"\r\n" +
                    "Content-Length: ${payload.size}\r\n" +
                    "Connection: close\r\n\r\n"
            val raw =
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(ip, 1400), 2_000)
                    socket.soTimeout = 3_000
                    socket.getOutputStream().apply {
                        write(request.toByteArray(Charsets.ISO_8859_1))
                        write(payload)
                        flush()
                    }
                    socket.getInputStream().readAllBytesCompat()
                }
            parseZoneGroups(httpBody(raw))
        }

    private fun InputStream.readAllBytesCompat(): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        /** The body of a raw HTTP response, de-chunked when needed. Throws on a non-200 status. */
        internal fun httpBody(raw: ByteArray): String {
            val separator = byteArrayOf(13, 10, 13, 10)
            val split = indexOf(raw, separator)
            check(split >= 0) { "Malformed speaker response" }
            val headers = String(raw, 0, split, Charsets.ISO_8859_1)
            val status =
                headers
                    .lineSequence()
                    .first()
                    .split(' ')
                    .getOrNull(1)
            check(status == "200") { "Speaker request failed: HTTP $status" }
            var body = raw.copyOfRange(split + 4, raw.size)
            if (headers.contains("transfer-encoding: chunked", ignoreCase = true)) body = dechunk(body)
            return String(body, Charsets.UTF_8)
        }

        private fun dechunk(data: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            var pos = 0
            while (pos < data.size) {
                val lineEnd = indexOf(data, byteArrayOf(13, 10), pos)
                if (lineEnd < 0) break
                val size = String(data, pos, lineEnd - pos, Charsets.ISO_8859_1).substringBefore(';').trim().toIntOrNull(16) ?: break
                if (size == 0) break
                val start = lineEnd + 2
                val end = minOf(start + size, data.size)
                out.write(data, start, end - start)
                pos = end + 2
            }
            return out.toByteArray()
        }

        private fun indexOf(
            data: ByteArray,
            pattern: ByteArray,
            from: Int = 0,
        ): Int {
            for (i in from..data.size - pattern.size) {
                if (pattern.indices.all { data[i + it] == pattern[it] }) return i
            }
            return -1
        }

        /** One CastSpeaker per Sonos group, skipping invisible members (bonded satellites, etc.). */
        internal fun parseZoneGroups(soapBody: String): List<CastSpeaker> {
            val state = unescape(soapBody.substringAfter("<ZoneGroupState>", "").substringBefore("</ZoneGroupState>"))
            check(state.isNotEmpty()) { "Speaker response had no zone groups" }
            return GROUP_REGEX
                .findAll(state)
                .mapNotNull { group ->
                    val coordinator = attributes(group.groupValues[1])["Coordinator"] ?: return@mapNotNull null
                    val members =
                        MEMBER_REGEX
                            .findAll(group.groupValues[2])
                            .map { attributes(it.groupValues[1]) }
                            .filter { it["Invisible"] != "1" && !it["ZoneName"].isNullOrBlank() }
                            .toList()
                    // Named as the Sonos app does: by the coordinator; the other members are aliases.
                    val lead = members.firstOrNull { it["UUID"] == coordinator } ?: members.firstOrNull() ?: return@mapNotNull null
                    val name = lead.getValue("ZoneName")
                    val aliases =
                        members
                            .map { it.getValue("ZoneName") }
                            .distinct()
                            .filter { it != name }
                            .sorted()
                    CastSpeaker(name, UPNP, "${coordinator}_MR", aliases)
                }.toList()
        }

        private fun attributes(tag: String): Map<String, String> =
            ATTRIBUTE_REGEX.findAll(tag).associate { it.groupValues[1] to unescape(it.groupValues[2]) }

        private fun unescape(s: String) =
            s
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&")

        /** Symfonium renderer type for UPnP (Sonos). */
        const val UPNP = 1

        /** Symfonium renderer type for Chromecast. */
        const val CHROMECAST = 3

        private const val SONOS_SERVICE = "_sonos._tcp"
        private const val CAST_SERVICE = "_googlecast._tcp"
        private const val DISCOVERY_WINDOW_MS = 1_500L
        private const val RESOLVE_TIMEOUT_MS = 1_500L
        private const val OVERALL_TIMEOUT_MS = 3_500L

        private val GROUP_REGEX = Regex("<ZoneGroup\\s+([^>]*)>(.*?)</ZoneGroup>", RegexOption.DOT_MATCHES_ALL)
        private val MEMBER_REGEX = Regex("<ZoneGroupMember\\s+([^>]*?)/?>")
        private val ATTRIBUTE_REGEX = Regex("(\\w+)=\"([^\"]*)\"")

        private val LABEL_SUFFIX_REGEX = Regex("\\(\\s*with\\b[^)]*\\)?", RegexOption.IGNORE_CASE)

        private val IGNORED_WORDS = setOf("the", "speaker", "room")

        private val NUMBER_WORDS =
            listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")
                .withIndex()
                .associate { (i, w) -> w to (i + 1).toString() }

        /** Lowercase words without filler, with spoken numbers ("one".."ten") as digits. */
        private fun words(s: String): List<String> =
            s
                .lowercase()
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() && it !in IGNORED_WORDS }
                .map { NUMBER_WORDS[it] ?: it }

        /** A group reads as "<name> (with <other members>)" so the user can tell which room is meant. */
        private fun label(speaker: CastSpeaker) =
            if (speaker.aliases.isEmpty()) speaker.name else "${speaker.name} (with ${speaker.aliases.joinToString(" and ")})"

        /**
         * The speaker a spoken [name] refers to: an exact normalized match, else the unique speaker
         * whose name (or a group member's name) contains every word of the request.
         *
         * @throws IllegalArgumentException when nothing matches or several speakers do; the message names the candidates.
         */
        fun match(
            name: String,
            speakers: List<CastSpeaker>,
        ): CastSpeaker {
            // The ambiguity question shows labels like "Office 1 (with Office 2)"; users repeat them.
            val wanted = words(name.replace(LABEL_SUFFIX_REGEX, ""))
            val normalized = speakers.map { speaker -> speaker to (listOf(speaker.name) + speaker.aliases).map(::words) }
            if (wanted.isNotEmpty()) {
                normalized.filter { (_, names) -> wanted in names }.singleOrNull()?.let { return it.first }
                val candidates = normalized.filter { (_, names) -> names.any { w -> wanted.all { it in w } } }.map { it.first }
                if (candidates.size == 1) return candidates.single()
                if (candidates.size > 1) {
                    throw IllegalArgumentException("Which speaker: ${candidates.joinToString(", or ", transform = ::label)}?")
                }
            }
            throw IllegalArgumentException("No speaker called $name. Speakers: ${speakers.joinToString(", ", transform = ::label)}")
        }
    }
}
