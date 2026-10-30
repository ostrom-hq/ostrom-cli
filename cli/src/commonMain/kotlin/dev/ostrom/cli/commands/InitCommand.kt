// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import dev.ostrom.cli.CliDependencies
import dev.ostrom.cli.ExitCode
import dev.ostrom.cli.launcher.Agent
import dev.ostrom.cli.launcher.list
import dev.ostrom.cli.launcher.objectOf
import dev.ostrom.cli.launcher.text
import dev.ostrom.cli.platform.ConfigKey
import dev.ostrom.cli.platform.ConfigStoreImpl
import dev.ostrom.cli.platform.FileWriteException
import dev.ostrom.cli.setup.ShellHooks
import kotlinx.serialization.json.JsonObject

/**
 * `ostrom init`: sets up this device and the repository it runs in. It links the organisation (kept in
 * `config.toml`), checks that the repository is one of the organisation's and shows the workflows its agents will
 * start with, puts Ostrom's hooks on the agents found on this device (an alias each, [ShellHooks]) and offers the
 * rules the analysis proposed. Nothing is written in the repository. Each change asks first, unless `--yes`; without a
 * terminal and without `--yes`, init says what it would do and changes nothing but the organisation's link.
 */
internal class InitCommand(private val dependencies: CliDependencies) : CliktCommand(name = "init") {
    override fun help(context: Context) =
        "Set up this device and repository: organisation, agents' hooks, starting rules"

    private val org by orgOption()
    private val repo by option("--repo", help = "This repository instead of the current one")
    private val server by serverFlag()
    private val yes by yesFlag()
    private val json by jsonFlag()

    /** What `--json` prints at the end, step by step. */
    private val summary = mutableMapOf<String, Any?>()

    override fun run() {
        dependencies.outsideSessions("init")
        val access = dependencies.orgAccess(server, org, ::choose)
        link(access)
        repository(access)
        hooks()
        rules(access)
        if (json) {
            dependencies.console.out("${objectOf(*summary.toList().toTypedArray())}\n")
        } else {
            say("Nothing is written in this repository. Next: ostrom claude")
        }
    }

    private fun say(line: String) {
        if (!json) dependencies.console.out("$line\n")
    }

    /** True when init may change something: `--yes`, or a yes typed at a terminal (never in `--json`). */
    private fun agreed(question: String): Boolean =
        yes || !json && dependencies.console.isInteractive && dependencies.confirm(question)

    /** One of several organisations, chosen at the terminal by its number or its name. */
    private fun choose(orgs: List<Org>): Org {
        if (json || !dependencies.console.isInteractive) {
            throw stopWith(ExitCode.USAGE, "You are a member of ${orgs.size} organisations: add --org <name>.")
        }
        orgs.forEachIndexed { index, org -> dependencies.console.out("  ${index + 1}. ${org.slug}  ${org.name}\n") }
        val answer = dependencies.ask("Organisation for this device [1-${orgs.size}]: ", "--org")
        return orgs.getOrNull((answer.toIntOrNull() ?: 0) - 1) ?: orgs.firstOrNull { it.slug == answer }
            ?: throw stopWith(ExitCode.USAGE, "No organisation \"$answer\" in the list.")
    }

    private fun link(access: OrgAccess) {
        val file = dependencies.configFile(dependencies.directory())
        try {
            ConfigStoreImpl(dependencies.files, file).set(ConfigKey.ORG, access.org.slug)
        } catch (e: FileWriteException) {
            throw stopWith(ExitCode.CANNOT_WRITE, "Could not write ${e.path}.")
        }
        summary["org"] = objectOf("id" to access.org.id, "slug" to access.org.slug, "role" to access.org.role)
        say("✓ ${access.org.name} (${access.org.slug}) on ${access.signed.serverUrl}, as @${access.signed.login}, " +
            access.org.role)
    }

    /** The repository is the organisation's (77 otherwise), and what its agents start with. */
    private fun repository(access: OrgAccess) {
        val name = repo ?: dependencies.currentRepo()
        summary["repo"] = name
        if (name == null) {
            say("  Not in a git repository: this device is set up. Run ostrom init in one to check it.")
            return
        }
        // GET /api/v1/repos (SRV-LECTURES): when the server lists them, a repository outside them is refused
        val repos = access.readIfServed("/api/v1/repos")?.list("items")?.filterIsInstance<JsonObject>()
        if (repos != null && repos.none { it.text("name") == name || it.text("name")?.endsWith("/$name") == true }) {
            throw stopWith(
                ExitCode.REFUSED,
                "$name is not one of ${access.org.name}'s repositories. Ask an admin to add it (${access.console}).",
            )
        }
        val workflows = access.readIfServed("/api/v1/workflows?repo=${segment(name)}")?.list("items")
            ?.filterIsInstance<JsonObject>()
        summary["workflows"] = workflows?.size
        val harness = when {
            workflows == null -> "not on this server yet"
            workflows.isEmpty() -> "no workflow yet (ostrom harness add <file.json>)"
            else -> workflows.joinToString(" · ") { "${it.text("name")} (${it.text("status")})" }
        }
        say("  Repository  $name${if (repos == null) "" else " · covered"}\n  Harness     $harness")
    }

    /** The agents of this device, and the aliases that start them with Ostrom's hooks. */
    private fun hooks() {
        val found = Agent.entries.filter { dependencies.finder.find(it.executable) != null }
        summary["agents"] = found.map { it.command }
        if (found.isEmpty()) {
            say("! No agent on this device. Install one, e.g. Claude Code: ${Agent.CLAUDE.installUrl}")
            return
        }
        say("  Agents      ${found.joinToString(" · ") { "${it.command} ✓" }}")
        val block = ShellHooks.block(found)
        val home = dependencies.environment.homeDirectory
        val file = home?.let { ShellHooks.startupFile(dependencies.environment["SHELL"], it) }
        val current = file?.let { dependencies.files.read(it) }.orEmpty()
        val names = found.joinToString(" and ") { it.command }
        val installed = file != null && ShellHooks.merged(current, block) == current
        summary["hooks"] = if (installed) file else null
        when {
            installed -> say("✓ Hooks       $names start with Ostrom's hooks (aliases in $file)")
            file == null -> say("  Hooks       add to your shell's startup file:\n${block.trimEnd()}")
            agreed("Start $names with Ostrom's hooks when you type their name (aliases in $file)?") -> {
                write(file, ShellHooks.merged(current, block))
                summary["hooks"] = file
                say("✓ Hooks       $names start with Ostrom's hooks from your next shell ($file)")
            }
            else -> say("  Hooks       not installed: ostrom init --yes adds the aliases to $file")
        }
    }

    private fun write(file: String, text: String) {
        try {
            dependencies.files.write(file, text)
        } catch (e: FileWriteException) {
            throw stopWith(ExitCode.CANNOT_WRITE, "Could not write ${e.path}.")
        }
    }

    /** The rules the analysis proposed, accepted here by an owner or admin who says yes. */
    private fun rules(access: OrgAccess) {
        val proposed = access.readIfServed("$RULES?status=proposed")?.list("items")?.filterIsInstance<JsonObject>()
        summary["proposedRules"] = proposed?.size
        when {
            proposed == null -> say("  Rules       not on this server yet")
            proposed.isEmpty() -> say("  Rules       none proposed yet: the analysis of ${access.org.name} " +
                "proposes them (${access.console}/rules)")
            else -> proposedRules(access, proposed)
        }
    }

    private fun proposedRules(access: OrgAccess, proposed: List<JsonObject>) {
        say("  Rules       ${proposed.size} proposed for ${access.org.name}:")
        proposed.take(SHOWN).forEach { say("    ${ruleLine(it)}") }
        if (proposed.size > SHOWN) say("    … and ${proposed.size - SHOWN} more: ostrom policy rules list")
        val ids = proposed.mapNotNull { it.text("id") }
        when {
            !access.org.admin -> say("  An owner or admin accepts them: ostrom policy rules accept --proposed")
            agreed("Accept these ${proposed.size} rules? Agents follow them from their next session.") -> {
                val count = access.decideRules(ids, "accepted")
                summary["acceptedRules"] = count
                say("✓ $count rules accepted")
            }
            else -> say("  Accept them: ostrom policy rules accept --proposed")
        }
    }

    private companion object {
        /** The proposed rules init lists; the others are counted. */
        const val SHOWN = 5
    }
}
