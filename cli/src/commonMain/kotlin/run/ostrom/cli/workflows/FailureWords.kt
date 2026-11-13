// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.workflows

/** A failure code in words; a text the server wrote is kept as it is. */
internal fun failureWords(code: String): String = FAILURES[code]
    ?: if (code.startsWith("max_visits:")) {
        "the loop came back to step \"${code.substringAfter(':')}\" more often than its limit allows"
    } else {
        code
    }

private val FAILURES = mapOf(
    "session_failed" to "the agent's session failed",
    "session_deleted" to "the agent's session was deleted",
    "session_missing" to "the agent's session could not be found",
    "ended_without_delivery" to "the agent ended its turn without delivering (and without asking a person)",
    "profile_missing" to "no cloud profile was available for an agent step",
    "starter_unavailable" to "the member who started the run is no longer active",
    "wait_cancelled" to "the question to a person was withdrawn",
    "wait_missing" to "the question to a person disappeared",
    "max_total_visits" to "the run reached the workflow's limit of visits in all",
    "max_depth" to "sub-workflows are nested deeper than the limit",
    "workflow_missing" to "a sub-workflow no longer exists",
    "invalid_arguments" to "the arguments given to a sub-workflow were refused",
    "sub_workflow_failed" to "a sub-workflow failed",
    "sub_workflow_cancelled" to "a sub-workflow was cancelled",
    "expired" to "the run was not finished within two weeks",
    "no_transition" to "a step ended with an outcome that has no edge",
)
