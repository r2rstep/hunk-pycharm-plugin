package dev.hunkreview.pycharm.ui.simple

import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.impl.DiffRequestPanelImpl
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.components.JBScrollPane
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.InlayProperties
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.collaboration.ui.codereview.diff.EditorComponentInlaysManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.ui.treeStructure.Tree
import dev.hunkreview.pycharm.model.HunkFileDetail
import dev.hunkreview.pycharm.model.HunkFileSummary
import dev.hunkreview.pycharm.model.HunkNote
import dev.hunkreview.pycharm.model.HunkReview
import dev.hunkreview.pycharm.model.HUNK_SOURCE_USER
import dev.hunkreview.pycharm.ui.HunkReviewUiHost
import javax.swing.JComponent
import javax.swing.AbstractAction
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.Rectangle
import java.awt.Point
import java.awt.event.MouseEvent
import java.nio.file.Paths
import javax.swing.event.MouseInputAdapter

private val THREAD_CARD_BACKGROUND = JBColor(Color(248, 249, 251), Color(38, 40, 45))
private val THREAD_DIVIDER_COLOR = JBColor(Color(225, 227, 232), Color(55, 58, 64))

/**
 * Phase 1 UI host: a plain Swing tree built only on stable platform APIs.
 * Superseded (behind [HunkReviewUiHost]) by a collaboration-tools-based host
 * in Phase 2 for MR-review visual/behavioral parity.
 */
class SimpleTreeHunkReviewUiHost(private val project: Project) : HunkReviewUiHost {

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
    private val noteInlays = mutableListOf<Inlay<*>>()
    private val noteInlayTargets = mutableMapOf<Inlay<*>, HunkNote>()
    private val noteInlayRenderers = mutableMapOf<Inlay<*>, ThreadRenderer>()
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
        currentNotes = notes
        renderNotes()
    }

    private fun renderNotes() {
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
            if (editor != null && line >= 0 && line < editor.document.lineCount) {
                addNoteInlay(editor, line, thread)
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

    private fun showHunk(index: Int) {
        currentFileDetail?.let { detail ->
            selectedHunkIndex = index
            lineMap = HunkPatchDiffContentBuilder.lineMap(detail, Paths.get(project.basePath ?: return), index)
            diffPanel.setRequest(HunkPatchDiffContentBuilder.build(detail, Paths.get(project.basePath ?: return), index))
            clearDiffBindings()
            installDiffBindings(detail, index)
        }
    }

    private fun requestComment() {
        val detail = currentFileDetail ?: return
        val target = selectedLine ?: return
        val summary = Messages.showInputDialog(component, "Comment summary:", "Add Hunk Comment", null) ?: return
        if (summary.isBlank()) return
        val rationale = Messages.showInputDialog(component, "Rationale (optional):", "Add Hunk Comment", null)
        commentRequestedHandler?.invoke(detail.path, target.hunkIndex, target.line, target.oldLine, summary, rationale)
    }

    private fun installDiffBindings(detail: HunkFileDetail, selectedHunk: Int? = null) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
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
                val mouseListener = object : EditorMouseListener {
                    override fun mouseClicked(event: EditorMouseEvent) {
                        val inlay = event.inlay ?: return
                        val note = noteInlayTargets[inlay] ?: return
                        val renderer = noteInlayRenderers[inlay] ?: return
                        val replyBounds = renderer.replyRowBounds(inlay) ?: return
                        if (replyBounds.contains(event.mouseEvent.point)) {
                            showInlineReply(editor, inlay, note)
                            event.consume()
                        }
                    }
                }
                mouseBindings += editor to mouseListener
                editor.addEditorMouseListener(mouseListener, this)
                if (index == 1 && anchors != null) installCommentGutters(editor, anchors)
            }
            renderNotes()
        }
    }

    private fun installCommentGutters(editor: Editor, anchors: List<HunkPatchDiffContentBuilder.LineAnchor?>) {
        val gutter = (editor as? EditorEx)?.gutterComponentEx ?: return
        val hoverState = HoverState()
        val hoverListener = object : MouseInputAdapter() {
            override fun mouseMoved(event: MouseEvent) {
                hoverState.line = editor.xyToVisualPosition(Point(0, event.y)).line
                gutter.repaint()
            }

            override fun mouseExited(event: MouseEvent) {
                hoverState.line = -1
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
            highlighter.gutterIconRenderer = AddCommentGutterRenderer {
                showInlineComment(editor, line, anchor)
            }.withHoverState(hoverState, line)
            commentHighlighters += editor to highlighter
        }
    }

    private fun showInlineComment(editor: Editor, line: Int, anchor: HunkPatchDiffContentBuilder.LineAnchor) {
        val editorImpl = editor as? EditorImpl ?: return
        val textArea = JBTextArea(3, 60).apply {
            lineWrap = true
            wrapStyleWord = true
            emptyText.text = "Write a review comment"
        }
        val save = javax.swing.JButton("Save comment")
        val cancel = javax.swing.JButton("Cancel")
        val panel = JPanel(BorderLayout(8, 4)).apply {
            border = JBUI.Borders.empty(8)
            add(JBScrollPane(textArea), BorderLayout.CENTER)
            add(JPanel().apply {
                add(cancel)
                add(save)
            }, BorderLayout.SOUTH)
        }
        val manager = EditorComponentInlaysManager(editorImpl)
        commentInlayManagers += manager
        manager.insertAfter(line, panel, -1) { null }
        textArea.requestFocusInWindow()

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
        cancel.addActionListener { close() }
        save.addActionListener { submit() }
        textArea.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "save-comment")
        textArea.actionMap.put("save-comment", object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) = submit()
        })
    }

    private fun addNoteInlay(editor: Editor, line: Int, thread: List<HunkNote>) {
        val offset = editor.document.getLineEndOffset(line)
        val renderer = ThreadRenderer(thread)
        val inlay = editor.inlayModel.addBlockElement(
            offset,
            InlayProperties().showAbove(false).relatesToPrecedingText(true).priority(-1),
            renderer
        )
        if (inlay != null) {
            noteInlays += inlay
            noteInlayTargets[inlay] = thread.first()
            noteInlayRenderers[inlay] = renderer
        }
    }

    private fun showInlineReply(editor: Editor, noteInlay: Inlay<*>, note: HunkNote) {
        val editorImpl = editor as? EditorImpl ?: return
        val textArea = JBTextArea(1, 60).apply {
            lineWrap = true
            wrapStyleWord = true
            emptyText.text = "Reply…"
            border = JBUI.Borders.empty(4, 6)
        }
        val plainBorder = JBUI.Borders.customLine(JBColor(Color(200, 203, 210), Color(80, 84, 92)), 1)
        val focusedBorder = JBUI.Borders.customLine(JBColor(Color(60, 130, 220), Color(90, 150, 235)), 1)
        val inputScroll = JBScrollPane(textArea).apply {
            border = plainBorder
            isOpaque = false
            viewport.isOpaque = false
        }
        textArea.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusGained(e: java.awt.event.FocusEvent?) {
                inputScroll.border = focusedBorder
            }
            override fun focusLost(e: java.awt.event.FocusEvent?) {
                inputScroll.border = plainBorder
            }
        })

        val avatar = object : JComponent() {
            init { preferredSize = java.awt.Dimension(40, 16) }
            override fun paintComponent(g: Graphics) {
                g.color = JBColor(Color(100, 105, 115), Color(190, 195, 205))
                g.fillOval(15, 0, 16, 16)
            }
        }
        val inputRow = JPanel(BorderLayout(0, 0)).apply {
            isOpaque = false
            add(avatar, BorderLayout.WEST)
            add(inputScroll, BorderLayout.CENTER)
        }

        val save = javax.swing.JButton("Reply")
        val cancel = javax.swing.JButton("Cancel")
        val buttonRow = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 4)).apply {
            isOpaque = false
            add(cancel)
            add(save)
        }

        val content = JPanel(BorderLayout(0, 4)).apply {
            isOpaque = true
            background = THREAD_CARD_BACKGROUND
            border = JBUI.Borders.empty(6, 0, 8, 0)
            add(inputRow, BorderLayout.CENTER)
            add(buttonRow, BorderLayout.SOUTH)
        }
        val panel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 8, 8, 8)
            add(content, BorderLayout.CENTER)
        }
        val manager = EditorComponentInlaysManager(editorImpl)
        commentInlayManagers += manager
        val line = editor.document.getLineNumber(noteInlay.offset)
        manager.insertAfter(line, panel, -1) { null }
        textArea.requestFocusInWindow()

        fun close() {
            manager.dispose()
            commentInlayManagers.remove(manager)
        }
        fun submit() {
            val body = textArea.text.trim()
            if (body.isEmpty()) return
            val summary = body.lineSequence().first().take(120)
            replyRequestedHandler?.invoke(note.noteId, summary, body.takeUnless { it == summary })
            close()
        }
        cancel.addActionListener { close() }
        save.addActionListener { submit() }
        textArea.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "save-reply")
        textArea.actionMap.put("save-reply", object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) = submit()
        })
    }

    private fun clearNoteInlays() {
        noteInlays.forEach { it.dispose() }
        noteInlays.clear()
        noteInlayTargets.clear()
        noteInlayRenderers.clear()
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

    private data class SelectedLine(val line: Int, val hunkIndex: Int, val oldLine: Boolean)

    private class AddCommentGutterRenderer(private val onClick: () -> Unit) : GutterIconRenderer() {
        private var icon: javax.swing.Icon = PlusIcon()
        fun withHoverState(state: HoverState, line: Int): AddCommentGutterRenderer {
            icon = PlusIcon(state, line)
            return this
        }
        override fun getIcon(): javax.swing.Icon = icon
        override fun getTooltipText(): String = "Add review comment"
        override fun getClickAction(): AnAction = object : AnAction("Add review comment") {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            override fun actionPerformed(event: AnActionEvent) = onClick()
        }
        override fun equals(other: Any?): Boolean = other is AddCommentGutterRenderer
        override fun hashCode(): Int = AddCommentGutterRenderer::class.java.hashCode()
    }

    private class HoverState(var line: Int = -1)

    private class PlusIcon(private val state: HoverState? = null, private val line: Int = -1) : javax.swing.Icon {
        override fun getIconWidth(): Int = 16
        override fun getIconHeight(): Int = 16
        override fun paintIcon(component: java.awt.Component, g: Graphics, x: Int, y: Int) {
            if (state != null && state.line != line) return
            g.color = JBColor(Color(100, 105, 115), Color(185, 190, 200))
            g.drawLine(x + 4, y + 8, x + 12, y + 8)
            g.drawLine(x + 8, y + 4, x + 8, y + 12)
        }
    }

    private class ThreadRenderer(notes: List<HunkNote>) : EditorCustomElementRenderer {
        private class Row(val label: String, val bodyLines: List<String>, val isReplyPrompt: Boolean = false)

        private val rows: List<Row> = notes.map { note ->
            val isUserNote = note.source.lowercase() == HUNK_SOURCE_USER
            val author = if (isUserNote) {
                note.author ?: System.getProperty("user.name").orEmpty().ifBlank { "You" }
            } else {
                "AI agent"
            }
            Row(author, note.body.split('\n'))
        } + Row("Reply", emptyList(), isReplyPrompt = true)

        private fun Row.heightInLines(): Int = bodyLines.size + 1

        override fun calcWidthInPixels(inlay: Inlay<*>): Int = inlay.editor.contentComponent.width.coerceAtLeast(220)
        override fun calcHeightInPixels(inlay: Inlay<*>): Int {
            val lineHeight = inlay.editor.lineHeight
            val rowsHeight = rows.sumOf { it.heightInLines() * lineHeight }
            val separatorsHeight = (rows.size - 1).coerceAtLeast(0) * SEPARATOR_GAP
            return rowsHeight + separatorsHeight + 24
        }

        /** Bounds of the clickable "Reply" row, in the same coordinate space as [Inlay.getBounds]. */
        fun replyRowBounds(inlay: Inlay<*>): Rectangle? {
            val bounds = inlay.bounds ?: return null
            val lineHeight = inlay.editor.lineHeight
            var top = bounds.y + 3
            for (index in 0 until rows.lastIndex) {
                if (index > 0) top += SEPARATOR_GAP
                top += rows[index].heightInLines() * lineHeight
            }
            if (rows.size > 1) top += SEPARATOR_GAP
            return Rectangle(bounds.x + 8, top, bounds.width - 16, lineHeight)
        }

        override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: com.intellij.openapi.editor.markup.TextAttributes) {
            val lineHeight = inlay.editor.lineHeight
            val x = targetRegion.x + 8
            val y = targetRegion.y + 3
            val width = targetRegion.width - 16
            val height = targetRegion.height - 6
            val bodyX = x + 40

            g.color = THREAD_CARD_BACKGROUND
            g.fillRoundRect(x, y, width, height, 12, 12)
            g.color = JBColor(Color(185, 190, 200), Color(92, 98, 110))
            g.drawRoundRect(x, y, width - 1, height - 1, 12, 12)

            var blockTop = y
            rows.forEachIndexed { index, row ->
                if (index > 0) {
                    g.color = THREAD_DIVIDER_COLOR
                    g.drawLine(x + 12, blockTop - GAP_AFTER_DIVIDER, x + width - 12, blockTop - GAP_AFTER_DIVIDER)
                }
                val headerBaseline = blockTop + lineHeight

                if (row.isReplyPrompt) {
                    g.color = JBColor(Color(55, 110, 190), Color(120, 175, 245))
                    g.drawLine(x + 19, blockTop + 17, x + 27, blockTop + 17)
                    g.drawLine(x + 23, blockTop + 13, x + 23, blockTop + 21)

                    g.font = g.font.deriveFont(Font.PLAIN, g.font.size2D)
                    g.drawString(row.label, bodyX, headerBaseline)
                } else {
                    g.color = JBColor(Color(100, 105, 115), Color(190, 195, 205))
                    g.fillOval(x + 15, blockTop + 9, 16, 16)

                    g.color = JBColor(Color(45, 47, 52), Color(235, 237, 242))
                    g.font = g.font.deriveFont(Font.BOLD, g.font.size2D)
                    g.drawString(row.label, bodyX, headerBaseline)

                    g.font = g.font.deriveFont(Font.PLAIN, g.font.size2D)
                    g.color = JBColor(Color(65, 67, 73), Color(215, 218, 225))
                    row.bodyLines.forEachIndexed { lineIndex, line ->
                        g.drawString(line, bodyX, headerBaseline + lineHeight + lineIndex * lineHeight)
                    }
                }

                blockTop += row.heightInLines() * lineHeight + SEPARATOR_GAP
            }
        }

        companion object {
            private const val GAP_BEFORE_DIVIDER = 8
            private const val GAP_AFTER_DIVIDER = 6
            private const val SEPARATOR_GAP = GAP_BEFORE_DIVIDER + GAP_AFTER_DIVIDER
        }
    }
}
