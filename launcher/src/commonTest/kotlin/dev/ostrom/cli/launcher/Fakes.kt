// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

import okio.IOException

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

    var failWrites = false

    override fun write(path: String, text: String, access: FileAccess) {
        if (failWrites) throw IOException("disk full")
        texts[path] = text
        this.access[path] = access
    }

    override fun createPrivateDirectory(path: String) {
        if (failDirectories) throw IOException("read-only file system")
        if (!directories.add(path)) throw IOException("$path already exists")
    }

    override fun list(path: String): List<String> =
        (texts.keys + directories + links.keys).mapNotNull { below(path, it) }
            .map { it.substringBefore('/').substringBefore('\\') }.distinct().sorted()

    override fun exists(path: String) = path in texts || path in directories

    override fun link(target: String, link: String): Boolean {
        links[link] = target
        return true
    }

    override fun deleteRecursively(path: String) {
        deleted += path
        val under = { entry: String -> entry == path || below(path, entry) != null }
        texts.keys.removeAll(under)
        directories.removeAll(under)
        links.keys.removeAll(under)
    }

    /**
     * What follows [path] in [entry], or null when [entry] is not under it. Okio keeps the separator a path was written
     * with (on Windows, `"/tmp".toPath() / "a"` is `/tmp/a`), and a real file system takes both: so does this one.
     */
    private fun below(path: String, entry: String): String? =
        SEPARATORS.map { path + it }.firstOrNull { entry.startsWith(it) }?.let { entry.removePrefix(it) }

    private companion object {
        val SEPARATORS = listOf("/", "\\")
    }
}
