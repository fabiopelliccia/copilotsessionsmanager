package com.github.fabiopelliccia.copilotsessionsimportexport.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolves the on-disk locations used by the GitHub Copilot CLI / IDE integration.
 *
 * The location can be overridden with the `copilot.home` system property or the
 * `COPILOT_HOME` environment variable, otherwise `~/.copilot` is used.
 */
object CopilotPaths {

    const val STORE_DB_NAME: String = "session-store.db"
    const val STATE_DIR_NAME: String = "session-state"

    private val WINDOWS_DRIVE = Regex("^[A-Za-z]:")

    /**
     * Rewrites a Windows path the way the Copilot CLI records it in `sessions.cwd` and in
     * `session-state/<id>/workspace.yaml`: backslash separators and a **lower case drive letter**.
     *
     * The IDE hands the CLI an upper case path (`workspaceFolders: ["C:\\Users\\..."]`) but the CLI
     * stores - and looks sessions up by - `c:\Users\...`. That lookup is an exact string match backed
     * by `idx_sessions_cwd`, so a session imported with the upper case form is never listed in the
     * chat even though the import itself succeeded.
     *
     * Non Windows paths are returned untouched.
     */
    fun normalizeWorkspacePath(path: String?): String? {
        val value = path?.trim() ?: return null
        if (!WINDOWS_DRIVE.containsMatchIn(value)) return value
        return value[0].lowercaseChar() + value.substring(1).replace('/', '\\')
    }

    fun copilotHome(): Path {
        System.getProperty("copilot.home")?.takeIf { it.isNotBlank() }?.let { return Paths.get(it) }
        System.getenv("COPILOT_HOME")?.takeIf { it.isNotBlank() }?.let { return Paths.get(it) }
        return Paths.get(System.getProperty("user.home")).resolve(".copilot")
    }

    fun storeDb(home: Path = copilotHome()): Path = home.resolve(STORE_DB_NAME)

    fun stateDir(home: Path = copilotHome()): Path = home.resolve(STATE_DIR_NAME)

    fun isValidHome(home: Path): Boolean =
        Files.isRegularFile(storeDb(home)) || Files.isDirectory(stateDir(home))
}
