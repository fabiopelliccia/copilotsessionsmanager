package com.github.fabiopelliccia.copilotsessionsimportexport.core

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonPrimitive
import java.io.BufferedInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class ArchiveManifest(
    val formatVersion: Int = SessionTransfer.FORMAT_VERSION,
    val exportedAt: String = "",
    val producer: String = "",
    val sourceHome: String? = null,
    val sessions: List<SessionInfo> = emptyList(),
)

/**
 * Facts about the IDE and JVM host that `core/` cannot read itself (no `com.intellij.*`
 * dependency is allowed here), supplied by the UI layer so the import log's environment section
 * is complete. Every field is best-effort and may be `null` when the caller does not have it -
 * for example the export action, or a test, never fills this in.
 */
data class ImportEnvironment(
    val pluginVersion: String? = null,
    val ideBuild: String? = null,
    val productName: String? = null,
    val copilotPluginId: String? = null,
    val copilotPluginVersion: String? = null,
    val copilotPluginEnabled: Boolean? = null,
)

/**
 * Exports and imports Copilot chat sessions as self contained `.zip` archives.
 *
 * Archive layout:
 * - `manifest.json` - see [ArchiveManifest]
 * - `sessions/<id>/store.json` - rows coming from `session-store.db`
 * - `sessions/<id>/state/...` - verbatim copy of `session-state/<id>`
 * - `sessions/<id>/ide-session.json` - the IDE side record, see [IdeSessionRecord]
 */
class SessionTransfer(
    val home: Path = CopilotPaths.copilotHome(),
) {

    companion object {
        /**
         * The layout this version writes. Archives are self describing: an entry that is not
         * there - `ide-session.json` is the one that can legitimately be missing - is handled by
         * its absence, never by the number below, so the version is only a guard against a layout
         * this build cannot understand at all.
         */
        const val FORMAT_VERSION: Int = 1

        /**
         * Highest number [readManifest] still accepts. Development builds predating 1.0.0 stamped
         * their archives with `2` while writing exactly this layout, so those archives are read
         * rather than rejected; anything above is genuinely unknown.
         */
        const val MAX_READABLE_FORMAT_VERSION: Int = 2
        const val MANIFEST_ENTRY: String = "manifest.json"
        const val ARCHIVE_EXTENSION: String = "zip"

        private const val SESSIONS_PREFIX = "sessions/"
        private const val STORE_ENTRY = "store.json"
        private const val IDE_ENTRY = "ide-session.json"
        private const val STATE_DIR = "state/"
        private const val SESSIONS_TABLE = "sessions"
        private const val CWD_KEY = "cwd"

        /** Database columns holding absolute paths that must follow a relocated session. */
        private val PATH_COLUMNS: Map<String, Set<String>> = mapOf(
            SESSIONS_TABLE to setOf(CWD_KEY),
            "session_files" to setOf("file_path"),
        )

        /** `workspace.yaml` keys holding absolute paths. */
        private val PATH_KEYS: List<String> = listOf(CWD_KEY, "git_root")

        private val GSON: Gson = GsonBuilder().setPrettyPrinting().create()

        private fun isTransient(fileName: String): Boolean =
            fileName.endsWith(".lock") || fileName.startsWith("inuse.")
    }

    val store: SessionStore = SessionStore(CopilotPaths.storeDb(home))
    val stateDir: Path = CopilotPaths.stateDir(home)

    fun isAvailable(): Boolean = CopilotPaths.isValidHome(home)

    /** All sessions known locally, either through the store database or the state folder. */
    fun listSessions(): List<SessionInfo> {
        val result = LinkedHashMap<String, SessionInfo>()
        for (session in store.listSessions()) {
            result[session.id] = enrich(session)
        }
        if (Files.isDirectory(stateDir)) {
            Files.newDirectoryStream(stateDir).use { stream ->
                for (dir in stream) {
                    if (!Files.isDirectory(dir)) continue
                    val id = dir.fileName.toString()
                    if (result.containsKey(id)) continue
                    val yaml = WorkspaceYaml.read(dir.resolve("workspace.yaml"))
                    result[id] = SessionInfo(
                        id = id,
                        name = yaml["name"],
                        repository = yaml["repository"],
                        cwd = yaml["cwd"],
                        branch = yaml["branch"],
                        createdAt = yaml["created_at"],
                        updatedAt = yaml["updated_at"],
                        hasState = true,
                        inStore = false,
                    )
                }
            }
        }
        return result.values.sortedByDescending { sortKey(it) }
    }

    private fun enrich(session: SessionInfo): SessionInfo {
        val dir = stateDir.resolve(session.id)
        if (!Files.isDirectory(dir)) return session
        val yaml = WorkspaceYaml.read(dir.resolve("workspace.yaml"))
        return session.copy(
            name = yaml["name"] ?: session.name,
            branch = session.branch ?: yaml["branch"],
            cwd = session.cwd ?: yaml["cwd"],
            hasState = true,
        )
    }

    private fun sortKey(session: SessionInfo): String =
        (session.updatedAt ?: session.createdAt ?: "").replace('T', ' ').removeSuffix("Z")

    // ---------------------------------------------------------------- export

    /**
     * Writes [sessionIds] into [target].
     *
     * [ideSessions] carries the record the GitHub Copilot plugin keeps inside the IDE, keyed by
     * session id. `core/` cannot read it - the API belongs to another plugin - so the UI layer
     * captures it and passes it in; a session exported without it restores its files but not its
     * entry in the chat history, which is reported as a warning.
     *
     * [ideCaptureUnavailable] tells why that capture could not run at all. When it is set, no
     * session can possibly carry an IDE record, so a single warning explaining the cause replaces
     * the per session one, which would otherwise blame the conversations for a plugin level problem.
     */
    fun export(
        sessionIds: List<String>,
        target: Path,
        progress: TransferProgress = TransferProgress.NOOP,
        ideSessions: Map<String, IdeSessionRecord> = emptyMap(),
        ideCaptureUnavailable: String? = null,
    ): ExportOutcome {
        require(sessionIds.isNotEmpty()) { "No session selected" }
        val known = listSessions().associateBy { it.id }
        val selected = sessionIds.mapNotNull { known[it] }
        target.parent?.let { Files.createDirectories(it) }

        val exported = ArrayList<SessionInfo>(selected.size)
        val warnings = ArrayList<String>()
        var unreadableFiles = 0
        if (ideCaptureUnavailable != null) {
            warnings.add(CopilotSessionsBundle.message("export.warning.ideUnavailable", ideCaptureUnavailable))
        }
        Files.newOutputStream(target).use { rawOut ->
            ZipOutputStream(rawOut, StandardCharsets.UTF_8).use { zip ->
                selected.forEachIndexed { index, session ->
                    val fraction = index.toDouble() / selected.size
                    progress.report(CopilotSessionsBundle.message("progress.exportingSession", session.displayName), fraction)
                    val data = store.exportSession(session.id)
                    if (!data.tables.containsKey("sessions")) {
                        warnings.add(
                            CopilotSessionsBundle.message(
                                "export.warning.noStoreRow",
                                session.displayName,
                                CopilotPaths.STORE_DB_NAME,
                            )
                        )
                    }
                    writeEntry(zip, "$SESSIONS_PREFIX${session.id}/$STORE_ENTRY", GSON.toJson(data).toByteArray(StandardCharsets.UTF_8))
                    val state = writeState(zip, session.id, warnings)
                    unreadableFiles += state.unreadable
                    val ideRecord = ideSessions[session.id]?.takeIf { !it.isEmpty }
                    if (ideRecord != null) {
                        writeEntry(
                            zip,
                            "$SESSIONS_PREFIX${session.id}/$IDE_ENTRY",
                            GSON.toJson(ideRecord).toByteArray(StandardCharsets.UTF_8),
                        )
                    } else if (ideCaptureUnavailable == null) {
                        warnings.add(
                            CopilotSessionsBundle.message("export.warning.noIdeRecord", session.displayName)
                        )
                    }
                    exported.add(session.copy(hasState = state.written, inStore = data.tables.containsKey("sessions")))
                }
                val manifest = ArchiveManifest(
                    exportedAt = Instant.now().toString(),
                    producer = "IntelliJ Github Copilot sessions",
                    sourceHome = home.toAbsolutePath().toString(),
                    sessions = exported,
                )
                writeEntry(zip, MANIFEST_ENTRY, GSON.toJson(manifest).toByteArray(StandardCharsets.UTF_8))
            }
        }
        progress.report(CopilotSessionsBundle.message("progress.exportCompleted"), 1.0)
        return ExportOutcome(exported.size, warnings, unreadableFiles)
    }

    /** What [writeState] copied: whether anything was written, and how many files could not be read. */
    private class StateOutcome(val written: Boolean, val unreadable: Int)

    /**
     * Copies `session-state/<id>` into the archive. Files that cannot be read - Copilot keeps
     * `events.jsonl` and `session.db` open while a conversation is active - are reported through
     * [warnings] instead of being dropped silently, because a missing `events.jsonl` restores an
     * empty conversation on the target machine.
     */
    private fun writeState(zip: ZipOutputStream, sessionId: String, warnings: MutableList<String>): StateOutcome {
        val dir = stateDir.resolve(sessionId)
        if (!Files.isDirectory(dir)) {
            warnings.add(CopilotSessionsBundle.message("export.warning.noState", sessionId))
            return StateOutcome(written = false, unreadable = 0)
        }
        var written = false
        var unreadable = 0
        Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val name = file.fileName.toString()
                if (!isTransient(name)) {
                    val relative = dir.relativize(file).joinToString("/") { it.toString() }
                    runCatching {
                        zip.putNextEntry(ZipEntry("$SESSIONS_PREFIX$sessionId/$STATE_DIR$relative"))
                        Files.newInputStream(file).use { it.copyTo(zip) }
                        zip.closeEntry()
                        written = true
                    }.onFailure { error ->
                        unreadable++
                        warnings.add("$sessionId/$relative: ${error.message ?: error.javaClass.simpleName}")
                    }
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                unreadable++
                warnings.add("$sessionId/${file.fileName}: ${exc.message ?: exc.javaClass.simpleName}")
                return FileVisitResult.CONTINUE
            }
        })
        return StateOutcome(written = written, unreadable = unreadable)
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, content: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content)
        zip.closeEntry()
    }

    // ---------------------------------------------------------------- import

    fun readManifest(archive: Path): ArchiveManifest {
        ZipFile(archive.toFile(), StandardCharsets.UTF_8).use { zip -> return readManifest(zip) }
    }

    private fun readManifest(zip: ZipFile): ArchiveManifest {
        val entry = zip.getEntry(MANIFEST_ENTRY)
            ?: throw IllegalArgumentException(CopilotSessionsBundle.message("error.notAnArchive"))
        zip.getInputStream(entry).use { input ->
            InputStreamReader(BufferedInputStream(input), StandardCharsets.UTF_8).use { reader ->
                val manifest = GSON.fromJson(reader, ArchiveManifest::class.java)
                    ?: throw IllegalArgumentException(CopilotSessionsBundle.message("error.unreadableManifest"))
                if (manifest.formatVersion > MAX_READABLE_FORMAT_VERSION) {
                    throw IllegalArgumentException(
                        CopilotSessionsBundle.message(
                            "error.archiveTooNew",
                            manifest.formatVersion.toString(),
                            MAX_READABLE_FORMAT_VERSION.toString(),
                        )
                    )
                }
                return manifest
            }
        }
    }

    fun exists(sessionId: String): Boolean =
        store.contains(sessionId) || Files.isDirectory(stateDir.resolve(sessionId))

    fun import(
        archive: Path,
        sessionIds: List<String>,
        policy: ConflictPolicy,
        relocateTo: String? = null,
        progress: TransferProgress = TransferProgress.NOOP,
        environment: ImportEnvironment = ImportEnvironment(),
        log: ImportLog = ImportLog.NOOP,
        ideSessionRestorer: IdeSessionRestorer = IdeSessionRestorer.NOOP,
    ): ImportOutcome {
        require(sessionIds.isNotEmpty()) { "No session selected" }
        check(store.isInitialized()) {
            CopilotSessionsBundle.message("error.storeNotInitialized", CopilotPaths.storeDb(home))
        }
        val startedAt = System.currentTimeMillis()
        // The most recently used session already on this machine, captured *before* the import
        // touches anything, so §E of the log can compare it against what was just restored.
        val nativeSessionId = listSessions().firstOrNull()?.id
        logEnvironment(log, environment)
        logOperationContext(log, archive, policy, relocateTo)

        val imported = ArrayList<ImportedSession>()
        val skipped = ArrayList<String>()
        val failures = LinkedHashMap<String, String>()
        val failureErrors = LinkedHashMap<String, Throwable>()
        val diagnosisTargets = ArrayList<Pair<String, ImportedSession>>()
        // Copilot looks sessions up by an exact `cwd` match, so the destination folder has to be
        // spelled the way the CLI itself spells it (lower case drive letter on Windows).
        val destination = CopilotPaths.normalizeWorkspacePath(relocateTo)
        var sourceHome: String? = null

        ZipFile(archive.toFile(), StandardCharsets.UTF_8).use { zip ->
            val manifest = runCatching { readManifest(zip) }.getOrNull()
            sourceHome = manifest?.sourceHome
            logManifest(log, manifest, sessionIds)
            sessionIds.forEachIndexed { index, sourceId ->
                progress.report(
                    CopilotSessionsBundle.message("progress.importingSession", sourceId),
                    index.toDouble() / sessionIds.size,
                )
                val sessionLog = log.child("Sessione $sourceId")
                try {
                    val alreadyPresent = exists(sourceId)
                    sessionLog.kv("alreadyPresent", alreadyPresent)
                    if (alreadyPresent) sessionLog.kv("alreadyPresent.reason", presenceReason(sourceId))
                    val targetId = when {
                        !alreadyPresent -> sourceId
                        policy == ConflictPolicy.SKIP -> {
                            sessionLog.line("policy = SKIP, session left untouched")
                            skipped.add(sourceId)
                            return@forEachIndexed
                        }

                        policy == ConflictPolicy.DUPLICATE -> UUID.randomUUID().toString()
                        else -> sourceId
                    }
                    sessionLog.kv("policy", policy.name)
                    sessionLog.kv("targetId", targetId)
                    if (alreadyPresent && policy == ConflictPolicy.REPLACE) {
                        store.deleteSession(targetId)
                        deleteRecursively(stateDir.resolve(targetId))
                        sessionLog.line("policy = REPLACE: previous database rows and state folder deleted")
                    }
                    val context = importSingle(zip, sourceId, targetId, destination, sourceHome, sessionLog)
                    val ide = restoreIdeRecord(zip, sourceId, targetId, context, ideSessionRestorer, sessionLog)
                    val verified = verify(targetId, sessionLog)
                        .copy(ideRecordPresent = ide.present, ideRecordRestored = ide.restored)
                    imported.add(verified)
                    diagnosisTargets.add(sourceId to verified)
                } catch (e: Exception) {
                    failures[sourceId] = e.message ?: e.javaClass.simpleName
                    failureErrors[sourceId] = e
                    sessionLog.failure("Import failed", e)
                }
            }
        }
        progress.report(CopilotSessionsBundle.message("progress.importCompleted"), 1.0)

        val failedChecks = ImportDiagnostics(home, stateDir, store).diagnose(
            targets = diagnosisTargets,
            projectFolder = destination,
            sourceHome = sourceHome,
            nativeSessionId = nativeSessionId,
            log = log,
        )
        logSummary(log, imported, skipped, failureErrors, failedChecks, startedAt)
        return ImportOutcome(imported, skipped, failures)
    }

    private fun presenceReason(sessionId: String): String = when {
        store.contains(sessionId) && Files.isDirectory(stateDir.resolve(sessionId)) ->
            "row in sessions table and session-state folder"

        store.contains(sessionId) -> "row in sessions table"
        else -> "session-state folder"
    }

    /** §A - everything the log needs to explain a schema or environment mismatch on the target machine. */
    private fun logEnvironment(log: ImportLog, environment: ImportEnvironment) {
        log.section("Ambiente")
        log.kv("import.localTime", java.time.LocalDateTime.now())
        log.kv("import.utcTime", Instant.now())
        log.kv("import.timeZone", java.util.TimeZone.getDefault().id)
        log.kv("locale", Locale.getDefault())
        log.kv("pluginVersion", environment.pluginVersion)
        log.kv("ideBuild", environment.ideBuild)
        log.kv("productName", environment.productName)
        log.kv("java.version", System.getProperty("java.version"))
        log.kv("os.name", System.getProperty("os.name"))
        log.kv("os.version", System.getProperty("os.version"))
        log.kv("os.arch", System.getProperty("os.arch"))
        log.kv("user.name", System.getProperty("user.name"))
        log.kv("user.home", System.getProperty("user.home"))
        log.kv("file.separator", System.getProperty("file.separator"))
        log.kv("defaultCharset", java.nio.charset.Charset.defaultCharset())
        log.kv("copilot.home (system property)", System.getProperty("copilot.home"))
        log.kv("COPILOT_HOME (env)", System.getenv("COPILOT_HOME"))
        log.kv("home (resolved)", home.toAbsolutePath())
        val storeDb = CopilotPaths.storeDb(home)
        log.kv("storeDb.path", storeDb.toAbsolutePath())
        log.kv("storeDb.exists", Files.isRegularFile(storeDb))
        log.kv("storeDb.sizeBytes", runCatching { Files.size(storeDb) }.getOrNull())
        log.kv("storeDb.writable", Files.isWritable(storeDb))
        log.kv("stateDir.path", stateDir.toAbsolutePath())
        log.kv("stateDir.exists", Files.isDirectory(stateDir))
        log.kv(
            "stateDir.subfolders",
            runCatching { Files.newDirectoryStream(stateDir).use { s -> s.count { Files.isDirectory(it) } } }.getOrNull(),
        )
        log.kv("store.isInitialized", store.isInitialized())
        for ((table, columns) in store.tableSchemas()) {
            log.kv(
                "schema.$table",
                columns.joinToString(", ") { "${it.name}:${it.type}${if (it.pk > 0) "(pk)" else ""}" },
            )
        }
        log.kv("copilotPlugin.id", environment.copilotPluginId)
        log.kv("copilotPlugin.version", environment.copilotPluginVersion)
        log.kv("copilotPlugin.enabled", environment.copilotPluginEnabled)
    }

    /** §B - the operation itself: archive, policy and relocation requested by the dialog. */
    private fun logOperationContext(log: ImportLog, archive: Path, policy: ConflictPolicy, relocateTo: String?) {
        log.section("Contesto dell'operazione")
        log.kv("archive.path", archive.toAbsolutePath())
        log.kv("archive.sizeBytes", runCatching { Files.size(archive) }.getOrNull())
        log.kv("archive.modified", runCatching { Files.getLastModifiedTime(archive) }.getOrNull())
        log.kv("policy", policy.name)
        log.kv("relocateTo.raw", relocateTo)
        log.kv("relocateTo.normalized", CopilotPaths.normalizeWorkspacePath(relocateTo))
    }

    private fun logManifest(log: ImportLog, manifest: ArchiveManifest?, selected: List<String>) {
        log.kv("manifest.formatVersion", manifest?.formatVersion)
        log.kv("manifest.exportedAt", manifest?.exportedAt)
        log.kv("manifest.producer", manifest?.producer)
        log.kv("manifest.sourceHome", manifest?.sourceHome)
        log.kv("manifest.sessionCount", manifest?.sessions?.size)
        manifest?.sessions?.forEach { session ->
            log.line(
                "archive session: id=${session.id} cwd=${session.cwd} branch=${session.branch} " +
                    "createdAt=${session.createdAt} updatedAt=${session.updatedAt} turnCount=${session.turnCount} " +
                    "hasState=${session.hasState} inStore=${session.inStore}"
            )
        }
        log.kv("selectedIds", selected)
    }

    /** §G - counts, failures with their full stack trace, the compact §D failure list and the total duration. */
    private fun logSummary(
        log: ImportLog,
        imported: List<ImportedSession>,
        skipped: List<String>,
        failures: Map<String, Throwable>,
        failedChecks: List<String>,
        startedAt: Long,
    ) {
        log.section("Riepilogo")
        log.kv("imported", imported.size)
        log.kv("skipped", skipped.size)
        log.kv("failed", failures.size)
        for ((id, error) in failures) log.failure("FAILED  $id", error)
        for (line in failedChecks) log.line(line)
        log.kv("durationMs", System.currentTimeMillis() - startedAt)
    }

    /**
     * Reads the session back from disk after it has been written, so the caller can tell the user
     * what Copilot will actually find.
     *
     * The chat history is served by the CLI from the `session-state` folders, not from
     * `session-store.db`: [listed] therefore reflects `workspace.yaml`, which is the file the
     * working directory filter is applied to, while [inStore] only tells whether the session is
     * also searchable through the session store.
     */
    private fun verify(targetId: String, log: ImportLog = ImportLog.NOOP): ImportedSession {
        val dir = stateDir.resolve(targetId)
        val stored = store.describe(targetId)
        val yaml = WorkspaceYaml.read(dir.resolve("workspace.yaml"))
        logVerification(dir, targetId, yaml, stored, log)
        return ImportedSession(
            id = targetId,
            displayName = stored?.copy(name = yaml["name"])?.displayName ?: yaml["name"] ?: targetId,
            cwd = yaml[CWD_KEY] ?: stored?.cwd,
            turnCount = stored?.turnCount ?: 0,
            hasState = Files.isDirectory(dir),
            listed = !yaml["id"].isNullOrBlank() && !yaml[CWD_KEY].isNullOrBlank(),
            inStore = stored != null,
        )
    }

    /** §C.9 - re-reads from disk, never from the objects the import just built in memory. */
    private fun logVerification(dir: Path, targetId: String, yaml: Map<String, String>, stored: SessionInfo?, log: ImportLog) {
        val entries = runCatching {
            Files.walk(dir).use { paths ->
                paths.filter { Files.isRegularFile(it) }
                    .map { file ->
                        val relative = dir.relativize(file).joinToString("/") { it.toString() }
                        val size = runCatching { Files.size(file) }.getOrDefault(-1)
                        val modified = runCatching { Files.getLastModifiedTime(file) }.getOrNull()
                        "$relative (size=$size, modified=$modified)"
                    }
                    .toList()
            }
        }.getOrDefault(emptyList())
        entries.take(200).forEach { log.line("tree: $it") }
        if (entries.size > 200) log.line("tree: ... and ${entries.size - 200} more file(s)")
        log.kv("workspace.yaml (reread)", yaml.entries.joinToString(", ") { (k, v) -> "$k=$v" })
        log.kv("store.describe", stored?.let { "cwd=${it.cwd}, createdAt=${it.createdAt}, updatedAt=${it.updatedAt}, turnCount=${it.turnCount}" } ?: "<no row>")
        for ((table, count) in store.countRows(targetId)) log.kv("rowCount.$table", count)
    }

    /**
     * Maps `session-state/<source id>` of the machine that produced the archive onto the folder
     * used here, so the checkpoint and snapshot paths recorded inside the events keep resolving.
     */
    private fun stateRelocation(sourceHome: String?, sourceId: String, targetId: String): PathMapper? {
        val home = sourceHome?.takeIf { it.isNotBlank() }?.trimEnd('/', '\\') ?: return null
        val separator = if (home.contains('\\')) "\\" else "/"
        val source = listOf(home, CopilotPaths.STATE_DIR_NAME, sourceId).joinToString(separator)
        val target = stateDir.resolve(targetId).toAbsolutePath().toString()
        return PathMapper(source, target).takeIf { it.isEnabled }
    }

    /**
     * Maps the whole `~/.copilot` of the source machine onto the local one. A conversation that
     * inspected Copilot's own files - other sessions, caches, the IDE registry - would otherwise
     * keep pointing at the home directory of another user, which does not exist here.
     */
    private fun homeRelocation(sourceHome: String?): PathMapper? {
        val source = sourceHome?.takeIf { it.isNotBlank() } ?: return null
        return PathMapper(source, home.toAbsolutePath().toString()).takeIf { it.isEnabled }
    }

    private fun importSingle(
        zip: ZipFile,
        sourceId: String,
        targetId: String,
        relocateTo: String?,
        sourceHome: String?,
        log: ImportLog = ImportLog.NOOP,
    ): ImportContext {
        val statePrefix = "$SESSIONS_PREFIX$sourceId/$STATE_DIR"
        val targetDir = stateDir.resolve(targetId)
        var restoredState = false
        var entryCount = 0
        var byteCount = 0L
        val discardedEntries = ArrayList<String>()

        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory || !entry.name.startsWith(statePrefix)) continue
            val relative = entry.name.removePrefix(statePrefix)
            if (relative.isEmpty()) continue
            val destinationFile = resolveSafely(targetDir, relative)
            if (destinationFile == null) {
                discardedEntries.add(relative)
                continue
            }
            Files.createDirectories(destinationFile.parent)
            zip.getInputStream(entry).use { input ->
                byteCount += Files.copy(input, destinationFile, StandardCopyOption.REPLACE_EXISTING)
            }
            entryCount++
            restoredState = true
        }
        log.kv("state.entriesWritten", entryCount)
        log.kv("state.bytesWritten", byteCount)
        if (discardedEntries.isNotEmpty()) log.kv("state.discardedZipSlipEntries", discardedEntries)

        val storeEntry = zip.getEntry("$SESSIONS_PREFIX$sourceId/$STORE_ENTRY")
        val data = storeEntry?.let { entry ->
            zip.getInputStream(entry).use { input ->
                InputStreamReader(BufferedInputStream(input), StandardCharsets.UTF_8).use { reader ->
                    GSON.fromJson(reader, SessionData::class.java) ?: SessionData()
                }
            }
        }

        if (!restoredState && data == null) {
            throw IllegalArgumentException("Session $sourceId is not contained in the archive")
        }
        val workspace = targetDir.resolve("workspace.yaml")
        log.kv("state.workspaceYamlPresent", Files.isRegularFile(workspace))
        log.kv("state.eventsJsonlPresent", Files.isRegularFile(targetDir.resolve("events.jsonl")))
        log.kv("state.sessionDbPresent", Files.isRegularFile(targetDir.resolve("session.db")))
        log.kv("state.checkpointsPresent", Files.isDirectory(targetDir.resolve("checkpoints")))
        log.kv("state.rewindSnapshotsPresent", Files.isDirectory(targetDir.resolve("rewind-file-snapshots")))
        log.kv("store.json.present", storeEntry != null)
        for ((table, tableData) in data?.tables.orEmpty()) {
            log.kv("store.json.$table.rows", tableData.rows.size)
            log.kv("store.json.$table.columns", tableData.columns)
        }

        // A single offset makes the whole session look freshly created on this machine: computed
        // once, from the newest timestamp found anywhere in the archive - the database, the
        // pre-existing `workspace.yaml` and `events.jsonl` - and applied everywhere below, so none
        // of them keeps dating the session on the source machine's clock.
        val yamlBeforeReconstruction = if (restoredState) WorkspaceYaml.read(workspace) else emptyMap()
        val delta = computeDelta(data, yamlBeforeReconstruction, targetDir)
        log.kv("timestamps.delta", delta)

        // Without this file the session is restored but never listed: recreate it from the store
        // data when the source machine had it locked while the archive was being written.
        if (restoredState && !Files.isRegularFile(workspace)) {
            WorkspaceYaml.create(workspace, workspaceFrom(data, sourceId, delta))
            log.line("workspace.yaml reconstructed from the archived database row")
        }
        val yaml = if (restoredState) WorkspaceYaml.read(workspace) else emptyMap()
        log.kv("workspace.yaml (before rewrite)", yaml.entries.joinToString(", ") { (k, v) -> "$k=$v" })
        val workspaceSource = columnValue(data, "sessions", "cwd") ?: yaml["cwd"]
        val mapper = PathMapper(workspaceSource, relocateTo)
        // `session_files` also records the files the agent wrote inside `session-state/<id>` of the
        // source machine, which the working directory mapper knows nothing about.
        val stateMapper = stateRelocation(sourceHome, sourceId, targetId)
        val homeMapper = homeRelocation(sourceHome)
        logMapper(log, "workspaceMapper", workspaceSource, relocateTo, mapper)
        logMapper(log, "stateMapper", sourceHome?.let { "$it/${CopilotPaths.STATE_DIR_NAME}/$sourceId" }, targetDir.toString(), stateMapper)
        logMapper(log, "homeMapper", sourceHome, home.toAbsolutePath().toString(), homeMapper)

        if (restoredState) {
            val replacements = LinkedHashMap<String, String>()
            if (sourceId != targetId) replacements["id"] = targetId
            if (relocateTo != null || mapper.isEnabled) {
                for (key in PATH_KEYS) {
                    val current = yaml[key] ?: continue
                    // `cwd` is what Copilot matches on, so it is forced to the canonical spelling of
                    // the destination even when the mapper has nothing to translate (same folder on
                    // both machines, or a path differing only by drive letter case).
                    val value = if (key == CWD_KEY && relocateTo != null) {
                        relocateTo
                    } else {
                        CopilotPaths.normalizeWorkspacePath(mapper.map(current))
                    }
                    if (value != null) replacements[key] = value
                }
            }
            // The session's own timestamps are shifted unconditionally, regardless of relocation:
            // this is what makes the session look freshly created, independently of its folder.
            for (key in listOf("created_at", "updated_at")) {
                val current = yaml[key] ?: continue
                replacements[key] = TimestampShifter.shift(current, delta)
            }
            WorkspaceYaml.rewrite(workspace, replacements)
            log.kv("workspace.yaml (replacements)", replacements.entries.joinToString(", ") { (k, v) -> "$k=$v" })
            log.kv(
                "workspace.yaml (after rewrite)",
                WorkspaceYaml.read(workspace).entries.joinToString(", ") { (k, v) -> "$k=$v" },
            )

            // `workspace.yaml` is what makes the session appear in the chat, but the CLI also
            // reads `events.jsonl` back: it still describes the source machine and has to be
            // adopted by this one before the conversation can be resumed.
            SessionStateRewriter(
                sourceId = sourceId,
                targetId = targetId,
                workspaceMapper = mapper,
                destinationCwd = relocateTo,
                stateMapper = stateMapper,
                homeMapper = homeMapper,
                deltaMillis = delta,
                log = log,
            ).rewrite(targetDir)
        }

        if (data != null && data.tables.isNotEmpty()) {
            val relocated = relocate(data, mapper, stateMapper, homeMapper, relocateTo, log)
            val shifted = shiftTimestamps(relocated, delta, log)
            SessionStore(CopilotPaths.storeDb(home), log).importSession(shifted, targetId)
        }

        // A folder that still carries the modification time of the source machine looks old to
        // any heuristic based on file metadata, even though the session was just restored.
        if (Files.isDirectory(targetDir)) touchRecursively(targetDir)

        return ImportContext(delta, listOfNotNull(mapper, stateMapper, homeMapper))
    }

    /**
     * The relocation decisions taken while restoring a session, reused to adopt its IDE side
     * record: both halves must end up with the same timestamps and the same translated paths.
     */
    private data class ImportContext(val delta: Long, val mappers: List<PathMapper>)

    private data class IdeRestoreOutcome(val present: Boolean, val restored: Boolean)

    /**
     * Restores the entry the GitHub Copilot plugin keeps inside the IDE, which is what actually
     * makes the conversation appear in the chat history - see [IdeSessionRecord].
     *
     * A v1 archive simply has no such entry; the session is still imported, and the caller reports
     * the limitation instead of pretending the import was complete.
     */
    private fun restoreIdeRecord(
        zip: ZipFile,
        sourceId: String,
        targetId: String,
        context: ImportContext,
        restorer: IdeSessionRestorer,
        log: ImportLog,
    ): IdeRestoreOutcome {
        val entry = zip.getEntry("$SESSIONS_PREFIX$sourceId/$IDE_ENTRY")
        log.kv("ide.recordPresentInArchive", entry != null)
        if (entry == null) return IdeRestoreOutcome(present = false, restored = false)

        val record = runCatching {
            zip.getInputStream(entry).use { input ->
                InputStreamReader(BufferedInputStream(input), StandardCharsets.UTF_8).use { reader ->
                    GSON.fromJson(reader, IdeSessionRecord::class.java)
                }
            }
        }.onFailure { log.failure("ide-session.json is unreadable", it) }.getOrNull()

        if (record == null || record.isEmpty) return IdeRestoreOutcome(present = false, restored = false)
        log.kv("ide.sourceTurns", record.turns.size())

        val adopted = IdeSessionRewriter.adopt(
            record = record,
            targetConversationId = targetId,
            deltaMillis = context.delta,
            mappers = context.mappers,
        )
        val restored = runCatching { restorer.restore(adopted, log) }
            .onFailure { log.failure("IDE side restore failed", it) }
            .getOrDefault(false)
        log.kv("ide.recordRestored", restored)
        return IdeRestoreOutcome(present = true, restored = restored)
    }

    /** Logs source, destination and [PathMapper.isEnabled] of a mapper, explaining a `false` value. */
    private fun logMapper(log: ImportLog, name: String, source: String?, target: String?, mapper: PathMapper?) {
        val enabled = mapper?.isEnabled ?: false
        log.kv("$name.source", source)
        log.kv("$name.target", target)
        log.kv("$name.isEnabled", enabled)
        if (!enabled) {
            val reason = when {
                source.isNullOrBlank() -> "empty source"
                target.isNullOrBlank() -> "empty target"
                else -> "source and target already coincide"
            }
            log.kv("$name.disabledReason", reason)
        }
    }

    /**
     * A single offset applied to every timestamp of the session - see [shiftTimestamps] and
     * [TimestampShifter] - computed once as `now - max(every timestamp found in the archive)`.
     * Applied even when zero or negative (clocks disagreeing between the two machines): the goal
     * is to normalize on the destination machine's clock, never to leave a source value untouched.
     */
    private fun computeDelta(data: SessionData?, yaml: Map<String, String>, targetDir: Path): Long {
        var max: Long? = null
        fun considerMillis(millis: Long?) {
            if (millis != null && (max == null || millis > max!!)) max = millis
        }
        fun considerText(value: String?) = considerMillis(TimestampShifter.parse(value)?.toEpochMilli())

        for (tableData in data?.tables.orEmpty().values) {
            val indexes = tableData.columns.indices.filter { TimestampShifter.isTimestampColumn(tableData.columns[it]) }
            if (indexes.isEmpty()) continue
            for (row in tableData.rows) {
                for (index in indexes) considerText(row.getOrNull(index)?.takeIf { it.isJsonPrimitive }?.asString)
            }
        }
        for ((key, value) in yaml) if (TimestampShifter.isTimestampColumn(key)) considerText(value)
        considerMillis(SessionStateRewriter.maxTimestampMillis(targetDir))

        val reference = max ?: return 0L
        return System.currentTimeMillis() - reference
    }

    /**
     * Shifts every database column [TimestampShifter.isTimestampColumn] recognises, in every
     * table, by [delta]. The original textual/numeric shape is preserved; a value
     * [TimestampShifter] does not recognise is left untouched and reported to [log].
     */
    private fun shiftTimestamps(data: SessionData, delta: Long, log: ImportLog): SessionData {
        val tables = LinkedHashMap<String, TableData>(data.tables.size)
        for ((table, tableData) in data.tables) {
            val indexes = tableData.columns.indices.filter { TimestampShifter.isTimestampColumn(tableData.columns[it]) }
            if (indexes.isEmpty()) {
                tables[table] = tableData
                continue
            }
            val rows = tableData.rows.map { row ->
                row.mapIndexed { index, value ->
                    if (index !in indexes || !value.isJsonPrimitive) {
                        value
                    } else {
                        val primitive = value.asJsonPrimitive
                        val text = primitive.asString
                        val shifted = TimestampShifter.shift(text, delta)
                        when {
                            shifted == text -> {
                                if (TimestampShifter.parse(text) == null) {
                                    log.kv("timestamp.unrecognized", "$table.${tableData.columns[index]} = $text")
                                }
                                value
                            }

                            primitive.isNumber -> JsonPrimitive(shifted.toLong())
                            else -> JsonPrimitive(shifted)
                        }
                    }
                }
            }
            tables[table] = tableData.copy(rows = rows)
        }
        return SessionData(tables)
    }

    /**
     * Refreshes the modification time of every restored file - and the folder itself - to now, so
     * a session that has just been imported does not look old to any heuristic based on file
     * metadata. Best-effort: a filesystem that refuses the update must not fail the import.
     */
    private fun touchRecursively(dir: Path) {
        val now = FileTime.from(Instant.now())
        runCatching {
            Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    runCatching { Files.setLastModifiedTime(file, now) }
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                    runCatching { Files.setLastModifiedTime(dir, now) }
                    return FileVisitResult.CONTINUE
                }
            })
        }
    }

    /** First value of [column] inside [table], used to detect the folder the session was bound to. */
    private fun columnValue(data: SessionData?, table: String, column: String): String? {
        val tableData = data?.tables?.get(table) ?: return null
        val index = tableData.columns.indexOf(column).takeIf { it >= 0 } ?: return null
        val value = tableData.rows.firstOrNull()?.getOrNull(index) ?: return null
        return if (value.isJsonPrimitive) value.asString else null
    }

    /**
     * Rebuilds the metadata Copilot needs to list a session out of the exported database row.
     * Only the simple, single line fields are restored: a summary or a name could contain
     * characters the flat `workspace.yaml` format cannot carry, and both are optional.
     */
    private fun workspaceFrom(data: SessionData?, sourceId: String, delta: Long): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        values["id"] = columnValue(data, "sessions", "id") ?: sourceId
        for (key in listOf(CWD_KEY, "repository", "host_type", "branch")) {
            columnValue(data, "sessions", key)?.takeIf { it.isNotBlank() }?.let { values[key] = it }
        }
        val now = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        for (key in listOf("created_at", "updated_at")) {
            val raw = columnValue(data, "sessions", key)
            // Already translated with the same delta as everything else, per the "just imported"
            // rule of §4.6 - a value the archive did not carry falls back to the moment of import.
            values[key] = raw?.takeIf { TimestampShifter.parse(it) != null }?.let { TimestampShifter.shift(it, delta) } ?: now
        }
        return values
    }

    /**
     * Returns a copy of [data] whose absolute paths point at the destination machine.
     *
     * [mapper] follows the working directory, [stateMapper] follows `session-state/<id>` and
     * [homeMapper] the rest of `~/.copilot`: the agent also records files it wrote inside Copilot's
     * own folders, and those paths name the home directory of the user on the source machine.
     */
    private fun relocate(
        data: SessionData,
        mapper: PathMapper,
        stateMapper: PathMapper?,
        homeMapper: PathMapper?,
        destination: String?,
        log: ImportLog = ImportLog.NOOP,
    ): SessionData {
        if (destination == null && !mapper.isEnabled && stateMapper == null && homeMapper == null) return data
        val tables = LinkedHashMap<String, TableData>(data.tables.size)
        for ((table, tableData) in data.tables) {
            val columns = PATH_COLUMNS[table]
            val indexes = columns
                ?.let { names -> tableData.columns.indices.filter { tableData.columns[it] in names } }
                .orEmpty()
            if (indexes.isEmpty()) {
                tables[table] = tableData
                continue
            }
            var relocatedCount = 0
            val rows = tableData.rows.map { row ->
                row.mapIndexed { index, value ->
                    when {
                        index !in indexes -> value
                        // The folder Copilot matches on: always written exactly as the CLI spells it.
                        table == SESSIONS_TABLE && tableData.columns[index] == CWD_KEY && destination != null -> {
                            if (!value.isJsonPrimitive || value.asString != destination) relocatedCount++
                            JsonPrimitive(destination)
                        }

                        !value.isJsonPrimitive -> value
                        else -> {
                            val current = value.asString
                            // Most specific mapper first, exactly as in SessionStateRewriter.
                            val relocated = stateMapper?.map(current)?.takeIf { it != current }
                                ?: homeMapper?.map(current)?.takeIf { it != current }
                                ?: mapper.map(current)?.takeIf { it != current }
                            // A path none of the mappers owns is left exactly as the source wrote it.
                            val normalized = relocated?.let { CopilotPaths.normalizeWorkspacePath(it) }
                            if (normalized != null) relocatedCount++
                            normalized?.let { JsonPrimitive(it) } ?: value
                        }
                    }
                }
            }
            tables[table] = tableData.copy(rows = rows)
            if (relocatedCount > 0) log.kv("$table.pathsRelocated", relocatedCount)
        }
        return SessionData(tables)
    }

    /** Guards against zip-slip: entries escaping the destination directory are ignored. */
    private fun resolveSafely(base: Path, relative: String): Path? {
        val normalizedBase = base.toAbsolutePath().normalize()
        val candidate = normalizedBase.resolve(relative.replace('\\', '/')).normalize()
        return if (candidate.startsWith(normalizedBase)) candidate else null
    }

    private fun deleteRecursively(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                runCatching { Files.delete(file) }
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                runCatching { Files.delete(dir) }
                return FileVisitResult.CONTINUE
            }
        })
    }
}
