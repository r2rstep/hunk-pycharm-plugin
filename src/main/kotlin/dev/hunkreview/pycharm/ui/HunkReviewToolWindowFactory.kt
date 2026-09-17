package dev.hunkreview.pycharm.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import dev.hunkreview.pycharm.session.HunkSessionService
import dev.hunkreview.pycharm.ui.simple.SimpleTreeHunkReviewUiHost

class HunkReviewToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val uiHost = SimpleTreeHunkReviewUiHost(project)
        val service = project.getService(HunkSessionService::class.java)

        uiHost.onFileSelected { path -> service.selectFile(path) }
        uiHost.onHunkSelected { path, hunkIndex -> service.selectHunk(path, hunkIndex) }

        val content = ContentFactory.getInstance().createContent(uiHost.component, "", false)
        toolWindow.contentManager.addContent(content)

        Disposer.register(toolWindow.disposable, uiHost)
        Disposer.register(toolWindow.disposable, Disposable { service.detachUiHost() })

        service.attachUiHost(uiHost)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
