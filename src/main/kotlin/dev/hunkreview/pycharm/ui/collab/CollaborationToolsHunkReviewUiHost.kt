package dev.hunkreview.pycharm.ui.collab

import com.intellij.collaboration.ui.codereview.CodeReviewChatItemUIUtil
import com.intellij.collaboration.ui.codereview.comment.CodeReviewCommentUIUtil
import com.intellij.collaboration.ui.codereview.comment.RoundedPanel
import com.intellij.collaboration.ui.codereview.diff.AddCommentGutterIconRenderer
import com.intellij.collaboration.ui.codereview.diff.EditorComponentInlaysManager
import com.intellij.collaboration.ui.codereview.timeline.thread.TimelineThreadCommentsPanel
import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.impl.DiffRequestPanelImpl
import com.intellij.icons.AllIcons
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
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.treeStructure.Tree
import com.intellij.diff.util.DiffUserDataKeysEx
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import dev.hunkreview.pycharm.model.HUNK_SOURCE_USER
import dev.hunkreview.pycharm.model.HunkFileDetail
import dev.hunkreview.pycharm.model.HunkFileSummary
import dev.hunkreview.pycharm.model.HunkNote
import dev.hunkreview.pycharm.model.HunkReview
import dev.hunkreview.pycharm.settings.HunkPluginSettings
import dev.hunkreview.pycharm.ui.HunkReviewUiHost
import dev.hunkreview.pycharm.ui.simple.HunkPatchDiffContentBuilder
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
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
import javax.swing.JCheckBoxMenuItem
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
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
    private var selectedLine: SelectedLine? = null
    private var lineMap: HunkPatchDiffContentBuilder.LineMap? = null
    private var currentNotes: List<HunkNote> = emptyList()
    private val installedEditors = mutableListOf<Editor>()
    private val editorsBySide = mutableMapOf<HunkPatchDiffContentBuilder.Side, Editor>()
    private val selectionBindings = mutableListOf<Pair<Editor, SelectionListener>>()
    private val noteInlayManagers = mutableListOf<EditorComponentInlaysManager>()
    private val replyDrafts = mutableMapOf<String, String>()
    private val replyTextAreas = mutableMapOf<String, JBTextArea>()
    private val mouseBindings = mutableListOf<Pair<Editor, EditorMouseListener>>()
    private val commentHighlighters = mutableListOf<Pair<Editor, RangeHighlighter>>()
    private val commentInlayManagers = mutableListOf<EditorComponentInlaysManager>()
    private val gutterBindings = mutableListOf<Pair<javax.swing.JComponent, MouseInputAdapter>>()

    private var filesListExpandedProportion = 0.32f
    private var filesListCollapsed = false
    private var groupByDirectory = HunkPluginSettings.getInstance().defaultGroupByDirectory
    private var renderedSessionId: String? = null
    private var onlyFilesWithComments = false
    private var currentFiles: List<HunkFileSummary> = emptyList()
    private var commentCountsByFile: Map<String, Int> = emptyMap()
    private var selectedFilePath: String? = null
    private var restoringFileSelection = false
    private val selectedFileLabel = JLabel("Select a file").apply {
        horizontalAlignment = SwingConstants.CENTER
        font = font.deriveFont(Font.BOLD)
        border = JBUI.Borders.empty(6, 10)
    }
    private val treeScrollPane = JBScrollPane(tree)
    private val collapseFilesListButton = JButton(AllIcons.General.ChevronLeft).apply {
        isFocusable = false
        isBorderPainted = false
        isContentAreaFilled = false
        isOpaque = false
        margin = JBUI.emptyInsets()
        // Pin the size instead of letting the L&F's button chrome grow the
        // preferred size past the collapsed strip's width, which was
        // clipping the icon down to a sliver.
        preferredSize = Dimension(24, 24)
        minimumSize = Dimension(24, 24)
        toolTipText = "Collapse file list"
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { toggleFilesListCollapsed() }
    }
    private val groupByButton = JButton(AllIcons.Actions.GroupBy).apply {
        isFocusable = false
        isBorderPainted = false
        isContentAreaFilled = false
        isOpaque = false
        margin = JBUI.emptyInsets()
        preferredSize = Dimension(24, 24)
        minimumSize = Dimension(24, 24)
        toolTipText = "Group By"
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { showGroupByPopup(this) }
    }
    private val filesListPanel = JPanel(BorderLayout()).apply {
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 0)
            add(groupByButton, BorderLayout.WEST)
            add(collapseFilesListButton, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(treeScrollPane, BorderLayout.CENTER)
    }

    // OnePixelSplitter (not JSplitPane) renders a theme-aware 1px divider;
    // the default Swing JSplitPane divider ignores Darcula and stays bright.
    private val splitter = OnePixelSplitter(false, filesListExpandedProportion).apply {
        setHonorComponentsMinimumSize(true)
        firstComponent = filesListPanel
        secondComponent = JPanel(BorderLayout()).apply {
            add(selectedFileLabel, BorderLayout.NORTH)
            add(diffPanel.component, BorderLayout.CENTER)
        }
    }

    val component: JComponent = splitter

    init {
        ReviewDiffNavigation.install(diffPanel, { nextReviewFilePath() }) { path ->
            selectedFilePath = path
            updateSelectedFileLabel(path)
            restoreFileSelection()
            fileSelectedHandler?.invoke(path)
        }
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
                    is HunkFileSummary -> {
                        val displayPath = if (groupByDirectory) userObject.path.substringAfterLast('/') else userObject.path
                        val commentCount = commentCountsByFile[userObject.path] ?: 0
                        if (commentCount > 0) {
                            append("$commentCount  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        }
                        val pathAttributes = if (commentCount > 0) {
                            SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, null)
                        } else {
                            SimpleTextAttributes.REGULAR_ATTRIBUTES
                        }
                        append(displayPath, pathAttributes)
                        append("  (+${userObject.additions} -${userObject.deletions})")
                    }
                    is DirectoryNode -> {
                        icon = AllIcons.Nodes.Folder
                        append(userObject.name)
                    }
                    else -> append(userObject?.toString().orEmpty())
                }
            }
        }
        tree.addTreeSelectionListener {
            if (restoringFileSelection) return@addTreeSelectionListener
            when (val userObject = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject) {
                is HunkFileSummary -> {
                    selectedFilePath = userObject.path
                    updateSelectedFileLabel(userObject.path)
                    fileSelectedHandler?.invoke(userObject.path)
                }
            }
        }
    }

    private fun toggleFilesListCollapsed() {
        filesListCollapsed = !filesListCollapsed
        if (filesListCollapsed) {
            filesListExpandedProportion = splitter.proportion
            treeScrollPane.isVisible = false
            groupByButton.isVisible = false
            // Wide enough for the pinned 24x24 collapse/expand button plus margin.
            filesListPanel.minimumSize = Dimension(30, 0)
            splitter.proportion = 0f
            collapseFilesListButton.icon = AllIcons.General.ChevronRight
            collapseFilesListButton.toolTipText = "Expand file list"
        } else {
            treeScrollPane.isVisible = true
            groupByButton.isVisible = true
            filesListPanel.minimumSize = null
            splitter.proportion = filesListExpandedProportion
            collapseFilesListButton.icon = AllIcons.General.ChevronLeft
            collapseFilesListButton.toolTipText = "Collapse file list"
        }
        splitter.revalidate()
        splitter.repaint()
    }

    private fun showGroupByPopup(invoker: JComponent) {
        val directoryItem = JCheckBoxMenuItem("Directory", groupByDirectory)
        directoryItem.addActionListener {
            groupByDirectory = directoryItem.isSelected
            rebuildFileTree()
        }
        val commentsItem = JCheckBoxMenuItem("Only files with comments", onlyFilesWithComments)
        commentsItem.addActionListener {
            onlyFilesWithComments = commentsItem.isSelected
            rebuildFileTree()
        }
        JPopupMenu().apply {
            add(directoryItem)
            add(commentsItem)
        }.show(invoker, 0, invoker.height)
    }

    override fun render(review: HunkReview, files: List<HunkFileSummary>) {
        if (renderedSessionId != review.sessionId) {
            groupByDirectory = HunkPluginSettings.getInstance().defaultGroupByDirectory
            renderedSessionId = review.sessionId
        }
        rootNode.userObject = review.title ?: review.sessionId
        currentFileDetail = null
        selectedFilePath = null
        updateSelectedFileLabel(null)
        currentNotes = emptyList()
        selectedLine = null
        lineMap = null
        currentFiles = files
        rebuildFileTree()
    }

    private fun rebuildFileTree() {
        rootNode.removeAllChildren()
        val visibleFiles = if (onlyFilesWithComments) {
            currentFiles.filter { (commentCountsByFile[it.path] ?: 0) > 0 }
        } else currentFiles
        if (groupByDirectory) {
            buildDirectoryNodes(visibleFiles).forEach { rootNode.add(it) }
        } else {
            visibleFiles.forEach { file -> rootNode.add(DefaultMutableTreeNode(file)) }
        }
        treeModel.reload()
        if (groupByDirectory) {
            TreeUtil.expandAll(tree)
        }
        restoreFileSelection()
    }

    private fun nextReviewFilePath(): String? {
        val detail = currentFileDetail ?: return null
        if (detail.path != selectedFilePath || detail.hunks.isEmpty()) return null
        val paths = mutableListOf<String>()
        fun collect(node: DefaultMutableTreeNode) {
            val file = node.userObject as? HunkFileSummary
            if (file != null) {
                if (file.hunkCount > 0) paths += file.path
                return
            }
            for (index in 0 until node.childCount) collect(node.getChildAt(index) as DefaultMutableTreeNode)
        }
        collect(rootNode)
        val currentIndex = paths.indexOf(detail.path)
        if (currentIndex < 0) return null
        return paths.getOrNull(currentIndex + 1)
    }

    private fun updateSelectedFileLabel(path: String?) {
        selectedFileLabel.text = path?.substringAfterLast('/') ?: "Select a file"
        selectedFileLabel.toolTipText = path
    }

    private fun restoreFileSelection() {
        val path = selectedFilePath ?: return
        fun findFile(node: DefaultMutableTreeNode): DefaultMutableTreeNode? {
            if ((node.userObject as? HunkFileSummary)?.path == path) return node
            for (index in 0 until node.childCount) {
                findFile(node.getChildAt(index) as DefaultMutableTreeNode)?.let { return it }
            }
            return null
        }
        val fileNode = findFile(rootNode) ?: return
        val treePath = TreePath(fileNode.path)
        if (tree.selectionPath == treePath) return
        restoringFileSelection = true
        try {
            tree.selectionPath = treePath
        } finally {
            restoringFileSelection = false
        }
    }

    /** Nests files under their containing directories, mirroring the platform Commit view's "Group by Directory". */
    private fun buildDirectoryNodes(files: List<HunkFileSummary>): List<DefaultMutableTreeNode> {
        val dirNodes = mutableMapOf<String, DefaultMutableTreeNode>()
        val topLevel = mutableListOf<DefaultMutableTreeNode>()
        files.sortedBy { it.path }.forEach { file ->
            val segments = file.path.split('/')
            var parent: DefaultMutableTreeNode? = null
            var pathSoFar = ""
            for (i in 0 until segments.size - 1) {
                pathSoFar = if (pathSoFar.isEmpty()) segments[i] else "$pathSoFar/${segments[i]}"
                val currentParent = parent
                val node = dirNodes.getOrPut(pathSoFar) {
                    val newNode = DefaultMutableTreeNode(DirectoryNode(segments[i]))
                    if (currentParent == null) topLevel += newNode else currentParent.add(newNode)
                    newNode
                }
                parent = node
            }
            val fileNode = DefaultMutableTreeNode(file)
            if (parent == null) topLevel += fileNode else parent.add(fileNode)
        }
        return topLevel
    }

    override fun showFileLoading(path: String) {
        selectedFilePath = path
        updateSelectedFileLabel(path)
        restoreFileSelection()
        currentFileDetail = null
        currentNotes = emptyList()
        selectedLine = null
        lineMap = null
        clearDiffBindings()
        diffPanel.setRequest(null)
    }

    override fun renderFile(detail: HunkFileDetail) {
        selectedFilePath = detail.path
        updateSelectedFileLabel(detail.path)
        restoreFileSelection()
        currentFileDetail = detail
        selectedLine = null
        lineMap = HunkPatchDiffContentBuilder.lineMap(detail, Paths.get(project.basePath ?: return))
        val request = HunkPatchDiffContentBuilder.build(detail, Paths.get(project.basePath ?: return), project)
        request?.putUserData(DiffUserDataKeysEx.SCROLL_TO_CHANGE, DiffUserDataKeysEx.ScrollToPolicy.FIRST_CHANGE)
        diffPanel.setRequest(request)
        installDiffBindings(request)
    }

    override fun updateNotes(notes: List<HunkNote>) {
        // The session poller calls this every ~2.5s regardless of whether
        // anything changed; without this guard, every poll tick tore down
        // and rebuilt every comment-thread inlay - including whichever
        // reply box the user was actively typing into - stealing focus
        // out from under them mid-keystroke.
        if (notes == currentNotes) return
        currentNotes = notes
        commentCountsByFile = notes.asSequence()
            .filter { it.parentId == null }
            .groupingBy { it.filePath }
            .eachCount()
        rebuildFileTree()
        renderNotes()
    }

    private fun renderNotes() {
        val focusedThreadId = replyTextAreas.entries.firstOrNull { it.value.isFocusOwner }?.key
        val focusedCaret = focusedThreadId?.let { replyTextAreas[it]?.caretPosition }
        // clearNoteInlays()+rebuild below tears down every thread's block
        // inlay in the file, not just the one that changed - which shifts
        // each editor's viewport as inlay heights disappear and reappear.
        // Restore the pre-rebuild offset so replying/commenting doesn't
        // scroll the diff to wherever the last-rebuilt thread lands.
        val scrollOffsets = installedEditors.associateWith { it.scrollingModel.verticalScrollOffset }
        clearNoteInlays()
        val detail = currentFileDetail ?: return
        val notesForFile = currentNotes.filter { it.filePath == detail.path }
        val repliesByParent = notesForFile.filter { it.parentId != null }.groupBy { it.parentId }
        notesForFile.filter { it.parentId == null }.forEach { root ->
            val thread = listOf(root) + repliesByParent[root.noteId].orEmpty()
            val map = lineMap ?: return@forEach
            val newLine = root.newRangeStart != null
            val side = if (newLine) HunkPatchDiffContentBuilder.Side.AFTER else HunkPatchDiffContentBuilder.Side.BEFORE
            val editor = editorsBySide[side]
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
        scrollOffsets.forEach { (editor, offset) -> editor.scrollingModel.scrollVertically(offset) }
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

    private data class SelectedLine(val line: Int, val hunkIndex: Int, val oldLine: Boolean)

    private data class DirectoryNode(val name: String)

    private fun installDiffBindings(request: SimpleDiffRequest?) {
        ApplicationManager.getApplication().invokeLater {
            val editors = EditorFactory.getInstance().allEditors.filter {
                isDescendant(it.component, diffPanel.component)
            }
            installedEditors += editors
            editors.forEach { editor ->
                val side = request?.let { HunkPatchDiffContentBuilder.sideOf(editor, it) } ?: return@forEach
                editorsBySide[side] = editor
                val anchors = if (side == HunkPatchDiffContentBuilder.Side.BEFORE) lineMap?.before else lineMap?.after
                val listener = object : SelectionListener {
                    override fun selectionChanged(event: SelectionEvent) {
                        if (!event.editor.selectionModel.hasSelection()) {
                            selectedLine = null
                            return
                        }
                        val line = event.editor.document.getLineNumber(event.editor.selectionModel.selectionStart)
                        val anchor = anchors?.getOrNull(line) ?: return
                        selectedLine = SelectedLine(anchor.sourceLine, anchor.hunkIndex, side == HunkPatchDiffContentBuilder.Side.BEFORE)
                    }
                }
                selectionBindings += editor to listener
                editor.selectionModel.addSelectionListener(listener, this)
                if (side == HunkPatchDiffContentBuilder.Side.AFTER && anchors != null) installCommentGutters(editor, anchors)
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
        editorsBySide.clear()
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
