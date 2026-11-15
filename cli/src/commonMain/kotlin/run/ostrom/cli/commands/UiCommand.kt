// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), cli/ui/Ui.kt (`teamcode ui`); see NOTICE.
package run.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import run.ostrom.cli.BuildInfo
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.ExitCode
import run.ostrom.cli.platform.CaptureResult
import run.ostrom.cli.platform.OperatingSystem
import run.ostrom.cli.platform.restrictToOwner
import run.ostrom.cli.platform.secureRandomBytes
import run.ostrom.cli.ui.BundleDesktopSource
import run.ostrom.cli.ui.DesktopFailure
import run.ostrom.cli.ui.DesktopInstallation
import run.ostrom.cli.ui.DesktopInstaller
import run.ostrom.cli.ui.DesktopLocations
import run.ostrom.cli.ui.DesktopManifestVerifier
import run.ostrom.cli.ui.DesktopPublisher
import run.ostrom.cli.ui.DesktopUi
import run.ostrom.cli.ui.OnlineDesktopSource
import run.ostrom.cli.ui.desktopLaunchCommand
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.Path.Companion.toPath

/**
 * `ostrom ui`: opens Ostrom Desktop, installing it first when it is missing or older than this CLI (TeamCode's
 * `teamcode ui`). The signed `desktop-manifest.json` of this CLI's version is authenticated with the release keys
 * compiled into this binary, the package's length and SHA-256 are checked before anything opens it, and on macOS the
 * Developer ID signature too; no administrator rights.
 */
internal class UiCommand(private val dependencies: CliDependencies) : CliktCommand(name = "ui") {
    override fun help(context: Context) =
        "Open Ostrom Desktop, installing the version of this CLI first when it is missing or older " +
            "(signed manifest, SHA-256 and, on macOS, the Developer ID signature are checked; no administrator rights)."

    val yes by option("--yes", help = "Install or update the desktop app without asking").flag()
    val noLaunch by option("--no-launch", help = "Install or update the desktop app if needed, without opening it")
        .flag()
    val bundle by option(
        "--bundle",
        help = "Install from an offline folder with desktop-manifest.json, desktop-manifest.json.sig and the package",
    )

    @Suppress("SwallowedException") // the refusal is the exit code and the message
    override fun run() {
        try {
            runBlocking { open() }
        } catch (failure: DesktopFailure) {
            throw PrintMessage(failure.text, failure.code.code, printError = true)
        }
    }

    private suspend fun open() {
        val console = dependencies.console
        val environment = dependencies.environment
        val os = environment.operatingSystem
        val files = dependencies.fileSystem
        val locations = DesktopLocations(os, { environment[it] }, files)
        val source = bundle?.let { BundleDesktopSource(it.toPath(), files) }
            ?: OnlineDesktopSource(dependencies.downloads, appReleasesUrl(environment), files)
        val installer = DesktopInstaller(
            os = os,
            target = releaseTarget(environment),
            locations = locations,
            source = source,
            temporary = dependencies.temporaryDirectory.toPath(),
            run = { command, timeout ->
                (dependencies.capture.capture(command, timeout) as? CaptureResult.Exited)?.code ?: -1
            },
            verifier = DesktopManifestVerifier(dependencies.releaseTrust),
            macosTeamId = DesktopPublisher.MACOS_TEAM_ID,
            fs = files,
            random = { Buffer().write(secureRandomBytes(RANDOM_BYTES)).readByteString().hex() },
            restrict = { restrictToOwner(it.toString(), executable = true) },
            report = { console.out("$it\n") },
        )
        val ui = DesktopUi(BuildInfo.VERSION, locations, installer, { console.out("$it\n") }) { question ->
            if (yes) {
                true
            } else {
                console.out("$question [y/N] ")
                val answer = dependencies.readLine()
                    ?: throw DesktopFailure(
                        ExitCode.USAGE,
                        "$question Run ostrom ui --yes to install it without asking.",
                    )
                answer.trim().lowercase() in setOf("y", "yes")
            }
        }
        val app = ui.prepare() ?: return
        if (noLaunch) {
            console.out("Ostrom Desktop ${app.version} is installed: ${app.root}\n")
            return
        }
        launch(os, app)
        console.out("Opened Ostrom Desktop ${app.version}.\n")
    }

    /** macOS opens it through Launch Services; elsewhere the launcher starts detached (output on the null device). */
    private fun launch(os: OperatingSystem, app: DesktopInstallation) {
        val command = desktopLaunchCommand(os, app)
        val started = if (os == OperatingSystem.MACOS) {
            (dependencies.capture.capture(command, OPEN_MILLIS) as? CaptureResult.Exited)?.code == 0
        } else {
            dependencies.capture.detach(command)
        }
        if (!started) throw DesktopFailure(ExitCode.FAILED, "✗ Ostrom Desktop could not be opened (${app.root}).")
    }

    private companion object {
        const val RANDOM_BYTES = 8
        const val OPEN_MILLIS = 15_000
    }
}

/**
 * Where the desktop packages are published: `OSTROM_APP_RELEASES_URL` when set (a mirror, the end-to-end tests), else
 * the GitHub releases of ostrom-app on `OSTROM_GITHUB_WEB_URL` (GitHub Enterprise), else github.com.
 */
internal fun appReleasesUrl(environment: run.ostrom.cli.platform.Environment): String =
    environment["OSTROM_APP_RELEASES_URL"]
        ?: "${(environment["OSTROM_GITHUB_WEB_URL"] ?: "https://github.com").trimEnd('/')}" +
        "/ostrom-hq/ostrom-app/releases"
