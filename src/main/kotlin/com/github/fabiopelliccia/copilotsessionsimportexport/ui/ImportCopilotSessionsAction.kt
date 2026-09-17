package com.github.fabiopelliccia.copilotsessionsimportexport.ui

import com.github.fabiopelliccia.copilotsessionsimportexport.core.CopilotPaths
import com.github.fabiopelliccia.copilotsessionsimportexport.core.CopilotSessionsBundle
import com.github.fabiopelliccia.copilotsessionsimportexport.core.FileImportLog
import com.github.fabiopelliccia.copilotsessionsimportexport.core.IdeSessionRestorer
import com.github.fabiopelliccia.copilotsessionsimportexport.core.ImportEnvironment
import com.github.fabiopelliccia.copilotsessionsimportexport.core.SessionTransfer
import com.github.fabiopelliccia.copilotsessionsimportexport.core.TransferProgress
import com.intellij.notification.NotificationAction
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Tools | Github Copilot sessions | Import Sessions... */
class ImportCopilotSessionsAction : CopilotSessionActionBase() {

    companion object {
        private const val LOG_SUBDIR = "copilot-sessions-import"
        private const val LOGS_TO_KEEP = 20
        private val LOG_FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        /** Matches the `<version>` element `patchPluginXml` stamps into the bundled `plugin.xml`. */
        private val VERSION_TAG = Regex("<version>([^<]*)</version>")
    }

    override fun perform(project: Project?, transfer: SessionTransfer) {
        val descriptor = FileChooserDescriptorFactory.singleFile()
            .withExtensionFilter(SessionTransfer.ARCHIVE_EXTENSION)
            .withTitle(CopilotSessionsBundle.message("dialog.import.chooser.title"))
            .withDescription(CopilotSessionsBundle.message("dialog.import.chooser.description"))
        val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return
        val archive: Path = chosen.toNioPath()

        val manifest = runWithProgress(project, CopilotSessionsBundle.message("progress.readingArchive")) {
            transfer.readManifest(archive)
        } ?: return
        if (manifest.sessions.isEmpty()) {
            Messages.showInfoMessage(project, CopilotSessionsBundle.message("dialog.import.empty"), TITLE)
            return
        }

        val dialog = ImportSessionsDialog(project, archive, manifest, transfer)
        if (!dialog.showAndGet()) return
        val selected = dialog.selectedSessions()
        if (selected.isEmpty()) return
        val policy = dialog.conflictPolicy()
        val relocateTo = dialog.relocationTarget()

        // Always written, with no option to disable it: the log of the one import that actually
        // matters - the one that goes wrong - must not depend on the user remembering to turn on
        // some diagnostic flag beforehand.
        val logDir = Paths.get(PathManager.getLogPath(), LOG_SUBDIR)
        val logFile = logDir.resolve("import-${LOG_FILE_STAMP.format(LocalDateTime.now())}.log")
        val log = FileImportLog(logFile)

        val title = CopilotSessionsBundle.message("progress.importingSessions")
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                val progress = TransferProgress { message, fraction ->
                    indicator.checkCanceled()
                    indicator.text = message
                    indicator.fraction = fraction
                }
                val restorer = IdeSessionRestorer { record, sessionLog ->
                    project != null && CopilotIdeSessionBridge.restore(project, record, sessionLog)
                }
                val outcome = transfer.import(
                    archive,
                    selected.map { it.id },
                    policy,
                    relocateTo,
                    progress,
                    pluginEnvironment(),
                    log,
                    restorer,
                )
                val projectFolder = CopilotPaths.normalizeWorkspacePath(project?.basePath)
                val problems = ArrayList<String>()
                val details = buildString {
                    append(CopilotSessionsBundle.message("notification.import.imported", outcome.imported.size))
                    if (outcome.skipped.isNotEmpty()) {
                        append(", " + CopilotSessionsBundle.message("notification.import.skipped", outcome.skipped.size))
                    }
                    if (outcome.failures.isNotEmpty()) {
                        append(", " + CopilotSessionsBundle.message("notification.import.failures", outcome.failures.size))
                        outcome.failures.forEach { (id, message) -> append("<br/>$id: $message") }
                    }
                    // Read back what Copilot will really find, so a silent "imported but invisible"
                    // result cannot happen any more.
                    for (session in outcome.imported) {
                        append(
                            "<br/>" + CopilotSessionsBundle.message(
                                "notification.import.session",
                                session.displayName,
                                session.cwd ?: CopilotSessionsBundle.message("notification.import.noFolder"),
                                session.turnCount,
                            )
                        )
                        if (!session.listed) {
                            problems.add(
                                CopilotSessionsBundle.message(
                                    "notification.import.problem.notListed",
                                    session.displayName,
                                )
                            )
                        } else if (projectFolder != null && session.cwd != projectFolder) {
                            problems.add(
                                CopilotSessionsBundle.message(
                                    "notification.import.problem.otherFolder",
                                    session.displayName,
                                    session.cwd.orEmpty(),
                                    projectFolder,
                                )
                            )
                        } else if (!session.ideRecordPresent) {
                            problems.add(
                                CopilotSessionsBundle.message(
                                    "notification.import.problem.noIdeRecord",
                                    session.displayName,
                                )
                            )
                        } else if (!session.ideRecordRestored) {
                            problems.add(
                                CopilotSessionsBundle.message(
                                    "notification.import.problem.ideRecordRefused",
                                    session.displayName,
                                )
                            )
                        } else if (!session.hasState) {
                            problems.add(
                                CopilotSessionsBundle.message(
                                    "notification.import.problem.noState",
                                    session.displayName,
                                )
                            )
                        } else if (!session.inStore || session.turnCount == 0) {
                            problems.add(
                                CopilotSessionsBundle.message(
                                    "notification.import.problem.notIndexed",
                                    session.displayName,
                                )
                            )
                        }
                    }
                    problems.forEach { append("<br/>&#9888; $it") }
                    if (outcome.imported.isNotEmpty()) {
                        append("<br/>" + CopilotSessionsBundle.message("notification.import.restartHint"))
                    }
                    append("<br/>" + CopilotSessionsBundle.message("notification.import.log", logFile))
                }
                val actions = buildList {
                    if (outcome.imported.isNotEmpty()) add(CopilotNotifications.restartAction())
                    add(CopilotNotifications.showLogAction(logFile))
                }.toTypedArray<NotificationAction>()
                if (outcome.failures.isEmpty() && problems.isEmpty()) {
                    CopilotNotifications.info(
                        project,
                        CopilotSessionsBundle.message("notification.import.success.title"),
                        details,
                        *actions,
                    )
                } else {
                    CopilotNotifications.warn(
                        project,
                        CopilotSessionsBundle.message("notification.import.warnings.title"),
                        details,
                        *actions,
                    )
                }
            }

            override fun onThrowable(error: Throwable) {
                log.section("Riepilogo")
                log.failure("Import failed before completion", error)
                CopilotNotifications.error(
                    project,
                    CopilotSessionsBundle.message("notification.import.failed.title"),
                    (error.message ?: error.javaClass.simpleName) +
                        "<br/>" + CopilotSessionsBundle.message("notification.import.log", logFile),
                    CopilotNotifications.showLogAction(logFile),
                )
            }

            override fun onFinished() {
                // The log is closed here rather than in a try/finally inside run(): onFinished runs
                // exactly once whether run() completed or onThrowable took over, so this is the one
                // place that reliably sees both outcomes.
                log.close()
                rotateLogs(logDir)
            }
        })
    }

    /**
     * Own version is read from the bundled `META-INF/plugin.xml` resource - the very file
     * `patchPluginXml` stamps with `pluginVersion` - instead of enumerating every installed plugin
     * through the internal `PluginManager.getPlugins()`. The Copilot plugin's id, version and
     * enabled state come from [CopilotIdeSessionBridge.pluginStatus], the single place in this
     * plugin that resolves another plugin's descriptor (see its kdoc for why that one lookup is
     * unavoidable).
     */
    private fun pluginEnvironment(): ImportEnvironment {
        val copilotStatus = CopilotIdeSessionBridge.pluginStatus()
        return ImportEnvironment(
            pluginVersion = ownPluginVersion(),
            ideBuild = runCatching { ApplicationInfo.getInstance().build.asString() }.getOrNull(),
            productName = runCatching { ApplicationNamesInfo.getInstance().fullProductName }.getOrNull(),
            copilotPluginId = copilotStatus?.id,
            copilotPluginVersion = copilotStatus?.version,
            copilotPluginEnabled = copilotStatus?.enabled,
        )
    }

    /**
     * Reads this plugin's own version straight from the `<version>` element `patchPluginXml`
     * stamps into the bundled `META-INF/plugin.xml`, so describing it in the log needs no
     * `PluginManager` lookup at all.
     */
    private fun ownPluginVersion(): String? = runCatching {
        javaClass.classLoader.getResourceAsStream("META-INF/plugin.xml")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?.let { VERSION_TAG.find(it)?.groupValues?.get(1) }
    }.getOrNull()

    /** Keeps the log folder from growing forever: only the most recent [LOGS_TO_KEEP] files survive. */
    private fun rotateLogs(dir: Path, keep: Int = LOGS_TO_KEEP) {
        runCatching {
            if (!Files.isDirectory(dir)) return
            val files = Files.newDirectoryStream(dir, "import-*.log").use { it.toList() }.sortedBy { it.fileName.toString() }
            if (files.size > keep) {
                files.take(files.size - keep).forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }
}
