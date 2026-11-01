package com.mahasamuha.assistant.capabilities

import com.mahasamuha.assistant.testutil.capability
import com.mahasamuha.assistant.testutil.definition
import com.mahasamuha.assistant.testutil.hybrid
import com.mahasamuha.assistant.testutil.local
import com.mahasamuha.assistant.testutil.remote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityCatalogTest {
    private fun index(
        capabilities: List<Capability>,
        vararg toolNames: String,
    ) = CachedIndex(
        principal = "user-1",
        fetchedAtMillis = 0,
        source = IndexSource.CACHE,
        capabilities = capabilities,
        tools = toolNames.map { definition(it) },
        skills = emptyList(),
    )

    private val logs = mutableListOf<String>()

    private fun merge(
        bundled: List<Capability>,
        cached: CachedIndex?,
    ) = CapabilityCatalog.merge(bundled, cached) { logs += it }

    @Test
    fun withoutAnIndexTheBundledCapabilitiesAreAllThereIs() {
        val bundled = listOf(capability("b", Load.LAZY, local("x")), capability("a", Load.EAGER, local("y")))
        val merged = merge(bundled, null)
        assertEquals(listOf("a", "b"), merged.map { it.capability.id })
        assertTrue(merged.all { it.remoteDefinitions.isEmpty() })
    }

    @Test
    fun anUnknownHybridCapabilityInTheIndexIsIgnored() {
        val cached = index(listOf(capability("surprise", Load.EAGER, local("x"), remote("r"))), "r")
        val merged = merge(listOf(capability("a", Load.EAGER, local("y"))), cached)
        assertEquals(listOf("a"), merged.map { it.capability.id })
        assertTrue(logs.single().contains("surprise"))
    }

    @Test
    fun anUnknownLocalCapabilityInTheIndexIsIgnored() {
        val cached = index(listOf(capability("ghost", Load.EAGER, local("x"))))
        assertEquals(emptyList<String>(), merge(emptyList(), cached).map { it.capability.id })
    }

    @Test
    fun theIndexCannotOverrideALocalCapability() {
        val bundled = capability("phone", Load.EAGER, local("place_call"), description = "from the app")
        val fromIndex = capability("phone", Load.LAZY, remote("evil_call"), description = "from the server")
        val merged = merge(listOf(bundled), index(listOf(fromIndex), "evil_call"))

        val phone = merged.single()
        assertEquals(bundled, phone.capability)
        // Its remote tool definition is not offered either: the capability has no remote tool.
        assertTrue(phone.remoteDefinitions.isEmpty())
        assertTrue(logs.single().contains("phone"))
    }

    @Test
    fun aServerCapabilityFromTheIndexIsAdded() {
        val server = capability("search", Load.LAZY, remote("web_search"))
        val merged = merge(listOf(capability("a", Load.EAGER, local("x"))), index(listOf(server), "web_search", "unrelated"))

        assertEquals(listOf("a", "search"), merged.map { it.capability.id })
        val added = merged.last()
        assertEquals(server, added.capability)
        assertEquals(setOf("web_search"), added.remoteDefinitions.keys)
    }

    @Test
    fun aHybridTakesDescriptionAndLoadFromTheIndexAndItsRemoteTools() {
        val bundled = capability("weather", Load.EAGER, local("get_weather"), description = "bundled text")
        val fromIndex = capability("weather", Load.LAZY, remote("pirate_forecast"), description = "server text")
        // Bundled needs a remote/hybrid tool to count as hybrid.
        val hybridBundled = bundled.copy(tools = bundled.tools + hybrid("pirate_forecast"))

        val merged = merge(listOf(hybridBundled), index(listOf(fromIndex), "pirate_forecast")).single()

        assertEquals("server text", merged.capability.description)
        assertEquals(Load.LAZY, merged.capability.load)
        assertEquals(listOf("get_weather", "pirate_forecast"), merged.capability.tools.map { it.name })
        assertEquals(
            Where.REMOTE,
            merged.capability.tools
                .last()
                .where,
        )
        assertEquals(setOf("pirate_forecast"), merged.remoteDefinitions.keys)
    }

    @Test
    fun aHybridKeepsTheBundledDescriptionWhenTheIndexHasNone() {
        val bundled = capability("w", Load.EAGER, local("a"), hybrid("h"), description = "bundled text")
        val fromIndex = capability("w", Load.EAGER, remote("r"), description = "")
        val merged = merge(listOf(bundled), index(listOf(fromIndex), "r")).single()
        assertEquals("bundled text", merged.capability.description)
    }

    @Test
    fun aHybridToolTheIndexLacksKeepsItsBundledEntry() {
        val bundled = capability("w", Load.EAGER, local("a"), hybrid("h1"), hybrid("h2"))
        val fromIndex = capability("w", Load.EAGER, remote("h1"))
        val merged = merge(listOf(bundled), index(listOf(fromIndex), "h1")).single()

        val byName = merged.capability.tools.associateBy { it.name }
        assertEquals(Where.REMOTE, byName.getValue("h1").where)
        assertEquals(Where.HYBRID, byName.getValue("h2").where)
        assertEquals(setOf("a", "h1", "h2"), byName.keys)
    }

    @Test
    fun aHybridIgnoresLocalToolsTheIndexClaims() {
        val bundled = capability("w", Load.EAGER, local("a"), hybrid("h"))
        val fromIndex = capability("w", Load.EAGER, local("sneaky"), remote("h"))
        val merged = merge(listOf(bundled), index(listOf(fromIndex), "h")).single()
        assertEquals(
            setOf("a", "h"),
            merged.capability.tools
                .map { it.name }
                .toSet(),
        )
    }

    @Test
    fun remoteDefinitionsAreOnlyThoseTheIndexCarries() {
        val bundled = capability("w", Load.EAGER, local("a"), hybrid("h"))
        val merged = merge(listOf(bundled), index(emptyList())).single()
        assertNull(merged.remoteDefinitions["h"])
        assertTrue(merged.remoteDefinitions.isEmpty())
    }
}
