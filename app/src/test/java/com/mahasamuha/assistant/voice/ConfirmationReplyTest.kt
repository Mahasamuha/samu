package com.mahasamuha.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmationReplyTest {
    private fun assertYes(vararg replies: String) = replies.forEach { assertTrue("\"$it\" should be a yes", ConfirmationReply.isYes(it)) }

    private fun assertNotYes(vararg replies: String) =
        replies.forEach {
            assertFalse("\"$it\" should not be a yes", ConfirmationReply.isYes(it))
        }

    @Test
    fun acceptsPlainYesPhrases() {
        assertYes(
            "yes",
            "yeah",
            "yep",
            "yup",
            "sure",
            "ok",
            "okay",
            "correct",
            "confirm",
            "right",
            "send",
            "send it",
            "call",
            "do it",
            "go",
            "go ahead",
            "sounds good",
        )
    }

    @Test
    fun ignoresCaseAndPunctuation() {
        assertYes("Yes", "YES!", "Yes.", "  yeah  ", "Okay.", "OK!", "Go ahead!", "That's right", "thats right.", "Send it.")
    }

    @Test
    fun acceptsAPoliteTrailingPlease() {
        assertYes("yes please", "Go ahead, please", "send it please")
    }

    @Test
    fun acceptsALeadingYesBeforeAnotherYesPhrase() {
        assertYes("yes send it", "yeah go ahead", "okay do it", "ok call", "sure sounds good", "yes, send it")
    }

    @Test
    fun collapsesRepeatedSpacesAndPunctuationInsideThePhrase() {
        assertYes("go   ahead", "sounds, good")
    }

    @Test
    fun rejectsNoAndCancellation() {
        assertNotYes("no", "nope", "cancel", "stop", "don't", "wait", "no thanks", "nah")
    }

    @Test
    fun rejectsAYesFollowedByAChange() {
        assertNotYes("yes but say ten minutes", "yes and tell him I'm late", "send it to Sam instead", "ok but change the message")
    }

    @Test
    fun rejectsAYesWithADifferentContinuation() {
        assertNotYes("yes no", "sure thing", "yes please send it now")
    }

    @Test
    fun rejectsEmptyAndPunctuationOnly() {
        assertNotYes("", "   ", "...", "please")
    }

    @Test
    fun rejectsOtherRequests() {
        assertNotYes("what's the weather", "call Sam instead")
    }

    @Test
    fun expectedWordsCoverYesAndNoAnswers() {
        val expected = ConfirmationReply.EXPECTED
        assertTrue(expected.containsAll(listOf("yes", "send it", "no", "cancel", "stop")))
        assertEquals(expected.size, expected.toSet().size)
    }
}
