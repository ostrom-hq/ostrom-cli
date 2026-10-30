// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.adapters

import dev.ostrom.cli.launcher.Agent
import dev.ostrom.cli.launcher.LaunchFiles
import dev.ostrom.cli.launcher.join
import dev.ostrom.cli.launcher.objectOf

/**
 * OpenCode: an extra config folder (`OPENCODE_CONFIG_DIR`) with Ostrom's plugin and the package's skills, the rules
 * and the relayed MCP servers by `OPENCODE_CONFIG_CONTENT`. The servers are stdio bridges to the local relay, so that
 * each call carries the session.
 */
class OpenCodeAdapter : AgentAdapter {
    override val agent = Agent.OPENCODE

    override fun prepare(layout: SessionLayout, files: LaunchFiles): AgentLaunch {
        val folder = join(layout.temporary, "opencode")
        files.createPrivateDirectory(folder)
        files.createPrivateDirectory(join(folder, "plugins"))
        files.write(join(folder, "plugins", "ostrom.js"), EmbeddedPlugins.OPENCODE)
        skillsInto(files, layout.plugin, join(folder, "skills"), null)
        val servers = layout.stdioServers.mapValues { (_, arguments) ->
            mapOf("type" to "local", "command" to listOf(layout.executable) + arguments, "enabled" to true)
        }
        val config = objectOf(
            "\$schema" to "https://opencode.ai/config.json",
            "instructions" to listOf(sessionRules(layout, files)),
            "mcp" to servers,
        )
        val environment = mapOf(
            "OPENCODE_CONFIG_DIR" to folder,
            "OPENCODE_CONFIG_CONTENT" to config.toString(),
            "OSTROM_PLUGIN_ROOT" to layout.plugin,
        )
        return AgentLaunch(layout.arguments, environment)
    }
}
