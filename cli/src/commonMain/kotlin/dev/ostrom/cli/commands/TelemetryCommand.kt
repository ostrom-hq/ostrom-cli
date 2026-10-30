// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import dev.ostrom.cli.CliDependencies
import dev.ostrom.cli.ExitCode
import dev.ostrom.cli.doctor.NO_HOME
import dev.ostrom.cli.platform.ConfigStore
import dev.ostrom.cli.platform.FileWriteException
import dev.ostrom.cli.telemetry.UsageState

/** `ostrom telemetry [status | enable | disable]`: anonymous usage statistics, off unless turned on. */
class TelemetryCommand(dependencies: CliDependencies) :
    GroupCommand("telemetry", "Anonymous usage statistics (off unless you turn them on)", comingSoon = false) {
    init {
        subcommands(TelemetryStatus(dependencies), TelemetryEnable(dependencies), TelemetryDisable(dependencies))
    }

    private val status = TelemetryStatus(dependencies)

    override fun runDefault() = status.print()
}

private class TelemetryStatus(private val dependencies: CliDependencies) : CliktCommand(name = "status") {
    override fun help(context: Context) = "Say whether statistics are sent, and what they contain"

    override fun run() = print()

    fun print() {
        dependencies.console.out(
            """
            |Usage statistics: ${dependencies.usage.state().summary}
            |When on, each command sends its name, whether it succeeded, a duration range, the OS, the architecture
            |and the version, with a random id of this install. Never arguments, paths, repositories, code or your
            |account. OSTROM_TELEMETRY=0 or DO_NOT_TRACK=1 turn them off whatever the setting.
            |""".trimMargin(),
        )
    }
}

/** Shared by enable and disable: the store, and a write failure as exit 73. */
private abstract class TelemetryChange(name: String, protected val dependencies: CliDependencies) :
    CliktCommand(name = name) {
    protected fun change(write: (ConfigStore) -> Unit) {
        val store = dependencies.config ?: fail(NO_HOME)
        try {
            write(store)
        } catch (e: FileWriteException) {
            fail("Could not write ${e.path}.")
        }
    }

    private fun fail(message: String): Nothing {
        dependencies.console.err("$message\n")
        throw ProgramResult(ExitCode.CANNOT_WRITE.code)
    }
}

private class TelemetryEnable(dependencies: CliDependencies) : TelemetryChange("enable", dependencies) {
    override fun help(context: Context) = "Send anonymous usage statistics"

    override fun run() {
        val usage = dependencies.usage
        if (usage.state() == UsageState.NOT_IN_THIS_BUILD) {
            // Nothing is written: no setting and no install id for a destination this build does not have.
            dependencies.console.out(NO_DESTINATION)
            return
        }
        change { usage.enable(it) }
        val state = usage.state()
        val message = if (state == UsageState.ON) {
            "✓ Usage statistics turned on, thank you. ostrom telemetry disable stops them."
        } else {
            "Usage statistics are set to on, but nothing is sent: right now they are ${state.summary}."
        }
        dependencies.console.out("$message\n")
    }
}

private class TelemetryDisable(dependencies: CliDependencies) : TelemetryChange("disable", dependencies) {
    override fun help(context: Context) = "Stop usage statistics and delete the install id"

    override fun run() {
        change { dependencies.usage.disable(it) }
        dependencies.console.out("✓ Usage statistics turned off; the install id is deleted.\n")
    }
}

private const val NO_DESTINATION = "This build of ostrom has no statistics destination: there is nothing to turn on.\n"
