package dev.hunkreview.pycharm.ui.simple

import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.impl.DiffRequestPanelImpl
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import dev.hunkreview.pycharm.model.HunkFileDetail
import dev.hunkreview.pycharm.model.HunkFileSummary
import dev.hunkreview.pycharm.model.HunkNote
import dev.hunkreview.pycharm.model.HunkReview
import dev.hunkreview.pycharm.ui.HunkReviewUiHost
import javax.swing.JComponent
import javax.swing.JSplitPane
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * Phase 1 UI host: a plain Swing tree built only on stable platform APIs.
 * Superseded (behind [HunkReviewUiHost]) by a collaboration-tools-based host
 * in Phase 2 for MR-review visual/behavioral parity.
 */
class SimpleTreeHunkReviewUiHost(project: Project) : HunkReviewUiHost {

    private val rootNode = DefaultMutableTreeNode("Hunk Review")
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel)
    private val diffPanel: DiffRequestPanel = DiffRequestPanelImpl(project, null)

    private var fileSelectedHandler: ((String) -> Unit)? = null
    private var hunkSelectedHandler: ((String, Int) -> Unit)? = null
    private var currentFileDetail: HunkFileDetail? = null

    val component: JComponent = JSplitPane(
        JSplitPane.HORIZONTAL_SPLIT,
        JBScrollPane(tree),
        diffPanel.component
    ).apply {
        resizeWeight = 0.32
    }

    init {
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.isRootVisible = true
        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                tree: JTree,
                value: Any?,
                selected: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean
            ) {
                when (val userObject = (value as? DefaultMutableTreeNode)?.userObject) {
                    is HunkFileSummary -> append("${userObject.path}  (+${userObject.additions} -${userObject.deletions})")
                    is HunkTreeItem -> append("Hunk ${userObject.index + 1}")
                    else -> append(userObject?.toString().orEmpty())
                }
            }
        }
        tree.addTreeSelectionListener {
            when (val userObject = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject) {
                is HunkFileSummary -> fileSelectedHandler?.invoke(userObject.path)
                is HunkTreeItem -> {
                    showHunk(userObject.index)
                    hunkSelectedHandler?.invoke(userObject.path, userObject.index)
                }
            }
        }
    }

    override fun render(review: HunkReview, files: List<HunkFileSummary>) {
        rootNode.removeAllChildren()
        rootNode.userObject = review.title ?: review.sessionId
        currentFileDetail = null
        files.forEach { file -> rootNode.add(DefaultMutableTreeNode(file)) }
        treeModel.reload()
    }

    override fun showFileLoading(path: String) {
        currentFileDetail = null
        diffPanel.setRequest(null)
    }

    override fun renderFile(detail: HunkFileDetail) {
        currentFileDetail = detail
        diffPanel.setRequest(HunkPatchDiffContentBuilder.build(detail))
        val fileNode = (0 until rootNode.childCount)
            .map { rootNode.getChildAt(it) as DefaultMutableTreeNode }
            .firstOrNull { (it.userObject as? HunkFileSummary)?.path == detail.path }
            ?: return
        fileNode.removeAllChildren()
        detail.hunks.forEach { hunk ->
            fileNode.add(DefaultMutableTreeNode(HunkTreeItem(detail.path, hunk.index)))
        }
        treeModel.reload(fileNode)
        tree.expandPath(javax.swing.tree.TreePath(fileNode.path))
    }

    override fun updateNotes(notes: List<HunkNote>) {
        // Inline note markers require the real diff viewer planned for Phase 2.
        // Keep the patch viewer read-only until that anchor exists.
    }

    override fun onFileSelected(handler: (path: String) -> Unit) {
        fileSelectedHandler = handler
    }

    override fun onHunkSelected(handler: (path: String, hunkIndex: Int) -> Unit) {
        hunkSelectedHandler = handler
    }

    override fun dispose() {
        fileSelectedHandler = null
        hunkSelectedHandler = null
        diffPanel.dispose()
    }

    private data class HunkTreeItem(val path: String, val index: Int)

    private fun showHunk(index: Int) {
        currentFileDetail?.let { detail ->
            diffPanel.setRequest(HunkPatchDiffContentBuilder.build(detail, index))
        }
    }
}
