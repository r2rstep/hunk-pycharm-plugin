package dev.hunkreview.pycharm.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import dev.hunkreview.pycharm.session.HunkSessionService

class StartHunkWorkingTreeReviewAction : AnAction() {

    private val log = logger<StartHunkWorkingTreeReviewAction>()

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val service = project.getService(HunkSessionService::class.java)

        // Open the tool window immediately so the command-palette action has
        // visible feedback while Hunk starts and the daemon registers it.
        showReviewToolWindow(project)

        service.startWorkingTreeReview(
            onReady = { sessionId ->
                log.info("Hunk session ready: $sessionId")
                showReviewToolWindow(project)
            },
            onError = { error ->
                log.warn("Failed to start Hunk review", error)
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, error.message ?: "Unknown error", "Hunk Review")
                }
            }
        )
    }

    private fun showReviewToolWindow(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Hunk Review")
            if (toolWindow == null) {
                log.warn("Hunk Review tool window is not registered")
                return@invokeLater
            }
            toolWindow.show {
                toolWindow.activate(null)
            }
        }
    }
}
