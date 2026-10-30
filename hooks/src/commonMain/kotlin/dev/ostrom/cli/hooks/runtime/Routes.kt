// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

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

    /** `POST`: a notification of the workflow package, sent by Ostrom. */
    const val NOTIFICATIONS = "/api/v1/notifications"

    /** `GET`: the gates of [repository] and the actions they cover. */
    fun catalog(repository: String) = "/api/v1/gates/catalog?repo=${Urls.encode(repository)}"

    /** `POST`: open a new segment chained to a closed session. */
    fun reopen(sessionId: String) = session(sessionId, "reopen")

    /** `POST`: a skill or a sub-agent of the package was opened. */
    fun seen(sessionId: String) = session(sessionId, "seen")

    private fun session(sessionId: String, action: String) = "/api/v1/sessions/${Urls.encode(sessionId)}/$action"
}
