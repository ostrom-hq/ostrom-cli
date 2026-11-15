// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0): cli/src/jvmTest/kotlin/me/nathanfallet/teamcode/cli/cloud/AttachFilesTest.kt and
// client/src/jvmTest/kotlin/me/nathanfallet/teamcode/client/CloudFileUploadTest.kt, for `ostrom cloud send --file`.
package run.ostrom.cli

import okio.ByteString.Companion.toByteString
import okio.Path.Companion.toPath
import run.ostrom.cli.commands.CloudFileTypes
import run.ostrom.cli.commands.FILES_OFF
import run.ostrom.cli.commands.NO_FILES
import run.ostrom.cli.commands.filesRefusal
import run.ostrom.cli.commands.sizeLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `ostrom cloud send --file`: the checks, the uploads and the words of TeamCode's `/attach`. */
class CloudSendFilesTest : SetupKit() {
    private val features = capabilities("runners.cloud.v1", "runners.cloud.conversation.v1", "runners.cloud.files.v1")
    private val send = "POST /api/v1/cloud/sessions/cs1/messages"
    private val policy = "GET /api/v1/cloud/files/policy"
    private val input = """{"id":"in1","kind":"message","status":"queued","authorId":"m1",""" +
        """"createdAt":"2026-11-15T10:00:00Z"}"""
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

    private fun policyOf(
        available: Boolean = true,
        file: Long = 1024,
        message: Long = 2048,
        files: Int = 5,
        types: String = """"image/png","text/plain"""",
    ) = """{"available":$available,"maxFileBytes":$file,"maxMessageBytes":$message,""" +
        """"maxFilesPerMessage":$files,"types":[$types]}"""

    private fun stored(id: String, name: String, bytes: ByteArray, sha: String = bytes.toByteString().sha256().hex()) =
        """{"id":"$id","sessionId":"cs1","name":"$name","size":${bytes.size},"contentType":"image/png",""" +
            """"sha256":"$sha","createdAt":"2026-11-15T10:00:00Z","expiresAt":"2026-11-16T10:00:00Z"}"""

    private fun upload(name: String) = "POST /api/v1/cloud/sessions/cs1/files?name=$name"

    private fun file(path: String, bytes: ByteArray) {
        val file = path.toPath()
        file.parent?.let(fileSystem::createDirectories)
        fileSystem.write(file) { write(bytes) }
    }

    private val served = mapOf(features, policy to (200 to policyOf()))

    private fun sendWith(routes: Map<String, Pair<Int, String>>, vararg files: String): Int =
        run(served + routes, "cloud", "send", "cs1", "Look", *files.flatMap { listOf("--file", it) }.toTypedArray())

    @Test
    fun eachFileIsUploadedThenNamedByTheMessage() {
        file("/work/shot.png", png)
        file("/work/notes", "a note".encodeToByteArray())
        val routes = mapOf(
            upload("shot.png") to (201 to stored("f1", "shot.png", png)),
            upload("notes") to (201 to stored("f2", "notes.txt", "a note".encodeToByteArray())),
            send to (202 to input),
        )
        assertEquals(0, sendWith(routes, "/work/shot.png", "/work/notes"))
        assertEquals("""{"text":"Look","fileIds":["f1","f2"]}""", bodyOf(send))
        val uploaded = requests.map { it.second }.filter { it.path.contains("/files?") }
        assertEquals(listOf(png.toList(), "a note".encodeToByteArray().toList()), uploaded.map { it.bytes?.toList() })
        assertTrue(uploaded.all { it.idempotencyKey != null })
        assertErr("Uploaded /work/shot.png (4 B).")
        assertErr("Uploaded /work/notes (6 B) as notes.txt.")
        assertEquals("in1\tmessage\tqueued\n", console.out.toString())
    }

    @Test
    fun aMessageWithoutFilesAsksNothingAboutFiles() {
        assertEquals(0, run(mapOf(features, send to (202 to input)), "cloud", "send", "cs1", "hi"))
        assertFalse(sent().any { it.contains("files") })
    }

    @Test
    fun aServerOrAnOrganizationWithoutFilesSaysSoBeforeReadingAnything() {
        val conversation = capabilities("runners.cloud.v1", "runners.cloud.conversation.v1")
        assertEquals(69, run(mapOf(conversation), "cloud", "send", "cs1", "x", "--file", "/nothing"))
        assertErr(NO_FILES)
        console.err.clear()
        assertEquals(77, sendWith(mapOf(policy to (200 to policyOf(available = false))), "/nothing"))
        assertErr(FILES_OFF)
        console.err.clear()
        assertEquals(69, sendWith(mapOf(policy to (200 to """{"available":true}""")), "/nothing"))
        assertErr("the files policy cannot be read")
        console.err.clear()
        assertEquals(66, sendWith(mapOf(policy to (404 to "{}")), "/nothing"))
        assertErr(NO_FILES)
        assertFalse(sent().any { it.startsWith(send) })
    }

    @Test
    fun tooManyFilesAreRefusedBeforeAnyUpload() {
        val six = Array(6) { "/f$it" }
        assertEquals(64, sendWith(emptyMap(), *six))
        assertErr("A message carries 5 files at most.")
        assertFalse(sent().any { it.contains("files") })
        console.err.clear()
        assertEquals(64, sendWith(mapOf(policy to (200 to policyOf(files = 1))), "/a", "/b"))
        assertErr("A message carries 1 files at most.")
    }

    @Test
    fun aFileThatCannotBeSentIsRefusedLocally() {
        fun refused(path: String, text: String, routes: Map<String, Pair<Int, String>> = emptyMap(), code: Int = 64) {
            console.err.clear()
            assertEquals(code, sendWith(routes, path))
            assertErr(text)
        }
        refused("/missing.png", "No file at /missing.png.", code = 66)
        refused("/a\u0000b", "is not a valid path.")
        fileSystem.createDirectories("/work/dir".toPath())
        refused("/work/dir", "/work/dir is a directory; send its files one by one.")
        file("/work/real.png", png)
        fileSystem.allowSymlinks = true
        fileSystem.createSymlink("/work/link.png".toPath(), "/work/real.png".toPath())
        refused("/work/link.png", "/work/link.png is a symbolic link; send the file it points to by its own path.")
        file("/work/empty.txt", ByteArray(0))
        refused("/work/empty.txt", "/work/empty.txt is empty.")
        file("/work/big.png", ByteArray(2000))
        refused("/work/big.png", "/work/big.png is larger than the 1 KB the organization allows per file.")
        file("/work/doc.pdf", png)
        refused("/work/doc.pdf", "/work/doc.pdf is not a type the organization takes (PNG, text).")
        file("/work/blob", byteArrayOf(0, 1, 2))
        refused("/work/blob", "/work/blob is not a type the organization takes (PNG, text).")
        file("/work/plain", "text".encodeToByteArray())
        val pngOnly = mapOf(policy to (200 to policyOf(types = "\"image/png\"")))
        refused("/work/plain", "is not a type the organization takes (PNG).", pngOnly)
        file("/work/a.png", ByteArray(900) { 1 })
        file("/work/b.png", ByteArray(900) { 2 })
        console.err.clear()
        assertEquals(64, sendWith(mapOf(policy to (200 to policyOf(message = 1024))), "/work/a.png", "/work/b.png"))
        assertErr("These files together are 1 KB; the organization allows 1 KB per message.")
        assertFalse(sent().any { it.contains("/files?") }, "Nothing is uploaded once a file is refused")
    }

    @Test
    fun anUploadIsRetriedUnderOneKeyWhenTheServerMayStillTakeIt() {
        file("/work/shot.png", png)
        val live = (served + mapOf(orgs(acme), send to (202 to input))).toMutableMap()
        live[upload("shot.png")] = 503 to "{}"
        val answers = ArrayDeque(
            listOf(
                408 to "{}",
                429 to """{"error":{"type":"rate_limited","message":"slow"}}""",
                201 to stored("f1", "shot.png", png),
            ),
        )
        onSleep = { live[upload("shot.png")] = answers.removeFirst() }
        signIn("/home/maya/.config/ostrom")
        val code = cli(environment = withServer, api = serverOf(live)).run(
            arrayOf("cloud", "send", "cs1", "", "--file", "/work/shot.png"),
        )
        assertEquals(0, code)
        val keys = requests.map { it.second }.filter { it.path.contains("/files?") }.map { it.idempotencyKey }
        assertEquals(4, keys.size)
        assertEquals(1, keys.toSet().size, "Every retry carries the same key: $keys")
        assertEquals(listOf(1_000L, 2_000L, 4_000L), waits)
        assertErr("Uploading /work/shot.png failed; retrying.")
    }

    @Test
    fun anUploadStopsAfterItsLastTryOrAtOnceOnARefusal() {
        file("/work/shot.png", png)
        assertEquals(69, sendWith(mapOf(upload("shot.png") to (500 to "{}")), "/work/shot.png"))
        assertEquals(4, waits.size, "Five tries in all")
        fun refusal(type: String, status: Int, text: String, code: Int, retried: Boolean = false) {
            console.err.clear()
            waits.clear()
            val body = """{"error":{"type":"$type","message":"The server says $type."}}"""
            assertEquals(code, sendWith(mapOf(upload("shot.png") to (status to body)), "/work/shot.png"))
            assertErr(text)
            assertEquals(retried, waits.isNotEmpty(), "$type retried: $retried")
        }
        refusal("file_too_large", 413, "/work/shot.png is larger than the 1 KB the organization allows per file.", 64)
        refusal("file_type_not_allowed", 415, "(PNG, text); its content decides, not its name.", 64)
        refusal("upload_quota", 429, "The server says upload_quota.", 77)
        refusal("file_storage_not_configured", 409, FILES_OFF, 77)
        refusal("file_storage_unavailable", 503, "file storage did not answer; send /work/shot.png again", 69, true)
        refusal("empty_file", 400, "/work/shot.png is empty.", 64)
        refusal("invalid_file_name", 400, "The server does not accept the name of /work/shot.png; rename it.", 64)
        refusal("idempotency_conflict", 422, "conflicted with an earlier one; send it again.", 64)
        refusal("forbidden", 403, "The server says forbidden.", 77)
        assertFalse(sent().any { it.startsWith(send) })
    }

    @Test
    fun theServerMustHaveStoredTheBytesSent() {
        file("/work/shot.png", png)
        val other = stored("f1", "shot.png", png, sha = "00".repeat(32))
        assertEquals(1, sendWith(mapOf(upload("shot.png") to (201 to other)), "/work/shot.png"))
        assertErr("The server stored other bytes than /work/shot.png; the message was not sent.")
        console.err.clear()
        val shorter = stored("f1", "shot.png", png).replace("\"size\":4", "\"size\":3")
        assertEquals(1, sendWith(mapOf(upload("shot.png") to (201 to shorter)), "/work/shot.png"))
        console.err.clear()
        assertEquals(69, sendWith(mapOf(upload("shot.png") to (201 to "{}")), "/work/shot.png"))
        assertErr("the upload of /work/shot.png cannot be read")
        assertFalse(sent().any { it.startsWith(send) })
    }

    @Test
    fun aMessageRefusedForItsFilesSaysWhy() {
        file("/work/shot.png", png)
        fun refusedFor(type: String, status: Int, text: String) {
            console.err.clear()
            val routes = mapOf(
                upload("shot.png") to (201 to stored("f1", "shot.png", png)),
                send to (status to """{"error":{"type":"$type","message":"no"}}"""),
            )
            assertEquals(64, sendWith(routes, "/work/shot.png"))
            assertErr(text)
        }
        refusedFor("file_unavailable", 409, "an attached file is no longer available")
        refusedFor("files_too_large", 413, "its files together exceed the organization's limit per message.")
        refusedFor("files_unsupported", 400, "this server does not take files.")
        refusedFor("invalid_files", 400, "the attached files are not valid for this session.")
        refusedFor("session_not_running", 409, "no")
    }

    @Test
    fun typesAndSizesReadAsTeamCodesDo() {
        assertEquals("image/png", CloudFileTypes.forName("Shot.PNG"))
        assertEquals("text/markdown", CloudFileTypes.forName("notes.markdown"))
        assertNull(CloudFileTypes.forName("png"))
        assertNull(CloudFileTypes.forName("archive.zip"))
        assertNull(CloudFileTypes.forName("Makefile"))
        assertTrue(CloudFileTypes.isText("héllo".encodeToByteArray()))
        assertFalse(CloudFileTypes.isText(byteArrayOf(0x41, 0)))
        assertFalse(CloudFileTypes.isText(byteArrayOf(0xC3.toByte())))
        assertEquals("512 B", sizeLabel(512))
        assertEquals("245 KB", sizeLabel(245 * 1024L))
        assertEquals("1.2 MB", sizeLabel(1_258_291))
        assertNull(filesRefusal("session_not_running"))
        assertNull(filesRefusal(null))
    }
}
