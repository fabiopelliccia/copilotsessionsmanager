package com.github.fabiopelliccia.copilotsessionsimportexport.core

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.io.BufferedReader
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Removes every trace of the machine that produced an archive from the restored
 * `session-state/<id>` folder.
 *
 * The chat history is **not** built from `session-store.db`: the CLI answers `session.list`
 * with the metadata of the `session-state` folders and the IDE filters that answer with an
 * exact comparison on the working directory. A session whose state files still describe the
 * source machine is therefore restored without ever becoming usable:
 *
 * - `events.jsonl` opens with a `session.start` event carrying the **original** `sessionId`
 *   and the **original** `context.cwd`; the CLI rewrites both when it forks a session itself.
 * - every `hook.start` event repeats the same `sessionId` and `cwd`.
 * - `rewind-file-snapshots/index.json` and the checkpoint paths point at folders that only
 *   exist on the source machine.
 *
 * Values are rewritten structurally (JSON aware) rather than by text substitution, so the
 * conversation content is never altered.
 */
internal class SessionStateRewriter(
    private val sourceId: String,
    private val targetId: String,
    /** Maps any path recorded below, at, or above the working directory of the source machine. */
    private val workspaceMapper: PathMapper,
    /** Canonical destination folder, forced on every `cwd` entry, or `null` to keep the original. */
    private val destinationCwd: String?,
    /** Maps `~/.copilot/session-state/<source id>` of the source machine onto the local folder. */
    private val stateMapper: PathMapper?,
    /** Maps the rest of `~/.copilot` of the source machine onto the local Copilot home. */
    private val homeMapper: PathMapper? = null,
    /** Milliseconds added to every recognised timestamp key, so the session looks freshly created. */
    private val deltaMillis: Long = 0L,
    private val log: ImportLog = ImportLog.NOOP,
) {

    companion object {
        private const val EVENTS_FILE = "events.jsonl"
        private val SNAPSHOT_INDEX = listOf("rewind-file-snapshots", "index.json")

        /**
         * Keys whose value is a single absolute path that has to follow the relocated session.
         *
         * Only machine facing keys belong here. Free text produced by the conversation itself -
         * `content`, `intention`, `command`, `detailedContent`, ... - mentions the same folders but
         * is deliberately left untouched, because rewriting it would alter the transcript.
         */
        internal val PATH_KEYS: Set<String> = setOf(
            "cwd",
            "gitRoot",
            "git_root",
            "workingDirectory",
            "checkpointPath",
            "sessionStatePath",
            "transcriptPath",
            "path",
            "paths",
            "possiblePaths",
            "filePath",
            "file_path",
            "fileName",
        )

        private const val CWD_KEY = "cwd"
        private const val SESSION_START = "session.start"

        private val GSON: Gson = Gson()

        /**
         * Scans `events.jsonl` **without rewriting anything**, collecting the timestamp keys of
         * [TimestampShifter.EVENT_TIMESTAMP_KEYS], so the caller can compute the delta to shift by
         * before [rewrite] runs. Unparsable lines are skipped, exactly as [rewrite] copies them
         * verbatim instead of failing the import.
         */
        fun maxTimestampMillis(stateDir: Path): Long? {
            val file = stateDir.resolve(EVENTS_FILE)
            if (!Files.isRegularFile(file)) return null
            var max: Long? = null
            runCatching {
                Files.newBufferedReader(file, StandardCharsets.UTF_8).use { reader ->
                    reader.forEachLine { line ->
                        if (line.isBlank()) return@forEachLine
                        val parsed = runCatching { JsonParser.parseString(line) }.getOrNull() ?: return@forEachLine
                        collectTimestamps(parsed, null) { instant ->
                            if (max == null || instant > max!!) max = instant
                        }
                    }
                }
            }
            return max
        }

        private fun collectTimestamps(element: JsonElement, key: String?, onFound: (Long) -> Unit) {
            when {
                element.isJsonObject -> for ((name, value) in element.asJsonObject.entrySet()) {
                    collectTimestamps(value, name, onFound)
                }

                element.isJsonArray -> element.asJsonArray.forEach { collectTimestamps(it, key, onFound) }
                element.isJsonPrimitive && key != null && key in TimestampShifter.EVENT_TIMESTAMP_KEYS ->
                    TimestampShifter.parse(element.asJsonPrimitive.asString)?.let { onFound(it.toEpochMilli()) }
            }
        }
    }

    // -- §C.6/§C.7 counters, reported through [log] once the rewrite of this session is complete. --
    private var linesRead = 0
    private var linesWritten = 0
    private var unparsableLines = 0
    private var firstEventType: String? = null
    private var sessionIdAdopted = false
    private var alreadyInUseCleared = false
    private var contextCwdAdopted = false
    private var timestampsShifted = 0
    private val remappedByKey = LinkedHashMap<String, Int>()

    fun rewrite(stateDir: Path) {
        rewriteEvents(stateDir.resolve(EVENTS_FILE))
        rewriteJson(SNAPSHOT_INDEX.fold(stateDir) { dir, part -> dir.resolve(part) })
        logSummary()
    }

    private fun logSummary() {
        log.kv("events.linesRead", linesRead)
        log.kv("events.linesWritten", linesWritten)
        log.kv("events.unparsableCopiedVerbatim", unparsableLines)
        log.kv("events.firstEventType", firstEventType)
        log.kv("events.adoptSessionStart.sessionIdWritten", sessionIdAdopted)
        log.kv("events.adoptSessionStart.alreadyInUseCleared", alreadyInUseCleared)
        log.kv("events.adoptSessionStart.contextCwdWritten", contextCwdAdopted)
        log.kv("events.timestampsShifted", timestampsShifted)
        for ((key, count) in remappedByKey) log.kv("events.remapped.$key", count)
    }

    /**
     * Rewrites `events.jsonl` line by line. Lines that cannot be parsed are copied verbatim:
     * a conversation restored with one unreadable event is still far better than none.
     */
    private fun rewriteEvents(file: Path) {
        if (!Files.isRegularFile(file)) return
        val temp = Files.createTempFile(file.parent, "events", ".tmp")
        try {
            Files.newBufferedReader(file, StandardCharsets.UTF_8).use { reader ->
                Files.newBufferedWriter(temp, StandardCharsets.UTF_8).use { writer ->
                    copyEvents(reader, writer)
                }
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(temp) }
            throw e
        }
    }

    private fun copyEvents(reader: BufferedReader, writer: BufferedWriter) {
        var first = true
        reader.forEachLine { line ->
            linesRead++
            if (line.isBlank()) return@forEachLine
            val parsed = runCatching { JsonParser.parseString(line) }.getOrNull()
            val output = if (parsed == null) {
                unparsableLines++
                line
            } else {
                if (first) {
                    firstEventType = (parsed as? JsonObject)
                        ?.get("type")
                        ?.takeIf { it.isJsonPrimitive }
                        ?.asString
                }
                val element = map(parsed)
                if (first) adoptSessionStart(element)
                GSON.toJson(element)
            }
            first = false
            writer.write(output)
            writer.newLine()
            linesWritten++
        }
    }

    /**
     * The very first event describes the session itself. Besides the identifier and the working
     * directory - already handled by [map] - the `alreadyInUse` flag has to be cleared, because
     * an archive is normally produced while the source chat is open and the CLI would otherwise
     * consider the restored conversation as owned by another process.
     */
    private fun adoptSessionStart(element: JsonElement) {
        val event = element as? JsonObject ?: return
        if (event.get("type")?.takeIf { it.isJsonPrimitive }?.asString != SESSION_START) return
        val data = event.getAsJsonObject("data") ?: return
        data.add("sessionId", JsonPrimitive(targetId))
        sessionIdAdopted = true
        if (data.has("alreadyInUse")) {
            data.add("alreadyInUse", JsonPrimitive(false))
            alreadyInUseCleared = true
        }
        destinationCwd?.let {
            data.getAsJsonObject("context")?.add(CWD_KEY, JsonPrimitive(it))
            contextCwdAdopted = true
        }
    }

    private fun rewriteJson(file: Path) {
        if (!Files.isRegularFile(file)) {
            log.kv("rewindSnapshots.present", false)
            return
        }
        log.kv("rewindSnapshots.present", true)
        val original = runCatching { Files.readString(file, StandardCharsets.UTF_8) }.getOrNull()
        val parsed = runCatching {
            Files.newBufferedReader(file, StandardCharsets.UTF_8).use { JsonParser.parseReader(it) }
        }.onFailure { log.kv("rewindSnapshots.parseError", it.message ?: it.javaClass.simpleName) }
            .getOrNull() ?: return
        val mapped = GSON.toJson(map(parsed))
        log.kv("rewindSnapshots.remapped", original != null && mapped != original)
        runCatching { Files.write(file, mapped.toByteArray(StandardCharsets.UTF_8)) }
    }

    private fun map(element: JsonElement, key: String? = null): JsonElement = when {
        element.isJsonObject -> JsonObject().apply {
            for ((name, value) in element.asJsonObject.entrySet()) {
                add(name, map(value, name))
            }
        }

        element.isJsonArray -> JsonArray().apply {
            // Array items keep the key of the property holding the array: `paths: [...]`.
            element.asJsonArray.forEach { add(map(it, key)) }
        }

        element.isJsonPrimitive && element.asJsonPrimitive.isString -> mapString(element.asString, key)
        element.isJsonPrimitive && element.asJsonPrimitive.isNumber && isTimestampKey(key) ->
            shiftNumber(element.asJsonPrimitive)

        else -> element
    }

    private fun isTimestampKey(key: String?): Boolean = key != null && key in TimestampShifter.EVENT_TIMESTAMP_KEYS

    private fun shiftNumber(primitive: JsonPrimitive): JsonElement {
        val text = primitive.asString
        val shifted = TimestampShifter.shift(text, deltaMillis)
        val asLong = shifted.toLongOrNull() ?: return primitive
        if (shifted != text) timestampsShifted++
        return JsonPrimitive(asLong)
    }

    private fun mapString(value: String, key: String?): JsonElement {
        if (value == sourceId) return JsonPrimitive(targetId)
        if (isTimestampKey(key)) {
            val shifted = TimestampShifter.shift(value, deltaMillis)
            if (shifted != value) timestampsShifted++
            return JsonPrimitive(shifted)
        }
        if (key == null || key !in PATH_KEYS) return JsonPrimitive(value)
        if (key == CWD_KEY && destinationCwd != null) {
            if (value != destinationCwd) remap(key)
            return JsonPrimitive(destinationCwd)
        }
        val translated = translate(value)
        val result = if (translated == null) {
            // A path none of the mappers recognises - typically written through an environment
            // variable, as in `$env:USERPROFILE\.copilot\session-state\<id>`. The folder name is
            // still ours to fix, so that it points at this session rather than the original one.
            value.replace(sourceId, targetId)
        } else {
            CopilotPaths.normalizeWorkspacePath(translated.replace(sourceId, targetId)) ?: value
        }
        if (result != value) remap(key)
        return JsonPrimitive(result)
    }

    private fun remap(key: String) {
        remappedByKey[key] = (remappedByKey[key] ?: 0) + 1
    }

    /**
     * The most specific mapper wins: the folder of this very session first, then the rest of the
     * source `~/.copilot` - other sessions, caches, the IDE registry - and finally the workspace.
     */
    private fun translate(value: String): String? =
        stateMapper?.map(value)?.takeIf { it != value }
            ?: homeMapper?.map(value)?.takeIf { it != value }
            ?: workspaceMapper.map(value)
}
