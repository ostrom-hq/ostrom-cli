// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import run.ostrom.cli.BuildInfo
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.ExitCode
import run.ostrom.cli.platform.Environment
import run.ostrom.cli.platform.OperatingSystem
import run.ostrom.cli.update.UpdateRequest
import kotlinx.coroutines.runBlocking

/** `ostrom update [--check] [--rollback] [--version V]`: the signed self-update (CLI.md `ostrom update`). */
internal class UpdateCommand(private val dependencies: CliDependencies) : CliktCommand(name = "update") {
    override fun help(context: Context) = "Update ostrom (signed release)"

    val check by option("--check", help = "Only say whether an update exists").flag()
    val rollback by option("--rollback", help = "Go back to the previous version").flag()
    val version by option("--version", help = "Install this version")

    // plumbing of the update notice, started detached once a day at most: records the latest release, says nothing
    val recordLatest by option("--record-latest", hidden = true).flag()

    override fun run() {
        if (recordLatest && listOf(rollback, check, version != null).any { it }) {
            throw UsageError("--record-latest is the update notice's own refresh: it takes no other option")
        }
        if (rollback && (check || version != null)) {
            throw UsageError("--rollback goes back to the version kept: it takes neither --check nor --version")
        }
        val updates = dependencies.selfUpdate
        val environment = dependencies.environment
        val request = UpdateRequest(
            current = BuildInfo.VERSION,
            target = releaseTarget(environment),
            executable = dependencies.executable,
            releases = releasesUrl(environment),
            version = version,
            checkOnly = check,
        )
        if (recordLatest) {
            // without a CLI folder there is nothing to record: the release is not even asked for
            val notice = dependencies.updateNotice ?: throw ProgramResult(ExitCode.OK.code)
            notice.record(runBlocking { updates.latest(request) })
            throw ProgramResult(ExitCode.OK.code)
        }
        val code = runBlocking { if (rollback) updates.rollback(request) else updates.update(request) }
        throw ProgramResult(code)
    }
}

/** The release target of this binary, as the release names its archives (`linux-x64`, `macos-arm64`…). */
internal fun releaseTarget(environment: Environment): String {
    val system = when (environment.operatingSystem) {
        OperatingSystem.MACOS -> "macos"
        OperatingSystem.LINUX -> "linux"
        OperatingSystem.WINDOWS -> "windows"
    }
    return "$system-${environment.architecture}"
}

/**
 * Where releases are published: `OSTROM_RELEASES_URL` when set (a mirror, the end-to-end tests), else the GitHub
 * releases of ostrom-cli on `OSTROM_GITHUB_WEB_URL` (GitHub Enterprise), else github.com.
 */
internal fun releasesUrl(environment: Environment): String = environment["OSTROM_RELEASES_URL"]
    ?: "${(environment["OSTROM_GITHUB_WEB_URL"] ?: "https://github.com").trimEnd('/')}/ostrom-hq/ostrom-cli/releases"
