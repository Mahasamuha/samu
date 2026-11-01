package com.mahasamuha.assistant.capabilities

import android.util.AtomicFile
import android.util.Log
import com.mahasamuha.assistant.auth.AuthManager
import com.mahasamuha.assistant.auth.NotSignedInException
import com.mahasamuha.assistant.llm.ToolDefinition
import com.mahasamuha.assistant.remote.McpClient
import com.mahasamuha.assistant.remote.toolDefinition
import com.mahasamuha.assistant.skills.Skill
import com.mahasamuha.assistant.voice.TAG
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Where an index came from: fetched just now, or read from disk. (No index at all means the bundled manifest alone.) */
enum class IndexSource { FRESH, CACHE }

/** The server's capability index, parsed (the `capability://index` document), with when and for whom it was fetched. */
data class CachedIndex(
    val principal: String,
    val fetchedAtMillis: Long,
    val source: IndexSource,
    val capabilities: List<Capability>,
    /** Definitions of every remote and hybrid tool the capabilities reference. */
    val tools: List<ToolDefinition>,
    /** Server-delivered skills, attached to capabilities. */
    val skills: List<Skill>,
) {
    fun ageMillis(now: Long = System.currentTimeMillis()) = (now - fetchedAtMillis).coerceAtLeast(0)
}

/**
 * The last capability index fetched from the MCP server, kept on disk so a session never waits for the
 * network. It lives in [noBackupFilesDir][android.content.Context.getNoBackupFilesDir], which is excluded
 * from backup and device transfer; it holds non-secret definitions only. It belongs to the signed-in
 * principal: another principal's cache is deleted on sight, and sign-out deletes it ([clear]).
 */
class CapabilityCache(
    directory: File,
    private val auth: AuthManager,
    private val client: McpClient,
) {
    private val file = AtomicFile(File(directory, "capability_index.json"))
    private val lock = Any()
    private var loaded = false
    private var index: CachedIndex? = null

    /** The cached index for the current principal, or null if none (never fetched, another principal's, signed out). */
    suspend fun current(): CachedIndex? {
        val principal = auth.principal()
        return withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (!loaded) {
                    index = readFile()
                    loaded = true
                }
                index?.takeIf { it.principal == principal }.also { if (it == null && index != null) clear() }
            }
        }
    }

    /**
     * Fetches `capability://index` and replaces the cache. On failure the existing cache stays and the
     * result carries the error. The new index is parsed before it's stored, so a bad document never
     * replaces a good one.
     */
    suspend fun refresh(): Result<CachedIndex> {
        return try {
            val principal = auth.principal() ?: throw NotSignedInException()
            val text = client.readResource(INDEX_URI)
            val fresh = withContext(Dispatchers.IO) { parse(text, principal, System.currentTimeMillis(), IndexSource.FRESH) }
            // Sign-out or a switch of principal during the fetch must not leave this index behind.
            val stillSame = auth.principal() == principal
            withContext(Dispatchers.IO) {
                synchronized(lock) {
                    if (stillSame) {
                        write(text, fresh)
                        index = fresh.copy(source = IndexSource.CACHE)
                        loaded = true
                    }
                }
            }
            if (!stillSame) throw NotSignedInException()
            Log.i(TAG, "Capability index refreshed: ${fresh.capabilities.size} capabilities, ${fresh.tools.size} tools, ${fresh.skills.size} skills")
            Result.success(fresh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Capability index refresh failed", e)
            Result.failure(e)
        }
    }

    /** Deletes the cache (sign-out). */
    fun clear() = synchronized(lock) {
        file.delete()
        index = null
        loaded = true
    }

    private fun readFile(): CachedIndex? {
        if (!file.baseFile.exists()) return null
        return try {
            val stored = JSONObject(String(file.readFully(), Charsets.UTF_8))
            parse(stored.getString("index"), stored.getString("principal"), stored.getLong("fetchedAt"), IndexSource.CACHE)
        } catch (e: Exception) {
            Log.w(TAG, "Dropping unreadable capability cache", e)
            file.delete()
            null
        }
    }

    /** Written to a temporary file and renamed into place by [AtomicFile]. */
    private fun write(text: String, parsed: CachedIndex) {
        val stored = JSONObject()
            .put("principal", parsed.principal)
            .put("fetchedAt", parsed.fetchedAtMillis)
            .put("index", text)
        val out = file.startWrite()
        try {
            out.write(stored.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    private fun parse(text: String, principal: String, fetchedAt: Long, source: IndexSource): CachedIndex {
        val json = JSONObject(text)
        val version = json.optInt("version")
        if (version != SUPPORTED_VERSION) throw IllegalStateException("Unsupported capability index version $version")
        val tools = json.optJSONArray("tools")
        val skills = json.optJSONArray("skills")
        return CachedIndex(
            principal = principal,
            fetchedAtMillis = fetchedAt,
            source = source,
            capabilities = Capability.parseAll(json.getJSONArray("capabilities")),
            tools = tools?.let { parseEntries(it, "tool", IndexLimits.MAX_TOOL_DEFINITIONS, ::parseTool) }.orEmpty(),
            skills = skills?.let { parseEntries(it, "skill", IndexLimits.MAX_SKILLS, ::parseSkill) }.orEmpty(),
        )
    }

    private fun parseTool(entry: JSONObject): ToolDefinition {
        val tool = toolDefinition(entry)
        IndexLimits.checkLength("description", tool.description, IndexLimits.MAX_TOOL_DESCRIPTION)
        IndexLimits.checkLength("inputSchema", tool.inputSchema.toString(), IndexLimits.MAX_INPUT_SCHEMA)
        return tool
    }

    private fun parseSkill(entry: JSONObject): Skill {
        val attached = entry.optJSONArray("capabilities")
        val skill = Skill(
            name = entry.getString("name"),
            description = entry.optString("description"),
            capabilities = (0 until (attached?.length() ?: 0)).map { attached!!.getString(it) },
            instructions = entry.optString("instructions"),
        )
        IndexLimits.checkLength("description", skill.description, IndexLimits.MAX_SKILL_DESCRIPTION)
        IndexLimits.checkLength("instructions", skill.instructions, IndexLimits.MAX_SKILL_INSTRUCTIONS)
        return skill
    }

    private companion object {
        const val INDEX_URI = "capability://index"
        const val SUPPORTED_VERSION = 1
    }
}
