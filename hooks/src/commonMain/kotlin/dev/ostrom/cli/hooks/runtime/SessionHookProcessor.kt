package dev.ostrom.cli.hooks.runtime

import dev.ostrom.cli.hooks.HookEvent
import dev.ostrom.cli.hooks.HookKind
import dev.ostrom.cli.hooks.HookProcessor
import dev.ostrom.cli.hooks.HookReply
import dev.ostrom.cli.hooks.gate.GitRunner

/**
 * What the hooks of a session reach outside themselves.
 *
 * @property calls Ostrom, through the launcher's relay.
 * @property files The session's small files.
 * @property git The real `git`.
 * @property background Starts `ostrom hook gates-refresh` detached.
 * @property canonical A folder with its symbolic links resolved.
 * @property workingDirectory Where the hook was started, for events that do not say.
 * @property checks The workflow package of the session.
 */
data class HookPorts(
    val calls: OstromCalls,
    val files: HookFiles,
    val git: GitRunner,
    val background: BackgroundRefresh,
    val canonical: (String) -> String,
    val workingDirectory: String,
    val checks: WorkflowChecks = NoWorkflowChecks,
)

/** The hooks of `ostrom <agent>`: the gate, the session hook and the background catalogue refresh. */
class SessionHookProcessor(private val context: HookContext, private val ports: HookPorts) : HookProcessor {
    override fun process(kind: HookKind, input: ByteArray?, dialect: String): HookReply {
        val session = context.copy(dialect = dialect)
        val ids = SessionIds(session.sessionFile, ports.files)
        val catalogues = CatalogueStore(session, ports.files, ports.calls, ports.background)
        val event = HookEvent.parse(input).fields
        return when (kind) {
            HookKind.GATE -> GateHook(
                session,
                ports.calls,
                ids,
                catalogues,
                ports.checks,
                ports.git,
                ports.canonical,
                ports.workingDirectory,
            ).handle(event)
            HookKind.SESSION ->
                SessionHook(session, ports.calls, ids, ports.files, ports.checks, ports.workingDirectory).handle(event)
            HookKind.GATES_REFRESH -> {
                catalogues.refreshNow()
                HookReply()
            }
        }
    }
}
