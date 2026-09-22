package dev.hunkreview.pycharm.ui.collab

import com.intellij.collaboration.ui.codereview.CodeReviewChatItemUIUtil
import com.intellij.collaboration.ui.codereview.comment.CodeReviewCommentUIUtil
import com.intellij.collaboration.ui.codereview.comment.RoundedPanel
import com.intellij.collaboration.ui.codereview.diff.AddCommentGutterIconRenderer
import com.intellij.collaboration.ui.codereview.diff.EditorComponentInlaysManager
import com.intellij.collaboration.ui.codereview.timeline.thread.TimelineThreadCommentsPanel
import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.impl.DiffRequestPanelImpl
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import dev.hunkreview.pycharm.model.HUNK_SOURCE_USER
import dev.hunkreview.pycharm.model.HunkFileDetail
import dev.hunkreview.pycharm.model.HunkFileSummary
import dev.hunkreview.pycharm.model.HunkNote
import dev.hunkreview.pycharm.model.HunkReview
import dev.hunkreview.pycharm.ui.HunkReviewUiHost
import dev.hunkreview.pycharm.ui.simple.HunkPatchDiffContentBuilder
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Point
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.nio.file.Paths
import javax.swing.AbstractAction
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.MouseInputAdapter

private val THREAD_CARD_BACKGROUND = JBColor(Color(248, 249, 251), Color(38, 40, 45))

/**
 * Phase 2+ UI host built on `com.intellij.collaboration.ui` - the same
 * internal module backing the bundled GitHub "Pull Requests" / GitLab
 * "Merge Requests" tool windows - for true visual/behavioral parity with
 * those review UIs (comment bubbles, reply threads, gutter icons). Only this
 * file references collaboration-tools types, per PLAN.md's "Review UI
 * adapter" isolation seam; a future IDE-version break here is a contained
 * rewrite, not a plugin-wide one.
 *
 * The native diff rendering itself ([DiffRequestPanelImpl] + the shared
 * [HunkPatchDiffContentBuilder]) is unchanged from [dev.hunkreview.pycharm.ui.simple.SimpleTreeHunkReviewUiHost] -
 * "collaboration-tools parity" per PLAN.md is about the review chrome
 * (comment threads, gutter affordances), not the diff algorithm/rendering.
 */
class CollaborationToolsHunkReviewUiHost(private val project: Project) : HunkReviewUiHost {

    private val rootNode = DefaultMutableTreeNode("Hunk Review")
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel)
    private val diffPanel: DiffRequestPanel = DiffRequestPanelImpl(project, null)

    private var fileSelectedHandler: ((String) -> Unit)? = null
    private var hunkSelectedHandler: ((String, Int) -> Unit)? = null
    private var commentRequestedHandler: ((String, Int, Int, Boolean, String, String?) -> Unit)? = null
    private var replyRequestedHandler: ((String, String, String?) -> Unit)? = null
    private var currentFileDetail: HunkFileDetail? = null
    private var selectedHunkIndex: Int? = null
    private var selectedLine: SelectedLine? = null
    private var lineMap: HunkPatchDiffContentBuilder.LineMap? = null
    private var currentNotes: List<HunkNote> = emptyList()
    private val installedEditors = mutableListOf<Editor>()
    private val selectionBindings = mutableListOf<Pair<Editor, SelectionListener>>()
    private val noteInlayManagers = mutableListOf<EditorComponentInlaysManager>()
    private val replyDrafts = mutableMapOf<String, String>()
    private val replyTextAreas = mutableMapOf<String, JBTextArea>()
    private val mouseBindings = mutableListOf<Pair<Editor, EditorMouseListener>>()
    private val commentHighlighters = mutableListOf<Pair<Editor, RangeHighlighter>>()
    private val commentInlayManagers = mutableListOf<EditorComponentInlaysManager>()
    private val gutterBindings = mutableListOf<Pair<javax.swing.JComponent, MouseInputAdapter>>()

    val component: JComponent = JSplitPane(
        JSplitPane.HORIZONTAL_SPLIT,
        JBScrollPane(tree),
        JPanel(BorderLayout()).apply {
            add(diffPanel.component, BorderLayout.CENTER)
        }
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
                    selectedHunkIndex = userObject.index
                    hunkSelectedHandler?.invoke(userObject.path, userObject.index)
                }
            }
        }
    }

    override fun render(review: HunkReview, files: List<HunkFileSummary>) {
        rootNode.removeAllChildren()
        rootNode.userObject = review.title ?: review.sessionId
        currentFileDetail = null
        currentNotes = emptyList()
        selectedHunkIndex = null
        selectedLine = null
        lineMap = null
        files.forEach { file -> rootNode.add(DefaultMutableTreeNode(file)) }
        treeModel.reload()
    }

    override fun showFileLoading(path: String) {
        currentFileDetail = null
        currentNotes = emptyList()
        selectedHunkIndex = null
        selectedLine = null
        lineMap = null
        clearDiffBindings()
        diffPanel.setRequest(null)
    }

    override fun renderFile(detail: HunkFileDetail) {
        currentFileDetail = detail
        selectedHunkIndex = null
        selectedLine = null
        lineMap = HunkPatchDiffContentBuilder.lineMap(detail, Paths.get(project.basePath ?: return))
        diffPanel.setRequest(HunkPatchDiffContentBuilder.build(detail, Paths.get(project.basePath ?: return)))
        installDiffBindings(detail)
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
        // The session poller calls this every ~2.5s regardless of whether
        // anything changed; without this guard, every poll tick tore down
        // and rebuilt every comment-thread inlay - including whichever
        // reply box the user was actively typing into - stealing focus
        // out from under them mid-keystroke.
        if (notes == currentNotes) return
        currentNotes = notes
        renderNotes()
    }

    private fun renderNotes() {
        val focusedThreadId = replyTextAreas.entries.firstOrNull { it.value.isFocusOwner }?.key
        val focusedCaret = focusedThreadId?.let { replyTextAreas[it]?.caretPosition }
        clearNoteInlays()
        val detail = currentFileDetail ?: return
        val notesForFile = currentNotes.filter { it.filePath == detail.path }
        val repliesByParent = notesForFile.filter { it.parentId != null }.groupBy { it.parentId }
        notesForFile.filter { it.parentId == null }.forEach { root ->
            val thread = listOf(root) + repliesByParent[root.noteId].orEmpty()
            val map = lineMap ?: return@forEach
            val newLine = root.newRangeStart != null
            val editor = installedEditors.getOrNull(if (newLine) 1 else 0)
                ?: installedEditors.firstOrNull()
            val anchors = if (newLine) map.after else map.before
            val line = anchors.indexOfFirst { anchor ->
                anchor?.let { it.hunkIndex == root.hunkIndex && it.sourceLine == root.newRangeStart } == true
            }.takeIf { it >= 0 }
                ?: anchors.indexOfFirst { it?.hunkIndex == root.hunkIndex }
            if (editor is EditorImpl && line >= 0 && line < editor.document.lineCount) {
                addThreadInlay(editor, line, thread)
            }
        }
        if (focusedThreadId != null) {
            replyTextAreas[focusedThreadId]?.let { area ->
                area.requestFocusInWindow()
                area.caretPosition = (focusedCaret ?: area.text.length).coerceIn(0, area.text.length)
            }
        }
    }

    override fun onFileSelected(handler: (path: String) -> Unit) {
        fileSelectedHandler = handler
    }

    override fun onHunkSelected(handler: (path: String, hunkIndex: Int) -> Unit) {
        hunkSelectedHandler = handler
    }

    override fun onCommentRequested(handler: (path: String, hunkIndex: Int, line: Int, oldLine: Boolean, summary: String, rationale: String?) -> Unit) {
        commentRequestedHandler = handler
    }

    override fun onReplyRequested(handler: (noteId: String, summary: String, rationale: String?) -> Unit) {
        replyRequestedHandler = handler
    }

    override fun dispose() {
        fileSelectedHandler = null
        hunkSelectedHandler = null
        commentRequestedHandler = null
        replyRequestedHandler = null
        clearDiffBindings()
        diffPanel.dispose()
    }

    private data class HunkTreeItem(val path: String, val index: Int)
    private data class SelectedLine(val line: Int, val hunkIndex: Int, val oldLine: Boolean)

    private fun showHunk(index: Int) {
        currentFileDetail?.let { detail ->
            selectedHunkIndex = index
            lineMap = HunkPatchDiffContentBuilder.lineMap(detail, Paths.get(project.basePath ?: return), index)
            diffPanel.setRequest(HunkPatchDiffContentBuilder.build(detail, Paths.get(project.basePath ?: return), index))
            clearDiffBindings()
            installDiffBindings(detail, index)
        }
    }

    private fun installDiffBindings(detail: HunkFileDetail, selectedHunk: Int? = null) {
        ApplicationManager.getApplication().invokeLater {
            val editors = EditorFactory.getInstance().allEditors.filter {
                isDescendant(it.component, diffPanel.component)
            }
            installedEditors += editors
            editors.forEachIndexed { index, editor ->
                val anchors = if (index == 0) lineMap?.before else lineMap?.after
                val listener = object : SelectionListener {
                    override fun selectionChanged(event: SelectionEvent) {
                        if (!event.editor.selectionModel.hasSelection()) {
                            selectedLine = null
                            return
                        }
                        val line = event.editor.document.getLineNumber(event.editor.selectionModel.selectionStart)
                        val anchor = anchors?.getOrNull(line) ?: return
                        selectedLine = SelectedLine(anchor.sourceLine, anchor.hunkIndex, index == 0)
                    }
                }
                selectionBindings += editor to listener
                editor.selectionModel.addSelectionListener(listener, this)
                if (index == 1 && anchors != null) installCommentGutters(editor, anchors)
            }
            renderNotes()
        }
    }

    private fun installCommentGutters(editor: Editor, anchors: List<HunkPatchDiffContentBuilder.LineAnchor?>) {
        val gutter = (editor as? EditorEx)?.gutterComponentEx ?: return
        val renderers = mutableListOf<HunkCommentGutterIconRenderer>()
        val hoverListener = object : MouseInputAdapter() {
            override fun mouseMoved(event: MouseEvent) {
                val hoveredLine = editor.xyToVisualPosition(Point(0, event.y)).line
                renderers.forEach { it.iconVisible = it.line == hoveredLine }
                gutter.repaint()
            }

            override fun mouseExited(event: MouseEvent) {
                renderers.forEach { it.iconVisible = false }
                gutter.repaint()
            }
        }
        gutter.addMouseMotionListener(hoverListener)
        gutterBindings += gutter to hoverListener
        anchors.forEachIndexed { line, anchor ->
            if (anchor == null || line >= editor.document.lineCount) return@forEachIndexed
            val highlighter = editor.markupModel.addLineHighlighter(
                null,
                line,
                HighlighterLayer.ADDITIONAL_SYNTAX
            )
            val renderer = HunkCommentGutterIconRenderer(line) { showInlineComment(editor, line, anchor) }
            highlighter.gutterIconRenderer = renderer
            renderers += renderer
            commentHighlighters += editor to highlighter
        }
    }

    private fun showInlineComment(editor: Editor, line: Int, anchor: HunkPatchDiffContentBuilder.LineAnchor) {
        val editorImpl = editor as? EditorImpl ?: return
        val textArea = JBTextArea(3, 60).apply {
            lineWrap = true
            wrapStyleWord = true
            margin = JBUI.insets(6, 8)
            emptyText.text = "Write a review comment"
        }
        val manager = EditorComponentInlaysManager(editorImpl)
        commentInlayManagers += manager

        fun close() {
            manager.dispose()
            commentInlayManagers.remove(manager)
        }
        fun submit() {
            val body = textArea.text.trim()
            if (body.isEmpty()) return
            val path = currentFileDetail?.path ?: return
            val summary = body.lineSequence().first().take(120)
            commentRequestedHandler?.invoke(path, anchor.hunkIndex, anchor.sourceLine, false, summary, body.takeUnless { it == summary })
            close()
        }

        val postButton = CodeReviewCommentUIUtil.createPostNowButton { submit() }
        val cancelButton = JButton("Cancel").apply { addActionListener { close() } }
        val buttonRow = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 4)).apply {
            isOpaque = false
            add(cancelButton)
            add(postButton)
        }
        val content = JPanel(BorderLayout(0, 4)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4)
            add(JBScrollPane(textArea), BorderLayout.CENTER)
            add(buttonRow, BorderLayout.SOUTH)
        }
        val wrapped = CodeReviewCommentUIUtil.createEditorInlayPanel(content)
        manager.insertAfter(line, wrapped, -1) { null }
        textArea.requestFocusInWindow()
        textArea.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "submit-comment")
        textArea.actionMap.put("submit-comment", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent?) = submit()
        })
    }

    /** Real GH/GitLab-style comment thread: avatar+bubble items via [CodeReviewChatItemUIUtil], folding via [TimelineThreadCommentsPanel]. */
    private fun addThreadInlay(editor: EditorImpl, line: Int, thread: List<HunkNote>) {
        val manager = EditorComponentInlaysManager(editor)
        noteInlayManagers += manager
        val panel = buildThreadPanel(thread)
        manager.insertAfter(line, panel, -1) { null }
    }

    private fun buildThreadPanel(thread: List<HunkNote>): JComponent {
        val listModel = CollectionListModel(thread)
        val commentsPanel = TimelineThreadCommentsPanel(listModel, commentComponentFactory = { note: HunkNote -> buildCommentItem(note) })
        val card = RoundedPanel(BorderLayout(0, 4), 12).apply {
            isOpaque = true
            background = THREAD_CARD_BACKGROUND
            border = JBUI.Borders.empty(6, 0)
            add(commentsPanel, BorderLayout.CENTER)
            add(buildReplyRow(thread.first()), BorderLayout.SOUTH)
        }
        return CodeReviewCommentUIUtil.createEditorInlayPanel(card)
    }

    private fun buildCommentItem(note: HunkNote): JComponent {
        val isUserNote = note.source.lowercase() == HUNK_SOURCE_USER
        val author = if (isUserNote) {
            note.author ?: System.getProperty("user.name").orEmpty().ifBlank { "You" }
        } else {
            "AI agent"
        }
        val body = JBTextArea(note.body).apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            border = null
        }
        val title = JLabel(author).apply { font = font.deriveFont(Font.BOLD) }
        return CodeReviewChatItemUIUtil.build(
            CodeReviewChatItemUIUtil.ComponentType.FULL,
            { size -> AvatarIcon(size) },
            body
        ) { withHeader(title) }
    }

    private fun buildReplyRow(rootNote: HunkNote): JComponent {
        val textArea = JBTextArea(2, 40).apply {
            lineWrap = true
            wrapStyleWord = true
            margin = JBUI.insets(6, 8)
            emptyText.text = "Reply…"
            text = replyDrafts[rootNote.noteId].orEmpty()
        }
        replyTextAreas[rootNote.noteId] = textArea
        textArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = save()
            override fun removeUpdate(event: DocumentEvent) = save()
            override fun changedUpdate(event: DocumentEvent) = save()
            private fun save() {
                replyDrafts[rootNote.noteId] = textArea.text
            }
        })

        fun submit() {
            val body = textArea.text.trim()
            if (body.isEmpty()) return
            val summary = body.lineSequence().first().take(120)
            replyRequestedHandler?.invoke(rootNote.noteId, summary, body.takeUnless { it == summary })
            textArea.text = ""
            replyDrafts.remove(rootNote.noteId)
        }

        val postButton = CodeReviewCommentUIUtil.createPostNowButton { submit() }
        textArea.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "submit-reply")
        textArea.actionMap.put("submit-reply", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent?) = submit()
        })
        val buttonRow = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 2)).apply {
            isOpaque = false
            add(postButton)
        }
        return JPanel(BorderLayout(0, 4)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 12, 10, 12)
            add(JBScrollPane(textArea), BorderLayout.CENTER)
            add(buttonRow, BorderLayout.SOUTH)
        }
    }

    private fun clearNoteInlays() {
        noteInlayManagers.toList().forEach { it.dispose() }
        noteInlayManagers.clear()
        replyTextAreas.clear()
    }

    private fun clearDiffBindings() {
        clearNoteInlays()
        commentInlayManagers.toList().forEach { it.dispose() }
        commentInlayManagers.clear()
        commentHighlighters.forEach { (editor, highlighter) ->
            editor.markupModel.removeHighlighter(highlighter)
        }
        commentHighlighters.clear()
        mouseBindings.forEach { (editor, listener) -> editor.removeEditorMouseListener(listener) }
        mouseBindings.clear()
        gutterBindings.forEach { (gutter, listener) -> gutter.removeMouseMotionListener(listener) }
        gutterBindings.clear()
        selectionBindings.forEach { (editor, listener) ->
            editor.selectionModel.removeSelectionListener(listener)
        }
        selectionBindings.clear()
        installedEditors.clear()
    }

    private fun isDescendant(child: java.awt.Component, parent: java.awt.Container): Boolean {
        var current: java.awt.Component? = child
        while (current != null) {
            if (current === parent) return true
            current = current.parent
        }
        return false
    }

    /** Real platform "add comment" gutter icon/click semantics, matching the GH/GitLab diff gutter exactly. */
    private class HunkCommentGutterIconRenderer(
        override val line: Int,
        private val onClick: () -> Unit
    ) : AddCommentGutterIconRenderer() {
        override fun disposeInlay() {}
        override fun getClickAction(): AnAction = object : AnAction("Add review comment") {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            override fun actionPerformed(event: AnActionEvent) = onClick()
        }
    }

    private class AvatarIcon(private val size: Int) : Icon {
        override fun getIconWidth(): Int = size
        override fun getIconHeight(): Int = size
        override fun paintIcon(component: java.awt.Component, g: Graphics, x: Int, y: Int) {
            g.color = JBColor(Color(100, 105, 115), Color(190, 195, 205))
            g.fillOval(x, y, size, size)
        }
    }
}
