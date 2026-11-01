package com.mahasamuha.assistant.capabilities

import android.util.Log
import com.mahasamuha.assistant.llm.ToolDefinition
import com.mahasamuha.assistant.remote.FallbackTool
import com.mahasamuha.assistant.skills.Skill
import com.mahasamuha.assistant.tools.ReadOnlyTool
import com.mahasamuha.assistant.tools.Tool
import com.mahasamuha.assistant.tools.ToolRegistry
import com.mahasamuha.assistant.voice.TAG

/** A capability that can be used in this session, with the tools it binds (optional missing ones left out). */
class AvailableCapability(val capability: Capability, val tools: List<Tool>)

/** What a bind returned to the model. */
class BindResult(val capability: String, val toolNames: List<String>, val instructions: String, val alreadyBound: Boolean)

/**
 * The capabilities usable in one session. Eager ones are bound into [registry] at construction; lazy ones
 * are listed in the prompt and bound by `use_capability` ([bind]), which adds their tools to the live
 * registry. Skills are active when any capability they attach to is bound; skills with none are always on.
 */
class SessionCatalog(
    available: List<AvailableCapability>,
    private val skills: List<Skill>,
    /** Where the index came from (fresh, cache, bundled), for the log. */
    val source: String,
) {
    private val byId = available.associateBy { it.capability.id }
    private val bound = mutableSetOf<String>()

    val registry = ToolRegistry()

    /** Lazy capabilities the model can bind, sorted by id. */
    val lazyCapabilities: List<Capability> = available.map { it.capability }.filter { it.load == Load.LAZY }.sortedBy { it.id }

    /** Every id [bind] accepts (eager and lazy), sorted. */
    val bindableIds: List<String> = byId.keys.sorted()

    /** Skills for the system prompt: instruction-only ones, and those attached to an eagerly bound capability. */
    val eagerSkills: List<Skill>

    init {
        // First, so a server tool can never shadow it (build() also drops remote tools with its name).
        if (lazyCapabilities.isNotEmpty()) registry.bind(listOf(UseCapabilityTool(this)))
        val eager = available.filter { it.capability.load == Load.EAGER }
        eager.forEach { bindTools(it) }
        eagerSkills = skills.filter { skill -> skill.capabilities.isEmpty() || skill.capabilities.any { it in bound } }.sortedBy { it.name }
        Log.i(
            TAG,
            "Capabilities: index=$source; listed ${available.joinToString { it.capability.id }.ifEmpty { "none" }}; " +
                "bound eagerly ${eager.joinToString { it.capability.id }.ifEmpty { "none" }}; " +
                "lazy ${lazyCapabilities.joinToString { it.id }.ifEmpty { "none" }}; " +
                "skills ${eagerSkills.joinToString { it.name }.ifEmpty { "none" }}",
        )
    }

    /** Binds an available capability (a no-op the second time); null if there's no such capability. */
    fun bind(id: String): BindResult? {
        val entry = byId[id] ?: return null
        val already = id in bound
        bindTools(entry)
        // Skills already in the system prompt are not repeated.
        val instructions = skills.filter { id in it.capabilities && it !in eagerSkills }.sortedBy { it.name }
            .joinToString("\n\n") { it.asPromptSection() }
        if (!already) Log.i(TAG, "Bound capability $id: ${entry.tools.joinToString { it.definition.name }}")
        return BindResult(id, entry.tools.map { it.definition.name }, instructions, already)
    }

    private fun bindTools(entry: AvailableCapability) {
        registry.bind(entry.tools)
        bound += entry.capability.id
    }

    companion object {
        /**
         * Resolves the merged capabilities against what this session has. A capability is available when
         * every required tool is: a local tool by permission (it's in [candidates] allowed), a remote one by
         * having a definition in the index, a hybrid one by either. The reserved/private/override rules are
         * those of plan §4b: a remote tool replaces a local one only if the local one allows it, and a
         * read-only local tool stays behind the remote one as the fallback.
         */
        fun build(
            candidates: List<Pair<Tool, Boolean>>,
            merged: List<MergedCapability>,
            skills: List<Skill>,
            remoteTool: (ToolDefinition) -> Tool,
            source: String,
        ): SessionCatalog {
            val local = candidates.filter { (_, allowed) -> allowed }.map { (tool, _) -> tool }
            val localByName = local.associateBy { it.definition.name }
            // A local tool's name stays reserved even in a session that leaves the tool out.
            val reserved = candidates.map { (tool, _) -> tool }.filterNot { it.allowRemote }.map { it.definition.name }.toSet() + UseCapabilityTool.NAME
            // Remote tools that a local tool calls itself never reach the model.
            val privateNames = candidates.flatMap { (tool, _) -> tool.privateRemoteTools }.toSet()

            val remoteByName = merged.flatMap { it.remoteDefinitions.values }
                .distinctBy { it.name }
                .filter { it.name !in privateNames }
                .filter { definition ->
                    (definition.name !in reserved).also { kept ->
                        if (!kept) Log.w(TAG, "Ignoring remote ${definition.name}: the name is reserved or the local one doesn't allow a remote override")
                    }
                }
                .associate { definition ->
                    val remote = remoteTool(definition)
                    val shadowed = localByName[definition.name]
                    definition.name to if (remote is ReadOnlyTool && shadowed is ReadOnlyTool) FallbackTool(remote, shadowed) else remote
                }

            fun resolve(tool: CapabilityTool): Tool? = when (tool.where) {
                Where.LOCAL -> localByName[tool.name]
                Where.REMOTE -> remoteByName[tool.name]
                Where.HYBRID -> remoteByName[tool.name] ?: localByName[tool.name]
            }

            val available = merged.mapNotNull { (capability, _) ->
                val resolved = capability.tools.map { it to resolve(it) }
                val missing = resolved.filter { (tool, bound) -> bound == null && tool.required }.map { (tool, _) -> tool.name }
                val tools = resolved.mapNotNull { (_, bound) -> bound }
                if (missing.isNotEmpty() || tools.isEmpty()) {
                    Log.i(TAG, "Capability ${capability.id} unavailable: missing ${missing.joinToString().ifEmpty { "all tools" }}")
                    null
                } else {
                    AvailableCapability(capability, tools)
                }
            }
            return SessionCatalog(available, skills, source)
        }
    }
}
