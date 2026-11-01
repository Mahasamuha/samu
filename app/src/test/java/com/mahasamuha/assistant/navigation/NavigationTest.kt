package com.mahasamuha.assistant.navigation

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.mahasamuha.assistant.contacts.PostalAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTest {
    /** In-memory preferences, so [SavedPlaces] runs without Android. */
    private class MemoryPrefs : SharedPreferences {
        val values = mutableMapOf<String, String?>()

        override fun getString(key: String, defValue: String?) = values[key] ?: defValue

        override fun edit() = object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, String?>()

            override fun putString(key: String, value: String?) = also { pending[key] = value }

            override fun apply() {
                values.putAll(pending)
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun putStringSet(key: String, values: MutableSet<String>?) = this
            override fun putInt(key: String, value: Int) = this
            override fun putLong(key: String, value: Long) = this
            override fun putFloat(key: String, value: Float) = this
            override fun putBoolean(key: String, value: Boolean) = this
            override fun remove(key: String) = this
            override fun clear() = this
        }

        override fun getAll(): MutableMap<String, *> = values
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String, defValue: Int) = defValue
        override fun getLong(key: String, defValue: Long) = defValue
        override fun getFloat(key: String, defValue: Float) = defValue
        override fun getBoolean(key: String, defValue: Boolean) = defValue
        override fun contains(key: String) = key in values
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}
    }

    private val prefs = MemoryPrefs()

    private val context = object : ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    private fun places(home: String = "", work: String = ""): SavedPlaces {
        val places = SavedPlaces(context as Context)
        places.home = home
        places.work = work
        return places
    }

    // --- SavedPlaces

    @Test
    fun homeAndWorkResolveInAnyCase() {
        val places = places(home = "1 Example Way", work = "2 Sample Road")
        assertEquals("1 Example Way", places.address("home"))
        assertEquals("1 Example Way", places.address("Home"))
        assertEquals("2 Sample Road", places.address("WORK"))
    }

    @Test
    fun otherPlacesAndUnsetOnesHaveNoSavedAddress() {
        val places = places(home = "1 Example Way")
        assertNull(places.address("costco"))
        assertNull(places.address("work"))
        assertNull(places.address(""))
    }

    @Test
    fun savedAddressesAreTrimmed() {
        assertEquals("1 Example Way", places(home = "  1 Example Way \n").address("home"))
        assertNull(places(home = "   ").address("home"))
    }

    // --- resolvePlace

    @Test
    fun aSavedPlaceIsAnnouncedByNameAndTheMapsAppGetsTheAddress() {
        val places = places(home = "1 Example Way", work = "2 Sample Road")
        assertEquals("home" to "1 Example Way", resolvePlace("home", places))
        assertEquals("to work" to "2 Sample Road", resolvePlace("Work", places))
    }

    @Test
    fun anUnsetHomeOrWorkIsAnErrorNotASearchForTheWordHome() {
        val e = assertThrows(IllegalArgumentException::class.java) { resolvePlace("Home", places()) }
        assertEquals("The user's home address isn't set. They can add it in the Samu app.", e.message)
        assertThrows(IllegalArgumentException::class.java) { resolvePlace("work", places(home = "1 Example Way")) }
    }

    @Test
    fun anyOtherDestinationGoesToTheMapsAppAsSaid() {
        assertEquals("to Costco" to "Costco", resolvePlace("Costco", places(home = "1 Example Way")))
    }

    @Test
    fun anEmptyDestinationIsRejected() {
        val e = assertThrows(IllegalArgumentException::class.java) { resolvePlace("", places()) }
        assertEquals("A destination is required", e.message)
    }

    // --- contact addresses

    private val home = PostalAddress("1 Example Way, Springfield", "Home")
    private val work = PostalAddress("2 Sample Road, Springfield", "Work")

    @Test
    fun aContactWithOneAddressUsesIt() {
        assertEquals("to Sam Lee's home address" to "1 Example Way, Springfield", chooseAddress("Sam Lee", listOf(home), ""))
    }

    @Test
    fun severalAddressesAreNarrowedByTheSpokenTypeInAnyCase() {
        assertEquals("to Sam Lee's work address" to "2 Sample Road, Springfield", chooseAddress("Sam Lee", listOf(home, work), "WORK"))
    }

    @Test
    fun severalAddressesWithoutAMatchingTypeListTheTypesButNotTheAddresses() {
        for (type in listOf("", "cabin")) {
            val e = assertThrows(IllegalArgumentException::class.java) { chooseAddress("Sam Lee", listOf(home, work), type) }
            assertEquals("Sam Lee has more than one address (home, work); ask which one", e.message)
            assertTrue(!e.message!!.contains("Example Way"))
        }
    }

    @Test
    fun twoAddressesOfTheSameTypeStayAmbiguous() {
        val second = PostalAddress("3 Other Street", "Home")
        assertThrows(IllegalArgumentException::class.java) { chooseAddress("Sam Lee", listOf(home, second), "home") }
    }

    @Test
    fun mapsAppIdsAreStable() {
        assertEquals(listOf("waze", "google_maps"), MapsApp.entries.map { it.id })
    }
}
