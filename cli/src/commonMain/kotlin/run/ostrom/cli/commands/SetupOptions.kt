// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.ExitCode
import run.ostrom.cli.launcher.Routes
import run.ostrom.cli.platform.FileWriteException

// What the setup commands (init, invite, members, policy, harness) share: their options, their questions, their stops.

/** `--org`, for the commands that act in an organisation. */
internal fun CliktCommand.orgOption() =
    option("--org", help = "Organisation, by its short name or id (else OSTROM_ORG, else the one of ostrom init)")

/** `--json`. */
internal fun CliktCommand.jsonFlag() = option("--json", help = "Machine-readable output").flag()

/** `--server`. */
internal fun CliktCommand.serverFlag() =
    option("--server", help = "Ostrom server URL (else OSTROM_URL, else the one of ostrom login)")

/** `--yes`. */
internal fun CliktCommand.yesFlag() = option("--yes", help = "Do not ask for confirmation").flag()

/** The message and exit code of a stopped command, on stderr. */
internal fun stopWith(code: ExitCode, message: String) = PrintMessage(message, code.code, printError = true)

/**
 * Asks [question] and reads the answer; without a terminal it stops with 64, telling to add [flag] (`--yes`).
 *
 * @return The answer, trimmed; empty at the end of the input.
 */
internal fun CliDependencies.ask(question: String, flag: String): String {
    if (!console.isInteractive) {
        throw stopWith(ExitCode.USAGE, "ostrom: no terminal to ask \"$question\": add $flag")
    }
    console.out(question)
    return readLine()?.trim().orEmpty()
}

/** True when the developer answered yes to [question] (`[y/N]`: anything else is no). */
internal fun CliDependencies.confirm(question: String): Boolean =
    ask("$question [y/N] ", "--yes").lowercase() in setOf("y", "yes")

/** Runs [write]; a file it could not write stops the command with 73, naming the file. */
internal fun writeOrStop(write: () -> Unit) {
    val failed = try {
        write()
        null
    } catch (e: FileWriteException) {
        e.path
    }
    if (failed != null) throw stopWith(ExitCode.CANNOT_WRITE, "Could not write $failed.")
}

/** A path segment, encoded. */
internal fun segment(value: String) = Routes.encode(value)
