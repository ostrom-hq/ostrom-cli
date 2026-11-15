// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.launcher.flag
import run.ostrom.cli.launcher.list
import run.ostrom.cli.launcher.objectOf
import run.ostrom.cli.launcher.text
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject

private const val DEVICES = "/api/v1/devices"
private const val WRITE_MILLIS = 15_000L

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
    val answer = runBlocking { signed.server.get(DEVICES, signed.token) }
        .orStop(signed.serverUrl, "No devices here.")
    if (json) {
        console.data("$answer\n")
        return
    }
    answer.list("items").filterIsInstance<JsonObject>().forEach { device ->
        val active = device["activeSessions"] ?: 0
        val current = if (device.flag("current") == true) "\tcurrent" else ""
        console.out("${device.text("id")}\t${device.text("name")}\t$active active sessions$current\n")
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
        val body = objectOf("name" to name)
        runBlocking { signed.server.call("PATCH", "$DEVICES/$id", body, signed.token, WRITE_MILLIS) }
            .orStop(signed.serverUrl, "No device $id.")
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
        runBlocking { signed.server.call("DELETE", "$DEVICES/$id", null, signed.token, WRITE_MILLIS) }
            .orStop(signed.serverUrl, "No device $id.")
        dependencies.console.out("Device removed and its sessions signed out.\n")
    }
}
