// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.ostrom.cli.BuildInfo
import dev.ostrom.cli.CliDependencies
import dev.ostrom.cli.platform.Environment
import dev.ostrom.cli.platform.OperatingSystem
import dev.ostrom.cli.update.UpdateRequest
import kotlinx.coroutines.runBlocking

/** `ostrom update [--check] [--rollback] [--version V]`: the signed self-update (CLI.md `ostrom update`). */
internal class UpdateCommand(private val dependencies: CliDependencies) : CliktCommand(name = "update") {
    override fun help(context: Context) = "Update ostrom (signed release)"

    val check by option("--check", help = "Only say whether an update exists").flag()
    val rollback by option("--rollback", help = "Go back to the previous version").flag()
    val version by option("--version", help = "Install this version")

    override fun run() {
        val updates = dependencies.selfUpdate
        val code = if (rollback) {
            updates.rollback(dependencies.executable)
        } else {
            val environment = dependencies.environment
            val request = UpdateRequest(
                current = BuildInfo.VERSION,
                target = releaseTarget(environment),
                executable = dependencies.executable,
                releases = releasesUrl(environment),
                version = version,
                checkOnly = check,
            )
            runBlocking { updates.update(request) }
        }
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
