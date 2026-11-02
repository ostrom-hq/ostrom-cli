// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import dev.ostrom.cli.platform.ConsoleImpl
import dev.ostrom.cli.platform.EnvironmentImpl
import kotlin.system.exitProcess

/**
 * Entry point of `ostrom`. `ostrom hook …` is answered before anything else is built: agents call it before every
 * tool, so its start-up time is the CLI's most visible cost. Its help, asked by a person, goes through the tree.
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "hook" && args.getOrNull(1) !in HELP) {
        exitProcess(CliDependencies.hookRunner(ConsoleImpl(), EnvironmentImpl()).run(args.drop(1)))
    }
    exitProcess(OstromCli(CliDependencies.create()).run(args))
}

/** The options that ask for a command's help. */
private val HELP = setOf("--help", "-h")
