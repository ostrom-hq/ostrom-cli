// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher

/**
 * The coding agents `ostrom run <agent>` starts (spec CLI.md § 2.2). Ostrom never installs an agent: when one is
 * missing, the CLI points to its official installation page and exits with 127.
 *
 * @property command The subcommand, `ostrom <command>`.
 * @property displayName How the agent is named in messages.
 * @property executable The program looked up on the PATH.
 * @property installUrl Where the developer installs it; null until the page is checked (only Claude Code's is quoted
 * by the spec, CLI.md § 2.2).
 */
enum class Agent(val command: String, val displayName: String, val executable: String, val installUrl: String?) {
    CLAUDE("claude", "Claude Code", "claude", "https://docs.claude.com/claude-code"),
    CODEX("codex", "Codex", "codex", null),
    OPENCODE("opencode", "OpenCode", "opencode", null),
    GEMINI("gemini", "Gemini CLI", "gemini", null),
    GROK("grok", "Grok CLI", "grok", null),
    HERMES("hermes", "Hermes Agent", "hermes", null),
    PI("pi", "Pi", "pi", null),
    ;

    /** Lookup by command name. */
    companion object {
        /** The agent started by `ostrom <command>`, or null. */
        fun of(command: String): Agent? = entries.firstOrNull { it.command == command }
    }
}
