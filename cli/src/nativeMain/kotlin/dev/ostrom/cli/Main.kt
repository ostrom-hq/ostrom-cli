// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import dev.ostrom.cli.hooks.runtime.GateTexts
import dev.ostrom.cli.platform.ConsoleImpl
import dev.ostrom.cli.platform.EnvironmentImpl
import kotlin.system.exitProcess

/**
 * Entry point of `ostrom`. `ostrom hook …` is answered before anything else is built: agents call it before every
 * tool, so its start-up time is the CLI's most visible cost. Its help, asked by a person, goes through the tree.
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "hook" && args.getOrNull(1) !in HELP) exitProcess(hook(args.drop(1)))
    exitProcess(OstromCli(CliDependencies.create()).run(args))
}

/**
 * `ostrom hook`, whose runner guards the event's reading and answer. What fails before it (the console, the
 * environment) refuses too: an agent runs the tool on any other exit code than 2.
 */
@Suppress("TooGenericExceptionCaught")
private fun hook(arguments: List<String>): Int = try {
    CliDependencies.hookRunner(ConsoleImpl(), EnvironmentImpl()).run(arguments)
} catch (failure: Throwable) { // not coroutine-reachable: main's own guard, before anything else runs
    refused("ostrom hook failed (${failure::class.simpleName}): ${GateTexts.GATE_FAILED}\n")
}

/** Says [reason] on stderr if it can, and refuses: 2. */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
private fun refused(reason: String): Int {
    try {
        ConsoleImpl().err(reason)
    } catch (ignored: Throwable) { // not coroutine-reachable: main's own guard, before anything else runs
        // the exit code says it
    }
    return REFUSED
}

/** The exit code every agent reads as a refusal of the tool. */
private const val REFUSED = 2

/** The options that ask for a command's help. */
private val HELP = setOf("--help", "-h")
