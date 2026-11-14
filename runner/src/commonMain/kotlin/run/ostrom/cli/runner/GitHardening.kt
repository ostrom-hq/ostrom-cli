// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

/**
 * The options every `git` of a session's preparation runs with, as TeamCode's `EnvironmentPreparation`: they win over
 * any configuration the workspace or the agent's home holds, so a repository's content runs nothing. No hook, no
 * fsmonitor, no transport but [protocols], no submodule, and no credential helper from the image, the repository or
 * the agent's home: the list is reset (`credential.helper=`), then only the runner's own helper answers, for
 * `https://github.com`.
 */
object GitHardening {
    /** The transports a recipe names: HTTPS only (tests add `file`). */
    val PROTOCOLS = setOf("https")

    /**
     * The `git` command and its options.
     *
     * @param helper The runner's credential helper (`<binary> cloud credential`).
     * @param protocols The transports allowed.
     *
     * @return `git` and its `-c` options, before the subcommand.
     */
    fun git(helper: String, protocols: Set<String> = PROTOCOLS): List<String> = buildList {
        add("git")
        val options = listOf("core.hooksPath=/dev/null", "core.fsmonitor=false", "protocol.allow=never") +
            protocols.sorted().map { "protocol.$it.allow=always" } +
            listOf(
                "fetch.recurseSubmodules=false",
                "submodule.recurse=false",
                // An empty helper first resets every helper configured so far, the session's own included.
                "credential.helper=",
                "credential.https://github.com.helper=$helper",
            )
        options.forEach {
            add("-c")
            add(it)
        }
    }

    /** The options of the session script's own `git` (the clone of a server without a recipe): HTTPS only. */
    const val SCRIPT_OPTIONS = "-c core.hooksPath=/dev/null -c core.fsmonitor=false -c protocol.allow=never " +
        "-c protocol.https.allow=always -c fetch.recurseSubmodules=false -c submodule.recurse=false"
}
