package com.github.fabiopelliccia.copilotsessionsimportexport.core

import java.io.Closeable
import java.io.PrintWriter
import java.io.StringWriter
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Structured log describing exactly what an import does, written so that a session that stays
 * invisible after being restored can be diagnosed without access to the machine that produced it.
 *
 * Every method is best-effort: a failure while writing the log must never make the import itself
 * fail, see [FileImportLog]. No conversation content is ever recorded, only paths, keys, counts,
 * sizes and metadata - see the truncation performed by [FileImportLog].
 */
interface ImportLog {
    /** Starts a top level section, rendered as `== TITLE ==` preceded by a blank line. */
    fun section(title: String)

    /** A single free text line. */
    fun line(message: String)

    /** `key = value` line; `value == null` is rendered as `<null>`, long values are truncated. */
    fun kv(key: String, value: Any?)

    /** Records an exception with its full stack trace; never re-thrown. */
    fun failure(message: String, error: Throwable)

    /** Indented sub-log for a single session, so its entries are visually grouped together. */
    fun child(title: String): ImportLog

    companion object {
        val NOOP: ImportLog = NoopImportLog
    }
}

private object NoopImportLog : ImportLog {
    override fun section(title: String) {}
    override fun line(message: String) {}
    override fun kv(key: String, value: Any?) {}
    override fun failure(message: String, error: Throwable) {}
    override fun child(title: String): ImportLog = this
}

/**
 * Writes every line to [file] in UTF-8, flushing immediately: a crash further down the import must
 * not lose what was logged up to that point. Opening the file is itself best-effort - a read only
 * or missing log directory silently turns every write into a no-op instead of failing the import.
 */
class FileImportLog private constructor(
    private val writer: Writer?,
    private val indent: String,
) : ImportLog, Closeable {

    constructor(file: Path) : this(openWriter(file), "")

    override fun section(title: String) {
        writeRaw("")
        writeLine("== ${title.uppercase()} ==")
    }

    override fun line(message: String) {
        writeLine(message)
    }

    override fun kv(key: String, value: Any?) {
        writeLine("$key = ${truncate(value)}")
    }

    override fun failure(message: String, error: Throwable) {
        writeLine("FAILURE: $message")
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        trace.lineSequence().filter { it.isNotEmpty() }.forEach { writeLine(it) }
    }

    override fun child(title: String): ImportLog {
        val child = FileImportLog(writer, "$indent    ")
        child.writeLine("-- $title --")
        return child
    }

    override fun close() {
        runCatching { writer?.close() }
    }

    private fun writeLine(message: String) {
        message.lineSequence().forEach { writeRaw("${timestamp()}  $indent$it") }
    }

    private fun writeRaw(text: String) {
        val target = writer ?: return
        runCatching {
            target.write(text)
            target.write("\n")
            target.flush()
        }
    }

    private fun timestamp(): String = LocalDateTime.now().format(TIME_FORMAT)

    private fun truncate(value: Any?): String {
        if (value == null) return "<null>"
        val text = value.toString()
        return if (text.length > MAX_VALUE_LENGTH) {
            "${text.take(MAX_VALUE_LENGTH)}…(+${text.length - MAX_VALUE_LENGTH})"
        } else {
            text
        }
    }

    companion object {
        private const val MAX_VALUE_LENGTH = 300
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

        private fun openWriter(file: Path): Writer? = runCatching {
            file.parent?.let { Files.createDirectories(it) }
            Files.newBufferedWriter(
                file,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
        }.getOrNull()
    }
}
