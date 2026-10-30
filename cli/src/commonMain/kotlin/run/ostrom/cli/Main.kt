package run.ostrom.cli

import run.ostrom.cli.platform.ConsoleImpl
import run.ostrom.cli.platform.EnvironmentImpl
import kotlin.system.exitProcess

/**
 * Entry point of `ostrom`. `ostrom hook …` is answered before anything else is built: agents call it before every
 * tool, so its start-up time is the CLI's most visible cost.
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "hook") {
        exitProcess(CliDependencies.hookRunner(ConsoleImpl(), EnvironmentImpl()).run(args.drop(1)))
    }
    exitProcess(OstromCli(CliDependencies.create()).run(args))
}
