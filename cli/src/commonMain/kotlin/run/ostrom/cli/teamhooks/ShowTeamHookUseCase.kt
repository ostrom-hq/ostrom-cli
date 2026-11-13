// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.teamhooks

import run.ostrom.cli.ExitCode
import run.ostrom.cli.launcher.team.DeliveredFile
import run.ostrom.cli.launcher.team.EffectiveHook
import run.ostrom.cli.launcher.team.HookApprovals
import run.ostrom.cli.launcher.team.HookFiles
import run.ostrom.cli.launcher.team.TeamHooks
import okio.ByteString.Companion.encodeUtf8

/**
 * A delivered hook, verified, to be read before a decision.
 *
 * @property files Each file with its text, or null for content that is not UTF-8 (not shown).
 * @property recorded Whether this version was printed whole and recorded as read, for `ostrom hooks approve`.
 */
internal class ShownHook(val hook: EffectiveHook, val files: List<Pair<DeliveredFile, String?>>, val recorded: Boolean)

/**
 * `ostrom hooks show`: the delivered version of a hook, its files verified against its digest. A version printed whole
 * is recorded as read ([HookApprovals.show]), so that `approve` takes this version and never one delivered since;
 * one with a file that is not UTF-8 text is not, and is approved only with its digest given.
 *
 * @param record False where team hooks are not run (Windows): nothing is recorded.
 */
internal class ShowTeamHookUseCase(
    private val hooks: TeamHooks,
    private val approvals: HookApprovals,
    private val record: Boolean,
) {
    suspend operator fun invoke(name: String): ShownHook {
        val hook = hooks.delivered(name)
        val files = verified(hooks, hook).map { file ->
            val text = file.content.utf8()
            file to text.takeIf { it.encodeUtf8() == file.content }
        }
        val whole = files.all { it.second != null }
        if (whole && record) approvals.show(hook.name, hook.digest)
        return ShownHook(hook, files, whole && record)
    }
}

/** The files of [hook], verified, or the command stops (78, or the server's refusal). */
internal suspend fun verified(hooks: TeamHooks, hook: EffectiveHook): List<DeliveredFile> =
    when (val files = hooks.files(hook)) {
        is HookFiles.Verified -> files.files
        is HookFiles.ServerFailure -> throw TeamHookServerFailure(files.failure)
        is HookFiles.Refused -> throw TeamHookStop(
            ExitCode.SIGNATURE_REFUSED,
            "The team hook ${hook.name} ${shown(hook.version)} was refused (${files.reason}): nothing of it is shown " +
                "nor approved.",
        )
    }
