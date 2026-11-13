// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.ExitCode
import run.ostrom.cli.launcher.team.HookApprovals
import run.ostrom.cli.launcher.team.HookState
import run.ostrom.cli.launcher.team.HookText
import run.ostrom.cli.launcher.team.TeamHooks
import run.ostrom.cli.platform.OperatingSystem
import run.ostrom.cli.teamhooks.DecideTeamHookUseCase
import run.ostrom.cli.teamhooks.ListTeamHooksUseCase
import run.ostrom.cli.teamhooks.ShowTeamHookUseCase
import run.ostrom.cli.teamhooks.TeamHookServerFailure
import run.ostrom.cli.teamhooks.TeamHookStop
import run.ostrom.cli.teamhooks.describe
import run.ostrom.cli.teamhooks.shown
import run.ostrom.core.api.models.ServerFeatures
import kotlinx.coroutines.runBlocking

private const val NO_HOOKS = "This Ostrom server does not deliver team hooks."
private const val NOT_ON_WINDOWS = "Team hooks are not run on Windows yet: ostrom installs none of them here."

/** What a team hooks command works with: the server's routes with this device's credential, the member's decisions. */
private class TeamHookAccess(val hooks: TeamHooks, val approvals: HookApprovals)

/** Whether this computer runs team hooks: POSIX only for now. */
private val CliDependencies.windows: Boolean get() = environment.operatingSystem == OperatingSystem.WINDOWS

/**
 * Runs [block] against the team hooks delivered to the person signed in: the organisation, then the server's
 * `hooks.v1` feature, then the calls. A stop of the use case is its exit code; a refusal of the server the spec's.
 */
private fun <T> CliDependencies.teamHooks(server: String?, org: String?, block: suspend (TeamHookAccess) -> T): T {
    val access = orgAccess(server, org)
    requireFeature(access.signed.serverUrl, access.signed.token, ServerFeatures.HOOKS, NO_HOOKS)
    val approvals = HookApprovals(directory(), files::read, files::write)
    return try {
        runBlocking { block(TeamHookAccess(TeamHooks(access.server, access.signed.token), approvals)) }
    } catch (expected: TeamHookStop) {
        throw stopWith(expected.code, "ostrom: ${expected.message}")
    } catch (expected: TeamHookServerFailure) {
        throw expected.failure.refusal(access.signed.serverUrl, "No team hooks here.")
    }
}

/** `ostrom hooks`: the team hooks delivered to you; the list unless a subcommand is given. */
internal class HooksCommand(private val dependencies: CliDependencies) :
    GroupCommand(
        "hooks",
        "The team hooks delivered to you: read, approve or refuse each version",
        "list",
        comingSoon = false,
    ) {
    override fun runDefault() = dependencies.listTeamHooks(server = null, org = null)
}

/** `ostrom hooks list`. */
internal class HooksListCommand(private val dependencies: CliDependencies) : CliktCommand(name = "list") {
    override fun help(context: Context) = "List the team hooks delivered to you and your decision on each version"

    private val org by orgOption()
    private val server by serverFlag()

    override fun run() = dependencies.listTeamHooks(server, org)
}

private fun CliDependencies.listTeamHooks(server: String?, org: String?) {
    val listing = teamHooks(server, org) { ListTeamHooksUseCase(it.hooks, it.approvals)() }
    listing.hooks.forEach { (hook, state) -> console.out("${describe(hook)} [${state.words}]\n") }
    if (listing.hooks.isEmpty()) console.err("No team hook is delivered to you.\n")
    if (listing.skipped > 0) {
        console.err("${listing.skipped} more cannot be installed by this ostrom: run ostrom update.\n")
    }
    if (windows) console.err("$NOT_ON_WINDOWS\n")
    if (listing.hooks.any { (_, state) -> state == HookState.AWAITING || state == HookState.NEW_VERSION }) {
        console.err("Read one with ostrom hooks show NAME, then ostrom hooks approve or refuse NAME.\n")
    }
}

/** `ostrom hooks show <name>`. */
internal class HooksShowCommand(private val dependencies: CliDependencies) : CliktCommand(name = "show") {
    override fun help(context: Context) =
        "Print a delivered hook's definition and files, verified against its digest, before you decide"

    private val name by argument("name")
    private val org by orgOption()
    private val server by serverFlag()

    override fun run() {
        val console = dependencies.console
        val shownHook = dependencies.teamHooks(server, org) {
            ShowTeamHookUseCase(it.hooks, it.approvals, record = !dependencies.windows)(name)
        }
        val hook = shownHook.hook
        console.out("${describe(hook)}\ndigest ${shown(hook.digest)}\n")
        for ((file, text) in shownHook.files) {
            val mode = if (file.executable) "executable" else "file"
            console.out("\n--- ${shown("${file.path} ($mode, ${file.content.size} bytes)")} ---\n")
            // what hides or reorders code is shown as <U+XXXX>, never given to the terminal; only bytes that are not
            // UTF-8 are withheld
            console.out((text?.let(HookText::visible) ?: "(binary content not shown)") + "\n")
        }
        when {
            dependencies.windows -> console.err("\n$NOT_ON_WINDOWS\n")
            shownHook.recorded -> console.out("\nApprove this version with ostrom hooks approve ${hook.name}.\n")
            else -> console.err(
                "\nSome files are not UTF-8 text and were not shown, so this version is not recorded as read. " +
                    "Inspect them yourself, then approve it with ostrom hooks approve ${hook.name} --digest " +
                    "${shown(hook.digest)}.\n",
            )
        }
    }
}

/** `ostrom hooks approve <name> [--digest D]`. */
internal class HooksApproveCommand(private val dependencies: CliDependencies) : CliktCommand(name = "approve") {
    override fun help(context: Context) =
        "Approve the version of a team hook you read with ostrom hooks show: your next launches run it"

    private val name by argument("name")
    private val digest by option(
        "--digest",
        help = "The digest of the version you checked (sha256:…); by default, the one ostrom hooks show printed last",
    )
    private val org by orgOption()
    private val server by serverFlag()

    override fun run() {
        if (dependencies.windows) throw stopWith(ExitCode.NOT_IMPLEMENTED, "ostrom: $NOT_ON_WINDOWS")
        val hook = dependencies.teamHooks(server, org) {
            DecideTeamHookUseCase(it.hooks, it.approvals).approve(name, digest)
        }
        dependencies.console.out(
            "Approved ${describe(hook)}. It runs with your user's rights from your next launch; a new version " +
                "needs a new approval.\n",
        )
    }
}

/** `ostrom hooks refuse <name>`. */
internal class HooksRefuseCommand(private val dependencies: CliDependencies) : CliktCommand(name = "refuse") {
    override fun help(context: Context) =
        "Refuse the delivered version of a team hook: no launch runs it, none asks again until a new version"

    private val name by argument("name")
    private val org by orgOption()
    private val server by serverFlag()

    override fun run() {
        val hook = dependencies.teamHooks(server, org) { DecideTeamHookUseCase(it.hooks, it.approvals).refuse(name) }
        dependencies.console.out(
            "Refused ${shown("${hook.name} ${hook.version}")}. Your agents do not run it; the team is not notified.\n",
        )
    }
}
