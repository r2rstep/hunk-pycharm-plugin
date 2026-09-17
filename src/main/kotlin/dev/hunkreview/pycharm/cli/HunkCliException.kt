package dev.hunkreview.pycharm.cli

sealed class HunkCliException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    class BinaryMissing(path: String) :
        HunkCliException("The `hunk` CLI was not found at '$path'. Configure its location in Settings | Tools | Hunk Review.")

    class NotFound(sessionId: String) :
        HunkCliException("No Hunk session found with id '$sessionId'.")

    class DaemonUnreachable(cause: Throwable? = null) :
        HunkCliException("Could not reach the Hunk daemon.", cause)

    class Unexpected(exitCode: Int, stderr: String) :
        HunkCliException("`hunk` exited with code $exitCode: $stderr")
}
