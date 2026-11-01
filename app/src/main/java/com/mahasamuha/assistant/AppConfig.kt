package com.mahasamuha.assistant

import android.net.Uri
import androidx.core.net.toUri

/** Public client settings from config.local.properties, via BuildConfig. No secrets. */
data class AppConfig(
    val issuer: Uri,
    val clientId: String,
    /** In priority order. v1 uses the first; the rest are for fallback later. */
    val gatewayUrls: List<Uri>,
    val redirectUri: Uri,
) {
    companion object {
        fun fromBuildConfig() =
            AppConfig(
                issuer = https(BuildConfig.ISSUER_URL),
                clientId = BuildConfig.CLIENT_ID,
                gatewayUrls = BuildConfig.GATEWAY_URLS.split(",").map { https(it.trim().trimEnd('/')) },
                redirectUri = "${BuildConfig.APPLICATION_ID}:/oauth2redirect".toUri(),
            )

        /** Tokens travel to these URLs, so a typo'd http:// fails here instead of at the first request. */
        private fun https(url: String): Uri =
            url.toUri().also {
                require(it.scheme == "https") { "Not an https URL: $url" }
            }
    }
}
