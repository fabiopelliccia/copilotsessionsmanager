package com.github.fabiopelliccia.copilotsessionsimportexport.core

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Answers "why doesn't the imported session show up in the chat" without access to the machine
 * that produced the archive.
 *
 * [diagnose] re-reads everything from disk - never from the objects the import just built in
 * memory - and writes an explicit `OK`/`KO` outcome for every check of §D, then compares the
 * imported sessions against the most recently used session that was already on this machine (§E)
 * and dumps the Copilot IDE registry (§F). It is invoked once, after every session has been
 * imported, and is deliberately free of any IntelliJ dependency so it can be unit tested.
 */
class ImportDiagnostics(
    private val home: Path,
    private val stateDir: Path,
    private val store: SessionStore,
) {

    /** One check of §5, numbered exactly as the work order lists them. */
    private data class Check(val number: Int, val description: String, val ok: Boolean, val detail: String? = null)

    /**
     * Runs every diagnostic for [targets] (source id to the session actually written) and returns
     * the compact `KO` lines of §G, one per failed check, in the form
     * `KO  <sessionId>  <check number>  <explanation>`.
     */
    fun diagnose(
        targets: List<Pair<String, ImportedSession>>,
        projectFolder: String?,
        sourceHome: String?,
        nativeSessionId: String?,
        log: ImportLog,
    ): List<String> {
        log.section("Visibility diagnosis")
        val failures = ArrayList<String>()
        for ((sourceId, session) in targets) {
            val checks = runChecks(sourceId, session, projectFolder, sourceHome)
            val sessionLog = log.child(session.displayName)
            for (check in checks) {
                val status = if (check.ok) "OK" else "KO"
                val suffix = check.detail?.let { " - $it" }.orEmpty()
                sessionLog.line("$status  #${check.number}  ${check.description}$suffix")
                if (!check.ok) {
                    failures.add("KO  ${session.id}  ${check.number}  ${check.detail ?: check.description}")
                }
            }
        }

        log.section("Comparison with a native session")
        compareWithNative(targets.map { it.second.id }.toSet(), nativeSessionId, log)

        log.section("Copilot IDE registry")
        dumpRegistry(log)

        return failures
    }

    // ------------------------------------------------------------------------------------- §5 --

    private fun runChecks(
        sourceId: String,
        session: ImportedSession,
        projectFolder: String?,
        sourceHome: String?,
    ): List<Check> {
        val dir = stateDir.resolve(session.id)
        val workspaceFile = dir.resolve("workspace.yaml")
        val yaml = WorkspaceYaml.read(workspaceFile)
        val checks = ArrayList<Check>()

        val readable = Files.isRegularFile(workspaceFile) && yaml.isNotEmpty()
        checks += Check(1, "workspace.yaml exists and is readable", readable)

        val idMatches = yaml["id"] == session.id
        checks += Check(
            2, "workspace.yaml id matches the folder name", idMatches,
            "workspace id = ${yaml["id"]}, folder = ${session.id}".takeIf { !idMatches },
        )

        val cwd = yaml["cwd"]
        checks += Check(3, "cwd is not blank", !cwd.isNullOrBlank())

        val identicalCwd = projectFolder == null || cwd == projectFolder
        checks += Check(
            4, "cwd is byte for byte identical to the normalized project folder", identicalCwd,
            if (!identicalCwd) diffHex(cwd.orEmpty(), projectFolder.orEmpty()) else null,
        )

        val canonical = cwd != null && CopilotPaths.normalizeWorkspacePath(cwd) == cwd
        checks += Check(5, "cwd is in canonical form (lower case drive, backslashes, no trailing slash)", canonical)

        val folderExists = cwd != null && runCatching { Files.isDirectory(Paths.get(cwd)) }.getOrDefault(false)
        checks += Check(6, "the cwd folder exists on this machine", folderExists)

        checks += Check(7, "events.jsonl exists, is not empty and starts with session.start for this id", firstEventOk(dir, session.id))

        // `sourceId` alone would also match the correctly rewritten `targetId` when the session
        // keeps its identifier (no duplication happened): only a genuine leftover counts as KO.
        val residual = countResidualPaths(dir.resolve("events.jsonl"), sourceHome, sourceId, session.id)
        checks += Check(
            8, "no residual PC1 path in the machine facing fields of events.jsonl", residual == 0,
            "residual occurrences = $residual",
        )

        val stored = store.describe(session.id)
        checks += Check(9, "sessions row exists and its cwd matches workspace.yaml", stored != null && stored.cwd == cwd)

        checks += Check(10, "turn_count > 0", session.turnCount > 0)

        checks += Check(11, "no lock file left in the restored folder", !hasLockFiles(dir))

        // The decisive one: everything above can pass and the conversation still stay invisible,
        // because the chat list is served by the IDE side record, not by `~/.copilot`.
        checks += Check(
            12, "the GitHub Copilot chat entry was restored inside the IDE", session.ideRecordRestored,
            when {
                !session.ideRecordPresent ->
                    "the archive carries no ide-session.json: re-export the session from the project it belongs to"

                else -> "the GitHub Copilot plugin did not accept the chat entry"
            }.takeIf { !session.ideRecordRestored },
        )

        return checks
    }

    private fun firstEventOk(dir: Path, sessionId: String): Boolean = runCatching {
        val file = dir.resolve("events.jsonl")
        if (!Files.isRegularFile(file) || Files.size(file) == 0L) return false
        Files.newBufferedReader(file, StandardCharsets.UTF_8).useLines { lines ->
            val firstLine = lines.firstOrNull { it.isNotBlank() } ?: return false
            val json = JsonParser.parseString(firstLine).asJsonObject
            json.get("type")?.takeIf { it.isJsonPrimitive }?.asString == "session.start" &&
                json.getAsJsonObject("data")?.get("sessionId")?.takeIf { it.isJsonPrimitive }?.asString == sessionId
        }
    }.getOrDefault(false)

    private fun hasLockFiles(dir: Path): Boolean = runCatching {
        if (!Files.isDirectory(dir)) return false
        Files.newDirectoryStream(dir).use { stream ->
            stream.any { entry ->
                val name = entry.fileName.toString()
                name.endsWith(".lock") || name.startsWith("inuse.")
            }
        }
    }.getOrDefault(false)

    /** Prints both strings' code points in hex starting at the first character that differs. */
    private fun diffHex(a: String, b: String): String {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        val hexA = a.drop(i).map { "%04x".format(it.code) }.joinToString(" ")
        val hexB = b.drop(i).map { "%04x".format(it.code) }.joinToString(" ")
        return "written=\"$a\" (from char $i: $hexA), expected=\"$b\" (from char $i: $hexB)"
    }

    /**
     * Counts how many string values of the machine facing keys of [SessionStateRewriter.PATH_KEYS]
     * inside `events.jsonl` still mention [sourceHome] or [sourceId], after the rewrite. The
     * conversation text itself is never scanned, only those keys. When the session kept its
     * original identifier ([sourceId] equal to [targetId], no duplication happened) matching
     * [sourceId] would also match the correctly rewritten identifier, so that half of the check is
     * skipped in that case.
     */
    private fun countResidualPaths(eventsFile: Path, sourceHome: String?, sourceId: String, targetId: String): Int {
        if (!Files.isRegularFile(eventsFile)) return 0
        val staleId = sourceId.takeIf { it != targetId }
        var count = 0
        runCatching {
            Files.newBufferedReader(eventsFile, StandardCharsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val parsed = runCatching { JsonParser.parseString(line) }.getOrNull() ?: continue
                    count += countMatches(parsed, null, sourceHome, staleId)
                }
            }
        }
        return count
    }

    private fun countMatches(element: JsonElement, key: String?, sourceHome: String?, staleId: String?): Int = when {
        element.isJsonObject -> element.asJsonObject.entrySet().sumOf { (name, value) -> countMatches(value, name, sourceHome, staleId) }
        element.isJsonArray -> element.asJsonArray.sumOf { countMatches(it, key, sourceHome, staleId) }
        element.isJsonPrimitive && element.asJsonPrimitive.isString && key != null && key in SessionStateRewriter.PATH_KEYS -> {
            val value = element.asJsonPrimitive.asString
            var hits = 0
            if (!sourceHome.isNullOrBlank() && value.contains(sourceHome, ignoreCase = true)) hits++
            if (!staleId.isNullOrEmpty() && value.contains(staleId)) hits++
            hits
        }

        else -> 0
    }

    // ------------------------------------------------------------------------------------- §E --

    /**
     * Compares every imported session against the most recently used session that was already on
     * this machine (captured by the caller *before* the import), so a missing mandatory key can be
     * spotted without access to the machine that produced the archive.
     */
    private fun compareWithNative(importedIds: Set<String>, nativeSessionId: String?, log: ImportLog) {
        if (nativeSessionId == null || nativeSessionId in importedIds) {
            log.line("no native session available for comparison (nothing else was on this machine before the import)")
            return
        }
        val nativeDir = stateDir.resolve(nativeSessionId)
        val nativeYaml = WorkspaceYaml.read(nativeDir.resolve("workspace.yaml"))
        log.kv("native.sessionId", nativeSessionId)
        for ((key, value) in nativeYaml) log.kv("native.workspace.$key", value)
        log.kv("native.files", listFiles(nativeDir))
        log.kv("native.folderModified", modifiedOf(nativeDir))

        for (importedId in importedIds) {
            val importedYaml = WorkspaceYaml.read(stateDir.resolve(importedId).resolve("workspace.yaml"))
            val onlyNative = nativeYaml.keys - importedYaml.keys
            val onlyImported = importedYaml.keys - nativeYaml.keys
            val differentFormat = nativeYaml.keys.intersect(importedYaml.keys).filter { key ->
                looksLikeDifferentFormat(nativeYaml.getValue(key), importedYaml.getValue(key))
            }
            log.line("vs $importedId: only in native = $onlyNative; only in imported = $onlyImported; different format = $differentFormat")
            log.kv("imported.$importedId.files", listFiles(stateDir.resolve(importedId)))
            log.kv("imported.$importedId.folderModified", modifiedOf(stateDir.resolve(importedId)))
        }
    }

    private fun looksLikeDifferentFormat(a: String, b: String): Boolean {
        if (a == b) return false
        if (TimestampShifter.parse(a) == null || TimestampShifter.parse(b) == null) return false
        return a.endsWith("Z") != b.endsWith("Z") || a.contains("T") != b.contains("T")
    }

    private fun listFiles(dir: Path): List<String> {
        if (!Files.isDirectory(dir)) return emptyList()
        return runCatching {
            Files.walk(dir).use { paths ->
                paths.filter { Files.isRegularFile(it) }.map { dir.relativize(it).joinToString("/") { p -> p.toString() } }.toList()
            }
        }.getOrDefault(emptyList())
    }

    private fun modifiedOf(path: Path): String =
        runCatching { Files.getLastModifiedTime(path).toString() }.getOrDefault("<unknown>")

    // ------------------------------------------------------------------------------------- §F --

    /**
     * Dumps the last 10 lines of every `*.jsonl` file under `~/.copilot/ide/.registry/`, limited to
     * the fields that describe how the IDE announced its working folder to the CLI, so it can be
     * compared against the `cwd` written above.
     */
    private fun dumpRegistry(log: ImportLog) {
        val registryDir = home.resolve("ide").resolve(".registry")
        if (!Files.isDirectory(registryDir)) {
            log.line("no ~/.copilot/ide/.registry/ folder found")
            return
        }
        val files = runCatching {
            Files.newDirectoryStream(registryDir, "*.jsonl").use { it.toList() }
        }.getOrDefault(emptyList())
        if (files.isEmpty()) {
            log.line("no *.jsonl file found in the registry")
            return
        }
        for (file in files) {
            log.line("registry file: ${file.fileName}")
            val lines = runCatching { Files.readAllLines(file, StandardCharsets.UTF_8) }.getOrDefault(emptyList())
            for (line in lines.takeLast(10)) {
                val parsed = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: continue
                val summary = listOf("workspaceFolders", "pid", "ide", "version")
                    .mapNotNull { field -> parsed.get(field)?.let { field to it } }
                    .joinToString(", ") { (field, value) -> "$field=$value" }
                log.line("  $summary")
            }
        }
    }
}
