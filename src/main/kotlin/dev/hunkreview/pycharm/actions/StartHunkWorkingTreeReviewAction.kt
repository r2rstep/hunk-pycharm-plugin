package dev.hunkreview.pycharm.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.hunkreview.pycharm.session.HunkSessionService
import dev.hunkreview.pycharm.settings.HunkPluginSettings

class StartHunkWorkingTreeReviewAction : AnAction() {

    private val log = logger<StartHunkWorkingTreeReviewAction>()

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val settings = HunkPluginSettings.getInstance()

        val comparisonRef = Messages.showInputDialog(
            project,
            "Branch, commit, or range to compare the working tree against:",
            "Start Hunk Review",
            null,
            settings.lastComparisonRef,
            null
        ) ?: return
        if (comparisonRef.isBlank()) return
        settings.lastComparisonRef = comparisonRef

        val service = project.getService(HunkSessionService::class.java)

        // Open the tool window immediately so the command-palette action has
        // visible feedback while Hunk starts and the daemon registers it.
        showHunkReviewToolWindow(project)

        service.startWorkingTreeReview(
            comparisonRef = comparisonRef,
            onReady = { sessionId ->
                log.info("Hunk session ready: $sessionId")
                showHunkReviewToolWindow(project)
            },
            onError = { error ->
                log.warn("Failed to start Hunk review", error)
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, error.message ?: "Unknown error", "Hunk Review")
                }
            }
        )
    }
}
