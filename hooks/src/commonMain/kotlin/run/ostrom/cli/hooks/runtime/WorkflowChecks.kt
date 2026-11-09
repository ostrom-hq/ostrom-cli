// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks.runtime

import run.ostrom.cli.hooks.HookReply
import run.ostrom.cli.hooks.gate.ToolSubject
import kotlinx.serialization.json.JsonObject

/**
 * The checks and notifications of the session's workflow package, run inside the gate hook: the checks before the
 * action (a failure refuses it now), the notifications after it (sent by Ostrom, never by the machine).
 */
interface WorkflowChecks {
    /** The digest of the package, recorded with the session at its start; null without a package. */
    val digest: String?

    /** The package's line of session context, or null. */
    val context: String?

    /** Whether an enforced check of the package guards this action. */
    fun guards(subject: ToolSubject): Boolean

    /**
     * Runs the checks that guard the action, before it.
     *
     * @return A refusal to answer now, or null to go on to the gate.
     */
    fun beforeTool(event: JsonObject, subject: ToolSubject, cwd: String): HookReply?

    /** Sends the notifications that follow the action, after it ran. */
    fun afterTool(event: JsonObject, subject: ToolSubject)
}

/** A session without a workflow package: nothing to check, nothing to notify. */
object NoWorkflowChecks : WorkflowChecks {
    override val digest: String? = null
    override val context: String? = null

    override fun guards(subject: ToolSubject): Boolean = false

    override fun beforeTool(event: JsonObject, subject: ToolSubject, cwd: String): HookReply? = null

    override fun afterTool(event: JsonObject, subject: ToolSubject) = Unit
}
