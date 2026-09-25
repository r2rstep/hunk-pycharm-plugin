package dev.hunkreview.pycharm.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

class HunkReviewConfigurable : Configurable {

    private var cliPathField: JBTextField? = null
    private var filesListViewCombo: JComboBox<String>? = null

    override fun getDisplayName(): String = "Hunk Review"

    override fun createComponent(): JComponent {
        val field = JBTextField()
        cliPathField = field
        val viewCombo = JComboBox(arrayOf("Flat", "Grouped by directory"))
        filesListViewCombo = viewCombo
        return JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(12)
            add(JPanel(GridBagLayout()).apply {
                val constraints = GridBagConstraints().apply {
                    gridx = 0
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                    anchor = GridBagConstraints.NORTHWEST
                }
                add(JBLabel("Hunk CLI executable:"), constraints.apply { gridy = 0 })
                add(field, constraints.apply { gridy = 1 })
                add(JBLabel("Leave blank to search PATH and common user bin directories."), constraints.apply { gridy = 2 })
                add(JBLabel("Default files list view:"), constraints.apply {
                    gridy = 3
                    insets = JBUI.insetsTop(12)
                })
                add(viewCombo, constraints.apply {
                    gridy = 4
                    insets = JBUI.emptyInsets()
                })
            }, BorderLayout.NORTH)
        }
    }

    override fun isModified(): Boolean =
        cliPathField?.text?.trim().orEmpty() != HunkPluginSettings.getInstance().cliPath.orEmpty() ||
            (filesListViewCombo?.selectedIndex == 1) != HunkPluginSettings.getInstance().defaultGroupByDirectory

    override fun apply() {
        HunkPluginSettings.getInstance().cliPath = cliPathField?.text?.trim()?.takeIf { it.isNotEmpty() }
        HunkPluginSettings.getInstance().defaultGroupByDirectory = filesListViewCombo?.selectedIndex == 1
    }

    override fun reset() {
        cliPathField?.text = HunkPluginSettings.getInstance().cliPath.orEmpty()
        filesListViewCombo?.selectedIndex = if (HunkPluginSettings.getInstance().defaultGroupByDirectory) 1 else 0
    }

    override fun disposeUIResources() {
        cliPathField = null
        filesListViewCombo = null
    }
}
