package com.mahasamuha.assistant

import android.content.Context
import androidx.core.content.edit
import java.net.URI
import java.net.URISyntaxException

/**
 * Checks and normalizes a gateway URL typed on the setup screen (plan §5b). Returns the base URL the clients
 * append `/v1/responses` and `/mcp` to. Throws
 * [IllegalArgumentException] with a message fit to show the user.
 *
 * The token is sent to this URL, so it must be https with a host and nothing else that changes where a
 * request goes: no credentials, query or fragment. A path is allowed (the clients append to the string), and
 * trailing slashes are dropped.
 */
fun parseGatewayUrl(input: String): String {
    val text = input.trim()
    require(text.isNotEmpty()) { "Enter the gateway URL (https://…)." }
    val uri =
        try {
            URI(text)
        } catch (e: URISyntaxException) {
            throw IllegalArgumentException("That isn't a valid URL.", e)
        }
    require(uri.scheme.equals("https", ignoreCase = true)) { "The URL must start with https://." }
    val host = uri.host
    require(!host.isNullOrEmpty()) { "The URL needs a host name." }
    require(uri.rawUserInfo == null) { "The URL can't contain a user name or password." }
    require(uri.rawQuery == null && !text.contains('?')) { "The URL can't have a query (?…)." }
    require(uri.rawFragment == null && !text.contains('#')) { "The URL can't have a fragment (#…)." }
    val port = if (uri.port == -1) "" else ":${uri.port}"
    return "https://${host.lowercase()}$port${uri.rawPath.orEmpty().trimEnd('/')}"
}

/** Thrown when a request needs the gateway but none has been set on the setup screen yet. */
class GatewayNotSetException : Exception("No gateway URL set")

/** The gateways in effect: the saved URL as a one-element list (endpoints are a list; v1 has one). */
fun gatewayUrls(saved: String?): List<String> = listOf(saved ?: throw GatewayNotSetException())

/** Whether saving [new] points the app at a different backend than [saved], which signs the user out. */
fun changesBackend(
    saved: String?,
    new: String,
): Boolean = saved != new

/** The gateway URL typed on the setup screen (plan §5b); null means none is set yet. */
class GatewayPreference(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)

    var url: String?
        get() = prefs.getString(URL, null)
        set(value) = prefs.edit { if (value == null) remove(URL) else putString(URL, value) }

    private companion object {
        const val URL = "url"
    }
}
