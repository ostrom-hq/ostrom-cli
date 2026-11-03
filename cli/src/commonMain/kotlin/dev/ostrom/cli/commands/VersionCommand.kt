// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import dev.ostrom.cli.BuildInfo
import dev.ostrom.cli.CliDependencies

/** `ostrom version`: the version, the engine and the platform of this binary. */
class VersionCommand(private val dependencies: CliDependencies) : CliktCommand(name = "version") {
    override fun help(context: Context) = "Print the version of ostrom"

    override fun run() = dependencies.console.out(versionLine(dependencies))
}

/** What `ostrom version` and `ostrom --version` print: the version, the engine and the platform of this binary. */
internal fun versionLine(dependencies: CliDependencies): String {
    val environment = dependencies.environment
    val platform = "${environment.operatingSystem.displayName} ${environment.architecture}"
    return "ostrom ${BuildInfo.VERSION} (kotlin-native, $platform)\n"
}
