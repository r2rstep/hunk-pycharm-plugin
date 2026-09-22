package dev.hunkreview.pycharm.actions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

private object LoggerHolder
private val log = logger<LoggerHolder>()

/** Shared by every action that starts a Hunk review (working tree, commit, range). */
internal fun showHunkReviewToolWindow(project: Project) {
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
