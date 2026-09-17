package com.github.fabiopelliccia.copilotsessionsimportexport.ui

import com.github.fabiopelliccia.copilotsessionsimportexport.core.CopilotSessionsBundle
import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ex.ApplicationEx
import com.intellij.openapi.project.Project
import java.nio.file.Path

internal object CopilotNotifications {

    private const val GROUP_ID = "Github Copilot sessions"

    fun info(project: Project?, title: String, content: String, vararg actions: NotificationAction) =
        notify(project, title, content, NotificationType.INFORMATION, actions)

    fun warn(project: Project?, title: String, content: String, vararg actions: NotificationAction) =
        notify(project, title, content, NotificationType.WARNING, actions)

    fun error(project: Project?, title: String, content: String, vararg actions: NotificationAction) =
        notify(project, title, content, NotificationType.ERROR, actions)

    /**
     * The IDE's Copilot chat panel loads the session list once when it connects to the language
     * server and does not notice files that a background task - such as this import - writes to
     * `session-state` afterwards. Restarting is the only reliable way to make it ask again, so the
     * import notification offers it as a one click action instead of telling the user to do it by
     * hand. The label follows the language of the machine, see [CopilotSessionsBundle].
     */
    fun restartAction(
        text: String = CopilotSessionsBundle.message("notification.action.restartIde"),
    ): NotificationAction =
        NotificationAction.createSimpleExpiring(text) {
            (ApplicationManager.getApplication() as ApplicationEx).restart(true)
        }

    /**
     * Opens the folder of the diagnostic import log with the file selected. Shown on every import
     * notification - informative, warning **and** error - because the log is written unconditionally
     * and is most useful exactly when the import did not go as expected.
     */
    fun showLogAction(logFile: Path): NotificationAction =
        NotificationAction.createSimpleExpiring(CopilotSessionsBundle.message("notification.action.showLog")) {
            RevealFileAction.openFile(logFile)
        }

    private fun notify(
        project: Project?,
        title: String,
        content: String,
        type: NotificationType,
        actions: Array<out NotificationAction>,
    ) {
        val notification: Notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(title, content, type)
        actions.forEach { notification.addAction(it) }
        notification.notify(project)
    }
}
