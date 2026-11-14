// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks.runtime

import run.ostrom.cli.hooks.HookEvent
import run.ostrom.cli.hooks.HookKind
import run.ostrom.cli.hooks.HookProcessor
import run.ostrom.cli.hooks.HookReply
import run.ostrom.cli.hooks.gate.GitRunner

/**
 * What the hooks of a session reach outside themselves.
 *
 * @property calls Ostrom, through the launcher's relay.
 * @property files The session's small files.
 * @property git The real `git`.
 * @property canonical A folder with its symbolic links resolved.
 * @property workingDirectory Where the hook was started, for events that do not say.
 * @property checks The workflow package of the session.
 * @property now The current time in milliseconds since the epoch: a gate policy past its signed expiry is not trusted.
 * @property sleep Waits the given milliseconds: the wake hook between two failed calls. The default never waits (a hook
 * that is not the wake does not call it); `main` gives the real one.
 */
data class HookPorts(
    val calls: OstromCalls,
    val files: HookFiles,
    val git: GitRunner,
    val canonical: (String) -> String,
    val workingDirectory: String,
    val checks: WorkflowChecks = NoWorkflowChecks,
    val now: () -> Long = SessionHooks::epochMillis,
    val sleep: (Long) -> Unit = {},
)

/**
 * The hooks of `ostrom run <agent>`: the gate, the session hook and the wake hook. An event the gate cannot read (too
 * large, late, not JSON) is refused: the gate never lets through what it did not read (S2-30).
 */
class SessionHookProcessor(private val context: HookContext, private val ports: HookPorts) : HookProcessor {
    override fun process(kind: HookKind, input: ByteArray?, dialect: String): HookReply {
        val session = context.copy(dialect = dialect)
        val ids = SessionIds(session.sessionFile, ports.files)
        val parsed = HookEvent.parse(input)
        val event = parsed.fields
        return when {
            kind == HookKind.SESSION ->
                SessionHook(session, ports.calls, ids, ports.files, ports.checks, ports.workingDirectory).handle(event)
            kind == HookKind.WAKE -> WakeHook(ports.calls, ids, ports.sleep, ports.now).handle(event)
            kind == HookKind.ACTIVITY -> ActivityHook(ports.calls).handle(parsed.name)
            parsed.name.isEmpty() -> HookReplies(dialect).deny(GateTexts.UNREAD_EVENT)
            else -> GateHook(
                session,
                ports.calls,
                ids,
                ports.files,
                CatalogueStoreImpl(
                    session,
                    ports.files,
                    ports.calls,
                    SignedGatePolicy(SignedGatePolicy.keysOf(session.instanceKeys)),
                    PackageSeal(session.packageSeal, session.packageManifest, ports.files),
                    ports.now,
                ),
                ports.checks,
                ports.git,
                ports.canonical,
                ports.workingDirectory,
            ).handle(event)
        }
    }
}
