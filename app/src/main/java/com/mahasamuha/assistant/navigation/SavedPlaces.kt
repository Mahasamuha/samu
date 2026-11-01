package com.mahasamuha.assistant.navigation

import android.content.Context
import androidx.core.content.edit

/**
 * The user's home and work addresses, set on the setup screen. They stay on the phone (app-private
 * preferences, and backup is off): the model passes "home" or "work", and only the maps app gets the address.
 */
class SavedPlaces(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("saved_places", Context.MODE_PRIVATE)

    var home: String
        get() = prefs.getString(HOME, null).orEmpty()
        set(value) = prefs.edit { putString(HOME, value.trim()) }

    var work: String
        get() = prefs.getString(WORK, null).orEmpty()
        set(value) = prefs.edit { putString(WORK, value.trim()) }

    /** The address for "home" or "work" (any case), or null if [place] is neither or it isn't set. */
    fun address(place: String): String? =
        when (place.lowercase()) {
            HOME -> home
            WORK -> work
            else -> null
        }?.takeIf { it.isNotEmpty() }

    companion object {
        const val HOME = "home"
        const val WORK = "work"
    }
}
