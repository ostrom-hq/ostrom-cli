// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import run.ostrom.cli.BuildInfo
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.update.UpdateRequest

/**
 * `ostrom install [--version V] [--bundle DIR] [--root DIR] [--no-path]`: a verified release installed for this user
 * with an immutable launcher and one folder per version (TeamCode's `install`, without its service).
 */
internal class InstallCommand(private val dependencies: CliDependencies) : CliktCommand(name = "install") {
    override fun help(context: Context) =
        "Install a signed release for this user: an immutable launcher, one folder per version, switched atomically"

    val version by option("--version", help = "Install this version (the latest by default)")
    val bundle by option(
        "--bundle",
        help = "Install offline from a folder holding latest.json, latest.json.sig and the release archive",
    )
    val root by option("--root", help = "The installation's folder (absolute), instead of this user's default one")
    val noPath by option(
        "--no-path",
        help = "Do not make ostrom reachable by name (~/.local/bin/ostrom, or the user PATH on Windows)",
    ).flag()

    override fun run() {
        val environment = dependencies.environment
        val request = UpdateRequest(
            current = BuildInfo.VERSION,
            target = releaseTarget(environment),
            executable = dependencies.executable,
            releases = releasesUrl(environment),
            version = version,
            bundle = bundle,
            root = root,
            noPath = noPath,
        )
        val code = runBlocking { dependencies.selfUpdate.install(request) { serverApiVersion(dependencies) } }
        throw ProgramResult(code)
    }
}
