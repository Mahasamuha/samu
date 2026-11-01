package com.mahasamuha.assistant.navigation

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import androidx.core.net.toUri
import com.mahasamuha.assistant.contacts.ContactDirectory
import com.mahasamuha.assistant.contacts.PostalAddress
import com.mahasamuha.assistant.llm.ToolAnnotations
import com.mahasamuha.assistant.llm.ToolDefinition
import com.mahasamuha.assistant.tools.ActionTool
import com.mahasamuha.assistant.tools.Confirmation
import com.mahasamuha.assistant.tools.PendingAction
import org.json.JSONArray
import org.json.JSONObject

/** The maps apps `navigate` can open, and their navigation intents. */
enum class MapsApp(val id: String, val spoken: String) {
    WAZE("waze", "Waze"),
    GOOGLE_MAPS("google_maps", "Google Maps");

    fun intent(destination: String): Intent = when (this) {
        WAZE -> Intent(Intent.ACTION_VIEW, "https://waze.com/ul?q=${Uri.encode(destination)}&navigate=yes".toUri())
            .setPackage("com.waze")
        GOOGLE_MAPS -> Intent(Intent.ACTION_VIEW, "google.navigation:q=${Uri.encode(destination)}".toUri())
            .setPackage("com.google.android.apps.maps")
    }
}

/**
 * `navigate`: starts turn-by-turn navigation in Waze (the default) or Google Maps, announced like a
 * call. Saved places and contacts' addresses are resolved here, so the model only ever sees "home",
 * "work" or a contact's name; the maps app is the one that gets the address. Any other destination
 * goes to the maps app as said, and it picks the place (usually the nearest match). With device
 * location off, [TurnOnLocationActivity] asks to turn it on first; neither maps app handles that well.
 *
 * [launch] starts the app from the session while it's visible (Android 17 blocks background
 * launches); [closePanel] dismisses the session once the app takes over. [contacts] is null when
 * contacts permission is off.
 */
class NavigateTool(
    private val context: Context,
    private val places: SavedPlaces,
    private val contacts: ContactDirectory?,
    private val launch: (Intent) -> Unit,
    private val closePanel: () -> Unit,
) : ActionTool {

    override val definition = ToolDefinition(
        name = "navigate",
        description = "Start turn-by-turn navigation on the user's phone, in Waze unless the user asks for Google " +
            "Maps. The phone announces it and opens the app.",
        inputSchema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("destination", JSONObject()
                    .put("type", "string")
                    .put("description", "Where to, as the user said it: a place or business (\"Costco\"), an address, " +
                        "or exactly \"home\" or \"work\" for the user's saved places. With contact_name, the address " +
                        "type if the user said one (\"work\"), otherwise empty."))
                .put("contact_name", JSONObject()
                    .put("type", "string")
                    .put("description", "To go to a contact's address: the contact's full name as find_contact returned it. Omit otherwise."))
                .put("app", JSONObject()
                    .put("type", "string")
                    .put("enum", JSONArray().put(MapsApp.WAZE.id).put(MapsApp.GOOGLE_MAPS.id))
                    .put("description", "Omit unless the user names Google Maps.")))
            .put("required", JSONArray().put("destination"))
            .put("additionalProperties", false),
        // Opening a maps app changes nothing and is easy to back out of; it's announced, not asked.
        annotations = ToolAnnotations(title = "Navigate", readOnlyHint = false, destructiveHint = false, openWorldHint = true),
    )

    override suspend fun prepare(arguments: JSONObject): PendingAction {
        val destination = arguments.optString("destination").trim()
        val contactName = arguments.optString("contact_name").trim()
        val app = MapsApp.entries.firstOrNull { it.id == arguments.optString("app") } ?: MapsApp.WAZE

        val (where, query) = when {
            contactName.isNotEmpty() -> contactAddress(contactName, destination)
            else -> resolvePlace(destination, places)
        }
        require(app.intent(query).resolveActivity(context.packageManager) != null) { "${app.spoken} isn't installed" }
        val locationOn = context.getSystemService(LocationManager::class.java).isLocationEnabled
        val note = if (locationOn) "" else " Location is off, opening activation panel."
        return PendingAction(Confirmation.Announce("Navigating $where in ${app.spoken}.$note", "Navigate $where in ${app.spoken}?")) {
            launch(if (locationOn) app.intent(query) else TurnOnLocationActivity.intent(context, query, app))
            closePanel()
            JSONObject().put("status", "navigating").toString()
        }
    }

    /**
     * The contact's address for the announcement and the maps app. With several, [type] ("work") picks one;
     * otherwise the model is told the types to ask about, never the addresses.
     */
    private suspend fun contactAddress(name: String, type: String): Pair<String, String> {
        requireNotNull(contacts) { "Contacts permission is off" }
        val addresses = contacts.addressesFor(name)
        require(addresses.isNotEmpty()) { "No address is saved for $name" }
        return chooseAddress(name, addresses, type)
    }
}

/**
 * How the announcement names the destination, and what the maps app is given, for a destination that is
 * not a contact's: "home" or "work" resolve to the saved address, anything else goes as said.
 */
internal fun resolvePlace(destination: String, places: SavedPlaces): Pair<String, String> =
    places.address(destination)?.let { address -> spokenPlace(destination) to address }
        ?: run {
            require(destination.lowercase() !in setOf(SavedPlaces.HOME, SavedPlaces.WORK)) {
                "The user's ${destination.lowercase()} address isn't set. They can add it in the Samu app."
            }
            require(destination.isNotEmpty()) { "A destination is required" }
            "to $destination" to destination
        }

/** "home", "to work": how the announcement names a saved place. */
private fun spokenPlace(place: String) = if (place.lowercase() == SavedPlaces.HOME) "home" else "to work"

/** The one of [addresses] to use: the only one, else the one labelled [type]; throws, naming the labels, when it's still unclear. */
internal fun chooseAddress(name: String, addresses: List<PostalAddress>, type: String): Pair<String, String> {
    val chosen: PostalAddress = addresses.singleOrNull()
        ?: addresses.filter { type.isNotEmpty() && it.label.equals(type, ignoreCase = true) }.singleOrNull()
        ?: throw IllegalArgumentException(
            "$name has more than one address (${addresses.joinToString(", ") { it.label.lowercase() }}); ask which one",
        )
    return "to $name's ${chosen.label.lowercase()} address" to chosen.address
}
