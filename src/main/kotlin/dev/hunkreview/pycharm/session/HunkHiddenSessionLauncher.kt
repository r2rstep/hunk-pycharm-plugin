package dev.hunkreview.pycharm.session

import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import java.io.File
import kotlin.concurrent.thread

/**
 * A non-PTY invocation of `hunk` does not register a session with the daemon
 * (`hunk diff < /dev/null > out` exits 0 with a static dump, no registration
 * - see PLAN.md "Verified ground truth"). A hidden PTY is therefore mandatory
 * here, not an optimization.
 */
class HunkHiddenSessionLauncher {

    fun launch(binaryPath: String, workingDirectory: File, args: List<String>): PtyProcess {
        val command = (listOf(binaryPath) + args).toTypedArray()
        val process = PtyProcessBuilder(command)
            .setDirectory(workingDirectory.absolutePath)
            .setConsole(false)
            .setEnvironment(HashMap(System.getenv()))
            .start()

        // Drain terminal updates so the child cannot block on a full PTY
        // buffer. Review state is read through `session` calls, not output.
        thread(name = "hunk-pty-drain-${process.pid()}", isDaemon = true) {
            process.inputStream.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (input.read(buffer) != -1) {
                    // Intentionally ignored.
                }
            }
        }
        return process
    }
}
