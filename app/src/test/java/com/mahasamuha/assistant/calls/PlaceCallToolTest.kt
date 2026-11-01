package com.mahasamuha.assistant.calls

import com.mahasamuha.assistant.contacts.Contact
import com.mahasamuha.assistant.contacts.PhoneNumber
import com.mahasamuha.assistant.tools.Confirmation
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceCallToolTest {
    private val placed = mutableListOf<String>()
    private val launched = mutableListOf<android.content.Intent>()
    private var closed = 0

    private fun tool(emergency: (String) -> Boolean = { false }) =
        PlaceCallTool(
            contacts = null,
            launch = { launched += it },
            closePanel = { closed++ },
            isEmergencyNumber = emergency,
            defaultDialerPackage = { "com.example.dialer" },
            placeCall = { placed += it },
        )

    private fun prepare(
        tool: PlaceCallTool,
        arguments: JSONObject,
    ) = runBlocking { tool.prepare(arguments) }

    @Test
    fun withoutContactsANumberIsAskedAboutNeverAnnounced() {
        val pending = prepare(tool(), JSONObject().put("phone_number", "555 0100").put("contact_name", "Sam Lee"))
        // Even with a contact name from the model: the phone can't verify it, so it asks, naming the number.
        assertEquals(Confirmation.Ask("Call 555 0100?"), pending.confirmation)
    }

    @Test
    fun rejectsCodesAndLettersBeforeAnythingElse() {
        for (bad in listOf("*#06#", "**21*5550100#", "555,1234", "1-800-FLOWERS", "", "5")) {
            val e = assertThrows(IllegalArgumentException::class.java) { prepare(tool(), JSONObject().put("phone_number", bad)) }
            assertTrue(e.message!!.contains("plain phone number"))
        }
    }

    @Test
    fun aMissingNumberIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { prepare(tool(), JSONObject()) }
    }

    @Test
    fun anEmergencyNumberOpensTheDialerAndIsAnnounced() {
        var checked: String? = null
        val pending =
            prepare(
                tool {
                    checked = it
                    true
                },
                JSONObject().put("phone_number", "9 1 1"),
            )

        // The emergency check runs on the digits, so "9 1 1" is not a way around it.
        assertEquals("911", checked)
        assertEquals(Confirmation.Announce("Opening the dialer for 911.", "Open the dialer for 911?"), pending.confirmation)
    }

    @Test
    fun aConfirmedCallToAPlainNumberIsPlacedOnce() {
        val pending = prepare(tool(), JSONObject().put("phone_number", "555 0100"))
        assertTrue(placed.isEmpty())
        val result = runBlocking { pending.perform() }
        // Without contacts the dialed number is the model's text, as given.
        assertEquals(listOf("555 0100"), placed)
        assertEquals(1, closed)
        assertTrue(launched.isEmpty())
        assertTrue(result.contains("calling"))
    }

    @Test
    fun theEmergencyCheckKeepsAPlusAndDropsSeparators() {
        var checked: String? = null
        prepare(
            tool {
                checked = it
                false
            },
            JSONObject().put("phone_number", "+1 (555) 010-0100"),
        )
        assertEquals("+15550100100", checked)
    }

    // --- certainty rules

    private val sam =
        Contact(
            "Sam Lee",
            listOf(PhoneNumber("555-0101", "Mobile"), PhoneNumber("555-0102", "Work"), PhoneNumber("555-0103", "Home")),
        )

    private val sameDigits: (String, String) -> Boolean = { a, b -> a.filter(Char::isDigit) == b.filter(Char::isDigit) }

    @Test
    fun aNumberThatIsTheOnlyOneOfItsTypeIsCertain() {
        assertEquals(PhoneNumber("555-0102", "Work"), certainNumber(sam, "(555) 0102", sameDigits))
    }

    @Test
    fun theSavedFormOfTheNumberIsReturnedNotTheModelsText() {
        assertEquals("555-0101", certainNumber(sam, "5550101", sameDigits)!!.number)
    }

    @Test
    fun twoNumbersOfTheSameTypeAreAmbiguous() {
        val twoMobiles = sam.copy(numbers = sam.numbers + PhoneNumber("555-0104", "Mobile"))
        assertNull(certainNumber(twoMobiles, "555-0101", sameDigits))
        assertNull(certainNumber(twoMobiles, "555-0104", sameDigits))
        // The unique-typed ones are still certain.
        assertEquals("555-0102", certainNumber(twoMobiles, "555-0102", sameDigits)!!.number)
    }

    @Test
    fun aNumberThatIsNotTheContactsIsNotCertain() {
        assertNull(certainNumber(sam, "555-0199", sameDigits))
        assertNull(certainNumber(Contact("Nobody", emptyList()), "555-0101", sameDigits))
    }
}
