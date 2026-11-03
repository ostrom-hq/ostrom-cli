// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.parameters.options.option
import dev.ostrom.cli.CliDependencies
import dev.ostrom.cli.ExitCode
import dev.ostrom.cli.launcher.Agent
import dev.ostrom.cli.launcher.jsonOf
import dev.ostrom.cli.launcher.list
import dev.ostrom.cli.launcher.objectOf
import dev.ostrom.cli.launcher.text
import dev.ostrom.cli.platform.ConfigKey
import dev.ostrom.cli.platform.ConfigStoreImpl
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

    /** Why the server refused a read init makes, by what was read: a token made on the console may lack a scope. */
    private val unreadable = mutableMapOf<String, String>()

    override fun run() {
        dependencies.outsideSessions("init")
        val access = dependencies.orgAccess(server, org, ::choose)
        link(access)
        repository(access)
        hooks()
        rules(access)
        summary["unreadable"] = unreadable.keys.toList().ifEmpty { null }
        if (json) {
            val done = JsonObject(summary.filterValues { it != null }.mapValues { jsonOf(it.value) })
            dependencies.console.data("$done\n")
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
        writeOrStop { ConfigStoreImpl(dependencies.files, file).set(ConfigKey.ORG, access.org.slug) }
        summary["org"] = objectOf("id" to access.org.id, "slug" to access.org.slug, "role" to access.org.role)
        say("✓ ${access.org.name} (${access.org.slug}) on ${access.signed.serverUrl}, as @${access.signed.login}, " +
            access.org.role)
    }

    /** The repository, whether the organisation covers it, and what its agents start with. */
    private fun repository(access: OrgAccess) {
        val name = repo ?: dependencies.currentRepo()
        summary["repo"] = name
        if (name == null) {
            say("  Not in a git repository: this device is set up. Run ostrom init in one to check it.")
            return
        }
        // GET /api/v1/repos (SRV-LECTURES), when served. A clone in a folder named otherwise is not found by its name:
        // a warning, never a refusal, so that init still sets the device up.
        // Beyond the first page (100), a repository not found is not known to be missing: no warning.
        val covered = access.readIfAllowed("/api/v1/repos?limit=$REPOS_PAGE", "repos", unreadable)
            ?.let { coverage(it, name) }
        summary["covered"] = covered
        val workflows = access.readIfAllowed("/api/v1/workflows?repo=${segment(name)}", "workflows", unreadable)
            ?.let { page -> page.list("items").filterIsInstance<JsonObject>() }
        summary["workflows"] = workflows?.size
        val harness = when {
            "workflows" in unreadable -> cannotRead(access, unreadable.getValue("workflows"))
            workflows == null -> "not on this server yet"
            workflows.isEmpty() -> "no workflow yet (ostrom harness add <file.json>)"
            else -> workflows.joinToString(" · ") { "${it.text("name")} (${it.text("status")})" }
        }
        say("  Repository  $name${if (covered == true) " · covered" else ""}\n  Harness     $harness")
        if (covered == false) {
            say("! $name is not among ${access.org.name}'s repositories on the server: ask an admin to add it " +
                "(${access.console}).")
        }
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
        val environment = dependencies.environment
        val file = environment.homeDirectory?.let { home ->
            ShellHooks.startupFile(environment["SHELL"], home, environment.operatingSystem, environment["ZDOTDIR"])
        }
        val current = file?.let { dependencies.files.read(it) }.orEmpty()
        val next = file?.let { ShellHooks.merged(current, block) }
        val names = found.joinToString(" and ") { it.command }
        summary["hooks"] = if (next != null && next == current) file else null
        val lines = block.trimEnd()
        when {
            file == null ->
                say("  Hooks       start them as ostrom <agent>, or add to your shell's startup file:\n$lines")
            next == null -> say("! Hooks       $file has Ostrom's markers, but not as init writes them: fix them, " +
                "or add these lines yourself:\n$lines")
            next == current -> say("✓ Hooks       $names start with Ostrom's hooks (aliases in $file)")
            agreed("Start $names with Ostrom's hooks when you type their name (aliases in $file)?") -> {
                writeOrStop { dependencies.files.write(file, next) }
                summary["hooks"] = file
                say("✓ Hooks       $names typed in a new terminal start with Ostrom's hooks ($file)")
            }
            else -> say("  Hooks       not installed: ostrom init --yes adds the aliases to $file")
        }
    }

    /** The rules the analysis proposed, accepted here by an owner or admin who says yes. */
    private fun rules(access: OrgAccess) {
        val page = access.readIfAllowed("$RULES?status=proposed", "rules", unreadable)
        if ("rules" in unreadable) {
            say("  Rules       ${cannotRead(access, unreadable.getValue("rules"))}")
            return
        }
        if (page == null) {
            say("  Rules       not on this server yet")
            return
        }
        val proposed = page.list("items").filterIsInstance<JsonObject>()
        summary["proposedRules"] = proposed.size
        if (proposed.isEmpty()) {
            say("  Rules       none proposed yet: the analysis of ${access.org.name} proposes them " +
                "(${access.console}/rules)")
        } else {
            proposedRules(access, proposed, !page.text("next").isNullOrEmpty())
        }
    }

    private fun proposedRules(access: OrgAccess, proposed: List<JsonObject>, more: Boolean) {
        val size = if (more) "${proposed.size}+" else "${proposed.size}"
        say("  Rules       $size proposed for ${access.org.name}:")
        proposed.take(SHOWN).forEach { say("    ${ruleLine(it)}") }
        if (proposed.size > SHOWN || more) say("    … and more: ostrom policy rules list --status proposed")
        when {
            !access.org.admin -> say("  An owner or admin accepts them: ostrom policy rules accept --proposed")
            // the token of ostrom login never holds an organisation's write scopes (S2-04): no question it cannot do
            !access.consoleToken -> say("  Accept them on the console (${access.console}/rules), or with " +
                "OSTROM_TOKEN set: ostrom policy rules accept --proposed")
            agreed("Accept these $size rules? Agents follow them from their next session.") -> {
                val count = access.decideProposed("accepted")
                summary["acceptedRules"] = count
                say("✓ $count rules accepted")
            }
            else -> say("  Accept them: ostrom policy rules accept --proposed")
        }
    }

    private companion object {
        /** The proposed rules init lists; the others are counted. */
        const val SHOWN = 5

        /** The repositories init reads to find this one: the largest page the contract allows. */
        const val REPOS_PAGE = 100
    }
}

/**
 * True when the page of repositories lists [name] (by itself or as `owner/name`), false when it does not and is the
 * last page, null when more pages follow: not found there, it is not known to be missing. A bare name listed never
 * covers an `owner/name`: a clone of another owner's repository of the same name is not this organisation's.
 */
private fun coverage(page: JsonObject, name: String): Boolean? {
    val found = page.list("items").filterIsInstance<JsonObject>().mapNotNull { it.text("name") }
        .any { it == name || it.endsWith("/$name") }
    return found.takeIf { it || page.text("next").isNullOrEmpty() }
}

/**
 * `GET` [path] when served, or null; a refusal (403) is noted in [unreadable] under [what] rather than failing init,
 * whose link and hooks are already done: a token made on the console may not hold every read scope.
 */
private fun OrgAccess.readIfAllowed(path: String, what: String, unreadable: MutableMap<String, String>): JsonObject? =
    try {
        readIfServed(path)
    } catch (refused: PrintMessage) {
        if (refused.statusCode != ExitCode.REFUSED.code) throw refused
        unreadable[what] = "${refused.message}"
        null
    }

/** What init says of a read the server refused for [why], and how to make it otherwise. */
private fun cannotRead(access: OrgAccess, why: String): String {
    val said = "this token cannot read them ($why)"
    return if (access.consoleToken) "$said: without OSTROM_TOKEN, ostrom login's token reads them" else said
}
