// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

/**
 * The environment `ostrom <agent>` gives the agent, read back by `ostrom hook gate|session` (hooks `HookContext`) and
 * `ostrom mcp`. Only the agent's own capability on the local relay goes in it: the device token, the launch's agent
 * token and the hooks' capability never do (audit A12/A13, S2-25).
 */
object LaunchVariables {
    /** `http://127.0.0.1:<port>`: the local relay the hooks call. */
    const val RELAY_URL = "OSTROM_RELAY_URL"

    /** The agent's capability on the relay, for this launch only: its MCP servers and inference, no hook route. */
    const val RELAY_TOKEN = "OSTROM_RELAY_TOKEN"

    /**
     * [RELAY_TOKEN] again, for `ostrom wait` in the agent's shell: Codex's default shell policy drops the variables
     * named like a token, a key or a secret.
     */
    const val RELAY_CLIENT = "OSTROM_RELAY_CLIENT"

    /** The private file holding the hooks' capability on the relay, which never enters an environment (S2-25). */
    const val RELAY_TOKEN_FILE = "OSTROM_RELAY_TOKEN_FILE"

    /** The instance's signing keys (`GET /api/v1/instance/keys`), which the gate policy must be signed with (S2-20). */
    const val INSTANCE_KEYS = "OSTROM_INSTANCE_KEYS"

    /** The SHA-256 of the session package's manifest: the hooks check the package against it at every run (S2-33). */
    const val PACKAGE_SEAL = "OSTROM_PACKAGE_SEAL"

    /** The manifest of the package laid down, `<sha256>  <path>` per file (S2-33). */
    const val PACKAGE_MANIFEST = "OSTROM_PACKAGE_MANIFEST"

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
