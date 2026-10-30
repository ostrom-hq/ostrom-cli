// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.adapters

import dev.ostrom.cli.launcher.Agent
import dev.ostrom.cli.launcher.LaunchFiles
import dev.ostrom.cli.launcher.LaunchVariables
import dev.ostrom.cli.launcher.jsonOf
import dev.ostrom.cli.launcher.join

/**
 * Pi: Ostrom's extension (`-e`) runs the gate and session hooks through this binary and registers the relayed MCP
 * servers; the rules and the package's context by `--append-system-prompt`, the package's skills by `--skill`.
 */
class PiAdapter : AgentAdapter {
    override val agent = Agent.PI

    override fun prepare(layout: SessionLayout, files: LaunchFiles): AgentLaunch {
        val extensions = join(layout.temporary, "pi-ext")
        files.createPrivateDirectory(extensions)
        val extension = join(extensions, "ostrom.ts")
        val servers = jsonOf(layout.stdioServers).toString()
        files.write(extension, EmbeddedPlugins.PI.replace(EmbeddedPlugins.MCP_SERVERS, servers))
        val rules = sessionRules(layout, files)
        val skills = join(layout.temporary, "pi-skills")
        val skillArguments = skillsInto(files, layout.plugin, skills, null)
            .flatMap { listOf("--skill", join(skills, it)) }
        val arguments = listOf("-e", extension, "--append-system-prompt", rules) + skillArguments + layout.arguments
        return AgentLaunch(arguments, mapOf(LaunchVariables.HOOK_DIALECT to "claude"))
    }
}

/** The rules and the package's context as one file of the session's plugin, for agents that read them from a file. */
internal fun sessionRules(layout: SessionLayout, files: LaunchFiles): String {
    val path = join(layout.plugin, "ostrom", "AGENTS-session.md")
    files.createPrivateDirectory(join(layout.plugin, "ostrom"))
    files.write(path, layout.rules)
    return path
}
