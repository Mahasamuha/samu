package com.mahasamuha.assistant.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactDirectoryTest {
    private fun plain(
        number: String,
        minDigits: Int = 2,
    ) = ContactDirectory.isPhoneNumber(number, minDigits)

    @Test
    fun acceptsDigitsPlusAndCommonSeparators() {
        assertTrue(plain("5550100"))
        assertTrue(plain("+1 555 0100"))
        assertTrue(plain("(555) 010-0123"))
        assertTrue(plain("555.010.0123"))
        assertTrue(plain("+15550100123"))
    }

    @Test
    fun rejectsStarAndHashCodes() {
        assertFalse(plain("*#06#"))
        assertFalse(plain("**21*5550100#"))
        assertFalse(plain("#31#5550100"))
        assertFalse(plain("*67 555 0100"))
    }

    @Test
    fun rejectsPausesAndWaits() {
        assertFalse(plain("5550100,1234"))
        assertFalse(plain("5550100;1234"))
    }

    @Test
    fun rejectsLettersAndVanityNumbers() {
        assertFalse(plain("1-800-FLOWERS"))
        assertFalse(plain("555O100"))
        assertFalse(plain("tel:5550100"))
    }

    @Test
    fun aPlusIsOnlyAllowedFirst() {
        assertFalse(plain("555+0100"))
        assertFalse(plain("++15550100"))
        assertFalse(plain(" +15550100"))
    }

    @Test
    fun rejectsEmptyAndNumbersWithTooFewDigits() {
        assertFalse(plain(""))
        assertFalse(plain("+"))
        assertFalse(plain("---"))
        assertFalse(plain("5", minDigits = 2))
        assertTrue(plain("55", minDigits = 2))
        assertFalse(plain("55", minDigits = 3))
        assertTrue(plain("555", minDigits = 3))
    }

    @Test
    fun rejectsWhitespaceTricksAndEmbeddedNewlines() {
        assertFalse(plain("555\n0100"))
        assertFalse(plain("555\t0100"))
        assertFalse(plain("5550100\u0000"))
    }

    @Test
    fun theSpacesInADigitSplitNumberStillCountAsDigits() {
        // "9 1 1" passes the plain check; callers must test the emergency number on the digits alone.
        assertTrue(plain("9 1 1"))
        assertEquals(3, "9 1 1".count { it.isDigit() })
    }

    @Test
    fun recipientOfANumberIsReadAsThatNumber() {
        // The platform formatter isn't available in unit tests (it returns null), so the raw number is read.
        val recipient = Recipient.of("5550100")
        assertEquals("5550100", recipient.number)
        assertEquals("5550100", recipient.description)
    }
}
