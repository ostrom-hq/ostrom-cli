// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Builds artifacts as the server serves them, for the runner's tests. */
object Artifacts {
    /** One `ArtifactFileContent`. */
    fun file(path: String, text: String, mode: String = DeliveredItems.FILE): JsonObject {
        val bytes = text.encodeUtf8()
        return buildJsonObject {
            put("path", path)
            put("mode", mode)
            put("size", bytes.size)
            put("sha256", bytes.sha256().hex())
            put("contentBase64", bytes.base64())
        }
    }

    /** The digest of [files], after [prefix]. */
    fun digest(files: List<JsonObject>, prefix: String = ""): String = DeliveredItems.digestOf(
        prefix + files.sortedBy { (it["path"] as JsonPrimitive).content }.joinToString("") {
            DeliveredItems.line(
                (it["path"] as JsonPrimitive).content,
                (it["mode"] as JsonPrimitive).content,
                (it["size"] as JsonPrimitive).content.toLong(),
                (it["sha256"] as JsonPrimitive).content,
            )
        },
    )

    /** An artifact of [files]. */
    fun artifact(
        name: String,
        version: String,
        digest: String,
        files: List<JsonObject>,
        definition: JsonObject? = null,
    ) =
        JsonObject(
            mapOf(
                "name" to JsonPrimitive(name),
                "version" to JsonPrimitive(version),
                "digest" to JsonPrimitive(digest),
                "files" to JsonArray(files),
            ) + listOfNotNull(definition?.let { "definition" to it }),
        )

    /** A hook definition. */
    fun definition(
        events: List<String> = listOf("PreToolUse"),
        matcher: String? = "Bash",
        command: String = "run.sh",
        timeout: Int? = 30,
    ) = buildJsonObject {
        putJsonArray("events") { events.forEach { add(JsonPrimitive(it)) } }
        if (matcher != null) put("matcher", matcher)
        put("command", command)
        if (timeout != null) put("timeoutSeconds", timeout)
    }
}

class DeliveredItemsTest {
    private val digest = "sha256:" + "a".repeat(64)

    @Test
    fun aPathHoldsAtMostEightPlainNames() {
        assertTrue(DeliveredItems.validPath("scripts/run.sh"))
        assertTrue(DeliveredItems.validPath((1..8).joinToString("/") { "d$it" }))
        assertFalse(DeliveredItems.validPath((1..9).joinToString("/") { "d$it" }))
        assertFalse(DeliveredItems.validPath("../x"))
        assertFalse(DeliveredItems.validPath(".hidden"))
        assertFalse(DeliveredItems.validPath("a/b."))
        assertFalse(DeliveredItems.validPath("a//b"))
        assertFalse(DeliveredItems.validPath("a".repeat(256)))
    }

    @Test
    fun anEntryIsInstalledOnlyWithAPlainNameVersionAndDigest() {
        val entry = buildJsonObject {
            put("name", "review-pr")
            put("version", "1.2.0")
            put("digest", digest)
        }
        assertEquals(Triple("review-pr", "1.2.0", digest), DeliveredItems.identity(entry))
        assertNull(DeliveredItems.identity(JsonObject(entry + ("name" to JsonPrimitive("Review")))))
        assertNull(DeliveredItems.identity(JsonObject(entry + ("name" to JsonPrimitive("a".repeat(65))))))
        assertNull(DeliveredItems.identity(JsonObject(entry + ("version" to JsonPrimitive("-1")))))
        assertNull(DeliveredItems.identity(JsonObject(entry + ("digest" to JsonPrimitive("md5:x")))))
        assertNull(DeliveredItems.identity(JsonObject(entry + ("name" to JsonPrimitive(1)))))
        assertNull(DeliveredItems.identity(JsonObject(entry - "version")))
        assertNull(DeliveredItems.identity(JsonObject(entry - "digest")))
    }

    @Test
    fun aDefinitionIsReadAsTheContractSays() {
        val read = assertNotNull(DeliveredItems.definition(Artifacts.definition(listOf("Stop", "PreToolUse"))))
        assertEquals(listOf("PreToolUse", "Stop"), read.events)
        assertEquals("Bash", read.matcher)
        assertEquals("hook\u0000PreToolUse,Stop\u0000Bash\u0000run.sh\u000030\n", read.digestLine)
        val defaults = assertNotNull(DeliveredItems.definition(Artifacts.definition(matcher = null, timeout = null)))
        assertNull(defaults.matcher)
        assertEquals(60, defaults.timeoutSeconds)
        assertEquals("hook\u0000PreToolUse\u0000\u0000run.sh\u000060\n", defaults.digestLine)
        val nullMatcher = JsonObject(Artifacts.definition() + ("matcher" to JsonNull))
        assertNull(assertNotNull(DeliveredItems.definition(nullMatcher)).matcher)
    }

    @Test
    fun aDefinitionThisRunnerCannotInstallIsRefused() {
        val refused = listOf(
            null,
            buildJsonObject { put("command", "run.sh") },
            Artifacts.definition(events = emptyList()),
            Artifacts.definition(events = listOf("Unknown")),
            Artifacts.definition(events = listOf("Stop", "Stop")),
            Artifacts.definition(matcher = ""),
            Artifacts.definition(matcher = "a\u0001"),
            Artifacts.definition(matcher = "m".repeat(257)),
            JsonObject(Artifacts.definition() + ("matcher" to JsonPrimitive(3))),
            Artifacts.definition(command = "../run.sh"),
            Artifacts.definition(timeout = 0),
            Artifacts.definition(timeout = 601),
            JsonObject(Artifacts.definition() + ("timeoutSeconds" to JsonPrimitive("30"))),
            JsonObject(Artifacts.definition() + ("timeoutSeconds" to JsonPrimitive(1.5))),
            JsonObject(Artifacts.definition() + ("events" to JsonArray(listOf(JsonPrimitive(1))))),
            JsonObject(Artifacts.definition() + ("events" to JsonArray(listOf(JsonObject(emptyMap()))))),
            JsonObject(Artifacts.definition() - "command"),
            JsonObject(Artifacts.definition() + ("timeoutSeconds" to JsonObject(emptyMap()))),
        )
        for (definition in refused) assertNull(DeliveredItems.definition(definition), "$definition")
    }

    @Test
    fun anArtifactIsVerifiedFileByFileThenByItsDigest() {
        val files = listOf(Artifacts.file("SKILL.md", "# Review"), Artifacts.file("bin/run", "echo", "executable"))
        val sum = Artifacts.digest(files)
        val identity = Triple("review", "1", sum)
        val verified = assertNotNull(DeliveredItems.verify(Artifacts.artifact("review", "1", sum, files), identity))
        assertEquals(listOf("SKILL.md", "bin/run"), verified.map { it.path })
        assertEquals(sum, DeliveredItems.digestOf(verified))
        val prefix = "hook\u0000Stop\u0000\u0000bin/run\u000060\n"
        val hookSum = Artifacts.digest(files, prefix)
        assertNotNull(
            DeliveredItems.verify(
                Artifacts.artifact("review", "1", hookSum, files),
                identity.copy(third = hookSum),
                prefix,
            ),
        )
    }

    @Test
    fun aHooksCommandMustBeOneOfItsExecutables() {
        val files = listOf(Artifacts.file("a.txt", "x"), Artifacts.file("run.sh", "echo", "executable"))
        val sum = Artifacts.digest(files)
        val artifact = Artifacts.artifact("guard", "1", sum, files)
        val identity = Triple("guard", "1", sum)
        assertNotNull(DeliveredItems.verify(artifact, identity, executable = "run.sh"))
        assertNull(DeliveredItems.verify(artifact, identity, executable = "a.txt"))
        assertNull(DeliveredItems.verify(artifact, identity, executable = "other.sh"))
    }

    @Test
    fun anArtifactThatDiffersFromItsManifestIsRefused() {
        val files = listOf(Artifacts.file("SKILL.md", "# Review"))
        val sum = Artifacts.digest(files)
        val identity = Triple("review", "1", sum)
        val badContent = JsonObject(files.single() + ("sha256" to JsonPrimitive("0".repeat(64))))
        val badSize = JsonObject(files.single() + ("size" to JsonPrimitive("8")))
        val wrongSize = JsonObject(files.single() + ("size" to JsonPrimitive(99)))
        val badMode = JsonObject(files.single() + ("mode" to JsonPrimitive("link")))
        val noPath = JsonObject(files.single() - "path")
        val badBase64 = JsonObject(files.single() + ("contentBase64" to JsonPrimitive("!!")))
        val refused = listOf(
            Artifacts.artifact("other", "1", sum, files),
            Artifacts.artifact("review", "2", sum, files),
            Artifacts.artifact("review", "1", "sha256:" + "0".repeat(64), files),
            Artifacts.artifact("review", "1", sum, emptyList()),
            Artifacts.artifact("review", "1", sum, listOf(badContent)),
            Artifacts.artifact("review", "1", sum, listOf(badSize)),
            Artifacts.artifact("review", "1", sum, listOf(wrongSize)),
            Artifacts.artifact("review", "1", sum, listOf(badMode)),
            Artifacts.artifact("review", "1", sum, listOf(noPath)),
            Artifacts.artifact("review", "1", sum, listOf(badBase64)),
            JsonObject(
                Artifacts.artifact("review", "1", sum, files) + ("files" to JsonArray(listOf(JsonPrimitive(1)))),
            ),
            JsonObject(Artifacts.artifact("review", "1", sum, files) - "files"),
        )
        for (artifact in refused) assertNull(DeliveredItems.verify(artifact, identity), "$artifact")
        val twice = listOf(Artifacts.file("a.md", "1"), Artifacts.file("A.md", "2"))
        val twiceSum = Artifacts.digest(twice)
        assertNull(
            DeliveredItems.verify(Artifacts.artifact("review", "1", twiceSum, twice), identity.copy(third = twiceSum)),
        )
        val noContent = JsonObject(files.single() - "contentBase64")
        assertNull(DeliveredItems.verify(Artifacts.artifact("review", "1", sum, listOf(noContent)), identity))
        for (field in listOf("mode", "size")) {
            val without = JsonObject(files.single() - field)
            assertNull(DeliveredItems.verify(Artifacts.artifact("review", "1", sum, listOf(without)), identity))
        }
        val objectSize = JsonObject(files.single() + ("size" to JsonObject(emptyMap())))
        assertNull(DeliveredItems.verify(Artifacts.artifact("review", "1", sum, listOf(objectSize)), identity))
        val hidden = listOf(Artifacts.file(".x", "1"))
        val hiddenSum = Artifacts.digest(hidden)
        assertNull(
            DeliveredItems.verify(
                Artifacts.artifact("review", "1", hiddenSum, hidden),
                identity.copy(third = hiddenSum),
            ),
        )
        assertNull(DeliveredItems.verify(Artifacts.artifact("review", "1", sum, files), identity, maxBytes = 3))
        val many = listOf(Artifacts.file("a", "1"), Artifacts.file("b", "2"))
        val manySum = Artifacts.digest(many)
        assertNull(
            DeliveredItems.verify(
                Artifacts.artifact("review", "1", manySum, many),
                identity.copy(third = manySum),
                maxFiles = 1,
            ),
        )
    }
}
