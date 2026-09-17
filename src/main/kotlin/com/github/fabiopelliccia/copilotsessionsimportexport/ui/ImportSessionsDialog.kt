package com.github.fabiopelliccia.copilotsessionsimportexport.ui

import com.github.fabiopelliccia.copilotsessionsimportexport.core.ArchiveManifest
import com.github.fabiopelliccia.copilotsessionsimportexport.core.ConflictPolicy
import com.github.fabiopelliccia.copilotsessionsimportexport.core.CopilotPaths
import com.github.fabiopelliccia.copilotsessionsimportexport.core.CopilotSessionsBundle
import com.github.fabiopelliccia.copilotsessionsimportexport.core.PathMapper
import com.github.fabiopelliccia.copilotsessionsimportexport.core.SessionInfo
import com.github.fabiopelliccia.copilotsessionsimportexport.core.SessionTransfer
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.nio.file.Path
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel

/** Lets the user pick which sessions contained in an archive have to be restored. */
internal class ImportSessionsDialog(
    project: Project?,
    private val archive: Path,
    manifest: ArchiveManifest,
    transfer: SessionTransfer,
) : DialogWrapper(project, true) {

    private val conflictCombo = ComboBox(DefaultComboBoxModel(ConflictPolicy.entries.toTypedArray()))

    private val relocateCheckBox = JBCheckBox(CopilotSessionsBundle.message("dialog.import.relocate.checkbox"))

    private val relocateField = TextFieldWithBrowseButton()

    private val panel = SessionSelectionPanel(
        rows = manifest.sessions.map { session ->
            SessionRow(
                session = session,
                status = CopilotSessionsBundle.message(
                    if (transfer.exists(session.id)) "dialog.import.status.present" else "dialog.import.status.new"
                ),
                selected = !transfer.exists(session.id),
            )
        },
        showStatus = true,
        onSelectionChanged = { isOKActionEnabled = true },
    )

    init {
        title = CopilotSessionsBundle.message("dialog.import.title")
        setOKButtonText(CopilotSessionsBundle.message("dialog.import.okButton"))
        conflictCombo.selectedItem = ConflictPolicy.DUPLICATE

        val projectPath = project?.basePath?.replace('/', java.io.File.separatorChar)
        relocateField.text = projectPath.orEmpty()
        relocateField.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.singleDir()
                .withTitle(CopilotSessionsBundle.message("dialog.import.relocate.chooser.title"))
                .withDescription(CopilotSessionsBundle.message("dialog.import.relocate.chooser.description")),
        )
        // Copilot filters its session list by working directory, so an archive coming from
        // another machine is invisible unless the recorded folder is remapped.
        relocateCheckBox.isSelected = projectPath != null &&
            manifest.sessions.any { PathMapper(it.cwd, projectPath).isEnabled }
        relocateCheckBox.addActionListener { updateRelocationState() }
        updateRelocationState()

        init()
    }

    private fun updateRelocationState() {
        relocateField.isEnabled = relocateCheckBox.isSelected
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply {
        add(
            JBLabel(CopilotSessionsBundle.message("dialog.import.archive", archive.toAbsolutePath()))
                .apply { border = JBUI.Borders.emptyBottom(8) },
            BorderLayout.NORTH,
        )
        add(panel, BorderLayout.CENTER)
        add(createOptionsPanel(), BorderLayout.SOUTH)
    }

    private fun createOptionsPanel(): JComponent = JPanel(GridBagLayout()).apply {
        border = JBUI.Borders.emptyTop(8)
        val gap = JBUI.insets(2, 0, 2, 6)

        add(
            relocateCheckBox,
            GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                anchor = GridBagConstraints.WEST
                insets = gap
            },
        )
        add(
            relocateField,
            GridBagConstraints().apply {
                gridx = 1
                gridy = 0
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(gap.top, 0, gap.bottom, 0)
            },
        )
        add(
            JBLabel(CopilotSessionsBundle.message("dialog.import.relocate.hint"))
                .apply { foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground() },
            GridBagConstraints().apply {
                gridx = 0
                gridy = 1
                gridwidth = 2
                anchor = GridBagConstraints.WEST
                insets = JBUI.insetsBottom(8)
            },
        )
        add(
            JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                add(JBLabel(CopilotSessionsBundle.message("dialog.import.conflict.label")))
                add(conflictCombo)
            },
            GridBagConstraints().apply {
                gridx = 0
                gridy = 2
                gridwidth = 2
                anchor = GridBagConstraints.WEST
                insets = JBUI.insets(0)
            },
        )
    }

    override fun getPreferredFocusedComponent(): JComponent = panel

    override fun doValidate(): ValidationInfo? = when {
        !panel.hasSelection() ->
            ValidationInfo(CopilotSessionsBundle.message("dialog.validation.selectSession"), panel)

        relocateCheckBox.isSelected && relocateField.text.isBlank() ->
            ValidationInfo(CopilotSessionsBundle.message("dialog.validation.selectFolder"), relocateField)

        else -> null
    }

    fun selectedSessions(): List<SessionInfo> = panel.selectedSessions()

    fun conflictPolicy(): ConflictPolicy = conflictCombo.selectedItem as ConflictPolicy

    /** Destination folder for the imported sessions, or `null` to keep the original one. */
    fun relocationTarget(): String? = relocateField.text.trim()
        .takeIf { relocateCheckBox.isSelected && it.isNotEmpty() }
        ?.let { CopilotPaths.normalizeWorkspacePath(it) }

    override fun getDimensionServiceKey(): String = "CopilotSessionsImportExport.ImportDialog"
}
