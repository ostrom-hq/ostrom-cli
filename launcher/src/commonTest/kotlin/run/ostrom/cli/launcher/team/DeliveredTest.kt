// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.team

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

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

class DeliveredTest {
    private val skill = listOf(
        Artifacts.Source("SKILL.md", "# Review\n"),
        Artifacts.Source("scripts/run.sh", "#!/bin/sh\n", Delivered.EXECUTABLE),
    )

    private fun verify(entries: List<JsonObject>, digest: String = Artifacts.digest(skill), max: Long = 1_000) =
        Delivered.verify(Artifacts.files(entries), digest, maxBytes = max)

    private fun refused(entries: List<JsonObject>, max: Long = 1_000) =
        assertIs<Verified.Refused>(verify(entries, max = max)).reason

    private fun with(key: String, value: JsonPrimitive) =
        JsonObject(Artifacts.entry(skill[0]) + (key to value))

    @Test
    fun theFilesOfAVersionAreTakenSortedWhenEachMatchesAndTheDigestToo() {
        val files = assertIs<Verified.Files>(verify(skill.reversed().map(Artifacts::entry))).files
        assertEquals(listOf("SKILL.md", "scripts/run.sh"), files.map { it.path })
        assertEquals(listOf(false, true), files.map { it.executable })
        assertEquals("# Review\n", files[0].content.utf8())
    }

    @Test
    fun aHookDigestBeginsWithItsDefinitionLine() {
        val prefix = "hook\u0000PreToolUse\u0000Bash\u0000check.sh\u000060\n"
        val digest = Artifacts.digest(skill, prefix)
        assertIs<Verified.Files>(Delivered.verify(Artifacts.files(skill.map(Artifacts::entry)), digest, prefix))
        assertIs<Verified.Refused>(Delivered.verify(Artifacts.files(skill.map(Artifacts::entry)), digest))
    }

    @Test
    fun aVersionIsRefusedWhenItsFilesAreNotWhatTheyClaim() {
        assertEquals("digest", refused(skill.take(1).map(Artifacts::entry)))
        assertEquals("it has no file", refused(emptyList()))
        assertEquals("a file is not described as the contract says", assertIs<Verified.Refused>(
            Delivered.verify(JsonObject(mapOf("files" to JsonArray(listOf(JsonPrimitive("x"))))), "d"),
        ).reason)
        val many = (0..Delivered.MAX_FILES).map { Artifacts.entry(Artifacts.Source("f$it", "x")) }
        assertEquals("it has more than 200 files", refused(many))
        val described = "a file is not described as the contract says"
        assertEquals(described, refused(listOf(JsonObject(Artifacts.entry(skill[0]) - "path"))))
        assertEquals(described, refused(listOf(with("mode", JsonPrimitive("setuid")))))
        assertEquals(described, refused(listOf(JsonObject(Artifacts.entry(skill[0]) - "mode"))))
        assertEquals(described, refused(listOf(with("contentBase64", JsonPrimitive("***")))))
        assertEquals(described, refused(listOf(JsonObject(Artifacts.entry(skill[0]) - "contentBase64"))))
        assertEquals(described, refused(listOf(with("size", JsonPrimitive(3)))))
        assertEquals(described, refused(listOf(with("size", JsonPrimitive("9")))))
        assertEquals(described, refused(listOf(JsonObject(Artifacts.entry(skill[0]) - "size"))))
        assertEquals(described, refused(listOf(with("sha256", JsonPrimitive("0".repeat(64))))))
        assertEquals("the path ../x is not allowed", refused(listOf(Artifacts.entry(Artifacts.Source("../x", "x")))))
        val twice = Artifacts.entry(skill[0])
        assertEquals("SKILL.md is twice", refused(listOf(twice, twice)))
        val other = Artifacts.entry(Artifacts.Source("skill.md", "x"))
        assertEquals("skill.md is twice", refused(listOf(twice, other)), "twice on a file system that ignores case")
        assertEquals("it has more than 1 files", assertIs<Verified.Refused>(
            Delivered.verify(Artifacts.files(skill.map(Artifacts::entry)), "d", maxFiles = 1),
        ).reason)
        assertEquals("it is bigger than 0 MiB", refused(skill.map(Artifacts::entry), max = 12))
    }

    @Test
    fun aPathIsShortPlainNamesWithoutDotsAtTheirEnds() {
        assertTrue(Delivered.validPath("a/b_c/d-1.0+x.md"))
        assertFalse(Delivered.validPath(".ostrom-skill"))
        assertFalse(Delivered.validPath("a/./b"))
        assertFalse(Delivered.validPath("a/b."))
        assertFalse(Delivered.validPath("a//b"))
        assertFalse(Delivered.validPath("/etc/passwd"))
        assertFalse(Delivered.validPath("a\\b"))
        assertFalse(Delivered.validPath("x".repeat(256)))
        assertTrue(Delivered.validPath("x".repeat(255)))
        assertFalse(Delivered.validPath(List(9) { "a" }.joinToString("/")))
        assertTrue(Delivered.validPath(List(8) { "a" }.joinToString("/")))
    }
}
