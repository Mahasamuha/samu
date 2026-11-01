package com.mahasamuha.assistant.capabilities

import com.mahasamuha.assistant.llm.ToolDefinition
import com.mahasamuha.assistant.skills.Skill
import com.mahasamuha.assistant.testutil.FakeActionTool
import com.mahasamuha.assistant.testutil.FakeReadOnlyTool
import com.mahasamuha.assistant.testutil.RecordingConfirmer
import com.mahasamuha.assistant.testutil.call
import com.mahasamuha.assistant.testutil.capability
import com.mahasamuha.assistant.testutil.definition
import com.mahasamuha.assistant.testutil.hybrid
import com.mahasamuha.assistant.testutil.local
import com.mahasamuha.assistant.testutil.remote
import com.mahasamuha.assistant.tools.Confirmation
import com.mahasamuha.assistant.tools.Tool
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCatalogTest {
    private fun merged(
        capability: Capability,
        vararg remoteNames: String,
    ) = MergedCapability(capability, remoteNames.associateWith { definition(it) })

    /** A remote tool: read-only ones are fakes that answer "remote:<name>". */
    private val remoteFactory: (ToolDefinition) -> Tool = { def ->
        if (def.annotations.readOnlyHint) {
            FakeReadOnlyTool(def.name, result = "remote:${def.name}")
        } else {
            FakeActionTool(def.name, Confirmation.Ask("ask ${def.name}"))
        }
    }

    private fun build(
        candidates: List<Pair<Tool, Boolean>>,
        merged: List<MergedCapability>,
        skills: List<Skill> = emptyList(),
    ) = SessionCatalog.build(candidates, merged, skills, remoteFactory, "test")

    private fun ro(
        name: String,
        allowRemote: Boolean = false,
        privateRemote: Set<String> = emptySet(),
    ) = FakeReadOnlyTool(name, result = "local:$name", allowRemote = allowRemote, privateRemoteTools = privateRemote)

    private val SessionCatalog.names get() = registry.names

    @Test
    fun eagerCapabilitiesAreBoundAtStartAndLazyOnesAreListedSorted() {
        val catalog =
            build(
                listOf(ro("a") to true, ro("b") to true, ro("c") to true),
                listOf(
                    merged(capability("zeta", Load.LAZY, local("c"))),
                    merged(capability("eager", Load.EAGER, local("a"))),
                    merged(capability("alpha", Load.LAZY, local("b"))),
                ),
            )
        assertEquals(setOf("a", UseCapabilityTool.NAME), catalog.names)
        assertEquals(listOf("alpha", "zeta"), catalog.lazyCapabilities.map { it.id })
        assertEquals(listOf("alpha", "eager", "zeta"), catalog.bindableIds)
    }

    @Test
    fun useCapabilityIsBoundFirstAndOnlyWhenLazyCapabilitiesExist() {
        val withLazy =
            build(
                listOf(ro("a") to true, ro("b") to true),
                listOf(merged(capability("e", Load.EAGER, local("a"))), merged(capability("l", Load.LAZY, local("b")))),
            )
        assertEquals(
            UseCapabilityTool.NAME,
            withLazy.registry.definitions
                .first()
                .name,
        )

        val onlyEager = build(listOf(ro("a") to true), listOf(merged(capability("e", Load.EAGER, local("a")))))
        assertEquals(setOf("a"), onlyEager.names)
        assertTrue(onlyEager.lazyCapabilities.isEmpty())
    }

    @Test
    fun bindingALazyCapabilityAddsItsToolsAndRepeatingItIsANoOp() {
        val catalog = build(listOf(ro("a") to true, ro("b") to true), listOf(merged(capability("l", Load.LAZY, local("a"), local("b")))))
        assertEquals(setOf(UseCapabilityTool.NAME), catalog.names)

        val first = catalog.bind("l")!!
        assertFalse(first.alreadyBound)
        assertEquals(listOf("a", "b"), first.toolNames)
        assertEquals(setOf(UseCapabilityTool.NAME, "a", "b"), catalog.names)
        val definitionsAfterFirst = catalog.registry.definitions

        val second = catalog.bind("l")!!
        assertTrue(second.alreadyBound)
        assertEquals(definitionsAfterFirst, catalog.registry.definitions)
    }

    @Test
    fun bindingAnUnknownCapabilityReturnsNull() {
        val catalog = build(listOf(ro("a") to true), listOf(merged(capability("l", Load.LAZY, local("a")))))
        assertNull(catalog.bind("nope"))
    }

    @Test
    fun aRemoteToolNamedUseCapabilityIsDropped() {
        val catalog =
            build(
                listOf(ro("a") to true),
                listOf(
                    merged(capability("l", Load.LAZY, local("a"))),
                    merged(capability("s", Load.EAGER, remote(UseCapabilityTool.NAME)), UseCapabilityTool.NAME),
                ),
            )
        // The server's capability has no usable tool left, so it isn't available; ours is the only use_capability.
        assertEquals(listOf("l"), catalog.bindableIds)
        // The registered use_capability is ours: it binds, rather than being the server's "remote:" stand-in.
        val result = runBlocking { catalog.registry.execute(call(UseCapabilityTool.NAME, """{"id":"l"}"""), RecordingConfirmer()) }
        assertEquals("l", JSONObject(result).getString("capability"))
    }

    @Test
    fun aRemoteToolWithAReservedLocalNameIsDroppedEvenIfTheLocalToolIsNotAllowedThisSession() {
        val catalog =
            build(
                listOf(FakeActionTool("send_sms", Confirmation.Ask("?")) to false),
                listOf(merged(capability("srv", Load.EAGER, remote("send_sms")), "send_sms")),
            )
        assertTrue(catalog.bindableIds.isEmpty())
        assertTrue(catalog.names.isEmpty())
    }

    @Test
    fun aLocalToolThatAllowsRemoteIsReplacedByTheRemoteOne() {
        val catalog =
            build(
                listOf(ro("get_weather", allowRemote = true) to true),
                listOf(merged(capability("weather", Load.EAGER, hybrid("get_weather")), "get_weather")),
            )
        val result = runBlocking { catalog.registry.execute(call("get_weather"), RecordingConfirmer()) }
        assertEquals("remote:get_weather", result)
    }

    @Test
    fun theLocalReadOnlyToolStaysBehindTheRemoteOneAsAFallback() {
        val failingRemote: (ToolDefinition) -> Tool = { FakeReadOnlyTool(it.name, failWith = RuntimeException("offline")) }
        val catalog =
            SessionCatalog.build(
                listOf(ro("get_weather", allowRemote = true) to true),
                listOf(merged(capability("weather", Load.EAGER, hybrid("get_weather")), "get_weather")),
                emptyList(),
                failingRemote,
                "test",
            )
        val result = runBlocking { catalog.registry.execute(call("get_weather"), RecordingConfirmer()) }
        assertEquals("local:get_weather", result)
    }

    @Test
    fun noFallbackIsWrappedWhenTheLocalToolIsNotAllowedThisSession() {
        val catalog =
            build(
                listOf(ro("get_weather", allowRemote = true) to false),
                listOf(merged(capability("weather", Load.EAGER, hybrid("get_weather")), "get_weather")),
            )
        val tool = catalog.bind("weather")!!
        assertEquals(listOf("get_weather"), tool.toolNames)
        assertEquals("remote:get_weather", runBlocking { catalog.registry.execute(call("get_weather"), RecordingConfirmer()) })
    }

    @Test
    fun aHybridToolFallsBackToTheLocalToolWhenTheIndexHasNoDefinition() {
        val catalog =
            build(
                listOf(ro("get_weather", allowRemote = true) to true),
                listOf(merged(capability("weather", Load.EAGER, hybrid("get_weather")))),
            )
        assertEquals("local:get_weather", runBlocking { catalog.registry.execute(call("get_weather"), RecordingConfirmer()) })
    }

    @Test
    fun remoteToolsALocalToolCallsItselfAreHiddenFromTheModel() {
        val catalog =
            build(
                listOf(ro("play_music", privateRemote = setOf("music_find")) to true),
                listOf(
                    merged(capability("music", Load.EAGER, local("play_music"), remote("music_find", required = false)), "music_find"),
                ),
            )
        assertEquals(setOf("play_music"), catalog.names)
    }

    @Test
    fun aCapabilityWhoseOnlyRemoteToolIsPrivateIsUnavailable() {
        val catalog =
            build(
                listOf(ro("play_music", privateRemote = setOf("music_find")) to true),
                listOf(merged(capability("find", Load.EAGER, remote("music_find")), "music_find")),
            )
        assertTrue(catalog.bindableIds.isEmpty())
    }

    @Test
    fun privateRemoteToolsStayHiddenEvenWhenTheLocalToolIsNotAllowed() {
        val catalog =
            build(
                listOf(ro("play_music", privateRemote = setOf("music_find")) to false),
                listOf(merged(capability("find", Load.EAGER, remote("music_find")), "music_find")),
            )
        assertTrue(catalog.names.isEmpty())
    }

    @Test
    fun anOptionalToolThatIsMissingDoesNotMakeTheCapabilityUnavailable() {
        val navigate = FakeActionTool("navigate", Confirmation.Announce("go", "go?"))
        val catalog =
            build(
                listOf(navigate to true, ro("find_contact") to false),
                listOf(merged(capability("navigation", Load.EAGER, local("navigate"), local("find_contact", required = false)))),
            )
        assertEquals(setOf("navigate"), catalog.names)
        assertEquals(listOf("navigate"), catalog.bind("navigation")!!.toolNames)
    }

    @Test
    fun aMissingRequiredToolMakesTheCapabilityUnavailable() {
        val navigate = FakeActionTool("navigate", Confirmation.Announce("go", "go?"))
        val catalog =
            build(
                listOf(navigate to false, ro("find_contact") to true),
                listOf(merged(capability("navigation", Load.EAGER, local("navigate"), local("find_contact", required = false)))),
            )
        assertTrue(catalog.bindableIds.isEmpty())
        assertTrue(catalog.names.isEmpty())
    }

    @Test
    fun aCapabilityWithNoResolvedToolsAtAllIsUnavailable() {
        val catalog =
            build(
                listOf(ro("a") to false),
                listOf(merged(capability("opt", Load.EAGER, local("a", required = false)))),
            )
        assertTrue(catalog.bindableIds.isEmpty())
    }

    @Test
    fun aRemoteToolNeedsADefinitionInTheIndex() {
        val catalog = build(emptyList(), listOf(merged(capability("srv", Load.EAGER, remote("web_search")))))
        assertTrue(catalog.bindableIds.isEmpty())
    }

    @Test
    fun remoteToolsWithTheSameNameInTwoCapabilitiesAreCreatedOnce() {
        val catalog =
            build(
                emptyList(),
                listOf(
                    merged(capability("one", Load.EAGER, remote("web_search")), "web_search"),
                    merged(capability("two", Load.LAZY, remote("web_search")), "web_search"),
                ),
            )
        assertEquals(setOf("web_search", UseCapabilityTool.NAME), catalog.names)
        assertEquals(listOf("one", "two"), catalog.bindableIds)
    }

    // --- skills

    private fun skill(
        name: String,
        vararg capabilities: String,
    ) = Skill(name, "$name description", capabilities.toList(), "$name instructions")

    @Test
    fun eagerSkillsAreInstructionOnlyOnesAndThoseOfEagerlyBoundCapabilitiesSortedByName() {
        val catalog =
            build(
                listOf(ro("a") to true, ro("b") to true),
                listOf(merged(capability("e", Load.EAGER, local("a"))), merged(capability("l", Load.LAZY, local("b")))),
                skills = listOf(skill("zz-global"), skill("on-eager", "e"), skill("on-lazy", "l"), skill("aa-both", "l", "e")),
            )
        assertEquals(listOf("aa-both", "on-eager", "zz-global"), catalog.eagerSkills.map { it.name })
    }

    @Test
    fun aSkillAttachedToAnUnavailableCapabilityIsNotActive() {
        val catalog =
            build(
                listOf(ro("a") to false),
                listOf(merged(capability("e", Load.EAGER, local("a")))),
                skills = listOf(skill("needs-e", "e"), skill("plain")),
            )
        assertEquals(listOf("plain"), catalog.eagerSkills.map { it.name })
    }

    @Test
    fun bindingReturnsTheInstructionsOfNewSkillsOnly() {
        val catalog =
            build(
                listOf(ro("a") to true, ro("b") to true),
                listOf(merged(capability("e", Load.EAGER, local("a"))), merged(capability("l", Load.LAZY, local("b")))),
                skills = listOf(skill("late-z", "l"), skill("late-a", "l"), skill("early", "l", "e"), skill("unrelated", "x")),
            )
        val result = catalog.bind("l")!!
        // "early" also attaches to an eager capability, so it is already in the system prompt.
        assertEquals(
            listOf(skill("late-a", "l").asPromptSection(), skill("late-z", "l").asPromptSection()).joinToString("\n\n"),
            result.instructions,
        )
    }

    @Test
    fun bindingTheSameCapabilityAgainStillReturnsItsInstructions() {
        val catalog =
            build(
                listOf(ro("b") to true),
                listOf(merged(capability("l", Load.LAZY, local("b")))),
                skills = listOf(skill("s", "l")),
            )
        val first = catalog.bind("l")!!
        val second = catalog.bind("l")!!
        assertEquals(first.instructions, second.instructions)
        assertNotNull(second.instructions.takeIf { it.isNotEmpty() })
    }

    @Test
    fun remoteActionToolsComeFromTheFactoryAndAskFirst() {
        val remoteAction: (ToolDefinition) -> Tool = { FakeActionTool(it.name, Confirmation.Ask("ask ${it.name}")) }
        val catalog =
            SessionCatalog.build(
                emptyList(),
                listOf(
                    MergedCapability(
                        capability("srv", Load.EAGER, remote("create_event")),
                        mapOf("create_event" to definition("create_event", readOnly = false)),
                    ),
                ),
                emptyList(),
                remoteAction,
                "test",
            )
        val confirmer = RecordingConfirmer()
        runBlocking { catalog.registry.execute(call("create_event", JSONObject().toString()), confirmer) }
        assertEquals(listOf<Confirmation>(Confirmation.Ask("ask create_event")), confirmer.asked)
    }
}
