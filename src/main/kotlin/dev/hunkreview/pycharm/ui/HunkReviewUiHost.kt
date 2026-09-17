package dev.hunkreview.pycharm.ui

import com.intellij.openapi.Disposable
import dev.hunkreview.pycharm.model.HunkFileSummary
import dev.hunkreview.pycharm.model.HunkFileDetail
import dev.hunkreview.pycharm.model.HunkNote
import dev.hunkreview.pycharm.model.HunkReview

/**
 * Isolation seam: [dev.hunkreview.pycharm.session.HunkSessionService] and
 * [dev.hunkreview.pycharm.cli.HunkCliInvoker] never reference
 * `com.intellij.collaboration.ui` types - only implementations of this
 * interface (and their supporting builders) do, so a future IDE-version
 * break in collaboration-tools is a contained rewrite of those
 * implementations, not a plugin-wide one. See PLAN.md "Review UI adapter".
 */
interface HunkReviewUiHost : Disposable {
    fun render(review: HunkReview, files: List<HunkFileSummary>)
    fun showFileLoading(path: String)
    fun renderFile(detail: HunkFileDetail)
    fun updateNotes(notes: List<HunkNote>)
    fun onFileSelected(handler: (path: String) -> Unit)
    fun onHunkSelected(handler: (path: String, hunkIndex: Int) -> Unit)
    fun onCommentRequested(handler: (path: String, hunkIndex: Int, line: Int, oldLine: Boolean, summary: String, rationale: String?) -> Unit)
    fun onReplyRequested(handler: (noteId: String, summary: String, rationale: String?) -> Unit)
}
