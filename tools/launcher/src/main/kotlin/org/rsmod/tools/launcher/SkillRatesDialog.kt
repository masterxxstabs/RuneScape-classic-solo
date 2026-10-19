package org.rsmod.tools.launcher

import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Frame
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.table.AbstractTableModel

class SkillRatesDialog(owner: Frame, initial: Map<String, SkillRateOverride>) :
    JDialog(owner, "Per-skill rates", true) {
    private val model = SkillRatesTableModel(initial)
    private val table = JTable(model)

    var result: Map<String, SkillRateOverride>? = null
        private set

    init {
        table.fillsViewportHeight = true
        table.rowHeight = 24
        table.surrendersFocusOnKeystroke = true
        table.putClientProperty("terminateEditOnFocusLost", true)

        val hint =
            JLabel(
                "<html>Leave a cell empty to use the global rate. Gather success applies to " +
                    "woodcutting, mining, fishing and thieving; gather yield to woodcutting, " +
                    "mining and fishing.</html>"
            )
        hint.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        val ok = JButton("OK")
        ok.addActionListener { confirm() }
        val clear = JButton("Clear all")
        clear.addActionListener { model.clear() }
        val cancel = JButton("Cancel")
        cancel.addActionListener { dispose() }

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT))
        buttons.add(clear)
        buttons.add(cancel)
        buttons.add(ok)

        contentPane.layout = BorderLayout()
        contentPane.add(hint, BorderLayout.NORTH)
        contentPane.add(JScrollPane(table), BorderLayout.CENTER)
        contentPane.add(buttons, BorderLayout.SOUTH)
        rootPane.defaultButton = ok
        setSize(560, 640)
        setLocationRelativeTo(owner)
    }

    private fun confirm() {
        if (table.isEditing && !table.cellEditor.stopCellEditing()) {
            return
        }
        val overrides = model.overrides()
        val error = validate(overrides)
        if (error != null) {
            JOptionPane.showMessageDialog(this, error, title, JOptionPane.WARNING_MESSAGE)
            return
        }
        result = overrides
        dispose()
    }

    private fun validate(overrides: Map<String, SkillRateOverride>): String? {
        for ((skill, override) in overrides) {
            if (listOfNotNull(override.xp, override.gatherSuccess).any { it <= 0.0 }) {
                return "Rates for $skill must be greater than 0."
            }
            if (override.gatherYield != null && override.gatherYield < 1.0) {
                return "Gather yield for $skill must be at least 1."
            }
        }
        return null
    }
}

private class SkillRatesTableModel(initial: Map<String, SkillRateOverride>) : AbstractTableModel() {
    private val rows =
        ALL_SKILLS.map { skill -> skill to (initial[skill] ?: SkillRateOverride()) }.toMutableList()

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = COLUMNS.size

    override fun getColumnName(column: Int): String = COLUMNS[column]

    override fun getColumnClass(column: Int): Class<*> =
        if (column == SKILL_COLUMN) String::class.java else java.lang.Double::class.java

    override fun isCellEditable(row: Int, column: Int): Boolean {
        val skill = rows[row].first
        return when (column) {
            XP_COLUMN -> true
            SUCCESS_COLUMN -> skill in GATHER_SUCCESS_SKILLS
            YIELD_COLUMN -> skill in GATHER_YIELD_SKILLS
            else -> false
        }
    }

    override fun getValueAt(row: Int, column: Int): Any? {
        val (skill, override) = rows[row]
        return when (column) {
            SKILL_COLUMN -> skill.replaceFirstChar(Char::uppercase)
            XP_COLUMN -> override.xp
            SUCCESS_COLUMN -> override.gatherSuccess
            YIELD_COLUMN -> override.gatherYield
            else -> null
        }
    }

    override fun setValueAt(value: Any?, row: Int, column: Int) {
        val (skill, override) = rows[row]
        val number = (value as? Number)?.toDouble()
        rows[row] =
            skill to
                when (column) {
                    XP_COLUMN -> override.copy(xp = number)
                    SUCCESS_COLUMN -> override.copy(gatherSuccess = number)
                    YIELD_COLUMN -> override.copy(gatherYield = number)
                    else -> override
                }
        fireTableCellUpdated(row, column)
    }

    fun clear() {
        rows.replaceAll { (skill, _) -> skill to SkillRateOverride() }
        fireTableDataChanged()
    }

    fun overrides(): Map<String, SkillRateOverride> =
        rows.filterNot { it.second.isEmpty }.toMap()

    companion object {
        private const val SKILL_COLUMN = 0
        private const val XP_COLUMN = 1
        private const val SUCCESS_COLUMN = 2
        private const val YIELD_COLUMN = 3

        private val COLUMNS = listOf("Skill", "XP rate", "Gather success", "Gather yield")
    }
}
