package com.github.fabiopelliccia.copilotsessionsimportexport.ui

import com.github.fabiopelliccia.copilotsessionsimportexport.core.CopilotSessionsBundle
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware

/**
 * The **Tools | Github Copilot sessions** submenu.
 *
 * It exists only to give the group a translated title. `plugin.xml` can carry one, but that text
 * would be resolved through the IDE bundle mechanism, which follows an installed language pack and
 * nothing else, while every other string of this plugin follows the language of the machine as
 * well - see [CopilotSessionsBundle]. Setting the presentation in [update] keeps the whole plugin
 * on one language instead of two.
 *
 * Both entries only read files and are safe while the IDE is indexing, hence [DumbAware].
 */
class CopilotSessionsActionGroup : DefaultActionGroup(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.text = CopilotSessionsBundle.message("action.group.text")
    }
}
