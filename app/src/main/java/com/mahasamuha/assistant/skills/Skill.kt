package com.mahasamuha.assistant.skills

import android.content.res.AssetManager
import android.util.Log
import com.mahasamuha.assistant.voice.TAG

/** An instruction bundle in the SKILL.md format (plan §4b, skills/README.md). */
data class Skill(
    val name: String,
    val description: String,
    /** Capabilities it attaches to (capabilities/README.md); empty for an instruction-only skill. */
    val capabilities: List<String> = emptyList(),
    val instructions: String,
) {
    /** The skill as the model reads it, in the system prompt or a capability bind result. */
    fun asPromptSection() = "## Skill: $name\n$description\n\n$instructions"

    companion object {
        /** Parses YAML frontmatter (a `key: value` subset, `capabilities` as `[a, b]`) followed by the instructions. */
        fun parse(text: String): Skill {
            val normalized = text.replace("\r\n", "\n")
            require(normalized.startsWith("---\n")) { "SKILL.md must start with frontmatter" }
            val end = normalized.indexOf("\n---", startIndex = 4)
            require(end >= 0) { "SKILL.md frontmatter isn't closed" }

            val fields = normalized.substring(4, end).lines()
                .mapNotNull { line -> line.split(":", limit = 2).takeIf { it.size == 2 }?.let { (k, v) -> k.trim() to v.trim() } }
                .toMap()
            val capabilities = fields["capabilities"]?.removeSurrounding("[", "]")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

            return Skill(
                name = requireNotNull(fields["name"]?.takeIf { it.isNotEmpty() }) { "SKILL.md needs a name" },
                description = fields["description"].orEmpty(),
                capabilities = capabilities,
                instructions = normalized.substring(end + 4).trim(),
            )
        }
    }
}

/** Skills bundled into the APK at build time (`delivery: app`). */
class BundledSkills(private val assets: AssetManager) {
    fun load(): List<Skill> = assets.list(DIR).orEmpty().sorted().mapNotNull { name ->
        try {
            assets.open("$DIR/$name/SKILL.md").bufferedReader().use { Skill.parse(it.readText()) }
        } catch (e: Exception) {
            Log.w(TAG, "Skipping skill $name", e)
            null
        }
    }

    private companion object {
        const val DIR = "skills"
    }
}
