// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.launcher.CliJson
import run.ostrom.core.api.models.DevicePage

// TeamCode's `teamcode devices list|rename|revoke` ("an interactive login is required"): the person's own
// `ostrom login`. A server without devices answers 404: orStop says so.

/** `ostrom devices`: the workstations where you are signed in, unless a subcommand is given. */
internal class DevicesCommand(private val dependencies: CliDependencies) :
    GroupCommand("devices", "The workstations where you are signed in", "list", comingSoon = false) {
    override fun runDefault() = dependencies.listDevices(server = null, json = false)
}

/** `ostrom devices list`. */
internal class DevicesListCommand(private val dependencies: CliDependencies) : CliktCommand(name = "list") {
    override fun help(context: Context) = "List the workstations where you are signed in"

    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() = dependencies.listDevices(server, json)
}

private fun CliDependencies.listDevices(server: String?, json: Boolean) {
    val signed = signedIn(server)
    val page = signed.typed("No devices here.") { devices.list() }
    if (json) {
        console.data(CliJson.encodeToString(DevicePage.serializer(), page) + "\n")
        return
    }
    page.items.forEach { device ->
        val active = device.activeSessions ?: 0
        val current = if (device.current == true) "\tcurrent" else ""
        console.out("${device.id}\t${device.name}\t$active active sessions$current\n")
    }
}

/** `ostrom devices rename ID --name N`. */
internal class DevicesRenameCommand(private val dependencies: CliDependencies) : CliktCommand(name = "rename") {
    override fun help(context: Context) = "Rename a device"

    private val id by argument("id", help = "The device's id (ostrom devices list)")
    private val name by option("--name", help = "The new name").required()
    private val server by serverFlag()

    override fun run() {
        dependencies.outsideSessions("devices rename")
        val signed = dependencies.signedIn(server)
        signed.typed("No device $id.") { devices.rename(id, name) }
        dependencies.console.out("Device renamed.\n")
    }
}

/** `ostrom devices revoke ID`. */
internal class DevicesRevokeCommand(private val dependencies: CliDependencies) : CliktCommand(name = "revoke") {
    override fun help(context: Context) = "Remove a device and sign out every session on it"

    private val id by argument("id", help = "The device's id (ostrom devices list)")
    private val server by serverFlag()

    override fun run() {
        dependencies.outsideSessions("devices revoke")
        val signed = dependencies.signedIn(server)
        signed.typed("No device $id.") { devices.delete(id) }
        dependencies.console.out("Device removed and its sessions signed out.\n")
    }
}
