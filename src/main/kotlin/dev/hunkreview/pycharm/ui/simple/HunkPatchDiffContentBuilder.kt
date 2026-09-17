package dev.hunkreview.pycharm.ui.simple

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.diff.impl.patch.PatchLine
import com.intellij.openapi.diff.impl.patch.PatchHunk
import com.intellij.openapi.diff.impl.patch.PatchReader
import com.intellij.openapi.diff.impl.patch.TextFilePatch
import dev.hunkreview.pycharm.model.HunkFileDetail

/** Converts Hunk's unified patch into the platform's native diff request. */
object HunkPatchDiffContentBuilder {

    fun build(detail: HunkFileDetail, selectedHunkIndex: Int? = null): SimpleDiffRequest? {
        val patchText = detail.patch ?: return null
        val filePatch = runCatching {
            PatchReader(patchText).readTextPatches().firstOrNull()
        }.getOrNull() ?: return null

        val hunks = selectedHunkIndex?.let { index ->
            filePatch.hunks.getOrNull(index)?.let { listOf(it) }
        } ?: filePatch.hunks
        if (selectedHunkIndex != null && hunks.isEmpty()) return null

        val oldText = buildText(hunks, setOf(PatchLine.Type.CONTEXT, PatchLine.Type.REMOVE))
        val newText = buildText(hunks, setOf(PatchLine.Type.CONTEXT, PatchLine.Type.ADD))
        val contentFactory = DiffContentFactory.getInstance()
        val before: DiffContent = if (oldText.isEmpty()) contentFactory.createEmpty() else contentFactory.create(oldText)
        val after: DiffContent = if (newText.isEmpty()) contentFactory.createEmpty() else contentFactory.create(newText)
        return SimpleDiffRequest(detail.path, before, after, "Before", "After")
    }

    private fun buildText(hunks: List<PatchHunk>, include: Set<PatchLine.Type>): String =
        hunks
            .flatMap { it.lines }
            .filter { it.type in include }
            .joinToString("\n") { it.text }
}
