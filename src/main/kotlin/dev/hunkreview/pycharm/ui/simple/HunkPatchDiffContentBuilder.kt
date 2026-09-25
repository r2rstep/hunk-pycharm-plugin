package dev.hunkreview.pycharm.ui.simple

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.diff.impl.patch.PatchHunk
import com.intellij.openapi.diff.impl.patch.PatchLine
import com.intellij.openapi.diff.impl.patch.PatchReader
import dev.hunkreview.pycharm.model.HunkFileDetail
import java.nio.file.Files
import java.nio.file.Path

/** Builds a native diff from the complete working-tree file and Hunk's patch. */
object HunkPatchDiffContentBuilder {

    enum class Side { BEFORE, AFTER }

    /** EmptyContent has no editor, so editor order does not identify the diff side. */
    fun sideOf(editor: Editor, request: SimpleDiffRequest): Side? = when (
        request.contents.indexOfFirst { (it as? DocumentContent)?.document === editor.document }
    ) {
        0 -> Side.BEFORE
        1 -> Side.AFTER
        else -> null
    }

    data class LineAnchor(val sourceLine: Int, val hunkIndex: Int)

    data class LineMap(val before: List<LineAnchor?>, val after: List<LineAnchor?>)

    private data class ParsedPatch(val hunks: List<PatchHunk>, val afterText: String)

    fun lineMap(detail: HunkFileDetail, basePath: Path, selectedHunkIndex: Int? = null): LineMap? =
        render(detail, basePath, selectedHunkIndex)?.let { LineMap(it.beforeAnchors, it.afterAnchors) }

    fun build(detail: HunkFileDetail, basePath: Path, project: Project, selectedHunkIndex: Int? = null): SimpleDiffRequest? {
        val rendered = render(detail, basePath, selectedHunkIndex) ?: return null
        val contentFactory = DiffContentFactory.getInstance()
        val sourcePath = basePath.resolve(detail.path).normalize()
        val fileSystem = LocalFileSystem.getInstance()
        val sourceFile = fileSystem.findFileByNioFile(sourcePath)
            ?: fileSystem.refreshAndFindFileByNioFile(sourcePath)
        // Keep the before side detached so Go to Source maps its caret through the diff to the working file.
        val before: DiffContent = rendered.beforeText
            .takeUnless(String::isEmpty)
            ?.let(contentFactory::create)
            ?: contentFactory.createEmpty()
        val after: DiffContent = rendered.afterText
            .takeUnless(String::isEmpty)
            ?.let { text ->
                sourceFile?.let { contentFactory.create(project, text, it) } ?: contentFactory.create(text)
            }
            ?: contentFactory.createEmpty()
        return SimpleDiffRequest(detail.path, before, after, "Before", "After")
    }

    private data class RenderedDiff(
        val beforeText: String,
        val afterText: String,
        val beforeAnchors: List<LineAnchor?>,
        val afterAnchors: List<LineAnchor?>
    )

    private fun render(detail: HunkFileDetail, basePath: Path, selectedHunkIndex: Int?): RenderedDiff? {
        val patch = parse(detail.patch) ?: return null
        val file = basePath.resolve(detail.path).normalize()
        if (!file.startsWith(basePath.normalize())) return null
        val afterText = runCatching { Files.readString(file) }.getOrElse { patch.afterText }
        val beforeText = reconstructBefore(afterText, patch.hunks)
        val beforeLines = lines(beforeText)
        val afterLines = lines(afterText)
        return RenderedDiff(
            beforeText,
            afterText,
            anchors(beforeLines.size, patch.hunks, before = true, selectedHunkIndex),
            anchors(afterLines.size, patch.hunks, before = false, selectedHunkIndex)
        )
    }

    private fun parse(patchText: String?): ParsedPatch? {
        if (patchText == null) return null
        val filePatch = runCatching { PatchReader(patchText).readTextPatches().firstOrNull() }.getOrNull() ?: return null
        val afterText = filePatch.hunks.flatMap { hunk ->
            hunk.lines.filter { it.type == PatchLine.Type.CONTEXT || it.type == PatchLine.Type.ADD }.map { it.text }
        }.joinToString("\n")
        return ParsedPatch(filePatch.hunks, afterText)
    }

    private fun reconstructBefore(afterText: String, hunks: List<PatchHunk>): String {
        val afterLines = lines(afterText)
        val beforeLines = mutableListOf<String>()
        var afterCursor = 0
        hunks.forEach { hunk ->
            while (afterCursor < hunk.startLineAfter && afterCursor < afterLines.size) {
                beforeLines += afterLines[afterCursor++]
            }
            hunk.lines.forEach { line ->
                when (line.type) {
                    PatchLine.Type.CONTEXT -> {
                        if (afterCursor < afterLines.size) beforeLines += afterLines[afterCursor++] else beforeLines += line.text
                    }
                    PatchLine.Type.ADD -> if (afterCursor < afterLines.size) afterCursor++
                    PatchLine.Type.REMOVE -> beforeLines += line.text
                }
            }
        }
        while (afterCursor < afterLines.size) beforeLines += afterLines[afterCursor++]
        return beforeLines.joinToString("\n")
    }

    private fun anchors(size: Int, hunks: List<PatchHunk>, before: Boolean, selectedHunkIndex: Int?): List<LineAnchor?> {
        val result = MutableList<LineAnchor?>(size) { null }
        hunks.forEachIndexed { fallbackIndex, hunk ->
            val hunkIndex = fallbackIndex
            if (selectedHunkIndex != null && selectedHunkIndex != hunkIndex) return@forEachIndexed
            var line = if (before) hunk.startLineBefore else hunk.startLineAfter
            hunk.lines.forEach { patchLine ->
                val included = if (before) {
                    patchLine.type == PatchLine.Type.CONTEXT || patchLine.type == PatchLine.Type.REMOVE
                } else {
                    patchLine.type == PatchLine.Type.CONTEXT || patchLine.type == PatchLine.Type.ADD
                }
                if (included && line in result.indices) result[line] = LineAnchor(line + 1, hunkIndex)
                if (before && patchLine.type != PatchLine.Type.ADD || !before && patchLine.type != PatchLine.Type.REMOVE) line++
            }
        }
        return result
    }

    private fun lines(text: String): List<String> = if (text.isEmpty()) emptyList() else text.split('\n')
}
