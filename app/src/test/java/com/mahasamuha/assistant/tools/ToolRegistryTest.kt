package com.mahasamuha.assistant.tools

import com.mahasamuha.assistant.testutil.FakeActionTool
import com.mahasamuha.assistant.testutil.FakeReadOnlyTool
import com.mahasamuha.assistant.testutil.RecordingConfirmer
import com.mahasamuha.assistant.testutil.call
import com.mahasamuha.assistant.testutil.definition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {
    private val announce = Confirmation.Announce("Calling Mom, mobile.", "Call Mom, mobile?")
    private val confirmer = RecordingConfirmer()

    private fun execute(
        registry: ToolRegistry,
        name: String,
        arguments: String = "{}",
        with: Confirmer = confirmer,
    ) = runBlocking { registry.execute(call(name, arguments), with) }

    // --- confirmation policy

    @Test
    fun announceStaysAnAnnouncementWhenNothingOutsideWasRead() {
        val tool = FakeActionTool("place_call", announce)
        execute(ToolRegistry(listOf(tool)), "place_call")
        assertEquals(listOf<Confirmation>(announce), confirmer.asked)
        assertEquals(1, tool.performed)
    }

    @Test
    fun announceBecomesAskAfterAnOpenWorldReadOnlyToolRuns() {
        val search = FakeReadOnlyTool("web_search", openWorld = true)
        val call = FakeActionTool("place_call", announce)
        val registry = ToolRegistry(listOf(search, call))

        execute(registry, "web_search")
        execute(registry, "place_call")

        // The question form is asked, not the announcement.
        assertEquals(listOf<Confirmation>(Confirmation.Ask("Call Mom, mobile?")), confirmer.asked)
    }

    @Test
    fun theAskStaysForTheRestOfTheSession() {
        val registry = ToolRegistry(listOf(FakeReadOnlyTool("get_weather", openWorld = true), FakeActionTool("place_call", announce)))
        execute(registry, "get_weather")
        repeat(3) { execute(registry, "place_call") }
        assertTrue(confirmer.asked.all { it is Confirmation.Ask })
        assertEquals(3, confirmer.asked.size)
    }

    @Test
    fun aClosedWorldReadOnlyToolDoesNotChangeAnnouncements() {
        val registry = ToolRegistry(listOf(FakeReadOnlyTool("find_contact", openWorld = false), FakeActionTool("place_call", announce)))
        execute(registry, "find_contact")
        execute(registry, "place_call")
        assertEquals(listOf<Confirmation>(announce), confirmer.asked)
    }

    @Test
    fun bindingMoreToolsNeverResetsTheAsk() {
        val registry = ToolRegistry(listOf(FakeReadOnlyTool("web_search", openWorld = true)))
        execute(registry, "web_search")
        registry.bind(listOf(FakeActionTool("place_call", announce), FakeReadOnlyTool("use_capability")))
        execute(registry, "use_capability")
        execute(registry, "place_call")
        assertEquals(listOf<Confirmation>(Confirmation.Ask("Call Mom, mobile?")), confirmer.asked)
    }

    @Test
    fun anOpenWorldToolThatFailsStillCountsAsHavingReadOutsideContent() {
        val registry =
            ToolRegistry(
                listOf(
                    FakeReadOnlyTool("web_search", openWorld = true, failWith = RuntimeException("boom")),
                    FakeActionTool("place_call", announce),
                ),
            )
        execute(registry, "web_search")
        execute(registry, "place_call")
        assertTrue(confirmer.asked.single() is Confirmation.Ask)
    }

    @Test
    fun smsIsAlwaysAsked() {
        val ask = Confirmation.Ask("Text Sam Lee: Running late. Send it?")
        val tool = FakeActionTool("send_sms", ask)
        execute(ToolRegistry(listOf(tool)), "send_sms")
        assertEquals(listOf<Confirmation>(ask), confirmer.asked)
    }

    @Test
    fun aReportIsNotTurnedIntoAnAskAfterOutsideContent() {
        val report = Confirmation.Report("Timer, 10 minutes.")
        val registry = ToolRegistry(listOf(FakeReadOnlyTool("web_search", openWorld = true), FakeActionTool("set_timer", report)))
        execute(registry, "web_search")
        execute(registry, "set_timer")
        assertEquals(listOf<Confirmation>(report), confirmer.asked)
    }

    @Test
    fun anActionIsPerformedOnlyAfterItIsConfirmed() {
        val tool = FakeActionTool("send_sms", Confirmation.Ask("Send?"))
        val registry = ToolRegistry(listOf(tool))
        val declining = RecordingConfirmer(ConfirmationResult.Declined("no"))

        execute(registry, "send_sms", with = declining)
        assertEquals(0, tool.performed)

        assertEquals("""{"status":"done"}""", execute(registry, "send_sms"))
        assertEquals(1, tool.performed)
    }

    @Test
    fun declinedReturnsNotConfirmedWithTheUsersWordsAndAScopeNote() {
        val registry = ToolRegistry(listOf(FakeActionTool("send_sms", Confirmation.Ask("Send?"))))
        val result =
            JSONObject(execute(registry, "send_sms", with = RecordingConfirmer(ConfirmationResult.Declined("yes but say ten minutes"))))

        assertEquals("not_confirmed", result.getString("status"))
        assertEquals("yes but say ten minutes", result.getString("user_said"))
        assertTrue(result.getString("note").contains("Only this action was not performed"))
    }

    @Test
    fun declinedWithoutWordsOmitsUserSaid() {
        val registry = ToolRegistry(listOf(FakeActionTool("send_sms", Confirmation.Ask("Send?"))))
        val result = JSONObject(execute(registry, "send_sms", with = RecordingConfirmer(ConfirmationResult.Declined(null))))
        assertEquals("not_confirmed", result.getString("status"))
        assertFalse(result.has("user_said"))
    }

    @Test
    fun aRemoteStyleActionToolWithAskIsAsked() {
        val ask = Confirmation.Ask("Run create event with title Lunch?")
        val tool = FakeActionTool("create_event", ask)
        execute(ToolRegistry(listOf(tool)), "create_event")
        assertEquals(listOf<Confirmation>(ask), confirmer.asked)
    }

    // --- errors

    @Test
    fun anUnknownToolIsAnErrorThatPointsAtUseCapability() {
        val result = JSONObject(execute(ToolRegistry(), "mystery"))
        assertTrue(result.getString("error").contains("Unknown tool mystery"))
        assertTrue(result.getString("error").contains("use_capability"))
    }

    @Test
    fun invalidArgumentJsonIsAnError() {
        val tool = FakeReadOnlyTool("t")
        val result = JSONObject(execute(ToolRegistry(listOf(tool)), "t", arguments = "{not json"))
        assertEquals("Arguments were not valid JSON", result.getString("error"))
        assertEquals(0, tool.runs)
    }

    @Test
    fun blankArgumentsAreAnEmptyObject() {
        val tool = FakeActionTool("t", Confirmation.Report("ok"))
        execute(ToolRegistry(listOf(tool)), "t", arguments = "  ")
        assertEquals(0, tool.lastArguments!!.length())
    }

    @Test
    fun aToolFailureBecomesAnErrorResult() {
        val registry = ToolRegistry(listOf(FakeReadOnlyTool("t", failWith = IllegalStateException("no location"))))
        assertEquals("no location", JSONObject(execute(registry, "t")).getString("error"))
    }

    @Test
    fun aFailureWithoutAMessageStillReportsSomething() {
        val registry = ToolRegistry(listOf(FakeReadOnlyTool("t", failWith = RuntimeException())))
        assertEquals("Tool failed", JSONObject(execute(registry, "t")).getString("error"))
    }

    @Test
    fun aPrepareFailureIsAnErrorAndAsksNothing() {
        val tool = FakeActionTool("send_sms", Confirmation.Ask("?"), prepareFails = IllegalArgumentException("The message is empty"))
        val result = JSONObject(execute(ToolRegistry(listOf(tool)), "send_sms"))
        assertEquals("The message is empty", result.getString("error"))
        assertTrue(confirmer.asked.isEmpty())
    }

    @Test
    fun cancellationPropagates() {
        val registry = ToolRegistry(listOf(FakeReadOnlyTool("t", failWith = CancellationException("cancelled"))))
        assertThrows(CancellationException::class.java) { execute(registry, "t") }
    }

    // --- registration

    @Test
    fun duplicateNamesAreRejectedAtConstruction() {
        assertThrows(IllegalArgumentException::class.java) { ToolRegistry(listOf(FakeReadOnlyTool("a"), FakeReadOnlyTool("a"))) }
    }

    @Test
    fun bindRejectsDuplicatesWithinTheBatchButSkipsAlreadyBoundNames() {
        val original = FakeReadOnlyTool("a", result = "original")
        val registry = ToolRegistry(listOf(original))
        assertThrows(IllegalArgumentException::class.java) { registry.bind(listOf(FakeReadOnlyTool("b"), FakeReadOnlyTool("b"))) }

        registry.bind(listOf(FakeReadOnlyTool("a", result = "replacement"), FakeReadOnlyTool("c")))
        assertEquals(setOf("a", "c"), registry.names)
        assertEquals("original", execute(registry, "a"))
    }

    @Test
    fun readOnlyHintMustMatchTheToolKind() {
        val readOnlyWithoutHint =
            object : ReadOnlyTool {
                override val definition = definition("liar", readOnly = false)

                override suspend fun run(arguments: JSONObject) = "x"
            }
        val actionWithHint =
            object : ActionTool {
                override val definition = definition("sneaky", readOnly = true)

                override suspend fun prepare(arguments: JSONObject) = PendingAction(Confirmation.Report("x")) { "x" }
            }
        assertThrows(IllegalArgumentException::class.java) { ToolRegistry(listOf(readOnlyWithoutHint)) }
        assertThrows(IllegalArgumentException::class.java) { ToolRegistry().bind(listOf(actionWithHint)) }
    }

    @Test
    fun definitionsFollowBindingOrder() {
        val registry = ToolRegistry(listOf(FakeReadOnlyTool("b")))
        registry.bind(listOf(FakeReadOnlyTool("a")))
        assertEquals(listOf("b", "a"), registry.definitions.map { it.name })
    }
}
