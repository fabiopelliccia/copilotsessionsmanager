package com.github.fabiopelliccia.copilotsessionsimportexport.core

/**
 * Translates the absolute paths recorded inside a session from the machine that produced
 * the archive to the machine that consumes it.
 *
 * Copilot scopes its session list by working directory: the chat picker only shows the rows
 * whose `sessions.cwd` (see the `idx_sessions_cwd` index) matches the folder currently open,
 * and the same value is repeated in `session-state/<id>/workspace.yaml`. An archive produced
 * on `C:\Users\alice\projects\demo` therefore stays invisible on a machine where the very same
 * project lives in `C:\Users\bob\work\demo`, even though the import itself succeeded.
 *
 * The mapper rewrites:
 * - the working directory itself,
 * - any path below it (for example `session_files.file_path`),
 * - any ancestor of it (for example `git_root`), by keeping the same number of parent levels.
 *
 * Comparisons are case insensitive and separator agnostic, because the stored values mix
 * `\` and `/` as well as upper/lower case drive letters.
 */
class PathMapper(source: String?, target: String?) {

    private val sourceSegments: List<String> = segments(canonical(source))
    private val targetSegments: List<String> = segments(canonical(target))
    private val separator: String = if (canonical(target).contains('\\')) "\\" else "/"

    /** `false` when there is nothing to translate, so the payload can be imported verbatim. */
    val isEnabled: Boolean =
        sourceSegments.isNotEmpty() &&
            targetSegments.isNotEmpty() &&
            !(sourceSegments.size == targetSegments.size && startsWith(sourceSegments, targetSegments))

    fun map(value: String?): String? {
        if (!isEnabled || value.isNullOrBlank()) return value
        val current = segments(canonical(value))
        if (current.isEmpty()) return value
        if (startsWith(current, sourceSegments)) {
            return join(targetSegments + current.drop(sourceSegments.size))
        }
        if (startsWith(sourceSegments, current)) {
            val levelsUp = sourceSegments.size - current.size
            if (targetSegments.size > levelsUp) return join(targetSegments.dropLast(levelsUp))
        }
        return value
    }

    private fun join(parts: List<String>): String =
        parts.joinToString(separator).ifEmpty { separator }

    private companion object {

        fun canonical(value: String?): String =
            value?.trim()?.trimEnd('/', '\\').orEmpty()

        fun segments(value: String): List<String> =
            if (value.isEmpty()) emptyList() else value.replace('\\', '/').split('/')

        fun startsWith(value: List<String>, prefix: List<String>): Boolean =
            value.size >= prefix.size && prefix.indices.all { value[it].equals(prefix[it], ignoreCase = true) }
    }
}
