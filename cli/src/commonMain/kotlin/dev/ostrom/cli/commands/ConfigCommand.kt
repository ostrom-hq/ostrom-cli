package dev.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import dev.ostrom.cli.CliDependencies
import dev.ostrom.cli.ExitCode
import dev.ostrom.cli.doctor.NO_HOME
import dev.ostrom.cli.platform.ConfigKey
import dev.ostrom.cli.platform.ConfigStore
import dev.ostrom.cli.platform.FileWriteException
import kotlin.math.max

/** `ostrom config [list | get K | set K V | unset K]`: settings of this device, never the organisation's policy. */
class ConfigCommand(dependencies: CliDependencies) :
    GroupCommand("config", "Settings of this device", comingSoon = false) {
    init {
        subcommands(
            ConfigList(dependencies),
            ConfigGet(dependencies),
            ConfigSet(dependencies),
            ConfigUnset(dependencies),
        )
    }

    private val list = ConfigList(dependencies)

    override fun runDefault() = list.print()
}

/** Shared lookups of the config subcommands. */
private abstract class ConfigSubcommand(
    name: String,
    protected val dependencies: CliDependencies,
) : CliktCommand(name = name) {
    /** The store, or exit 73 when the device has no home folder to keep it in. */
    protected fun store(): ConfigStore = dependencies.config ?: fail(ExitCode.CANNOT_WRITE, NO_HOME)

    /** The key named [name], or exit 64 with the list of keys. */
    protected fun key(name: String): ConfigKey = ConfigKey.of(name)
        ?: fail(ExitCode.USAGE, "Unknown key \"$name\". Keys: ${ConfigKey.entries.joinToString(", ") { it.key }}.")

    /** Runs [write], turning a write failure into exit 73. */
    protected fun saving(write: () -> Unit) {
        try {
            write()
        } catch (e: FileWriteException) {
            fail(ExitCode.CANNOT_WRITE, "Could not write ${e.path}.")
        }
    }

    protected fun fail(code: ExitCode, message: String): Nothing {
        dependencies.console.err("$message\n")
        throw ProgramResult(code.code)
    }
}

private class ConfigList(dependencies: CliDependencies) : ConfigSubcommand("list", dependencies) {
    override fun help(context: Context) = "Show every key and its value"

    override fun run() = print()

    fun print() {
        val values = store().list()
        val width = ConfigKey.entries.fold(0) { widest, key -> max(widest, key.key.length) }
        val lines = ConfigKey.entries.joinToString("") { key ->
            "${key.key.padEnd(width)}  ${values[key] ?: "(not set)"}\n"
        }
        dependencies.console.out(lines)
    }
}

private class ConfigGet(dependencies: CliDependencies) : ConfigSubcommand("get", dependencies) {
    private val name by argument("key")

    override fun help(context: Context) = "Print the value of a key (exit 1 when it is not set)"

    override fun run() {
        val value = store().get(key(name)) ?: throw ProgramResult(ExitCode.FAILED.code)
        dependencies.console.out("$value\n")
    }
}

private class ConfigSet(dependencies: CliDependencies) : ConfigSubcommand("set", dependencies) {
    private val name by argument("key")
    private val value by argument("value")

    override fun help(context: Context) = "Set a key"

    override fun run() {
        val key = key(name)
        saving { store().set(key, value) }
        dependencies.console.out("✓ ${key.key} = $value\n")
    }
}

private class ConfigUnset(dependencies: CliDependencies) : ConfigSubcommand("unset", dependencies) {
    private val name by argument("key")

    override fun help(context: Context) = "Remove a key"

    override fun run() {
        val key = key(name)
        saving { store().unset(key) }
        dependencies.console.out("✓ ${key.key} unset\n")
    }
}
