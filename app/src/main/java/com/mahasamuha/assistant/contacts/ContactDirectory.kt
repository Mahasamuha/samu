package com.mahasamuha.assistant.contacts

import android.content.ContentResolver
import android.content.res.Resources
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.PhoneLookup
import android.telephony.PhoneNumberUtils
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PhoneNumber(val number: String, val label: String)

data class Contact(val name: String, val numbers: List<PhoneNumber>)

data class PostalAddress(val address: String, val label: String)

/**
 * How a read-back or announcement names a recipient, and the number that will be used. [description] is
 * never phrased as a choice: it's read inside yes/no questions, and "A or B?" invites a name back.
 */
data class Recipient(val description: String, val number: String) {
    companion object {
        /** No matching contact (or no contacts permission): read the number itself. */
        fun of(number: String) = Recipient(ContactDirectory.formatNumber(number), number)
    }
}

/** Name and number lookups against the phone's contacts. Needs READ_CONTACTS. */
class ContactDirectory(private val resolver: ContentResolver, private val resources: Resources) {

    /**
     * Contacts whose name matches [query] (the provider's name filter: word prefixes, and nicknames),
     * with their numbers. The same number listed twice (e.g. from two accounts) appears once.
     */
    suspend fun find(query: String, limit: Int = 5): List<Contact> = withContext(Dispatchers.IO) {
        val uri = Uri.withAppendedPath(Phone.CONTENT_FILTER_URI, Uri.encode(query))
        val projection = arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.NORMALIZED_NUMBER, Phone.TYPE, Phone.LABEL)
        val byContact = linkedMapOf<Long, Pair<String, MutableMap<String, PhoneNumber>>>()
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val name = cursor.getString(1) ?: continue
                val number = cursor.getString(2) ?: continue
                val key = cursor.getString(3) ?: number.filter { it.isDigit() || it == '+' }
                val label = Phone.getTypeLabel(resources, cursor.getInt(4), cursor.getString(5)).toString()
                byContact.getOrPut(id) { name to linkedMapOf() }.second.putIfAbsent(key, PhoneNumber(number, label))
            }
        }
        byContact.values.take(limit).map { (name, numbers) -> Contact(name, numbers.values.toList()) }
    }

    /**
     * Contacts whose name sounds like [heard], best first, for when [find] matches nothing because speech
     * recognition misspelled an uncommon name. Kept: names scoring at least [SOUND_ALIKE_MIN] and within
     * [SOUND_ALIKE_MARGIN] of the best (see [soundAlikeNames]), since the right name tends to stand clear of the rest. The whole list
     * of names is read on the phone; only these candidates leave it.
     */
    suspend fun soundAlike(heard: String, limit: Int = 3): List<Contact> {
        val names = withContext(Dispatchers.IO) {
            buildSet {
                resolver.query(Phone.CONTENT_URI, arrayOf(Phone.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) cursor.getString(0)?.let(::add)
                }
            }
        }
        return soundAlikeNames(heard, names, limit).mapNotNull { name -> find(name).firstOrNull { it.name == name } }
    }

    /**
     * The postal addresses saved for the contact named exactly [name] (a name find_contact returned),
     * with their labels. The same address listed twice (e.g. from two accounts) appears once.
     */
    suspend fun addressesFor(name: String): List<PostalAddress> = withContext(Dispatchers.IO) {
        val projection = arrayOf(StructuredPostal.FORMATTED_ADDRESS, StructuredPostal.TYPE, StructuredPostal.LABEL)
        val selection = "${StructuredPostal.DISPLAY_NAME} = ?"
        val byAddress = linkedMapOf<String, PostalAddress>()
        resolver.query(StructuredPostal.CONTENT_URI, projection, selection, arrayOf(name), null)?.use { cursor ->
            while (cursor.moveToNext()) {
                // Formatted addresses span lines; a maps search wants one.
                val address = cursor.getString(0)?.lines()?.joinToString(", ") { it.trim() }?.takeIf { it.isNotBlank() } ?: continue
                val label = StructuredPostal.getTypeLabel(resources, cursor.getInt(1), cursor.getString(2)).toString()
                byAddress.putIfAbsent(address, PostalAddress(address, label))
            }
        }
        byAddress.values.toList()
    }

    /**
     * Every contact the provider matches to [number], with the number as saved and its label. Usually one;
     * several when the number is saved under more than one contact. The saved number may be written
     * differently from [number] (a `+1` prefix, separators); on the test phone a different area code,
     * or only the last 7 digits, did not match (docs/phase3-notes.md).
     */
    private suspend fun savedAs(number: String): List<Pair<String, PhoneNumber>> = withContext(Dispatchers.IO) {
        val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val projection = arrayOf(PhoneLookup.DISPLAY_NAME, PhoneLookup.NUMBER, PhoneLookup.TYPE, PhoneLookup.LABEL)
        val byName = linkedMapOf<String, PhoneNumber>()
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0) ?: continue
                val saved = cursor.getString(1) ?: continue
                val label = Phone.getTypeLabel(resources, cursor.getInt(2), cursor.getString(3)).toString()
                byName.putIfAbsent(name, PhoneNumber(saved, label))
            }
        }
        byName.toList()
    }

    /**
     * Who a text or call to [number] goes to, and the number to actually use. When the number belongs to
     * a contact, the read-back names the contact and the contact's saved number is used, so what the
     * user hears is what happens, however the model wrote the number. Matches that are different saved
     * numbers fall back to reading the model's number aloud.
     */
    suspend fun recipient(number: String): Recipient {
        val matches = savedAs(number)
        val saved = matches.firstOrNull()?.second?.number
        if (saved == null || matches.any { !sameNumber(it.second.number, saved) }) return Recipient.of(number)
        val description = matches.singleOrNull()
            ?.let { (name, phone) -> "$name, ${phone.label.lowercase()}" }
            ?: ("the number saved as " + matches.joinToString(" and ") { it.first })
        return Recipient(description, saved)
    }

    companion object {
        fun formatNumber(number: String): String =
            PhoneNumberUtils.formatNumber(number, Locale.getDefault().country) ?: number

        fun sameNumber(a: String, b: String): Boolean =
            PhoneNumberUtils.areSamePhoneNumber(a, b, Locale.getDefault().country.lowercase())

        /**
         * True for a plain number the model may pass to a call or text: digits, an optional leading +,
         * and spaces, dots, dashes or parentheses. Rejects star codes (`*#06#`, `**21*…#` call
         * forwarding), pauses and waits (`,` `;`), and letters.
         */
        fun isPhoneNumber(number: String, minDigits: Int): Boolean =
            PLAIN_NUMBER.matches(number) && number.count { it.isDigit() } >= minDigits

        private val PLAIN_NUMBER = Regex("""\+?[0-9 ().-]+""")
    }
}
