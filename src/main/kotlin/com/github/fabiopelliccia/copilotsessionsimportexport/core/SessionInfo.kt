package com.github.fabiopelliccia.copilotsessionsimportexport.core

/**
 * Metadata describing a single Copilot chat session.
 *
 * Instances are used both to populate the selection tables and as the payload of the
 * archive manifest, therefore every field must stay serializable by Gson.
 */
data class SessionInfo(
    val id: String,
    val name: String? = null,
    val summary: String? = null,
    val repository: String? = null,
    val cwd: String? = null,
    val branch: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val turnCount: Int = 0,
    val hasState: Boolean = false,
    val inStore: Boolean = false,
) {
    val displayName: String
        get() = sequenceOf(name, summary, cwd)
            .filterNotNull()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.let { if (it.length > 160) it.take(157) + "..." else it }
            ?: id
}

/** Reports progress of a long running transfer operation. */
fun interface TransferProgress {
    fun report(message: String, fraction: Double)

    companion object {
        val NOOP: TransferProgress = TransferProgress { _, _ -> }
    }
}

/**
 * Strategy applied when an imported session id already exists locally.
 *
 * [toString] is what the combo box of the import dialog renders, so it is translated; the log writes
 * [name] instead, to stay readable whatever the language of the machine that produced it.
 */
enum class ConflictPolicy(private val key: String) {
    SKIP("conflict.skip"),
    REPLACE("conflict.replace"),
    DUPLICATE("conflict.duplicate");

    override fun toString(): String = CopilotSessionsBundle.message(key)
}

/**
 * What the destination machine really contains after a session has been written.
 *
 * The values are read back from disk, so they describe what Copilot itself will see:
 * [listed] tells whether `session-state/<id>/workspace.yaml` carries the metadata the chat
 * history is built from, [inStore] whether the session is also indexed in `session-store.db`
 * for search, and [cwd] is the folder the session is bound to.
 *
 * [ideRecordRestored] covers the other half of the story: the entry the GitHub Copilot plugin keeps
 * inside the IDE. Without it the session exists on disk but the chat tool window never lists it,
 * so an import can pass every other check and still change nothing - see [IdeSessionRecord].
 */
data class ImportedSession(
    val id: String,
    val displayName: String,
    val cwd: String?,
    val turnCount: Int = 0,
    val hasState: Boolean = false,
    val listed: Boolean = false,
    val inStore: Boolean = false,
    val ideRecordPresent: Boolean = false,
    val ideRecordRestored: Boolean = false,
)

data class ImportOutcome(
    val imported: List<ImportedSession> = emptyList(),
    val skipped: List<String> = emptyList(),
    val failures: Map<String, String> = emptyMap(),
) {
    val importedIds: List<String> get() = imported.map { it.id }
}

/**
 * Result of an export. [warnings] lists everything that made the archive less than complete, and
 * [unreadableFiles] counts the `session-state` files that were locked while exporting: only those
 * are fixed by closing the conversation and exporting again.
 */
data class ExportOutcome(
    val sessions: Int = 0,
    val warnings: List<String> = emptyList(),
    val unreadableFiles: Int = 0,
)
