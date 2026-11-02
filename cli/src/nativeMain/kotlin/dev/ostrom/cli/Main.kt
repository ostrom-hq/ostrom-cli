// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import dev.ostrom.cli.hooks.HookKind
import dev.ostrom.cli.hooks.runtime.GateTexts
import dev.ostrom.cli.hooks.runtime.HookReplies
import dev.ostrom.cli.platform.ConsoleImpl
import dev.ostrom.cli.platform.EnvironmentImpl
import dev.ostrom.cli.platform.exitOnCrash
import kotlin.system.exitProcess

/**
 * Entry point of `ostrom`. `ostrom hook …` is answered before anything else is built: agents call it before every
 * tool, so its start-up time is the CLI's most visible cost. Its help, asked by a person, goes through the tree.
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "hook" && args.getOrNull(1) !in HELP) {
        val environment = EnvironmentImpl()
        if (args.getOrNull(1) == HookKind.GATE.command) {
            // a crash no catch sees (a stack overflow, an abort) refuses the tool too: any other code lets it run
            val refusal = HookReplies(environment["OSTROM_HOOK_DIALECT"] ?: "claude").deny(GateTexts.GATE_CRASHED)
            exitOnCrash(refusal.exitCode, refusal.stdout, refusal.stderr)
        }
        exitProcess(CliDependencies.hookRunner(ConsoleImpl(), environment).run(args.drop(1)))
    }
    exitProcess(OstromCli(CliDependencies.create()).run(args))
}

/** The options that ask for a command's help. */
private val HELP = setOf("--help", "-h")
