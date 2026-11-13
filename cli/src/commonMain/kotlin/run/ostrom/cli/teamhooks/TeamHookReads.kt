// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.teamhooks

import run.ostrom.cli.ExitCode
import run.ostrom.cli.launcher.ServerAnswer
import run.ostrom.cli.launcher.team.EffectiveHook
import run.ostrom.cli.launcher.team.HookManifest
import run.ostrom.cli.launcher.team.HookText
import run.ostrom.cli.launcher.team.TeamHooks

/** A team hooks command stops with [code] and [message]. */
internal class TeamHookStop(val code: ExitCode, message: String) : Exception(message)

/** The server refused a call of a team hooks command, or could not be reached. */
internal class TeamHookServerFailure(val failure: ServerAnswer.Failed) : Exception(failure.message)

/** The hooks delivered to the member, and how many this ostrom cannot install. */
internal class DeliveredHooks(val hooks: List<EffectiveHook>, val skipped: Int)

/** The hooks the server delivers to the caller; a refusal is [TeamHookServerFailure]. */
internal suspend fun TeamHooks.delivered(): DeliveredHooks = when (val manifest = manifest()) {
    is HookManifest.Read -> DeliveredHooks(manifest.hooks, manifest.skipped)
    is HookManifest.Failed -> throw TeamHookServerFailure(manifest.failure)
    HookManifest.Unreadable -> throw TeamHookStop(ExitCode.UNREACHABLE, "The team hooks of this server cannot be read.")
}

/** The delivered hook named [name], or exit 66. */
internal suspend fun TeamHooks.delivered(name: String): EffectiveHook =
    delivered().hooks.firstOrNull { it.name == name }
        ?: throw TeamHookStop(ExitCode.NOT_FOUND, "No team hook named ${shown(name)} is delivered to you.")

/** [text] from the server or the member, one line, with what hides made visible. */
internal fun shown(text: String) = HookText.visible(text, keepLines = false)

/** One line about [hook]: every word of it comes from the server, so what hides is made visible. */
internal fun describe(hook: EffectiveHook): String {
    val definition = hook.definition
    val matcher = definition.matcher?.let { " (matcher $it)" }.orEmpty()
    val events = definition.events.sorted().joinToString(", ")
    return shown("${hook.name} ${hook.version}: runs ${definition.command} on $events$matcher, timeout " +
        "${definition.timeoutSeconds} s")
}
