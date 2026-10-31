package com.mahasamuha.assistant.capabilities

import android.util.Log
import com.mahasamuha.assistant.llm.ToolDefinition
import com.mahasamuha.assistant.voice.TAG

/** A capability after merging, with the definitions of those of its remote tools the index carries. */
data class MergedCapability(
    val capability: Capability,
    /** Remote and hybrid tools of [capability] that the index has a definition for, by name. */
    val remoteDefinitions: Map<String, ToolDefinition>,
)

object CapabilityCatalog {
    /**
     * Merges the bundled manifest with the cached (or fresh) index (phase 3.5 plan, "Merging"). The APK
     * owns local capabilities and the existence and local tools of hybrid ones; the index adds server
     * capabilities, and the remote tools, `description` and `load` of hybrid ones. Permissions and what's
     * actually available are applied afterwards. Sorted by id.
     */
    fun merge(
        bundled: List<Capability>,
        cached: CachedIndex?,
        log: (String) -> Unit = { Log.w(TAG, it) },
    ): List<MergedCapability> {
        val indexed = cached?.capabilities.orEmpty().associateBy { it.id }
        val definitions = cached?.tools.orEmpty().associateBy { it.name }
        val bundledIds = bundled.map { it.id }.toSet()

        val merged = bundled.map { capability ->
            val fromIndex = indexed[capability.id]?.takeIf { capability.tier == Tier.HYBRID }
            if (fromIndex == null) capability else mergeHybrid(capability, fromIndex)
        }.toMutableList()

        cached?.capabilities.orEmpty().forEach { fromIndex ->
            when {
                fromIndex.id in bundledIds -> {
                    val owner = bundled.first { it.id == fromIndex.id }
                    if (owner.tier != Tier.HYBRID) log("Ignoring index capability ${fromIndex.id}: the app's is ${owner.tier.name.lowercase()}")
                }
                fromIndex.tier == Tier.SERVER -> merged += fromIndex
                else -> log("Ignoring index capability ${fromIndex.id}: ${fromIndex.tier.name.lowercase()} but unknown to the app")
            }
        }

        return merged.sortedBy { it.id }.map { capability ->
            val remote = capability.tools.filter { it.where != Where.LOCAL }.mapNotNull { tool -> definitions[tool.name]?.let { tool.name to it } }
            MergedCapability(capability, remote.toMap())
        }
    }

    /** The APK's local tools stay; the index supplies the remote ones. A hybrid tool the index lacks keeps the bundled entry. */
    private fun mergeHybrid(bundled: Capability, index: Capability): Capability {
        val indexRemote = index.tools.filter { it.where != Where.LOCAL }
        val indexNames = indexRemote.map { it.name }.toSet()
        val kept = bundled.tools.filter { it.where == Where.LOCAL || (it.where == Where.HYBRID && it.name !in indexNames) }
        return bundled.copy(
            description = index.description.ifEmpty { bundled.description },
            load = index.load,
            tools = (kept + indexRemote).distinctBy { it.name },
        )
    }
}
