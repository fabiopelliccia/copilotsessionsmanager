package com.github.fabiopelliccia.copilotsessionsimportexport.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.util.UUID

/**
 * The session record the GitHub Copilot plugin keeps **inside the IDE**, carried through the
 * archive as raw JSON.
 *
 * Restoring `~/.copilot` is not enough to make a conversation reappear in the chat: that folder
 * belongs to the Copilot CLI, while the list the chat tool window renders is served by a per
 * project Nitrite database of the IDE plugin
 * (`<local app data>/github-copilot/<ide>/chat-agent-sessions/<project>/copilot-agent-sessions-nitrite.db`),
 * whose rows are the `NtAgentSession` / `NtAgentTurn` / `NtAgentWorkingSetItem` entities. A session
 * without such a row is restored correctly on disk and still never listed.
 *
 * `core/` must not depend on the IntelliJ or Copilot APIs, so the entities are kept as the JSON
 * tree they serialize to. The reflective bridge that reads and writes them lives in `ui/`.
 *
 * [conversationId] is the Copilot CLI session id - the folder name under `session-state` - and is
 * what ties this record back to the rest of the archive.
 */
data class IdeSessionRecord(
    val conversationId: String? = null,
    val session: JsonObject? = null,
    val turns: JsonArray = JsonArray(),
    val workingSet: JsonArray = JsonArray(),
) {
    val isEmpty: Boolean get() = session == null

    val ideSessionId: String? get() = session?.stringOrNull("id")
}

/**
 * Adopts an [IdeSessionRecord] produced on another machine.
 *
 * The rewrite is structural - key by key on the JSON tree, never a text substitution - so it obeys
 * the same rule as the rest of the import: the conversation itself is never modified. Only the
 * identifiers, the epoch millisecond timestamps and the machine facing path fields are touched;
 * `stringContent` and `contents`, which carry what the user and the agent said, are left exactly as
 * they were.
 */
object IdeSessionRewriter {

    /** Keys holding an epoch millisecond timestamp, shifted by the import's single offset. */
    private val TIMESTAMP_KEYS = setOf("createdAt", "modifiedAt", "activeAt", "deletedAt")

    /** Keys holding an absolute path of the source machine. */
    private val PATH_KEYS = setOf("fileUrl", "path", "filePath", "dirName", "workspaceFolder", "uri")

    /**
     * Returns a copy of [record] bound to this machine: [targetConversationId] replaces the CLI
     * session id, a fresh IDE side id is generated unless [newIdeSessionId] says otherwise, every
     * timestamp is shifted by [deltaMillis] and every path is translated through [mappers].
     */
    fun adopt(
        record: IdeSessionRecord,
        targetConversationId: String,
        deltaMillis: Long,
        mappers: List<PathMapper>,
        newIdeSessionId: String = UUID.randomUUID().toString(),
    ): IdeSessionRecord {
        val session = record.session?.deepCopy() ?: return record
        val turns = record.turns.deepCopy()
        val workingSet = record.workingSet.deepCopy()
        val enabledMappers = mappers.filter { it.isEnabled }

        rewrite(session, deltaMillis, enabledMappers)
        rewrite(turns, deltaMillis, enabledMappers)
        rewrite(workingSet, deltaMillis, enabledMappers)

        session.addProperty("id", newIdeSessionId)
        session.addProperty("conversationId", targetConversationId)
        // Turns and working set travel as separate lists and are re-attached when the record is
        // converted back: leaving a copy inside the session object would duplicate them.
        session.remove("turns")
        session.remove("workingSet")
        for (element in turns) (element as? JsonObject)?.addProperty("sessionId", newIdeSessionId)
        for (element in workingSet) (element as? JsonObject)?.addProperty("sessionId", newIdeSessionId)

        return IdeSessionRecord(
            conversationId = targetConversationId,
            session = session,
            turns = turns,
            workingSet = workingSet,
        )
    }

    private fun rewrite(element: JsonElement, deltaMillis: Long, mappers: List<PathMapper>) {
        when {
            element.isJsonObject -> {
                val obj = element.asJsonObject
                for (key in obj.keySet().toList()) {
                    val value = obj.get(key)
                    when {
                        key in TIMESTAMP_KEYS && value.isNumber() ->
                            obj.addProperty(key, value.asJsonPrimitive.asLong + deltaMillis)

                        key in PATH_KEYS && value.isNonBlankString() ->
                            obj.addProperty(key, mapAll(value.asString, mappers))

                        else -> rewrite(value, deltaMillis, mappers)
                    }
                }
            }

            element.isJsonArray -> element.asJsonArray.forEach { rewrite(it, deltaMillis, mappers) }
        }
    }

    private fun mapAll(value: String, mappers: List<PathMapper>): String =
        mappers.fold(value) { current, mapper -> mapper.map(current) ?: current }

    private fun JsonElement.isNumber(): Boolean =
        isJsonPrimitive && (this as JsonPrimitive).isNumber

    private fun JsonElement.isNonBlankString(): Boolean =
        isJsonPrimitive && (this as JsonPrimitive).isString && asString.isNotBlank()
}

internal fun JsonObject.stringOrNull(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

/**
 * Writes an [IdeSessionRecord] into the GitHub Copilot plugin storage of the IDE.
 *
 * The implementation needs the Copilot and IntelliJ APIs, which `core/` is not allowed to depend
 * on, so the import receives it from the UI layer. [NOOP] restores nothing and is what tests and
 * headless callers use.
 */
fun interface IdeSessionRestorer {
    /** Returns `true` when the record was actually written to the IDE storage. */
    fun restore(record: IdeSessionRecord, log: ImportLog): Boolean

    companion object {
        val NOOP: IdeSessionRestorer = IdeSessionRestorer { _, _ -> false }
    }
}
