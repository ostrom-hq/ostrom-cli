// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks.runtime

/**
 * The routes of Ostrom the hooks call, all in one place (ostrom-core `api/openapi.yaml`, under `/api/v1`). The
 * launcher's relay forwards these and nothing else. A hook never uses up a yes nor ends a session (S2-25, S2-29):
 * Ostrom uses the yes up when it sees the push land, and the launcher, not the agent, ends the session.
 */
object Routes {
    /** `POST`: start a session or a segment (SessionStart). */
    const val SESSIONS = "/api/v1/sessions"

    /** `POST`: ask the gate of an action (PreToolUse). */
    const val GATES = "/api/v1/gates"

    /**
     * `POST`: a gated action the hook refused on the workstation without asking (git redirected, a push it cannot
     * read), told after the refusal, best effort (`reportLocalGateDenial`, audit A6).
     */
    const val LOCAL_DENIALS = "/api/v1/gates/local-denials"

    /** `POST`: the run of a check of the workflow package (`ReportCheckRequest`), best effort. */
    const val CHECKS = "/api/v1/checks"

    /** `POST`: a notification of the workflow package, sent by Ostrom. */
    const val NOTIFICATIONS = "/api/v1/notifications"

    /**
     * `GET`: the organisation's gate policy for [repository], signed by the instance with a short expiry
     * (`ostrom.gates/1`, security audit S2-20); the relay keeps it in its memory for 30 s.
     */
    fun signedCatalog(repository: String) = "/api/v1/gates/catalog/signed?repo=${Urls.encode(repository)}"

    /** `POST`: open a new segment chained to a closed session. */
    fun reopen(sessionId: String) = session(sessionId, "reopen")

    /** `POST`: a skill or a sub-agent of the package was opened. */
    fun seen(sessionId: String) = session(sessionId, "seen")

    private fun session(sessionId: String, action: String) = "/api/v1/sessions/${Urls.encode(sessionId)}/$action"
}
