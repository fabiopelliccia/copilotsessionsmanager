package com.github.fabiopelliccia.copilotsessionsimportexport.ui

import com.github.fabiopelliccia.copilotsessionsimportexport.core.CopilotPaths
import com.github.fabiopelliccia.copilotsessionsimportexport.core.CopilotSessionsBundle
import com.github.fabiopelliccia.copilotsessionsimportexport.core.SessionTransfer
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/**
 * Shared plumbing for the export and import actions.
 *
 * [textKey] and [descriptionKey] name the bundle entries the presentation is built from: the texts
 * declared in `plugin.xml` are only the English fallback shown before this class is loaded.
 */
abstract class CopilotSessionActionBase(
    private val textKey: String,
    private val descriptionKey: String,
) : DumbAwareAction() {

    companion object {
        // Loading either action is the first thing that happens in every flow of this plugin, so
        // this is the earliest - and only - point where the bundle is guaranteed to learn the
        // language of the IDE before a text is asked for.
        init {
            IdeDisplayLanguage.install()
        }

        internal const val TITLE = "Github Copilot sessions"

        private val LOG = Logger.getInstance(CopilotSessionActionBase::class.java)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.text = CopilotSessionsBundle.message(textKey)
        e.presentation.description = CopilotSessionsBundle.message(descriptionKey)
    }

    final override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val home = CopilotPaths.copilotHome()
        val transfer = SessionTransfer(home)
        if (!transfer.isAvailable()) {
            Messages.showErrorDialog(
                project,
                CopilotSessionsBundle.message("dialog.error.noCopilotData", home.toAbsolutePath()),
                TITLE,
            )
            return
        }
        perform(project, transfer)
    }

    protected abstract fun perform(project: Project?, transfer: SessionTransfer)

    /**
     * Runs a short blocking operation (a database or archive read) with a modal progress, so the
     * EDT never touches the file system itself.
     *
     * A cancellation is re-thrown rather than reported: it is control flow, not a failure, and
     * swallowing it would both show the user an error they caused on purpose and break the
     * platform's own cancellation handling.
     */
    protected fun <T> runWithProgress(project: Project?, title: String, action: () -> T): T? =
        try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously<T, Exception>(
                { action() },
                title,
                false,
                project,
            )
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("$title failed", e)
            Messages.showErrorDialog(project, e.message ?: e.javaClass.simpleName, TITLE)
            null
        }
}
