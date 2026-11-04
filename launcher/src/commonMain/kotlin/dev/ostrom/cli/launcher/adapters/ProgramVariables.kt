// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.adapters

/**
 * The variables that change a program the agent or its hooks start, pinned the same way for Claude Code and Gemini CLI
 * (security audit of the CLI n°2, H3 and H4; review REVUES-CLI-4). A repository's settings (`env` of
 * `.claude/settings.json`) or `.env` could otherwise set them: `GIT_CONFIG_COUNT` naming `core.fsmonitor` runs the
 * repository's script at the agent's next `git status`, outside the gate, with the hooks' token file in its
 * environment (probed on Claude Code 2.1.318); `BASH_ENV` or `SHELLOPTS=noexec` stop the gate before it runs.
 *
 * Each agent adds its own: Gemini's trust, endpoints and sandbox ([GeminiEnvironment]), Claude Code's shell
 * ([ClaudeEnvironment]).
 */
internal object ProgramVariables {
    /**
     * Read as unset when empty by the shells, the loader, git and the interpreters: the launcher's value, else the
     * developer's, else empty.
     */
    val PINNED = listOf(
        // the shell that runs the hooks, where it is found, the loader under it
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
        // what loads code into an interpreter (Gemini CLI 0.66's BLOCKED_EXECUTION_ENVS, and Python's home)
        "NODE_OPTIONS",
        "NODE_PATH",
        "NODE_CLI_FLAGS",
        "_FORCE_NODE_OPTIONS",
        "ELECTRON_RUN_AS_NODE",
        "PYTHONPATH",
        "PYTHONSTARTUP",
        "PYTHONHOME",
        "RUBYOPT",
        "RUBYLIB",
        "PERL5OPT",
        "PERL5LIB",
        "PERL5DB",
        "JAVA_TOOL_OPTIONS",
        "_JAVA_OPTIONS",
        "CLASSPATH",
        "DOTNET_STARTUP_HOOKS",
        "CORECLR_PROFILER_PATH",
        "CORECLR_ENABLE_PROFILING",
        // the proxies and certificates the hooks' HTTP client and Node would trust
        "HTTP_PROXY",
        "HTTPS_PROXY",
        "ALL_PROXY",
        "NO_PROXY",
        "http_proxy",
        "https_proxy",
        "all_proxy",
        "no_proxy",
        "NODE_TLS_REJECT_UNAUTHORIZED",
        "NODE_EXTRA_CA_CERTS",
        // git's configuration and the programs it runs (an empty one is checked to change nothing, git 2.47)
        "GIT_CONFIG_COUNT",
        "GIT_CONFIG_PARAMETERS",
        "GIT_CONFIG_NOSYSTEM",
        "GIT_EXTERNAL_DIFF",
        "GIT_EXEC_PATH",
        "GIT_ASKPASS",
        "SSH_ASKPASS",
        "GIT_PROXY_COMMAND",
        "GIT_ALTERNATE_OBJECT_DIRECTORIES",
        "XDG_CONFIG_HOME",
        "PAGER",
        "GIT_PAGER",
        "BROWSER",
        // the configuration of the programs the agents' own tools run: ripgrep's file can name a `--pre` program of the
        // repository's, run behind a search the gate allows (Gemini CLI 0.66, review REVUES-CLI-6)
        "RIPGREP_CONFIG_PATH",
    )

    /**
     * Not unset when empty: git stops on an empty `GIT_DIR`, `GIT_WORK_TREE`, `GIT_INDEX_FILE`, `GIT_COMMON_DIR` or
     * `GIT_OBJECT_DIRECTORY`, reads no global or system configuration (the developer's name, their credential helper)
     * with an empty `GIT_CONFIG_GLOBAL` or `GIT_CONFIG_SYSTEM`, and runs nothing for an empty `GIT_SSH_COMMAND` or
     * `GIT_SSH` (checked, git 2.47); an empty editor or shell is not "the default" either. So each is pinned only to
     * the developer's own value; one they do not set is left out, and closed another way: Claude Code does not read
     * the repository's settings ([ClaudeAdapter]), Gemini is not started where the repository's `.env` names one
     * ([GeminiEnvironment]).
     */
    val KEPT = listOf(
        "GIT_DIR",
        "GIT_WORK_TREE",
        "GIT_INDEX_FILE",
        "GIT_COMMON_DIR",
        "GIT_OBJECT_DIRECTORY",
        "GIT_CONFIG_GLOBAL",
        "GIT_CONFIG_SYSTEM",
        "GIT_TEMPLATE_DIR",
        "GIT_SSH",
        "GIT_SSH_COMMAND",
        "GIT_EDITOR",
        // an empty one stops `git rebase -i` ("unable to start editor ''", git 2.47, review REVUES-CLI-6)
        "GIT_SEQUENCE_EDITOR",
        "EDITOR",
        "VISUAL",
        "HOME",
        "SHELL",
        "ZDOTDIR",
    )

    /**
     * Each of [names] and each of [KEPT] the launcher sets or the developer has: the launcher's value (empty for one it
     * removes), else the developer's, else empty.
     *
     * @param set The variables the launcher and the adapter set (a value) or remove (null).
     */
    fun pinned(names: Collection<String>, set: Map<String, String?>, variable: (String) -> String?) =
        (names + KEPT.filter { it in set || variable(it) != null }).associateWith { name ->
            if (name in set) set[name].orEmpty() else variable(name).orEmpty()
        }
}
