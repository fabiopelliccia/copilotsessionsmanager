package com.github.fabiopelliccia.copilotsessionsimportexport.core

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Minimal reader for the flat `workspace.yaml` files stored in `session-state/<id>`.
 * A full YAML parser is intentionally avoided: the file only contains `key: value` pairs.
 */
object WorkspaceYaml {

    private val KEY_REGEX = Regex("^([A-Za-z0-9_]+):\\s?(.*)$")

    fun read(file: Path): Map<String, String> {
        if (!Files.isRegularFile(file)) return emptyMap()
        val result = LinkedHashMap<String, String>()
        runCatching {
            Files.readAllLines(file, StandardCharsets.UTF_8).forEach { line ->
                val match = KEY_REGEX.matchEntire(line) ?: return@forEach
                result[match.groupValues[1]] = match.groupValues[2].trim()
            }
        }
        return result
    }

    /**
     * Replaces the value of the given top level keys, leaving every other line untouched.
     * Missing keys are appended, so a file that lost its `id` or `cwd` is repaired instead of
     * being left in the state that makes Copilot drop the session from the chat history.
     *
     * The file is always written with `LF`, the separator the Copilot CLI itself uses.
     */
    fun rewrite(file: Path, values: Map<String, String>) {
        if (values.isEmpty() || !Files.isRegularFile(file)) return
        val lines = runCatching { Files.readAllLines(file, StandardCharsets.UTF_8) }.getOrNull() ?: return
        val missing = LinkedHashMap(values)
        var changed = false
        val patched = lines.map { line ->
            val match = KEY_REGEX.matchEntire(line) ?: return@map line
            val key = match.groupValues[1]
            val replacement = missing.remove(key)
            if (replacement == null || match.groupValues[2].trim() == replacement) {
                line
            } else {
                changed = true
                "$key: $replacement"
            }
        } + missing.map { (key, value) -> "$key: $value" }
        if (changed || missing.isNotEmpty()) write(file, patched)
    }

    /** Recreates a `workspace.yaml` that the archive could not carry, so the session stays listable. */
    fun create(file: Path, values: Map<String, String>) {
        val entries = values.filterValues { it.isNotBlank() }
        if (entries.isEmpty()) return
        runCatching { Files.createDirectories(file.parent) }
        write(file, entries.map { (key, value) -> "$key: $value" })
    }

    private fun write(file: Path, lines: List<String>) {
        val content = lines.joinToString("\n", postfix = "\n")
        runCatching { Files.write(file, content.toByteArray(StandardCharsets.UTF_8)) }
    }
}
