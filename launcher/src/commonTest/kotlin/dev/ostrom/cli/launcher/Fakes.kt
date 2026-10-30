// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

import okio.IOException
import okio.Path

/** A server answering by `METHOD path` (query included); anything else is a 404. Records every request. */
class FakeServer(private val routes: MutableMap<String, ServerReply> = mutableMapOf()) : ServerTransport {
    val requests = mutableListOf<ServerCall>()
    var unreachable: Set<String> = emptySet()

    operator fun set(route: String, answer: Pair<Int, String>) {
        routes[route] = ServerReply(answer.first, answer.second)
    }

    override suspend fun send(call: ServerCall): ServerReply {
        requests += call
        val route = "${call.method} ${call.path}"
        if (route in unreachable) return ServerReply(null, "connection refused")
        return routes[route] ?: ServerReply(NOT_FOUND, """{"error":{"type":"not_found","message":"no $route"}}""")
    }

    /** The requests sent to [route]. */
    fun sent(route: String) = requests.filter { "${it.method} ${it.path}" == route }

    private companion object {
        const val NOT_FOUND = 404
    }
}

/** Files in memory: text by path, links by path, the access each file was written with. */
class MemoryFiles : LaunchFiles {
    val texts = mutableMapOf<String, String>()
    val access = mutableMapOf<String, FileAccess>()
    val directories = mutableSetOf<String>()
    val links = mutableMapOf<String, String>()
    val deleted = mutableListOf<String>()
    var failDirectories = false

    override fun read(path: String) = texts[path]

    override fun write(path: String, text: String, access: FileAccess) {
        texts[path] = text
        this.access[path] = access
    }

    override fun createPrivateDirectory(path: String) {
        if (failDirectories) throw IOException("read-only file system")
        directories += path
    }

    override fun list(path: String): List<String> {
        val prefix = path + Path.DIRECTORY_SEPARATOR
        return (texts.keys + directories + links.keys).filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix).substringBefore(Path.DIRECTORY_SEPARATOR) }.distinct().sorted()
    }

    override fun exists(path: String) = path in texts || path in directories

    override fun link(target: String, link: String): Boolean {
        links[link] = target
        return true
    }

    override fun deleteRecursively(path: String) {
        deleted += path
        texts.keys.removeAll { it == path || it.startsWith(path + Path.DIRECTORY_SEPARATOR) }
    }
}
