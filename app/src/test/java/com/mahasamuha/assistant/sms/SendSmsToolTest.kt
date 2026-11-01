package com.mahasamuha.assistant.sms

import com.mahasamuha.assistant.tools.Confirmation
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SendSmsToolTest {
    private class FakeSender(
        private val outcome: SmsSender.Outcome = SmsSender.Outcome.Sent,
    ) {
        val sent = mutableListOf<Pair<String, String>>()

        suspend fun send(
            number: String,
            message: String,
        ): SmsSender.Outcome {
            sent += number to message
            return outcome
        }
    }

    private val sender = FakeSender()
    private val tool = SendSmsTool(sender::send, contacts = null)

    private fun args(
        number: String = "555 0100",
        message: String = "Running late",
    ) = JSONObject().put("phone_number", number).put("message", message)

    private fun prepare(arguments: JSONObject) = runBlocking { tool.prepare(arguments) }

    @Test
    fun theReadBackIsBuiltFromTheResolvedNumberAndMessage() {
        val pending = prepare(args())
        assertEquals(Confirmation.Ask("Text 555 0100: Running late. Send it?"), pending.confirmation)
    }

    @Test
    fun anExistingTerminatorIsNotDoubled() {
        for (end in listOf(".", "!", "?")) {
            val pending = prepare(args(message = "See you soon$end"))
            assertEquals("Text 555 0100: See you soon$end Send it?", pending.confirmation.text)
        }
    }

    @Test
    fun nothingIsSentUntilThePendingActionIsPerformed() {
        val pending = prepare(args())
        assertTrue(sender.sent.isEmpty())
        val result = runBlocking { pending.perform() }
        assertEquals("""{"status":"sent"}""", result)
        // The text goes out as written: the pacing period is only in the read-back.
        assertEquals(listOf("555 0100" to "Running late"), sender.sent)
    }

    @Test
    fun aRadioThatHasNotReportedYetIsSending() {
        val slow = SendSmsTool(FakeSender(SmsSender.Outcome.Pending)::send, null)
        val result = JSONObject(runBlocking { runBlocking { slow.prepare(args()) }.perform() })
        assertEquals("sending", result.getString("status"))
    }

    @Test
    fun rejectsNumbersThatAreNotPlain() {
        for (bad in listOf("*#06#", "555,1234", "call me", "", "55")) {
            val e = assertThrows(IllegalArgumentException::class.java) { prepare(args(number = bad)) }
            assertTrue(e.message!!.contains("plain phone number"))
        }
    }

    @Test
    fun threeDigitsAreEnoughForAShortCode() {
        prepare(args(number = "555"))
    }

    @Test
    fun rejectsEmptyAndTooLongMessages() {
        assertThrows(IllegalArgumentException::class.java) { prepare(args(message = "   ")) }
        assertThrows(IllegalArgumentException::class.java) { prepare(args(message = "")) }
        prepare(args(message = "x".repeat(320)))
        assertThrows(IllegalArgumentException::class.java) { prepare(args(message = "x".repeat(321))) }
    }

    @Test
    fun unspokenControlAndFormatCharactersAreRemovedFromTheMessage() {
        // A zero-width space and a right-to-left override would be sent without being heard.
        val pending = prepare(args(message = "Pay​ now‮"))
        assertEquals("Text 555 0100: Pay now. Send it?", pending.confirmation.text)
        runBlocking { pending.perform() }
        assertEquals("Pay now", sender.sent.single().second)
    }

    @Test
    fun newlinesAreKeptButAMessageOfOnlyInvisibleCharactersIsEmpty() {
        val pending = prepare(args(message = "Line one\nLine two"))
        assertTrue(pending.confirmation.text.contains("Line one\nLine two"))
        assertThrows(IllegalArgumentException::class.java) { prepare(args(message = "​​\t")) }
    }

    @Test
    fun isAlwaysAnAskNeverAnAnnouncement() {
        assertTrue(prepare(args()).confirmation is Confirmation.Ask)
    }
}
