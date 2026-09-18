package com.github.fabiopelliccia.copilotsessionsimportexport.core

import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.sqlite.SQLiteDataSource
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

class SessionTransferTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val sessionId = "11111111-2222-3333-4444-555555555555"
    private val originalLocale: Locale = Locale.getDefault(Locale.Category.DISPLAY)

    private companion object {
        const val CWD = "cwd"
    }

    // export.warning.* is localized (CopilotSessionsBundle), and some assertions below check its
    // English wording verbatim: pin the display locale so the round trip does not depend on the
    // regional settings of the machine running the tests.
    @Before
    fun forceEnglishLocale() = Locale.setDefault(Locale.Category.DISPLAY, Locale.US)

    @After
    fun restoreLocale() = Locale.setDefault(Locale.Category.DISPLAY, originalLocale)

    @Test
    fun `exports and imports a session into an empty home`() {
        val source = createHome("source", withSession = true)
        val archive = temp.newFolder("out").toPath().resolve("sessions.zip")

        val exported = SessionTransfer(source).export(listOf(sessionId), archive)
        assertEquals(1, exported.sessions)
        // A headless export has no IDE to read the chat entry from, so the archive is knowingly
        // incomplete and says so instead of reporting a clean success.
        assertEquals(1, exported.warnings.size)
        assertTrue(exported.warnings.first().contains("chat history"))
        assertTrue(Files.isRegularFile(archive))

        val target = createHome("target", withSession = false)
        val transfer = SessionTransfer(target)
        val manifest = transfer.readManifest(archive)
        assertEquals(1, manifest.sessions.size)
        assertEquals("Demo session", manifest.sessions.first().name)

        val outcome = transfer.import(archive, listOf(sessionId), ConflictPolicy.SKIP)
        assertEquals(listOf(sessionId), outcome.importedIds)
        assertTrue(outcome.failures.isEmpty())
        assertTrue(outcome.imported.first().listed)
        assertEquals(2, outcome.imported.first().turnCount)

        val restored = transfer.listSessions()
        assertEquals(1, restored.size)
        assertEquals(sessionId, restored.first().id)
        assertEquals(2, restored.first().turnCount)
        assertTrue(restored.first().hasState)
        assertTrue(outcome.imported.first().inStore)
        // The line that is not valid JSON is kept verbatim instead of being dropped.
        assertTrue(
            Files.readString(CopilotPaths.stateDir(target).resolve(sessionId).resolve("events.jsonl"))
                .contains("not json at all")
        )
    }

    @Test
    fun `carries the IDE chat entry through the archive and adopts it on import`() {
        val source = createHome("sourceIde", withSession = true)
        val archive = temp.newFolder("outIde").toPath().resolve("sessions.zip")

        val record = IdeSessionRecord(
            conversationId = sessionId,
            session = JsonParser.parseString(
                """
                {
                  "id": "ide-1111",
                  "conversationId": "$sessionId",
                  "createdAt": 1767261600000,
                  "modifiedAt": 1767261600000,
                  "input": "open C:\\projects\\demo\\src\\Main.kt please"
                }
                """.trimIndent()
            ).asJsonObject,
            turns = JsonParser.parseString(
                """[{"id":"t1","sessionId":"ide-1111","createdAt":1767261600000,
                     "request":{"contents":"C:\\projects\\demo mentioned here"}}]"""
            ).asJsonArray,
            workingSet = JsonParser.parseString(
                """[{"id":"w1","sessionId":"ide-1111","fileUrl":"C:\\projects\\demo\\src\\Main.kt"}]"""
            ).asJsonArray,
        )

        val exported = SessionTransfer(source).export(listOf(sessionId), archive, ideSessions = mapOf(sessionId to record))
        assertTrue(exported.warnings.isEmpty())

        val target = createHome("targetIde", withSession = false)
        val restored = ArrayList<IdeSessionRecord>()
        val outcome = SessionTransfer(target).import(
            archive,
            listOf(sessionId),
            ConflictPolicy.SKIP,
            "D:\\work\\demo",
            ideSessionRestorer = { adopted, _ -> restored.add(adopted) },
        )
        assertTrue(outcome.imported.first().ideRecordPresent)
        assertTrue(outcome.imported.first().ideRecordRestored)

        val adopted = restored.single()
        assertEquals(sessionId, adopted.conversationId)
        // A fresh IDE side id keeps a re-import from colliding with the record it came from.
        assertNotEquals("ide-1111", adopted.ideSessionId)
        assertEquals(adopted.ideSessionId, adopted.turns.first().asJsonObject.get("sessionId").asString)
        assertEquals(adopted.ideSessionId, adopted.workingSet.first().asJsonObject.get("sessionId").asString)
        // Machine facing paths follow the session...
        assertEquals(
            "d:\\work\\demo\\src\\Main.kt",
            adopted.workingSet.first().asJsonObject.get("fileUrl").asString,
        )
        // ...while what was said is never touched, neither in the prompt nor inside a turn.
        val adoptedSession = adopted.session!!
        assertEquals("open C:\\projects\\demo\\src\\Main.kt please", adoptedSession.get("input").asString)
        assertTrue(
            adopted.turns.first().asJsonObject
                .getAsJsonObject("request").get("contents").asString.contains("C:\\projects\\demo")
        )
        assertNotEquals(1767261600000L, adoptedSession.get("createdAt").asLong)
    }

    @Test
    fun `rewrites the source machine identifier, folder and state paths inside the events`() {
        val source = createHome("source8", withSession = true)
        val archive = temp.newFolder("out8").toPath().resolve("sessions.zip")
        SessionTransfer(source).export(listOf(sessionId), archive)

        // The session already exists here, so DUPLICATE gives it a fresh identifier: that is the
        // case where leaving the original one inside the events makes the copy unusable.
        val target = createHome("target8", withSession = true)
        val transfer = SessionTransfer(target)
        val outcome = transfer.import(archive, listOf(sessionId), ConflictPolicy.DUPLICATE, "D:\\work\\demo")
        val newId = outcome.importedIds.single()
        assertNotEquals(sessionId, newId)

        val events = eventsOf(target, newId)
        val start = events.first().getAsJsonObject("data")
        // Copilot rewrites exactly these three fields when it forks a session itself.
        assertEquals(newId, start.get("sessionId").asString)
        assertFalse(start.get("alreadyInUse").asBoolean)
        assertEquals("d:\\work\\demo", start.getAsJsonObject("context").get("cwd").asString)
        assertEquals("d:\\work", start.getAsJsonObject("context").get("gitRoot").asString)
        // Numbers must survive the round trip as plain integers - exponent notation would break
        // them - but the value itself is now shifted like every other timestamp, so it must differ
        // from the one written by the source machine.
        val shiftedTimestamp = events.first().get("timestamp").asString
        assertTrue(shiftedTimestamp.matches(Regex("\\d{13}")))
        assertNotEquals("1767261600000", shiftedTimestamp)

        val hook = events[1].getAsJsonObject("data")
        assertEquals(newId, hook.get("sessionId").asString)
        assertEquals("d:\\work\\demo", hook.get(CWD).asString)

        val turn = events[2].getAsJsonObject("data")
        assertEquals(
            CopilotPaths.normalizeWorkspacePath(checkpointOf(target, newId)),
            turn.get("checkpointPath").asString,
        )
        // Prose is never touched: only the fields Copilot reads back are rewritten.
        assertEquals("open C:\\projects\\demo\\src\\Main.kt please", turn.get("text").asString)

        val snapshots = Files.readString(
            CopilotPaths.stateDir(target).resolve(newId).resolve("rewind-file-snapshots").resolve("index.json")
        )
        assertTrue(snapshots.contains(newId))
        assertFalse(snapshots.contains(sessionId))
        assertTrue(snapshots.contains("d:\\\\work\\\\demo\\\\src\\\\Main.kt"))
    }

    @Test
    fun `reads an archive stamped by a development build and rejects an unknown format`() {
        val source = createHome("sourceFormat", withSession = true)
        val archive = temp.newFolder("outFormat").toPath().resolve("sessions.zip")
        SessionTransfer(source).export(listOf(sessionId), archive)

        val transfer = SessionTransfer(createHome("targetFormat", withSession = false))
        assertEquals(SessionTransfer.FORMAT_VERSION, transfer.readManifest(archive).formatVersion)

        // Builds predating 1.0.0 stamped this very layout with a higher number: those archives are
        // still read, because what can legitimately be missing is detected by its absence.
        stampFormatVersion(archive, SessionTransfer.MAX_READABLE_FORMAT_VERSION)
        assertEquals(1, transfer.readManifest(archive).sessions.size)

        stampFormatVersion(archive, SessionTransfer.MAX_READABLE_FORMAT_VERSION + 1)
        assertTrue(runCatching { transfer.readManifest(archive) }.exceptionOrNull() is IllegalArgumentException)
    }

    private fun stampFormatVersion(archive: Path, version: Int) {
        FileSystems.newFileSystem(archive).use { zip ->
            val manifest = zip.getPath(SessionTransfer.MANIFEST_ENTRY)
            val json = JsonParser.parseString(Files.readString(manifest)).asJsonObject
            json.addProperty("formatVersion", version)
            Files.writeString(manifest, json.toString())
        }
    }

    @Test
    fun `relocates the copilot home of the source machine, in the events and in the store`() {
        val source = createHome("source9", withSession = true)
        val archive = temp.newFolder("out9").toPath().resolve("sessions.zip")
        SessionTransfer(source).export(listOf(sessionId), archive)

        val target = createHome("target9", withSession = false)
        SessionTransfer(target).import(archive, listOf(sessionId), ConflictPolicy.SKIP, "D:\\work\\demo")

        val tool = eventsOf(target, sessionId).single { it.get("type").asString == "tool" }.getAsJsonObject("data")
        // Written by the agent inside its own state folder: it must follow the session here.
        assertEquals(
            CopilotPaths.normalizeWorkspacePath(stateFileOf(target, sessionId)),
            tool.get("transcriptPath").asString,
        )
        assertEquals("d:\\work\\demo\\src\\Main.kt", tool.get("fileName").asString)
        // The rest of `~/.copilot`: it named the home of another user, which does not exist here.
        assertEquals(
            CopilotPaths.normalizeWorkspacePath(target.toAbsolutePath().resolve("ide").toString()),
            tool.getAsJsonArray("possiblePaths").single().asString,
        )

        connect(CopilotPaths.storeDb(target)).use { connection ->
            connection.createStatement().use { statement ->
                val paths = mutableListOf<String>()
                statement.executeQuery("SELECT file_path FROM session_files ORDER BY id").use { rows ->
                    while (rows.next()) paths += rows.getString(1)
                }
                assertEquals(
                    listOf(
                        "d:\\work\\demo\\src\\Main.kt",
                        CopilotPaths.normalizeWorkspacePath(stateFileOf(target, sessionId)),
                    ),
                    paths,
                )
            }
        }
    }

    @Test
    fun `skips or duplicates an already existing session`() {
        val source = createHome("source2", withSession = true)
        val archive = temp.newFolder("out2").toPath().resolve("sessions.zip")
        SessionTransfer(source).export(listOf(sessionId), archive)

        val transfer = SessionTransfer(source)
        val skipped = transfer.import(archive, listOf(sessionId), ConflictPolicy.SKIP)
        assertEquals(listOf(sessionId), skipped.skipped)
        assertEquals(1, transfer.listSessions().size)

        val duplicated = transfer.import(archive, listOf(sessionId), ConflictPolicy.DUPLICATE)
        assertEquals(1, duplicated.imported.size)
        val newId = duplicated.importedIds.first()
        assertNotEquals(sessionId, newId)

        val sessions = transfer.listSessions()
        assertEquals(2, sessions.size)
        val copy = sessions.first { it.id == newId }
        assertEquals(2, copy.turnCount)
        assertEquals("Demo session", copy.name)
        assertTrue(
            Files.readString(CopilotPaths.stateDir(source).resolve(newId).resolve("workspace.yaml"))
                .contains("id: $newId")
        )
    }

    @Test
    fun `relocates the session to the folder used on the target machine`() {
        val source = createHome("source3", withSession = true)
        val archive = temp.newFolder("out3").toPath().resolve("sessions.zip")
        SessionTransfer(source).export(listOf(sessionId), archive)

        val target = createHome("target3", withSession = false)
        val transfer = SessionTransfer(target)

        val outcome = transfer.import(archive, listOf(sessionId), ConflictPolicy.SKIP, "D:\\work\\demo")
        assertEquals(listOf(sessionId), outcome.importedIds)
        assertTrue(outcome.failures.isEmpty())

        // Copilot records the drive letter in lower case and only finds sessions by exact match.
        assertEquals("d:\\work\\demo", transfer.listSessions().first().cwd)
        assertEquals("d:\\work\\demo", outcome.imported.first().cwd)
        val workspace = Files.readString(CopilotPaths.stateDir(target).resolve(sessionId).resolve("workspace.yaml"))
        assertTrue(workspace.contains("cwd: d:\\work\\demo"))
        assertTrue(workspace.contains("git_root: d:\\work"))
        assertTrue(workspace.contains("repository: acme/demo"))
    }

    @Test
    fun `canonicalizes the folder even when source and destination only differ in spelling`() {
        val source = createHome("source5", withSession = true)
        val archive = temp.newFolder("out5").toPath().resolve("sessions.zip")
        SessionTransfer(source).export(listOf(sessionId), archive)

        val target = createHome("target5", withSession = false)
        val transfer = SessionTransfer(target)

        // Same folder as the source machine, but spelled the way the IDE hands it over:
        // without this the row keeps `C:\projects\demo` and Copilot never matches it.
        val outcome = transfer.import(archive, listOf(sessionId), ConflictPolicy.SKIP, "C:/projects/demo")

        assertEquals("c:\\projects\\demo", outcome.imported.first().cwd)
        assertEquals("c:\\projects\\demo", transfer.listSessions().first().cwd)
        assertTrue(
            Files.readString(CopilotPaths.stateDir(target).resolve(sessionId).resolve("workspace.yaml"))
                .contains("cwd: c:\\projects\\demo")
        )
    }

    @Test
    fun `reports a session that is listed but not indexed for search`() {
        val source = createHome("source6", withSession = true)
        val archive = temp.newFolder("out6").toPath().resolve("sessions.zip")
        SessionTransfer(source).export(listOf(sessionId), archive)
        // Drop the database rows, keeping the state folder: the chat still lists the session,
        // because it is served from `workspace.yaml`, but the session store cannot search it.
        stripStoreEntries(archive)

        val target = createHome("target6", withSession = false)
        val outcome = SessionTransfer(target).import(archive, listOf(sessionId), ConflictPolicy.SKIP)

        val restored = outcome.imported.single()
        assertTrue(restored.listed)
        assertFalse(restored.inStore)
        assertEquals(0, restored.turnCount)
        assertTrue(restored.hasState)
    }

    @Test
    fun `recreates the metadata the chat needs when the archive could not carry it`() {
        val source = createHome("source9", withSession = true)
        val archive = temp.newFolder("out9").toPath().resolve("sessions.zip")
        SessionTransfer(source).export(listOf(sessionId), archive)
        stripEntries(archive) { it.endsWith("workspace.yaml") }

        val target = createHome("target9", withSession = false)
        val outcome = SessionTransfer(target).import(archive, listOf(sessionId), ConflictPolicy.SKIP)

        val restored = outcome.imported.single()
        assertTrue(restored.listed)
        assertEquals("C:\\projects\\demo", restored.cwd)
        val workspace = Files.readString(CopilotPaths.stateDir(target).resolve(sessionId).resolve("workspace.yaml"))
        assertTrue(workspace.contains("id: $sessionId"))
        // The reconstructed session is dated as if just imported (§4.6 of the work order): the SQL format of
        // the archived created_at survives, but never its original value.
        assertTrue(workspace.contains(Regex("created_at: \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")))
        assertFalse(workspace.contains("created_at: 2026-01-01 10:00:00"))
    }

    @Test
    fun `warns when the conversation content is missing`() {
        val home = createHome("source7", withSession = true)
        deleteRecursively(CopilotPaths.stateDir(home).resolve(sessionId))

        val archive = temp.newFolder("out7").toPath().resolve("sessions.zip")
        val outcome = SessionTransfer(home).export(listOf(sessionId), archive)

        assertEquals(1, outcome.sessions)
        assertTrue(outcome.warnings.any { it.contains("session-state") })
    }

    // ---------------------------------------------------------------- import log (§5)

    @Test
    fun `import log contains the environment, visibility diagnosis and summary sections`() {
        val source = createHome("sourceLog1", withSession = true)
        val archive = temp.newFolder("outLog1").toPath().resolve("sessions.zip")
        SessionTransfer(source).export(listOf(sessionId), archive)

        val target = createHome("targetLog1", withSession = false)
        val logFile = temp.newFolder("logs1").toPath().resolve("import.log")
        FileImportLog(logFile).use { log ->
            SessionTransfer(target).import(archive, listOf(sessionId), ConflictPolicy.SKIP, log = log)
        }

        val content = Files.readString(logFile)
        assertTrue(content.isNotBlank())
        assertTrue(content.contains("== ENVIRONMENT =="))
        assertTrue(content.contains("== VISIBILITY DIAGNOSIS =="))
        assertTrue(content.contains("== SUMMARY =="))
    }

    @Test
    fun `check 4 stays OK when the requested folder differs from the canonical form only by spelling`() {
        val source = createHome("sourceLog2", withSession = true)
        val archive = temp.newFolder("outLog2").toPath().resolve("sessions.zip")
        SessionTransfer(source).export(listOf(sessionId), archive)

        val target = createHome("targetLog2", withSession = false)
        val logFile = temp.newFolder("logs2").toPath().resolve("import.log")
        FileImportLog(logFile).use { log ->
            // Upper case drive letter and forward slashes: the canonical form Copilot needs is
            // "c:\projects\demo", and the diagnosis must compare against that, not the raw request.
            SessionTransfer(target).import(archive, listOf(sessionId), ConflictPolicy.SKIP, "C:/projects/demo", log = log)
        }

        val content = Files.readString(logFile)
        assertTrue(content.contains("OK  #4"))
        assertFalse(content.contains("KO  #4"))
    }

    @Test
    fun `import log documents the reconstruction of a missing workspace yaml`() {
        val source = createHome("sourceLog3", withSession = true)
        val archive = temp.newFolder("outLog3").toPath().resolve("sessions.zip")
        SessionTransfer(source).export(listOf(sessionId), archive)
        stripEntries(archive) { it.endsWith("workspace.yaml") }

        val target = createHome("targetLog3", withSession = false)
        val logFile = temp.newFolder("logs3").toPath().resolve("import.log")
        FileImportLog(logFile).use { log ->
            SessionTransfer(target).import(archive, listOf(sessionId), ConflictPolicy.SKIP, log = log)
        }

        val content = Files.readString(logFile)
        assertTrue(content.contains("workspace.yaml reconstructed from the archived database row"))
        // The reconstructed file does carry an id and a folder, so checks 1 and 2 must read OK.
        assertTrue(content.contains("OK  #1"))
        assertTrue(content.contains("OK  #2"))
    }

    @Test
    fun `import log never contains the text of a conversation turn`() {
        val source = createHome("sourceLog4", withSession = true)
        val archive = temp.newFolder("outLog4").toPath().resolve("sessions.zip")
        SessionTransfer(source).export(listOf(sessionId), archive)

        val target = createHome("targetLog4", withSession = false)
        val logFile = temp.newFolder("logs4").toPath().resolve("import.log")
        FileImportLog(logFile).use { log ->
            SessionTransfer(target).import(archive, listOf(sessionId), ConflictPolicy.SKIP, log = log)
        }

        val content = Files.readString(logFile)
        // "open C:\projects\demo\src\Main.kt please" (eventsOf) and "question 0"/"answer 0"
        // (seedSession) are the only conversation text in the fixture: only paths, keys, counts and
        // metadata may reach the log, never the turns themselves.
        assertFalse(content.contains("please"))
        assertFalse(content.contains("question 0"))
        assertFalse(content.contains("answer 0"))
    }

    @Test
    fun `import succeeds even when the log file cannot be written`() {
        val source = createHome("sourceLog5", withSession = true)
        val archive = temp.newFolder("outLog5").toPath().resolve("sessions.zip")
        SessionTransfer(source).export(listOf(sessionId), archive)

        val target = createHome("targetLog5", withSession = false)
        // A path below a regular file can never be created: FileImportLog must swallow the failure.
        val blocker = temp.newFolder("logs5").toPath().resolve("blocker.txt")
        Files.writeString(blocker, "not a directory")
        val log = FileImportLog(blocker.resolve("import.log"))

        val outcome = try {
            SessionTransfer(target).import(archive, listOf(sessionId), ConflictPolicy.SKIP, log = log)
        } finally {
            log.close()
        }

        assertEquals(listOf(sessionId), outcome.importedIds)
    }

    // ---------------------------------------------------------------- imported dates (§4.6)

    @Test
    fun `imported timestamps look freshly created while preserving the turn spacing`() {
        val source = createHome("sourceTs1", withSession = true)
        // The shared fixture has its checkpoint dated after `updated_at`: pin it down so
        // `updated_at` - matching the latest turn - is the true maximum of the archive, exactly
        // the scenario this test (and §4.6 of the work order) describes.
        connect(CopilotPaths.storeDb(source)).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("UPDATE sessions SET updated_at = '2026-01-02 11:01:00' WHERE id = '$sessionId'")
                statement.executeUpdate("UPDATE checkpoints SET created_at = '2026-01-02 10:55:00' WHERE session_id = '$sessionId'")
            }
        }
        val archive = temp.newFolder("outTs1").toPath().resolve("sessions.zip")
        SessionTransfer(source).export(listOf(sessionId), archive)

        val target = createHome("targetTs1", withSession = false)
        val transfer = SessionTransfer(target)
        val before = System.currentTimeMillis()
        transfer.import(archive, listOf(sessionId), ConflictPolicy.SKIP)
        val after = System.currentTimeMillis()

        val restored = transfer.store.describe(sessionId)!!
        val updatedAt = TimestampShifter.parse(restored.updatedAt)!!.toEpochMilli()
        val createdAt = TimestampShifter.parse(restored.createdAt)!!.toEpochMilli()
        assertTrue(updatedAt in (before - 5_000)..(after + 5_000))
        assertTrue(createdAt < updatedAt)

        connect(CopilotPaths.storeDb(target)).use { connection ->
            connection.createStatement().use { statement ->
                val timestamps = ArrayList<Long>()
                statement.executeQuery(
                    "SELECT timestamp FROM turns WHERE session_id = '$sessionId' ORDER BY turn_index"
                ).use { rows ->
                    while (rows.next()) timestamps += TimestampShifter.parse(rows.getString(1))!!.toEpochMilli()
                }
                assertEquals(2, timestamps.size)
                // seedSession spaces the two turns exactly 60 seconds apart ("11:00:00" / "11:01:00").
                assertEquals(60_000L, timestamps[1] - timestamps[0])
            }
        }
    }

    @Test
    fun `no imported timestamp equals its source value`() {
        val source = createHome("sourceTs2", withSession = true)
        val archive = temp.newFolder("outTs2").toPath().resolve("sessions.zip")
        SessionTransfer(source).export(listOf(sessionId), archive)

        val target = createHome("targetTs2", withSession = false)
        val transfer = SessionTransfer(target)
        transfer.import(archive, listOf(sessionId), ConflictPolicy.SKIP)

        val restored = transfer.store.describe(sessionId)!!
        assertNotEquals("2026-01-01 10:00:00", restored.createdAt)
        assertNotEquals("2026-01-02 11:00:00", restored.updatedAt)

        val workspace = Files.readString(CopilotPaths.stateDir(target).resolve(sessionId).resolve("workspace.yaml"))
        assertFalse(workspace.contains("created_at: 2026-01-01T10:00:00.000Z"))
        assertFalse(workspace.contains("updated_at: 2026-01-02T11:00:00.000Z"))
    }

    @Test
    fun `each timestamp format is preserved after shifting`() {
        val source = createHome("sourceTs3", withSession = true)
        val archive = temp.newFolder("outTs3").toPath().resolve("sessions.zip")
        SessionTransfer(source).export(listOf(sessionId), archive)

        val target = createHome("targetTs3", withSession = false)
        SessionTransfer(target).import(archive, listOf(sessionId), ConflictPolicy.SKIP)

        // workspace.yaml keeps the ISO-8601 shape (with milliseconds and Z) it was exported with.
        val workspace = Files.readString(CopilotPaths.stateDir(target).resolve(sessionId).resolve("workspace.yaml"))
        val createdAtLine = workspace.lines().single { it.startsWith("created_at:") }
        assertTrue(createdAtLine.matches(Regex("created_at: \\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z")))

        // events.jsonl keeps the 13 digit epoch millisecond shape of the session.start event.
        val firstEventTimestamp = eventsOf(target, sessionId).first().get("timestamp").asString
        assertTrue(firstEventTimestamp.matches(Regex("\\d{13}")))
    }

    @Test
    fun `an unrecognizable timestamp value is left untouched by the import`() {
        val home = createHome("sourceTs4", withSession = true)
        connect(CopilotPaths.storeDb(home)).use { connection ->
            connection.prepareStatement("UPDATE checkpoints SET created_at = ? WHERE session_id = ?").use { statement ->
                statement.setString(1, "not-a-real-date")
                statement.setString(2, sessionId)
                statement.executeUpdate()
            }
        }
        val archive = temp.newFolder("outTs4").toPath().resolve("sessions.zip")
        SessionTransfer(home).export(listOf(sessionId), archive)

        val target = createHome("targetTs4", withSession = false)
        SessionTransfer(target).import(archive, listOf(sessionId), ConflictPolicy.SKIP)

        connect(CopilotPaths.storeDb(target)).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT created_at FROM checkpoints WHERE session_id = '$sessionId'").use { rows ->
                    assertTrue(rows.next())
                    assertEquals("not-a-real-date", rows.getString(1))
                }
            }
        }
    }

    @Test
    fun `a turn whose text contains a 13 digit number is not modified`() {
        val home = createHome("sourceTs5", withSession = true)
        connect(CopilotPaths.storeDb(home)).use { connection ->
            connection.prepareStatement(
                "UPDATE turns SET user_message = ? WHERE session_id = ? AND turn_index = 0"
            ).use { statement ->
                statement.setString(1, "please open ticket 1234567890123 today")
                statement.setString(2, sessionId)
                statement.executeUpdate()
            }
        }
        val archive = temp.newFolder("outTs5").toPath().resolve("sessions.zip")
        SessionTransfer(home).export(listOf(sessionId), archive)

        val target = createHome("targetTs5", withSession = false)
        SessionTransfer(target).import(archive, listOf(sessionId), ConflictPolicy.SKIP)

        connect(CopilotPaths.storeDb(target)).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT user_message FROM turns WHERE session_id = '$sessionId' AND turn_index = 0"
                ).use { rows ->
                    assertTrue(rows.next())
                    assertEquals("please open ticket 1234567890123 today", rows.getString(1))
                }
            }
        }
    }

    private fun deleteRecursively(dir: Path) {
        Files.walk(dir).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
        }
    }

    /** Rewrites [archive] keeping every entry but the per session `store.json` payloads. */
    private fun stripStoreEntries(archive: Path) = stripEntries(archive) { it.endsWith("store.json") }

    private fun stripEntries(archive: Path, drop: (String) -> Boolean) {
        val patched = archive.resolveSibling("patched.zip")
        java.util.zip.ZipFile(archive.toFile()).use { zip ->
            java.util.zip.ZipOutputStream(Files.newOutputStream(patched)).use { out ->
                for (entry in zip.entries()) {
                    if (drop(entry.name)) continue
                    out.putNextEntry(java.util.zip.ZipEntry(entry.name))
                    zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
        Files.move(patched, archive, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    @Test
    fun `normalizes windows folders the way copilot records them`() {
        assertEquals("c:\\Users\\bob\\demo", CopilotPaths.normalizeWorkspacePath("C:/Users/bob/demo"))
        assertEquals("c:\\Users\\bob\\demo", CopilotPaths.normalizeWorkspacePath("  c:\\Users\\bob\\demo  "))
        assertEquals("/home/bob/demo", CopilotPaths.normalizeWorkspacePath("/home/bob/demo"))
        assertEquals(null, CopilotPaths.normalizeWorkspacePath(null))
    }

    @Test
    fun `keeps the original folder when no relocation is requested`() {
        val source = createHome("source4", withSession = true)
        val archive = temp.newFolder("out4").toPath().resolve("sessions.zip")
        SessionTransfer(source).export(listOf(sessionId), archive)

        val target = createHome("target4", withSession = false)
        val transfer = SessionTransfer(target)
        transfer.import(archive, listOf(sessionId), ConflictPolicy.SKIP)

        assertEquals("C:\\projects\\demo", transfer.listSessions().first().cwd)
    }

    @Test
    fun `path mapper translates descendants and ancestors`() {
        val mapper = PathMapper("C:\\projects\\demo", "/home/bob/work/demo")
        assertTrue(mapper.isEnabled)
        assertEquals("/home/bob/work/demo", mapper.map("c:/projects/DEMO"))
        assertEquals("/home/bob/work/demo/src/Main.kt", mapper.map("C:\\projects\\demo\\src\\Main.kt"))
        assertEquals("/home/bob/work", mapper.map("C:\\projects"))
        assertEquals("C:\\other", mapper.map("C:\\other"))
        assertFalse(PathMapper("C:\\projects\\demo", "c:/projects/demo/").isEnabled)
        assertFalse(PathMapper("C:\\projects\\demo", null).isEnabled)
    }

    private fun createHome(name: String, withSession: Boolean): Path {
        val home = temp.newFolder(name).toPath()
        Files.createDirectories(CopilotPaths.stateDir(home))
        createSchema(CopilotPaths.storeDb(home))
        if (withSession) {
            seedSession(home)
        }
        return home
    }

    private fun connect(db: Path) = SQLiteDataSource().apply {
        url = "jdbc:sqlite:" + db.toAbsolutePath().toString().replace('\\', '/')
    }.connection

    private fun createSchema(db: Path) {
        connect(db).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE sessions (
                        id TEXT PRIMARY KEY, cwd TEXT, repository TEXT, host_type TEXT, branch TEXT,
                        summary TEXT, created_at TEXT, updated_at TEXT)
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE turns (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        session_id TEXT NOT NULL REFERENCES sessions(id),
                        turn_index INTEGER NOT NULL, user_message TEXT, assistant_response TEXT,
                        timestamp TEXT, UNIQUE(session_id, turn_index))
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE checkpoints (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        session_id TEXT NOT NULL REFERENCES sessions(id),
                        checkpoint_number INTEGER NOT NULL, title TEXT, created_at TEXT,
                        UNIQUE(session_id, checkpoint_number))
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE session_files (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        session_id TEXT NOT NULL REFERENCES sessions(id),
                        file_path TEXT NOT NULL)
                    """.trimIndent()
                )
            }
        }
    }

    private fun seedSession(home: Path) {
        connect(CopilotPaths.storeDb(home)).use { connection ->
            connection.prepareStatement(
                "INSERT INTO sessions (id, cwd, repository, host_type, branch, summary, created_at, updated_at) " +
                    "VALUES (?,?,?,?,?,?,?,?)"
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.setString(2, "C:\\projects\\demo")
                statement.setString(3, "acme/demo")
                statement.setString(4, "github")
                statement.setString(5, "main")
                statement.setString(6, "A demo conversation")
                statement.setString(7, "2026-01-01 10:00:00")
                statement.setString(8, "2026-01-02 11:00:00")
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO turns (session_id, turn_index, user_message, assistant_response, timestamp) VALUES (?,?,?,?,?)"
            ).use { statement ->
                repeat(2) { index ->
                    statement.setString(1, sessionId)
                    statement.setInt(2, index)
                    statement.setString(3, "question $index")
                    statement.setString(4, "answer $index")
                    statement.setString(5, "2026-01-02 11:0$index:00")
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            connection.prepareStatement(
                "INSERT INTO checkpoints (session_id, checkpoint_number, title, created_at) VALUES (?,?,?,?)"
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.setInt(2, 1)
                statement.setString(3, "First checkpoint")
                statement.setString(4, "2026-01-02 11:05:00")
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO session_files (session_id, file_path) VALUES (?,?)"
            ).use { statement ->
                // A file of the project, and one the agent wrote inside its own state folder.
                for (path in listOf("C:\\projects\\demo\\src\\Main.kt", stateFileOf(home, sessionId))) {
                    statement.setString(1, sessionId)
                    statement.setString(2, path)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }

        val dir = CopilotPaths.stateDir(home).resolve(sessionId)
        Files.createDirectories(dir.resolve("checkpoints"))
        Files.createDirectories(dir.resolve("rewind-file-snapshots"))
        Files.writeString(
            dir.resolve("workspace.yaml"),
            """
            id: $sessionId
            cwd: C:\projects\demo
            git_root: C:\projects
            repository: acme/demo
            branch: main
            name: Demo session
            created_at: 2026-01-01T10:00:00.000Z
            updated_at: 2026-01-02T11:00:00.000Z
            """.trimIndent(),
            StandardCharsets.UTF_8,
        )
        Files.writeString(dir.resolve("events.jsonl"), eventsOf(home), StandardCharsets.UTF_8)
        Files.writeString(
            dir.resolve("rewind-file-snapshots").resolve("index.json"),
            """{"sessionId":"$sessionId","files":[{"path":"C:\\projects\\demo\\src\\Main.kt"}]}""",
            StandardCharsets.UTF_8,
        )
        Files.writeString(dir.resolve("checkpoints").resolve("index.md"), "# checkpoints\n", StandardCharsets.UTF_8)
        Files.writeString(dir.resolve("inuse.123.lock"), "123", StandardCharsets.UTF_8)
    }

    /** The events as the source machine wrote them: its own identifier, folder and state paths. */
    private fun eventsOf(home: Path): String {
        val checkpoint = checkpointOf(home, sessionId)
        val stateFile = stateFileOf(home, sessionId)
        val ideDir = home.toAbsolutePath().resolve("ide").toString()
        return listOf(
            """{"type":"session.start","timestamp":1767261600000,"data":{"sessionId":"$sessionId",""" +
                """"alreadyInUse":true,"startTime":1767261600000,""" +
                """"context":{"cwd":"C:\\projects\\demo","gitRoot":"C:\\projects"}}}""",
            """{"type":"hook.start","data":{"sessionId":"$sessionId","cwd":"C:\\projects\\demo"}}""",
            """{"type":"turn","data":{"text":"open C:\\projects\\demo\\src\\Main.kt please",""" +
                """"checkpointPath":"${checkpoint.replace("\\", "\\\\")}"}}""",
            """{"type":"tool","data":{"transcriptPath":"${stateFile.replace("\\", "\\\\")}",""" +
                """"fileName":"C:\\projects\\demo\\src\\Main.kt",""" +
                """"possiblePaths":["${ideDir.replace("\\", "\\\\")}"]}}""",
            "not json at all",
        ).joinToString("\n", postfix = "\n")
    }

    private fun checkpointOf(home: Path, id: String): String =
        home.toAbsolutePath().resolve("session-state").resolve(id).resolve("checkpoints").resolve("001.md").toString()

    /** A file the agent wrote inside the state folder of the session, as `session_files` records it. */
    private fun stateFileOf(home: Path, id: String): String =
        home.toAbsolutePath().resolve("session-state").resolve(id).resolve("files").resolve("notes.md").toString()

    private fun eventsOf(home: Path, id: String): List<com.google.gson.JsonObject> =
        Files.readAllLines(CopilotPaths.stateDir(home).resolve(id).resolve("events.jsonl"), StandardCharsets.UTF_8)
            .filter { it.startsWith("{") }
            .map { com.google.gson.JsonParser.parseString(it).asJsonObject }
}
