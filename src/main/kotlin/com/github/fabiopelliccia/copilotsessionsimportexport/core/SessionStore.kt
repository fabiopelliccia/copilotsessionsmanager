package com.github.fabiopelliccia.copilotsessionsimportexport.core

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types
import java.util.Base64

/** Rows of a single table, serialized in a schema tolerant way. */
data class TableData(
    val columns: List<String> = emptyList(),
    val rows: List<List<JsonElement>> = emptyList(),
)

/** Complete database payload of one session. */
data class SessionData(
    val tables: Map<String, TableData> = emptyMap(),
)

/** One row of `PRAGMA table_info`, exposed so the import log can report the schema it found. */
data class ColumnInfo(val name: String, val type: String, val pk: Int)

/**
 * Read/write access to `~/.copilot/session-store.db`.
 *
 * Every operation is schema tolerant: tables or columns that are unknown to the local
 * Copilot version are ignored, so archives stay compatible across CLI updates.
 *
 * [log] is best-effort diagnostics only: it never changes what is read or written, it just
 * records the schema found and, while importing, which columns were kept, discarded as unknown or
 * skipped as an auto generated key, and how many rows were actually inserted.
 */
class SessionStore(private val dbFile: Path, private val log: ImportLog = ImportLog.NOOP) {

    companion object {
        /**
         * Session scoped tables, in insertion order (`sessions` must come first because
         * every other table references it).
         */
        val SESSION_TABLES: List<String> = listOf(
            "sessions",
            "turns",
            "checkpoints",
            "session_files",
            "session_refs",
            "forge_trajectory_events",
            "assistant_usage_events",
            "search_index",
        )

        private const val BLOB_KEY = "__blob__"
    }

    fun exists(): Boolean = Files.isRegularFile(dbFile)

    /** `true` when the local database already contains the Copilot schema. */
    fun isInitialized(): Boolean = exists() && withConnection { tableExists(it, "sessions") }

    private fun connect(): Connection {
        val config = SQLiteConfig().apply {
            setBusyTimeout(10_000)
            enforceForeignKeys(false)
        }
        val dataSource = SQLiteDataSource(config)
        dataSource.url = "jdbc:sqlite:" + dbFile.toAbsolutePath().toString().replace('\\', '/')
        return dataSource.connection
    }

    private fun <T> withConnection(block: (Connection) -> T): T = connect().use(block)

    private fun tableExists(connection: Connection, table: String): Boolean =
        connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type IN ('table','view') AND name = ?"
        ).use { statement ->
            statement.setString(1, table)
            statement.executeQuery().use { it.next() }
        }

    private fun columnsOf(connection: Connection, table: String): List<ColumnInfo> =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(\"$table\")").use { rs ->
                buildList {
                    while (rs.next()) {
                        add(ColumnInfo(rs.getString("name"), rs.getString("type") ?: "", rs.getInt("pk")))
                    }
                }
            }
        }

    /**
     * Columns that must not be copied: integer primary keys are auto-generated and
     * reusing them would clash with rows already present in the target database.
     */
    private fun generatedColumns(columns: List<ColumnInfo>): Set<String> {
        val primaryKeys = columns.filter { it.pk > 0 }
        if (primaryKeys.size != 1) return emptySet()
        val pk = primaryKeys.first()
        return if (pk.type.equals("INTEGER", ignoreCase = true)) setOf(pk.name) else emptySet()
    }

    private fun keyColumn(table: String): String = if (table == "sessions") "id" else "session_id"

    fun listSessions(): List<SessionInfo> {
        if (!exists()) return emptyList()
        return withConnection { connection ->
            if (!tableExists(connection, "sessions")) return@withConnection emptyList()
            val available = columnsOf(connection, "sessions").map { it.name }.toSet()
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT s.*${turnCountColumn(connection)} FROM sessions s").use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(readSession(rs, available))
                        }
                    }
                }
            }
        }
    }

    /**
     * Reads back a single session exactly as Copilot sees it, or `null` when the row is missing.
     * Used to verify what an import really wrote into the database.
     */
    fun describe(sessionId: String): SessionInfo? {
        if (!exists()) return null
        return withConnection { connection ->
            if (!tableExists(connection, "sessions")) return@withConnection null
            val available = columnsOf(connection, "sessions").map { it.name }.toSet()
            val sql = "SELECT s.*${turnCountColumn(connection)} FROM sessions s WHERE s.id = ?"
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, sessionId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) readSession(rs, available) else null
                }
            }
        }
    }

    private fun turnCountColumn(connection: Connection): String =
        if (tableExists(connection, "turns")) {
            ", (SELECT COUNT(*) FROM turns t WHERE t.session_id = s.id) AS turn_count"
        } else {
            ", 0 AS turn_count"
        }

    private fun readSession(rs: ResultSet, available: Set<String>): SessionInfo = SessionInfo(
        id = rs.getString("id"),
        summary = if ("summary" in available) rs.getString("summary") else null,
        repository = if ("repository" in available) rs.getString("repository") else null,
        cwd = if ("cwd" in available) rs.getString("cwd") else null,
        branch = if ("branch" in available) rs.getString("branch") else null,
        createdAt = if ("created_at" in available) rs.getString("created_at") else null,
        updatedAt = if ("updated_at" in available) rs.getString("updated_at") else null,
        turnCount = rs.getInt("turn_count"),
        inStore = true,
    )

    /**
     * Schema of every table of [SESSION_TABLES] that exists locally: columns, their declared type
     * and their primary key flag, straight from `PRAGMA table_info`. Used by the import log to show
     * whether the Copilot database on this machine has the shape the archive expects.
     */
    fun tableSchemas(): Map<String, List<ColumnInfo>> {
        if (!exists()) return emptyMap()
        return withConnection { connection ->
            SESSION_TABLES.filter { tableExists(connection, it) }.associateWith { columnsOf(connection, it) }
        }
    }

    /**
     * `SELECT COUNT(*)` for [sessionId] in every table of [SESSION_TABLES] that exists locally,
     * read back after an import to verify what was actually written, rather than trusting the
     * batch counts collected while inserting.
     */
    fun countRows(sessionId: String): Map<String, Int> {
        if (!exists()) return emptyMap()
        return withConnection { connection ->
            SESSION_TABLES.filter { tableExists(connection, it) }.associateWith { table ->
                connection.prepareStatement(
                    "SELECT COUNT(*) FROM \"$table\" WHERE \"${keyColumn(table)}\" = ?"
                ).use { statement ->
                    statement.setString(1, sessionId)
                    statement.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
                }
            }
        }
    }

    fun existingIds(): Set<String> = listSessions().mapTo(HashSet()) { it.id }

    fun contains(sessionId: String): Boolean {
        if (!exists()) return false
        return withConnection { connection ->
            if (!tableExists(connection, "sessions")) return@withConnection false
            connection.prepareStatement("SELECT 1 FROM sessions WHERE id = ?").use { statement ->
                statement.setString(1, sessionId)
                statement.executeQuery().use { it.next() }
            }
        }
    }

    fun exportSession(sessionId: String): SessionData {
        if (!exists()) return SessionData()
        return withConnection { connection ->
            val tables = LinkedHashMap<String, TableData>()
            for (table in SESSION_TABLES) {
                if (!tableExists(connection, table)) continue
                val data = runCatching { readTable(connection, table, sessionId) }.getOrNull() ?: continue
                if (data.rows.isNotEmpty()) {
                    tables[table] = data
                }
            }
            SessionData(tables)
        }
    }

    private fun readTable(connection: Connection, table: String, sessionId: String): TableData {
        val sql = "SELECT * FROM \"$table\" WHERE \"${keyColumn(table)}\" = ?"
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, sessionId)
            statement.executeQuery().use { rs ->
                val metaData = rs.metaData
                val columns = (1..metaData.columnCount).map { metaData.getColumnName(it) }
                val rows = ArrayList<List<JsonElement>>()
                while (rs.next()) {
                    rows.add((1..metaData.columnCount).map { toJson(rs, it) })
                }
                return TableData(columns, rows)
            }
        }
    }

    private fun toJson(rs: ResultSet, index: Int): JsonElement {
        return when (val value = rs.getObject(index)) {
            null -> JsonNull.INSTANCE
            is ByteArray -> JsonObject().apply {
                addProperty(BLOB_KEY, Base64.getEncoder().encodeToString(value))
            }

            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
    }

    fun deleteSession(sessionId: String) {
        if (!exists()) return
        withConnection { connection ->
            connection.autoCommit = false
            try {
                for (table in SESSION_TABLES.asReversed()) {
                    if (!tableExists(connection, table)) continue
                    runCatching {
                        connection.prepareStatement(
                            "DELETE FROM \"$table\" WHERE \"${keyColumn(table)}\" = ?"
                        ).use { statement ->
                            statement.setString(1, sessionId)
                            statement.executeUpdate()
                        }
                    }
                }
                connection.commit()
            } catch (e: Exception) {
                runCatching { connection.rollback() }
                throw e
            } finally {
                connection.autoCommit = true
            }
        }
    }

    /**
     * Inserts the archived rows, remapping the session identifier to [targetId].
     * Unknown tables/columns are silently dropped to stay forward compatible.
     */
    fun importSession(data: SessionData, targetId: String) {
        withConnection { connection ->
            connection.autoCommit = false
            try {
                for (table in SESSION_TABLES) {
                    val tableData = data.tables[table] ?: continue
                    if (tableData.rows.isEmpty()) continue
                    if (!tableExists(connection, table)) {
                        log.line("$table: not present locally, ${tableData.rows.size} archived row(s) dropped")
                        continue
                    }
                    runCatching { writeTable(connection, table, tableData, targetId) }
                        .onFailure { error ->
                            log.failure("$table: import failed", error)
                            if (table == "sessions") throw error
                        }
                }
                connection.commit()
            } catch (e: Exception) {
                runCatching { connection.rollback() }
                throw e
            } finally {
                connection.autoCommit = true
            }
        }
    }

    private fun writeTable(connection: Connection, table: String, data: TableData, targetId: String) {
        val targetColumns = columnsOf(connection, table)
        val skipped = generatedColumns(targetColumns)
        val targetNames = targetColumns.map { it.name }.toSet()
        val discarded = data.columns.filter { it !in targetNames }
        val indexes = data.columns.indices.filter { data.columns[it] in targetNames && data.columns[it] !in skipped }
        log.kv("$table.archiveColumns", data.columns)
        log.kv("$table.localColumns", targetColumns.map { it.name })
        if (discarded.isNotEmpty()) log.kv("$table.discardedUnknown", discarded)
        if (skipped.isNotEmpty()) log.kv("$table.skippedGeneratedKey", skipped)
        log.kv("$table.rowsAppended", data.rows.size)
        if (indexes.isEmpty()) {
            log.kv("$table.rowsInserted", 0)
            return
        }

        val names = indexes.joinToString(", ") { "\"${data.columns[it]}\"" }
        val placeholders = indexes.joinToString(", ") { "?" }
        val key = keyColumn(table)
        val sql = "INSERT OR REPLACE INTO \"$table\" ($names) VALUES ($placeholders)"

        connection.prepareStatement(sql).use { statement ->
            for (row in data.rows) {
                indexes.forEachIndexed { position, columnIndex ->
                    val column = data.columns[columnIndex]
                    val value = row.getOrNull(columnIndex) ?: JsonNull.INSTANCE
                    if (column == key) {
                        statement.setString(position + 1, targetId)
                    } else {
                        bind(statement, position + 1, value)
                    }
                }
                statement.addBatch()
            }
            val results = runCatching { statement.executeBatch() }
                .onFailure { log.failure("$table: executeBatch failed", it) }
                .getOrThrow()
            var inserted = 0
            for (result in results) inserted += if (result >= 0) result else 1
            log.kv("$table.rowsInserted", inserted)
        }
    }

    private fun bind(statement: java.sql.PreparedStatement, index: Int, value: JsonElement) {
        when {
            value.isJsonNull -> statement.setNull(index, Types.NULL)
            value.isJsonObject -> {
                val blob = value.asJsonObject.get(BLOB_KEY)
                if (blob != null && blob.isJsonPrimitive) {
                    statement.setBytes(index, Base64.getDecoder().decode(blob.asString))
                } else {
                    statement.setString(index, value.toString())
                }
            }

            value.isJsonPrimitive -> {
                val primitive = value.asJsonPrimitive
                when {
                    primitive.isBoolean -> statement.setInt(index, if (primitive.asBoolean) 1 else 0)
                    primitive.isNumber -> {
                        val number = primitive.asNumber.toString()
                        val asLong = number.toLongOrNull()
                        if (asLong != null) statement.setLong(index, asLong)
                        else statement.setDouble(index, number.toDouble())
                    }

                    else -> statement.setString(index, primitive.asString)
                }
            }

            else -> statement.setString(index, value.toString())
        }
    }
}
