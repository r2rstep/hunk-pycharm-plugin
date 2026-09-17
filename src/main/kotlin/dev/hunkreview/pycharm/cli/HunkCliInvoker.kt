package dev.hunkreview.pycharm.cli

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.util.ExecUtil
import dev.hunkreview.pycharm.model.HunkFileDetail
import dev.hunkreview.pycharm.model.HunkReview
import dev.hunkreview.pycharm.model.HunkSessionSummary
import kotlinx.serialization.decodeFromString

/**
 * One-shot `hunk session <sub> <sessionId> --json` calls, separate from the
 * long-lived PTY process that owns the session itself.
 *
 * Error policy (see PLAN.md "CLI bridge & error handling"): every call here
 * throws on a non-zero exit code. Exit code 1 while a session is not yet
 * registered (readiness polling) is expected and must be swallowed by the
 * *caller*, not here - once a sessionId has been pid-confirmed, a non-zero
 * exit is a real failure and should propagate with raw stderr.
 */
class HunkCliInvoker(private val binaryPath: String) {

    fun listSessions(): List<HunkSessionSummary> {
        val output = run("session", "list", "--json")
        return hunkJson.decodeFromString<SessionListResponseDto>(output.stdout).sessions.map { it.toModel() }
    }

    fun review(
        sessionId: String,
        includePatch: Boolean = false,
        includeNotes: Boolean = false
    ): Pair<HunkReview, HunkFileDetail?> {
        val args = buildList {
            add("session"); add("review"); add(sessionId)
            if (includePatch) add("--include-patch")
            if (includeNotes) add("--include-notes")
            add("--json")
        }
        val output = run(*args.toTypedArray())
        val dto = hunkJson.decodeFromString<SessionReviewResponseDto>(output.stdout)
        return dto.review.toModel() to dto.review.selectedFile?.toModel()
    }

    fun navigate(sessionId: String, filePath: String) {
        // Hunk's --new-line target must be inside a diff hunk. Line 1 is not
        // valid for many files, so use the first (CLI 1-based) hunk as the
        // file-selection anchor.
        run("session", "navigate", sessionId, "--file", filePath, "--hunk", "1")
    }

    fun navigateHunk(sessionId: String, filePath: String, hunkIndex: Int) {
        // The JSON model exposes zero-based hunk indexes; the CLI expects
        // positive, one-based hunk numbers.
        run("session", "navigate", sessionId, "--file", filePath, "--hunk", (hunkIndex + 1).toString())
    }

    fun addComment(sessionId: String, filePath: String, line: Int, oldLine: Boolean, summary: String, rationale: String?) {
        buildList {
            add("session"); add("comment"); add("add"); add(sessionId)
            add("--file"); add(filePath)
            add(if (oldLine) "--old-line" else "--new-line"); add(line.toString())
            add("--summary"); add(summary)
            if (!rationale.isNullOrBlank()) {
                add("--rationale"); add(rationale)
            }
        }.let { run(*it.toTypedArray()) }
    }

    fun addReply(sessionId: String, noteId: String, summary: String, rationale: String?) {
        buildList {
            add("session"); add("comment"); add("add"); add(sessionId)
            add("--reply-to"); add(noteId)
            add("--summary"); add(summary)
            if (!rationale.isNullOrBlank()) {
                add("--rationale"); add(rationale)
            }
        }.let { run(*it.toTypedArray()) }
    }

    private fun run(vararg args: String): ProcessOutput {
        val commandLine = GeneralCommandLine(binaryPath, *args)
        val output = ExecUtil.execAndGetOutput(commandLine)
        if (output.exitCode != 0) {
            throw HunkCliException.Unexpected(output.exitCode, output.stderr.ifBlank { output.stdout })
        }
        return output
    }
}
