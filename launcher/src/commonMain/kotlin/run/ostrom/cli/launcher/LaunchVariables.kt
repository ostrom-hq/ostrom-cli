// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher

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

    /**
     * The Unix socket of a cloud session's runner, which runs the package checks. Always set by the launcher, empty on
     * a workstation, so that a repository's `.env` (which Gemini CLI loads without replacing a variable already set)
     * cannot point the hooks at a runner of its own.
     */
    const val RUNNER_CHECKS = "OSTROM_RUNNER_CHECKS"

    /** The hook protocol of the agent family (`claude`, `grok`, `gemini`, `hermes`). */
    const val HOOK_DIALECT = "OSTROM_HOOK_DIALECT"

    /**
     * How long a gate hook may run before the agent gives up on it, in milliseconds: past it, Grok, Gemini, Hermes and
     * Claude Code (without `onFailure: block`) let the action through.
     */
    const val HOOK_BUDGET_MS = "OSTROM_HOOK_BUDGET_MS"

    /** The session's settings, for an MCP server started with a filtered environment. */
    const val SESSION_SETTINGS = "OSTROM_SESSION_SETTINGS"

    /** The hook view of the session package (`package.json`). */
    const val PACKAGE = "OSTROM_PACKAGE"

    /** Never passed on: the server URL; the hooks reach Ostrom through the relay only. */
    const val SERVER = "OSTROM_URL"

    /** The CLI's own folder, as the launcher resolved it, for the hooks to read the same. */
    const val HOME = "OSTROM_HOME"

    /** Never passed on: the developer's own device token, when set in their shell. */
    const val DEVICE_TOKEN = "OSTROM_TOKEN"

    /**
     * Every `OSTROM_` variable the CLI reads, its hooks and `ostrom mcp` included: an agent that loads a repository's
     * `.env` without replacing a variable already set (Gemini CLI) gets them all, empty when Ostrom has no value.
     */
    val ALL: List<String> = listOf(
        RELAY_URL,
        RELAY_TOKEN,
        RELAY_CLIENT,
        RELAY_TOKEN_FILE,
        INSTANCE_KEYS,
        PACKAGE,
        PACKAGE_SEAL,
        PACKAGE_MANIFEST,
        REPO,
        DEV,
        AGENT,
        GIT,
        GATES,
        SESSION_FILE,
        SESSION_SETTINGS,
        HARNESS_VERSION,
        LAUNCH_TOP,
        LAUNCH_ID,
        MODEL,
        RUNNER_CHECKS,
        HOOK_DIALECT,
        HOOK_BUDGET_MS,
        SERVER,
        DEVICE_TOKEN,
        HOME,
        // read by the CLI itself, its hooks or the agents' plugins
        "OSTROM_HOOK_TIMING",
        "OSTROM_TELEMETRY",
        "OSTROM_ORG",
        "OSTROM_CONSOLE_URL",
        "OSTROM_GITHUB_WEB_URL",
        "OSTROM_RELEASES_URL",
        "OSTROM_PLUGIN_ROOT",
        "OSTROM_BIN",
        "OSTROM_KEEP_PLUGIN",
        "OSTROM_INFERENCE",
        "OSTROM_ALIVE_MS",
    )
}
