package dev.hunkreview.pycharm.cli

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.diagnostic.logger
import dev.hunkreview.pycharm.settings.HunkPluginSettings
import java.io.File

object HunkCliLocator {

    private val log = logger<HunkCliLocator>()

    // Minimum version confirmed (Sept 2026) to have the full `session` family
    // this plugin depends on - see PLAN.md "Verified ground truth". hunk is
    // pre-1.0, so re-check the changelog before raising this.
    val MINIMUM_VERSION = HunkVersion(0, 22, 0)

    fun resolveBinary(): String {
        val configured = HunkPluginSettings.getInstance().cliPath
        if (!configured.isNullOrBlank()) {
            val file = File(configured)
            if (file.canExecute()) return file.absolutePath
            throw HunkCliException.BinaryMissing(configured)
        }

        val fromPath = PathEnvironmentVariableUtil.findInPath("hunk")
            ?: throw HunkCliException.BinaryMissing("hunk (not found on PATH)")
        return fromPath.absolutePath
    }

    fun checkVersion(binaryPath: String): HunkVersion {
        val commandLine = GeneralCommandLine(binaryPath, "--version")
        val output = try {
            ExecUtil.execAndGetOutput(commandLine)
        } catch (e: Exception) {
            throw HunkCliException.DaemonUnreachable(e)
        }
        if (output.exitCode != 0) {
            throw HunkCliException.Unexpected(output.exitCode, output.stderr)
        }
        val version = HunkVersion.parse(output.stdout)
            ?: throw HunkCliException.Unexpected(output.exitCode, "Could not parse version from: ${output.stdout}")
        if (version < MINIMUM_VERSION) {
            log.warn("hunk CLI version $version is older than the minimum tested version $MINIMUM_VERSION")
        }
        return version
    }
}

data class HunkVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<HunkVersion> {

    override fun compareTo(other: HunkVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""(\d+)\.(\d+)\.(\d+)""")

        fun parse(raw: String): HunkVersion? {
            val match = PATTERN.find(raw) ?: return null
            val (major, minor, patch) = match.destructured
            return HunkVersion(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}
