// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.team

import run.ostrom.cli.launcher.parseObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8

// The launcher's test fixtures of team hooks, for the commands' tests: a test source set sees no other module's.

/** A version's files as the server sends them, and their digest. */
internal object Artifacts {
    /** One file: its path, its mode and its text. */
    class Source(
        val path: String,
        val text: String,
        val mode: String = Delivered.FILE,
        val content: ByteString = text.encodeUtf8(),
    )

    fun entry(source: Source): JsonObject {
        val bytes = source.content
        return JsonObject(
            mapOf(
                "path" to JsonPrimitive(source.path),
                "mode" to JsonPrimitive(source.mode),
                "size" to JsonPrimitive(bytes.size),
                "sha256" to JsonPrimitive(bytes.sha256().hex()),
                "contentBase64" to JsonPrimitive(bytes.base64()),
            ),
        )
    }

    fun digest(sources: List<Source>, prefix: String = ""): String = Delivered.digestOf(
        prefix + sources.sortedBy { it.path }.joinToString("") {
            val bytes = it.content
            Delivered.line(it.path, it.mode, bytes.size.toLong(), bytes.sha256().hex())
        },
    )

    fun files(entries: List<JsonObject>) = JsonObject(mapOf("files" to JsonArray(entries)))
}

/** A team hook as the server delivers it, its digest computed as the catalogue does. */
internal object Hooks {
    const val DEFINITION =
        """{"events":["PreToolUse","Stop"],"matcher":"Bash","command":"check.sh","timeoutSeconds":30}"""
    val files = listOf(
        Artifacts.Source("check.sh", "#!/bin/sh\nexit 0\n", Delivered.EXECUTABLE),
        Artifacts.Source("README.md", "Checks.\n"),
    )
    val definition = checkNotNull(HookDefinition.of(parseObject(DEFINITION)))
    val digest = Artifacts.digest(files, definition.digestLine)

    fun entry(name: String = "guard", version: String = "1.0.0", digest: String = Hooks.digest) =
        """{"name":"$name","version":"$version","digest":"$digest","definition":$DEFINITION,"sizeBytes":1,""" +
            """"fileCount":2}"""

    fun artifact(
        name: String = "guard",
        version: String = "1.0.0",
        digest: String = Hooks.digest,
        definition: String = DEFINITION,
        files: List<Artifacts.Source> = Hooks.files,
    ) = JsonObject(
        mapOf(
            "name" to JsonPrimitive(name),
            "version" to JsonPrimitive(version),
            "digest" to JsonPrimitive(digest),
            "definition" to checkNotNull(parseObject(definition)),
            "files" to JsonArray(files.map(Artifacts::entry)),
        ),
    ).toString()
}
