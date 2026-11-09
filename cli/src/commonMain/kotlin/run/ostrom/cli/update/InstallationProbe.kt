// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.update

import run.ostrom.cli.platform.CaptureResult
import run.ostrom.cli.platform.ProcessCapture
import okio.Path

/**
 * The hidden first argument that asks a binary whether it is a version and speaks an API:
 * `ostrom --ostrom-installation-probe <version> <apiVersion>` exits 0 only when both hold (TeamCode's
 * `--teamcode-installation-probe`). Handled at the very start of `main`, before anything is built.
 */
const val INSTALLATION_PROBE = "--ostrom-installation-probe"

/** Asks a binary, before `ostrom update` puts it in place, whether it runs here as the version and API expected. */
fun interface InstallationProbe {
    /**
     * @param binary The binary to run.
     * @param version The version it must be.
     * @param apiVersion The API version of the server it must speak.
     * @return True only when it ran and answered yes.
     */
    fun probe(binary: String, version: String, apiVersion: Int): Boolean
}

/** Why `ostrom update` refuses [binary], or null when it answers the probe as [version] speaking [apiVersion]. */
internal fun InstallationProbe.refusal(binary: Path, version: String, apiVersion: Int): String? =
    if (probe(binary.toString(), version, apiVersion)) {
        null
    } else {
        "✗ ostrom $version does not start here, or does not speak the API v$apiVersion of your Ostrom server. " +
            "Nothing was changed."
    }

/**
 * [InstallationProbe] by running the binary: a binary that does not start here (another architecture, a missing
 * library), that is another version, or that does not speak the API, exits otherwise or not at all.
 */
class InstallationProbeImpl(private val capture: ProcessCapture) : InstallationProbe {
    override fun probe(binary: String, version: String, apiVersion: Int): Boolean {
        val ran = capture.capture(listOf(binary, INSTALLATION_PROBE, version, apiVersion.toString()), TIMEOUT_MILLIS)
        return ran is CaptureResult.Exited && ran.code == 0
    }

    private companion object {
        /** A binary that answers starts in milliseconds; a cold start on a loaded machine stays far under this. */
        const val TIMEOUT_MILLIS = 10_000
    }
}

/**
 * The exit code of a probe [arguments] ([INSTALLATION_PROBE] first), or null when the line is not one: 0 when it
 * names exactly [version] and an API version in [supported], 1 otherwise.
 */
fun installationProbeExit(arguments: List<String>, version: String, supported: Set<Int>): Int? {
    if (arguments.firstOrNull() != INSTALLATION_PROBE) return null
    val answers = arguments.size == PROBE_SIZE && arguments[1] == version &&
        arguments[2].toIntOrNull()?.let { it in supported } == true
    return if (answers) 0 else 1
}

private const val PROBE_SIZE = 3
