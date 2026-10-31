package com.mahasamuha.assistant.capabilities

import android.content.res.AssetManager
import org.json.JSONObject

/**
 * The local and hybrid capabilities bundled into the APK at build time (the `bundleCapabilities` Gradle
 * task). This is what a session has when no index has ever been fetched.
 */
class BundledCapabilities(private val assets: AssetManager) {
    val capabilities: List<Capability> by lazy {
        val json = assets.open(FILE).bufferedReader().use { JSONObject(it.readText()) }
        Capability.parseAll(json.getJSONArray("capabilities"))
    }

    private companion object {
        const val FILE = "capabilities.json"
    }
}
