package dev.hunkreview.pycharm.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.ChangesUtil
import dev.hunkreview.pycharm.session.HunkSessionService

/**
 * Commit/Local Changes tool window context menu: "Review Changelist with
 * Hunk". Reviews only the files in the current selection (a changelist, or
 * a subset of changes within one), via `hunk diff HEAD -- <paths>`.
 */
class ReviewChangelistWithHunkAction : DumbAwareAction() {

    private val log = logger<ReviewChangelistWithHunkAction>()

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val changes = VcsDataKeys.CHANGES.getData(e.dataContext)
        e.presentation.isEnabledAndVisible = e.project != null && !changes.isNullOrEmpty()
        e.presentation.text = "Review Changelist with Hunk"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val changes = VcsDataKeys.CHANGES.getData(e.dataContext) ?: return
        val basePath = project.basePath ?: return

        val paths = changes.toList()
            .mapNotNull { change -> ChangesUtil.getFilePath(change)?.path }
            .map { path -> relativize(path, basePath) }
            .distinct()
        if (paths.isEmpty()) return

        val service = project.getService(HunkSessionService::class.java)

        val onError: (Throwable) -> Unit = { error ->
            log.warn("Failed to start Hunk changelist review", error)
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

        service.startChangelistReview(paths, onReady, onError)
    }

    private fun relativize(path: String, basePath: String): String {
        val normalizedBase = basePath.trimEnd('/')
        return if (path.startsWith("$normalizedBase/")) path.removePrefix("$normalizedBase/") else path
    }
}
