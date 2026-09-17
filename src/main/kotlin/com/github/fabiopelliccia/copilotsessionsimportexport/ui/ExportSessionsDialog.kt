package com.github.fabiopelliccia.copilotsessionsimportexport.ui

import com.github.fabiopelliccia.copilotsessionsimportexport.core.CopilotSessionsBundle
import com.github.fabiopelliccia.copilotsessionsimportexport.core.SessionInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** Lets the user pick which local Copilot sessions have to be written to an archive. */
internal class ExportSessionsDialog(
    project: Project?,
    sessions: List<SessionInfo>,
) : DialogWrapper(project, true) {

    private val panel = SessionSelectionPanel(
        rows = sessions.map { SessionRow(it) },
        showStatus = false,
        onSelectionChanged = { isOKActionEnabled = true },
    )

    init {
        title = CopilotSessionsBundle.message("dialog.export.title")
        setOKButtonText(CopilotSessionsBundle.message("dialog.export.okButton"))
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply {
        add(
            JBLabel(CopilotSessionsBundle.message("dialog.export.hint"))
                .apply { border = JBUI.Borders.emptyBottom(8) },
            BorderLayout.NORTH,
        )
        add(panel, BorderLayout.CENTER)
    }

    override fun getPreferredFocusedComponent(): JComponent = panel

    override fun doValidate(): ValidationInfo? =
        if (panel.hasSelection()) null
        else ValidationInfo(CopilotSessionsBundle.message("dialog.validation.selectSession"), panel)

    fun selectedSessions(): List<SessionInfo> = panel.selectedSessions()

    override fun getDimensionServiceKey(): String = "CopilotSessionsImportExport.ExportDialog"
}
