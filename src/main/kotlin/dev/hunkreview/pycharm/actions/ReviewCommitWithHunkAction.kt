package dev.hunkreview.pycharm.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.vcs.log.VcsLogDataKeys
import dev.hunkreview.pycharm.session.HunkSessionService

/**
 * Phase 4 (PLAN.md): VCS Log "Review commit/range with Hunk". A single
 * selected commit is reviewed with `hunk show <rev>`; multiple selected
 * commits are reviewed as a range with `hunk diff <base> <head>`, where
 * base/head are the oldest/newest of the selection by commit time (VCS Log
 * selection order is display order, not chronological order, so it can't be
 * used directly to tell which end is which).
 */
class ReviewCommitWithHunkAction : DumbAwareAction() {

    private val log = logger<ReviewCommitWithHunkAction>()

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val selectedCount = VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION.getData(e.dataContext)?.commits?.size ?: 0
        e.presentation.isEnabledAndVisible = e.project != null && selectedCount > 0
        e.presentation.text = if (selectedCount > 1) "Review Commit Range with Hunk" else "Review Commit with Hunk"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val selection = VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION.getData(e.dataContext) ?: return
        val service = project.getService(HunkSessionService::class.java)

        val onError: (Throwable) -> Unit = { error ->
            log.warn("Failed to start Hunk commit review", error)
            ApplicationManager.getApplication().invokeLater {
                Messages.showErrorDialog(project, error.message ?: "Unknown error", "Hunk Review")
            }
        }
        val onReady: (String) -> Unit = { sessionId ->
            log.info("Hunk session ready: $sessionId")
            showHunkReviewToolWindow(project)
        }

        // Open the tool window immediately so the context-menu action has
        // visible feedback while Hunk starts and the daemon registers it.
        showHunkReviewToolWindow(project)

        val details = selection.cachedMetadata
        if (details.isEmpty()) return
        if (details.size == 1) {
            service.startCommitReview(details.single().id.asString(), onReady, onError)
        } else {
            val sortedByTime = details.sortedBy { it.commitTime }
            val base = sortedByTime.first().id.asString()
            val head = sortedByTime.last().id.asString()
            service.startRangeReview(base, head, onReady, onError)
        }
    }
}
