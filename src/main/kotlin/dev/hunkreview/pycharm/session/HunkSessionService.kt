package dev.hunkreview.pycharm.session

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.pty4j.PtyProcess
import dev.hunkreview.pycharm.cli.HunkCliInvoker
import dev.hunkreview.pycharm.cli.HunkCliLocator
import dev.hunkreview.pycharm.ui.HunkReviewUiHost
import dev.hunkreview.pycharm.model.HUNK_SOURCE_USER
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture

/**
 * Owns the hidden PTY-backed `hunk diff`/`hunk show` process for this
 * project and the pid -> sessionId resolution described in PLAN.md. Killed
 * only on project close (via [dispose]) or an explicit "Stop Review" action -
 * never on tool-window hide/tab-switch, since the session should persist the
 * same way a real terminal-hosted `hunk diff` would.
 */
@Service(Service.Level.PROJECT)
class HunkSessionService(private val project: Project) : Disposable {

    private val log = logger<HunkSessionService>()
    private val launcher = HunkHiddenSessionLauncher()

    private var ptyProcess: PtyProcess? = null
    private var invoker: HunkCliInvoker? = null
    private var uiHost: HunkReviewUiHost? = null
    private val activeSessionId = AtomicReference<String?>(null)
    private val fileSelectionLock = Any()
    private val fileSelectionGeneration = AtomicLong(0)
    private val poller = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "hunk-review-poller").apply { isDaemon = true }
    }
    private var notesPoll: ScheduledFuture<*>? = null

    val sessionId: String?
        get() = activeSessionId.get()

    fun currentInvoker(): HunkCliInvoker? = invoker

    fun attachUiHost(host: HunkReviewUiHost) {
        uiHost = host
        sessionId?.let { id -> ApplicationManager.getApplication().executeOnPooledThread { refreshFileTree(id) } }
    }

    fun detachUiHost() {
        uiHost = null
    }

    fun startWorkingTreeReview(comparisonRef: String, onReady: (String) -> Unit, onError: (Throwable) -> Unit) {
        // Comparing against comparisonRef (rather than diffing only the
        // index) surfaces both staged and unstaged changes in one review.
        startReview(listOf("diff", comparisonRef), onReady, onError)
    }

    /** Phase 4: VCS Log "Review commit with Hunk" - reviews a single commit. */
    fun startCommitReview(revision: String, onReady: (String) -> Unit, onError: (Throwable) -> Unit) {
        startReview(listOf("show", revision), onReady, onError)
    }

    /** Phase 4: VCS Log "Review commit range with Hunk" - reviews the diff between two commits. */
    fun startRangeReview(baseRevision: String, headRevision: String, onReady: (String) -> Unit, onError: (Throwable) -> Unit) {
        startReview(listOf("diff", baseRevision, headRevision), onReady, onError)
    }

    /** Commit panel "Review changelist with Hunk" - reviews only the given (repo-relative) paths. */
    fun startChangelistReview(paths: List<String>, onReady: (String) -> Unit, onError: (Throwable) -> Unit) {
        startReview(listOf("diff", "--") + paths, onReady, onError)
    }

    private fun startReview(args: List<String>, onReady: (String) -> Unit, onError: (Throwable) -> Unit) {
        try {
            stopIfRunning()

            val binaryPath = HunkCliLocator.resolveBinary()
            val cliInvoker = HunkCliInvoker(binaryPath)
            invoker = cliInvoker

            val basePath = project.basePath ?: error("Project has no base path")
            val process = launcher.launch(binaryPath, File(basePath), args)
            ptyProcess = process

            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Starting Hunk review", false) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        val id = pollForSessionId(cliInvoker, process.pid())
                        activeSessionId.set(id)
                        refreshFileTree(id)
                        startNotesPolling(id)
                        onReady(id)
                    } catch (e: Exception) {
                        log.warn("Failed to resolve Hunk session id", e)
                        stopIfRunning()
                        onError(e)
                    }
                }
            })
        } catch (e: Exception) {
            onError(e)
        }
    }

    /** PyCharm -> hunk sync: selecting a file navigates the live session to it. */
    fun selectFile(path: String) {
        val id = sessionId ?: return
        val generation = fileSelectionGeneration.incrementAndGet()
        ApplicationManager.getApplication().invokeLater {
            if (fileSelectionGeneration.get() == generation) {
                uiHost?.showFileLoading(path)
            }
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val cliInvoker = invoker ?: return@executeOnPooledThread
            synchronized(fileSelectionLock) {
                if (fileSelectionGeneration.get() != generation) return@executeOnPooledThread
                try {
                    // Hunk has one mutable selected file. Serialize navigate +
                    // review so two clicks cannot observe each other's state.
                    cliInvoker.navigate(id, path)
                    val (review, detail) = cliInvoker.review(id, includePatch = true, includeNotes = true)
                    if (fileSelectionGeneration.get() != generation || detail?.path != path) {
                        return@executeOnPooledThread
                    }
                    ApplicationManager.getApplication().invokeLater {
                        if (fileSelectionGeneration.get() != generation) return@invokeLater
                        uiHost?.renderFile(detail)
                        uiHost?.updateNotes(review.reviewNotes)
                    }
                } catch (e: Exception) {
                    log.warn("Failed to sync file selection '$path' to Hunk session $id", e)
                }
            }
        }
    }

    /** PyCharm -> hunk sync for a hunk selected in the loaded file detail. */
    fun selectHunk(path: String, hunkIndex: Int) {
        val id = sessionId ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val cliInvoker = invoker ?: return@executeOnPooledThread
            try {
                cliInvoker.navigateHunk(id, path, hunkIndex)
            } catch (e: Exception) {
                log.warn("Failed to sync hunk $hunkIndex in '$path' to Hunk session $id", e)
            }
        }
    }

    fun addComment(path: String, hunkIndex: Int, line: Int, oldLine: Boolean, summary: String, rationale: String?) {
        val id = sessionId ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                invoker?.addComment(id, path, line, oldLine, summary, rationale, source = HUNK_SOURCE_USER)
                refreshNotes(id)
            } catch (e: Exception) {
                log.warn("Failed to add comment to '$path' hunk $hunkIndex", e)
            }
        }
    }

    fun addReply(noteId: String, summary: String, rationale: String?) {
        val id = sessionId ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                invoker?.addReply(id, noteId, summary, rationale, source = HUNK_SOURCE_USER)
                refreshNotes(id)
            } catch (e: Exception) {
                log.warn("Failed to add reply to comment $noteId", e)
            }
        }
    }

    private fun refreshFileTree(id: String) {
        val cliInvoker = invoker ?: return
        try {
            val summary = cliInvoker.listSessions().firstOrNull { it.sessionId == id } ?: return
            val (review, _) = cliInvoker.review(id, includeNotes = true)
            ApplicationManager.getApplication().invokeLater {
                uiHost?.render(review, summary.files)
                uiHost?.updateNotes(review.reviewNotes)
            }
        } catch (e: Exception) {
            log.warn("Failed to refresh Hunk file tree for session $id", e)
        }
    }

    private fun startNotesPolling(id: String) {
        notesPoll?.cancel(false)
        notesPoll = poller.scheduleWithFixedDelay({ refreshNotes(id) }, 2500, 2500, TimeUnit.MILLISECONDS)
    }

    private fun refreshNotes(id: String) {
        try {
            val (review, _) = invoker?.review(id, includeNotes = true) ?: return
            ApplicationManager.getApplication().invokeLater {
                if (activeSessionId.get() == id) uiHost?.updateNotes(review.reviewNotes)
            }
        } catch (e: Exception) {
            log.debug("Failed to poll Hunk review notes for session $id", e)
        }
    }

    /**
     * Readiness signal per PLAN.md: process-alive is not sufficient (a
     * non-PTY-registered process never appears in `session list`), so poll by
     * pid until the daemon registers it. Timeout may need tuning once real
     * cold-daemon-start latency is measured (PLAN.md open item #2).
     */
    private fun pollForSessionId(
        cliInvoker: HunkCliInvoker,
        pid: Long,
        timeoutMs: Long = 15_000,
        intervalMs: Long = 150
    ): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                val launcherPids = processTreePids(pid)
                cliInvoker.listSessions().firstOrNull { it.pid in launcherPids }?.let {
                    log.debug("Resolved Hunk session ${it.sessionId} from process tree $launcherPids")
                    return it.sessionId
                }
            } catch (e: Exception) {
                // Exit code 1 while unregistered is expected here (see
                // PLAN.md CLI error policy) - keep polling rather than
                // failing fast.
                log.trace("Hunk session not registered yet (pid=$pid): ${e.message}")
            }
            Thread.sleep(intervalMs)
        }
        error("Timed out waiting for Hunk session (pid=$pid) to register with the daemon")
    }

    /**
     * The npm-installed `hunk` command is a Node wrapper which starts the
     * native Hunk executable. Hunk reports the native child PID, while pty4j
     * returns the wrapper PID. Include descendants so both installation forms
     * resolve correctly.
     */
    private fun processTreePids(rootPid: Long): Set<Long> {
        val pids = buildSet {
            add(rootPid)
            ProcessHandle.of(rootPid).ifPresent { process ->
                process.descendants().forEach { add(it.pid()) }
            }
        }
        return pids
    }

    fun stopIfRunning() {
        val process = ptyProcess ?: return
        activeSessionId.set(null)
        notesPoll?.cancel(false)
        notesPoll = null
        invoker = null
        ptyProcess = null
        if (process.isAlive) {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        }
    }

    override fun dispose() {
        stopIfRunning()
        poller.shutdownNow()
    }
}
