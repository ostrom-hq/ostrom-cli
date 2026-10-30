// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

/**
 * The routes of Ostrom the hooks call, all in one place (ostrom-core `api/openapi.yaml`, under `/api/v1`). The
 * launcher's relay forwards these and nothing else.
 */
object Routes {
    /** `POST`: start a session or a segment (SessionStart). */
    const val SESSIONS = "/api/v1/sessions"

    /** `POST`: ask the gate of an action (PreToolUse). */
    const val GATES = "/api/v1/gates"

    /** `POST`: a check of the workflow package ran. */
    const val CHECKS = "/api/v1/checks"

    /** `POST`: a notification of the workflow package, sent by Ostrom. */
    const val NOTIFICATIONS = "/api/v1/notifications"

    /** `GET`: the gates of [repository] and the actions they cover. */
    fun catalog(repository: String) = "/api/v1/gates/catalog?repo=${Urls.encode(repository)}"

    /** `POST`: a yes served the action it was given for. */
    fun consume(gateId: String) = "/api/v1/gates/${Urls.encode(gateId)}/consume"

    /** `GET`: a gate request, long-polled up to [waitSeconds] for its answer. */
    fun gate(gateId: String, waitSeconds: Int) = "/api/v1/gates/${Urls.encode(gateId)}?wait=$waitSeconds"

    /** `POST`: end a session; the server signs its receipt. */
    fun end(sessionId: String) = session(sessionId, "end")

    /** `POST`: open a new segment chained to a closed session. */
    fun reopen(sessionId: String) = session(sessionId, "reopen")

    /** `POST`: a skill or a sub-agent of the package was opened. */
    fun seen(sessionId: String) = session(sessionId, "seen")

    /** `POST`: the launcher's sign of life. */
    fun alive(sessionId: String) = session(sessionId, "alive")

    private fun session(sessionId: String, action: String) = "/api/v1/sessions/${Urls.encode(sessionId)}/$action"
}
