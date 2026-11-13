// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.waits

import run.ostrom.cli.launcher.ServerCall
import run.ostrom.cli.launcher.ServerJson
import run.ostrom.cli.launcher.ServerReply
import run.ostrom.cli.launcher.ServerTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** A server of waits that answers `METHOD path` from [routes] (404 otherwise) and keeps what it was asked. */
internal class FakeWaitsServer : ServerTransport {
    val routes = mutableMapOf<String, ServerReply>()
    val calls = mutableListOf<ServerCall>()

    override suspend fun send(call: ServerCall): ServerReply {
        calls += call
        val missing = ServerReply(404, """{"error":{"type":"not_found","message":"no"}}""")
        return routes["${call.method} ${call.path}"] ?: missing
    }

    fun answer(route: String, status: Int, body: String) {
        routes[route] = ServerReply(status, body)
    }

    val server get() = WaitsServer(ServerJson(this), "tok")

    fun bodyOf(index: Int): JsonObject = Json.parseToJsonElement(calls[index].body.orEmpty()) as JsonObject
}

/** A wait as the server gives it. */
internal fun waitJson(
    id: String = "w1",
    status: String? = "open",
    fields: String = "[]",
    actions: String = "[]",
): JsonObject {
    val state = status?.let { """"status":"$it",""" }.orEmpty()
    val text = """{"id":"$id",$state"kind":"question","assignee":"m1","createdAt":"2026-11-13T10:00:00Z",""" +
        """"question":{"title":"Which region?","fields":$fields,"actions":$actions}}"""
    return Json.parseToJsonElement(text) as JsonObject
}
