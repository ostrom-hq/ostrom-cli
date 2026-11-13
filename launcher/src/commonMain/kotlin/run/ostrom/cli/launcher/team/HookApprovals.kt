// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.team

import run.ostrom.cli.launcher.flag
import run.ostrom.cli.launcher.join
import run.ostrom.cli.launcher.objectOf
import run.ostrom.cli.launcher.parseObject
import run.ostrom.cli.launcher.text
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.IOException

/** The member's decision on one version of a team hook: [digest] approved, or refused. */
data class HookDecision(val digest: String, val approved: Boolean)

/** Where a hook stands for the member, as `ostrom hooks list` and a launch say it. */
enum class HookState(val words: String) {
    /** No decision yet. */
    AWAITING("awaiting your approval"),

    /** A decision on another version: this one is not approved. */
    NEW_VERSION("new version awaiting your approval"),

    /** This version is approved: a launch runs it. */
    APPROVED("approved"),

    /** This version is refused: no launch runs it, none asks about it again. */
    REFUSED("refused"),
}

/**
 * The member's decisions on team hooks, kept on this computer only, in the CLI's private folder: `hooks/approvals.json`
 * (`{name: {digest, approved}}`) and `hooks/shown.json` (`{name: digest}`, the version `ostrom hooks show` printed
 * last, which `approve` records unless `--digest` names one). A decision binds one digest: a new version, or any change
 * to its files or definition, needs a new one. A file that cannot be read is no decision: nothing is approved.
 *
 * @param read The text of a file, or null.
 * @param write Writes a file readable by its owner only; by default, none is written (a launch only reads).
 */
class HookApprovals(
    configDirectory: String,
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit = ::readOnly,
) {
    private val approvals = join(configDirectory, FOLDER, "approvals.json")
    private val shown = join(configDirectory, FOLDER, "shown.json")

    /** Every decision, by hook name. */
    fun decisions(): Map<String, HookDecision> = entries(approvals).mapNotNull { (name, value) ->
        val decision = value as? JsonObject
        val digest = decision?.text("digest")
        val approved = decision?.flag("approved")
        if (digest == null || approved == null) null else name to HookDecision(digest, approved)
    }.toMap()

    /** Records [decision] for [name]. */
    fun decide(name: String, decision: HookDecision) {
        val next = decisions() + (name to decision)
        val body = next.mapValues { (_, it) -> objectOf("digest" to it.digest, "approved" to it.approved) }
        write(approvals, JsonObject(body).toString())
    }

    /** Where [hook] stands for the member. */
    fun stateOf(hook: EffectiveHook, decisions: Map<String, HookDecision> = decisions()): HookState {
        val decision = decisions[hook.name]
        return when {
            decision == null -> HookState.AWAITING
            decision.digest != hook.digest -> HookState.NEW_VERSION
            decision.approved -> HookState.APPROVED
            else -> HookState.REFUSED
        }
    }

    /** The digest `ostrom hooks show` printed last, by hook name. */
    fun shown(): Map<String, String> = entries(shown).mapNotNull { (name, value) ->
        (value as? JsonPrimitive)?.takeIf { it.isString }?.let { name to it.content }
    }.toMap()

    /** Records that [digest] of [name] was printed whole. */
    fun show(name: String, digest: String) {
        write(shown, JsonObject((shown() + (name to digest)).mapValues { JsonPrimitive(it.value) }).toString())
    }

    private fun entries(path: String) = parseObject(read(path)).orEmpty().entries.map { it.key to it.value }

    private companion object {
        const val FOLDER = "hooks"
    }
}

private fun readOnly(path: String, @Suppress("UNUSED_PARAMETER") text: String): Unit =
    throw IOException("$path is not written by a launch")
