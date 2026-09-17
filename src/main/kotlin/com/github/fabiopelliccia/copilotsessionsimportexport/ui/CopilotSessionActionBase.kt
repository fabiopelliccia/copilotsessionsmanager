package com.github.fabiopelliccia.copilotsessionsimportexport.ui

import com.github.fabiopelliccia.copilotsessionsimportexport.core.CopilotPaths
import com.github.fabiopelliccia.copilotsessionsimportexport.core.CopilotSessionsBundle
import com.github.fabiopelliccia.copilotsessionsimportexport.core.SessionTransfer
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/** Shared plumbing for the export and import actions. */
abstract class CopilotSessionActionBase : AnAction(), DumbAware {

    companion object {
        // Loading either action is the first thing that happens in every flow of this plugin, so
        // this is the earliest - and only - point where the bundle is guaranteed to learn the
        // language of the IDE before a text is asked for.
        init {
            IdeDisplayLanguage.install()
        }

        internal const val TITLE = "Github Copilot sessions"
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

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

    /** Runs a short blocking operation (database read) with a modal progress. */
    protected fun <T> runWithProgress(project: Project?, title: String, action: () -> T): T? =
        try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously<T, Exception>(
                { action() },
                title,
                false,
                project,
            )
        } catch (e: Exception) {
            Messages.showErrorDialog(project, e.message ?: e.javaClass.simpleName, TITLE)
            null
        }
}
