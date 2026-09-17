package com.github.fabiopelliccia.copilotsessionsimportexport.ui

import com.github.fabiopelliccia.copilotsessionsimportexport.core.CopilotSessionsBundle
import com.github.fabiopelliccia.copilotsessionsimportexport.core.SessionTransfer
import com.github.fabiopelliccia.copilotsessionsimportexport.core.TransferProgress
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Tools | Github Copilot sessions | Export Sessions... */
class ExportCopilotSessionsAction : CopilotSessionActionBase() {

    override fun perform(project: Project?, transfer: SessionTransfer) {
        val sessions = runWithProgress(project, CopilotSessionsBundle.message("progress.readingSessions")) {
            transfer.listSessions()
        } ?: return
        if (sessions.isEmpty()) {
            Messages.showInfoMessage(project, CopilotSessionsBundle.message("dialog.export.empty"), TITLE)
            return
        }

        val dialog = ExportSessionsDialog(project, sessions)
        if (!dialog.showAndGet()) return
        val selected = dialog.selectedSessions()
        if (selected.isEmpty()) return

        val descriptor = FileSaverDescriptor(
            CopilotSessionsBundle.message("dialog.export.title"),
            CopilotSessionsBundle.message("dialog.export.chooser.description"),
            SessionTransfer.ARCHIVE_EXTENSION,
        )
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        val wrapper = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(null as VirtualFile?, "copilot-sessions-$timestamp.zip")
            ?: return
        val target: Path = wrapper.file.toPath()

        val title = CopilotSessionsBundle.message("progress.exportingSessions")
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                val progress = TransferProgress { message, fraction ->
                    indicator.checkCanceled()
                    indicator.text = message
                    indicator.fraction = fraction
                }
                // The chat history entry lives in the IDE, not in `~/.copilot`, and is scoped to the
                // open project: without a project there is nothing to read - see IdeSessionRecord.
                val capture = project?.let { CopilotIdeSessionBridge.capture(it, selected.map { s -> s.id }) }
                    ?: CopilotIdeSessionBridge.CaptureResult(
                        unavailableReason = CopilotSessionsBundle.message("ide.unavailable.noProject"),
                    )
                val outcome = transfer.export(
                    selected.map { it.id },
                    target,
                    progress,
                    capture.records,
                    capture.unavailableReason,
                )
                val details = buildString {
                    append(CopilotSessionsBundle.message("notification.export.written", outcome.sessions, target.toAbsolutePath()))
                    outcome.warnings.forEach { append("<br/>&#9888; $it") }
                    if (outcome.unreadableFiles > 0) {
                        append("<br/>" + CopilotSessionsBundle.message("notification.export.unreadableFiles"))
                    }
                }
                if (outcome.warnings.isEmpty()) {
                    CopilotNotifications.info(
                        project,
                        CopilotSessionsBundle.message("notification.export.success.title"),
                        details,
                    )
                } else {
                    CopilotNotifications.warn(
                        project,
                        CopilotSessionsBundle.message("notification.export.warnings.title"),
                        details,
                    )
                }
            }

            override fun onThrowable(error: Throwable) {
                CopilotNotifications.error(
                    project,
                    CopilotSessionsBundle.message("notification.export.failed.title"),
                    error.message ?: error.javaClass.simpleName,
                )
            }
        })
    }
}
