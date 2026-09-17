package com.github.fabiopelliccia.copilotsessionsimportexport.ui

import com.github.fabiopelliccia.copilotsessionsimportexport.core.CopilotSessionsBundle
import com.github.fabiopelliccia.copilotsessionsimportexport.core.SessionInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.RowFilter
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter

internal class SessionRow(
    val session: SessionInfo,
    val status: String = "",
    var selected: Boolean = false,
)

/**
 * Columns of the session table. The model keys its logic on the constant, never on the header text:
 * that text is translated and would otherwise change the behaviour of the table with the language.
 */
internal enum class SessionColumn(private val key: String?, val width: Int) {
    SELECTION(null, 34),
    SESSION("table.column.session", 320),
    FOLDER("table.column.folder", 220),
    REPOSITORY("table.column.repository", 180),
    BRANCH("table.column.branch", 90),
    UPDATED("table.column.updated", 90),
    TURNS("table.column.turns", 90),
    STATUS("table.column.status", 90),
    ID("table.column.id", 240);

    val title: String get() = key?.let { CopilotSessionsBundle.message(it) }.orEmpty()
}

internal class SessionTableModel(
    val rows: List<SessionRow>,
    private val showStatus: Boolean,
) : AbstractTableModel() {

    private val columns: List<SessionColumn> = buildList {
        add(SessionColumn.SELECTION)
        add(SessionColumn.SESSION)
        add(SessionColumn.FOLDER)
        add(SessionColumn.REPOSITORY)
        add(SessionColumn.BRANCH)
        add(SessionColumn.UPDATED)
        add(SessionColumn.TURNS)
        if (showStatus) add(SessionColumn.STATUS)
        add(SessionColumn.ID)
    }

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String = columns[column].title

    fun column(index: Int): SessionColumn = columns[index]

    override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
        0 -> Boolean::class.javaObjectType
        else -> String::class.java
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex == 0

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
        val row = rows[rowIndex]
        val session = row.session
        return when (columns[columnIndex]) {
            SessionColumn.SELECTION -> row.selected
            SessionColumn.SESSION -> session.displayName
            SessionColumn.FOLDER -> session.cwd.orEmpty()
            SessionColumn.REPOSITORY -> session.repository.orEmpty()
            SessionColumn.BRANCH -> session.branch.orEmpty()
            SessionColumn.UPDATED -> (session.updatedAt ?: session.createdAt).orEmpty()
            SessionColumn.TURNS -> session.turnCount.toString()
            SessionColumn.STATUS -> row.status
            SessionColumn.ID -> session.id
        }
    }

    override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
        if (columnIndex == 0) {
            rows[rowIndex].selected = aValue == true
            fireTableRowsUpdated(rowIndex, rowIndex)
        }
    }

    fun setAllSelected(visibleRows: List<Int>, selected: Boolean) {
        visibleRows.forEach { rows[it].selected = selected }
        fireTableDataChanged()
    }

    fun selected(): List<SessionInfo> = rows.filter { it.selected }.map { it.session }
}

/**
 * Reusable table with a checkbox column and a quick filter, shared by the export and
 * the import dialogs.
 */
internal class SessionSelectionPanel(
    rows: List<SessionRow>,
    showStatus: Boolean,
    private val onSelectionChanged: () -> Unit,
) : JPanel(BorderLayout()) {

    private val model = SessionTableModel(rows, showStatus)
    private val table = JBTable(model)
    private val sorter = TableRowSorter(model)
    private val searchField = SearchTextField()
    private val counterLabel = JBLabel()

    init {
        table.rowSorter = sorter
        table.setShowGrid(false)
        table.autoResizeMode = JBTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        table.columnModel.getColumn(0).apply {
            maxWidth = JBUI.scale(SessionColumn.SELECTION.width)
            minWidth = JBUI.scale(SessionColumn.SELECTION.width)
        }
        for (index in 1 until model.columnCount) {
            table.columnModel.getColumn(index).preferredWidth = JBUI.scale(model.column(index).width)
        }
        model.addTableModelListener {
            updateCounter()
            onSelectionChanged()
        }

        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = applyFilter()
        })

        val actions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            add(JButton(CopilotSessionsBundle.message("table.selectAll")).apply {
                addActionListener { this@SessionSelectionPanel.model.setAllSelected(visibleModelRows(), true) }
            })
            add(JButton(CopilotSessionsBundle.message("table.selectNone")).apply {
                addActionListener { this@SessionSelectionPanel.model.setAllSelected(visibleModelRows(), false) }
            })
        }

        val header = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            add(searchField, BorderLayout.CENTER)
            add(actions, BorderLayout.EAST)
            border = JBUI.Borders.emptyBottom(6)
        }

        add(header, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER)
        add(counterLabel.apply { border = JBUI.Borders.emptyTop(6) }, BorderLayout.SOUTH)
        preferredSize = Dimension(JBUI.scale(940), JBUI.scale(460))
        updateCounter()
    }

    private fun visibleModelRows(): List<Int> = (0 until table.rowCount).map { table.convertRowIndexToModel(it) }

    private fun applyFilter() {
        val text = searchField.text.trim()
        val searchableColumns = (1 until model.columnCount).toList().toIntArray()
        sorter.rowFilter =
            if (text.isEmpty()) null
            else RowFilter.regexFilter("(?i)" + Regex.escape(text), *searchableColumns)
    }

    private fun updateCounter() {
        counterLabel.text = CopilotSessionsBundle.message("table.counter", model.selected().size, model.rowCount)
    }

    fun selectedSessions(): List<SessionInfo> = model.selected()

    fun hasSelection(): Boolean = model.rows.any { it.selected }
}
