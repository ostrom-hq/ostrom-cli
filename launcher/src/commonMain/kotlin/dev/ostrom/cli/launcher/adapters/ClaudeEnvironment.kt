// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.adapters

import dev.ostrom.cli.launcher.LaunchVariables

/**
 * The variables Claude Code's session settings hold in their `env` (security audit of the CLI n°2, H4). The `env` of a
 * repository's `.claude/settings.json` rewrites the session's environment, the shell that runs every hook included:
 * `SHELLOPTS` with `noexec` makes Ostrom's gate a no-op that exits 0, and `OSTROM_RELAY_URL` or `ANTHROPIC_BASE_URL`
 * would point the gate or the inference elsewhere (probed on Claude Code 2.1.317). The settings Claude Code is started
 * with (`--settings`) come before the project's, so Ostrom writes there every variable it sets or removes, every name
 * of [LaunchVariables.ALL], and each variable that changes the shell running the hooks, where it is found, the loader
 * under it, Node, or the proxy the hooks' HTTP client would send the relay's token through: the launcher's value,
 * else the developer's, else empty (which Ostrom's own commands read as unset).
 */
internal object ClaudeEnvironment {
    /**
     * @param own The variables the adapter sets (a value) or removes (null): the provider's, the gate's budget.
     * @return Each pinned variable and its value, `""` for one Ostrom removes or the developer does not set.
     */
    fun pinned(layout: SessionLayout, own: Map<String, String?>): Map<String, String> {
        val set = layout.environment + own
        return (set.keys + LaunchVariables.ALL + SHELL).associateWith { name ->
            if (name in set) set[name].orEmpty() else layout.variable(name).orEmpty()
        }
    }

    // what changes the shell that runs the hooks, where it is found, the loader under it, Node, or the proxy
    private val SHELL = listOf(
        "PATH",
        "BASH_ENV",
        "ENV",
        "SHELLOPTS",
        "BASHOPTS",
        "CDPATH",
        "GLOBIGNORE",
        "IFS",
        "PS4",
        "LD_PRELOAD",
        "LD_AUDIT",
        "LD_LIBRARY_PATH",
        "LD_DEBUG",
        "LD_PROFILE",
        "DYLD_INSERT_LIBRARIES",
        "DYLD_LIBRARY_PATH",
        "DYLD_FRAMEWORK_PATH",
        "DYLD_FALLBACK_LIBRARY_PATH",
        "DYLD_FALLBACK_FRAMEWORK_PATH",
        "DYLD_FORCE_FLAT_NAMESPACE",
        "NODE_OPTIONS",
        "NODE_PATH",
        "HTTP_PROXY",
        "HTTPS_PROXY",
        "ALL_PROXY",
        "NO_PROXY",
        "http_proxy",
        "https_proxy",
        "all_proxy",
        "no_proxy",
    )
}
