// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

/**
 * The environment `ostrom <agent>` gives the agent, read back by `ostrom hook gate|session` (hooks `HookContext`) and
 * `ostrom mcp`. Only the local relay's capability goes in it: the device token and the launch's agent token never do
 * (audit A12/A13).
 */
object LaunchVariables {
    /** `http://127.0.0.1:<port>`: the local relay the hooks call. */
    const val RELAY_URL = "OSTROM_RELAY_URL"

    /** The relay's capability, valid for this launch only. */
    const val RELAY_TOKEN = "OSTROM_RELAY_TOKEN"

    /** The repository the session is for (`owner/name`). */
    const val REPO = "OSTROM_REPO"

    /** Who runs the session. */
    const val DEV = "OSTROM_DEV"

    /** The agent (`claude-code`, `codex`…). */
    const val AGENT = "OSTROM_AGENT"

    /** The git the hooks run, resolved by the launcher: not the first `git` the agent could put on its PATH (S2-28). */
    const val GIT = "OSTROM_GIT"

    /** The package's copy of the gate catalogue: the hook only adds what it gates (S2-20). */
    const val GATES = "OSTROM_GATES"

    /** Where the launch keeps its Ostrom session id. */
    const val SESSION_FILE = "OSTROM_SESSION_FILE"

    /** The version of the harness laid down. */
    const val HARNESS_VERSION = "OSTROM_HARNESS_VERSION"

    /** The top of the repository the agent was launched in. */
    const val LAUNCH_TOP = "OSTROM_LAUNCH_TOP"

    /** The launch, given by Ostrom. */
    const val LAUNCH_ID = "OSTROM_LAUNCH_ID"

    /** The model asked at launch. */
    const val MODEL = "OSTROM_MODEL"

    /** The hook protocol of the agent family (`claude`, `grok`, `gemini`, `hermes`). */
    const val HOOK_DIALECT = "OSTROM_HOOK_DIALECT"

    /** How long a gate hook may run before the agent gives up on it, in milliseconds (Grok lets the action through). */
    const val HOOK_BUDGET_MS = "OSTROM_HOOK_BUDGET_MS"

    /** The session's settings, for an MCP server started with a filtered environment. */
    const val SESSION_SETTINGS = "OSTROM_SESSION_SETTINGS"

    /** The hook view of the session package (`package.json`). */
    const val PACKAGE = "OSTROM_PACKAGE"

    /** Never passed on: the server URL; the hooks reach Ostrom through the relay only. */
    const val SERVER = "OSTROM_URL"

    /** Never passed on: the developer's own device token, when set in their shell. */
    const val DEVICE_TOKEN = "OSTROM_TOKEN"
}
