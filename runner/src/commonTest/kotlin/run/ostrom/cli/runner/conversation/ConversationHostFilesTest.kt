// SPDX-License-Identifier: Apache-2.0
// TeamCode's CloudAgentTest file cases, one scenario per test, on the real file system: long by nature.
@file:Suppress("LongMethod")

package run.ostrom.cli.runner.conversation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import run.ostrom.cli.platform.EnvironmentImpl
import run.ostrom.cli.platform.OperatingSystem
import run.ostrom.cli.platform.pathStatus
import run.ostrom.cli.platform.symbolicLink
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock

class ConversationHostFilesTest {
    private class FakeProcess : AgentProcess {
        val written = mutableListOf<String>()
        val output = Channel<String?>(Channel.UNLIMITED)
        var exit: Int? = null
        override suspend fun writeLine(line: String): Boolean {
            if (exit != null) return false
            written += line
            return true
        }
        override suspend fun readLine(maxLine: Int) = if (exit != null) null else output.receive()
        override fun closeInput() = Unit
        override fun exitCode() = exit
        override suspend fun stop(graceMillis: Long): Int {
            if (exit == null) {
                exit = 0
                output.trySend(null)
            }
            return 0
        }
    }

    private class MemoryState : AgentStateStore {
        var value: AgentState? = null
        override suspend fun read() = value
        override suspend fun write(state: AgentState) {
            value = state
        }
        override suspend fun clear() {
            value = null
        }
    }

    /** A runner channel that serves message files, counting the downloads, and records what the host announced. */
    private class FileChannel : AgentChannel {
        val inputs = Channel<List<RunnerInputView>>(Channel.UNLIMITED)
        val acks = mutableListOf<RunnerInputAckView>()
        val uploaded = mutableListOf<RunnerEventView>()
        val bytes = mutableMapOf<String, ByteArray>()
        var downloads = 0
        var failure: Exception? = null
        val announced = mutableListOf<Boolean>()
        override suspend fun agent() = RunnerAgentView("claude")
        override suspend fun events(events: List<RunnerEventView>): RunnerEventsAckView {
            uploaded += events
            return RunnerEventsAckView(events.last().sequence, uploaded.size.toLong())
        }
        override suspend fun poll(waitMillis: Long, desiredState: String, accepts: RunnerAccepts): RunnerInputsView {
            announced += accepts.files
            return RunnerInputsView(inputs.receive())
        }
        override suspend fun acknowledge(acks: List<RunnerInputAckView>) {
            this.acks += acks
        }
        override suspend fun file(fileId: String, maxBytes: Long, receive: (ByteArray, Int) -> Unit) {
            downloads++
            failure?.let { throw it }
            val content = bytes.getValue(fileId)
            // Two chunks, as the channel streams them.
            val half = content.size / 2
            receive(content.copyOfRange(0, half), half)
            receive(content.copyOfRange(half, content.size), content.size - half)
        }
        fun serve(
            id: String,
            name: String,
            content: ByteArray,
            type: String = "image/png",
            sha256: String = content.toByteString().sha256().hex(),
        ): RunnerFileView {
            bytes[id] = content
            return RunnerFileView(id, name, content.size.toLong(), type, sha256)
        }
    }

    /** A fresh directory standing for the user's temporary directory, and another for the session's working one. */
    private class Scratch {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ostrom-files-test-${Random.nextLong().toULong()}"
        val temporary = (root / "tmp").also { FileSystem.SYSTEM.createDirectories(it) }
        val workspace = (root / "workspace").also { FileSystem.SYSTEM.createDirectories(it) }
        fun close() = FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
    }

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val windows = EnvironmentImpl().operatingSystem == OperatingSystem.WINDOWS

    private fun TestScope.fileHost(
        channel: FileChannel,
        scratch: Scratch,
        started: MutableList<Pair<AgentCommand, FakeProcess>>,
        uploads: SessionUploads?,
    ) = ConversationHost(
        channel,
        { command, _, _ -> FakeProcess().also { started += command to it } },
        MemoryState(),
        scratch.workspace.toString(),
        "1.0",
        now = { testScheduler.currentTime },
        uploads = uploads,
    )

    private suspend fun until(condition: () -> Boolean) {
        repeat(1_000) {
            if (condition()) return
            delay(100)
        }
        fail("Condition not reached")
    }

    private val rejected = CloudInputStatuses.REJECTED

    private fun message(id: String, text: String?, vararg files: RunnerFileView, attempt: Int = 1) =
        RunnerInputView(id, CloudInputKinds.MESSAGE, text, attempt, files.toList())

    private fun textOf(line: String): String {
        val content = Json.parseToJsonElement(line).jsonObject["message"]!!.jsonObject["content"]!!.jsonArray
        return (content.single().jsonObject["text"] as JsonPrimitive).content
    }

    private fun remote(scratch: Scratch, now: () -> Long = { Clock.System.now().toEpochMilliseconds() }) =
        SessionUploads.remote("s-1", "501", scratch.temporary, now)

    @Test
    fun messageFilesLandInTheUserTemporaryDirectoryPrivatelyAndTheAgentReadsTheirPath() = runTest {
        val scratch = Scratch()
        try {
            val channel = FileChannel()
            val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
            val uploads = remote(scratch)
            val job = launch { fileHost(channel, scratch, started, uploads).run() }
            val file = channel.serve("f1", "shot.png", png)
            channel.inputs.send(listOf(message("in-1", "Look at this", file)))
            until { channel.acks.isNotEmpty() }
            assertEquals(CloudInputStatuses.CONSUMED, channel.acks.single().status)
            assertEquals(true, channel.announced.first(), "The host announces files once its directory is ready")

            val directory = assertNotNull(uploads.directory)
            assertEquals(FileSystem.SYSTEM.canonicalize(scratch.temporary / "ostrom-501" / "s-1"), directory)
            val written = directory / "f1-shot.png"
            assertContentEquals(png, FileSystem.SYSTEM.read(written) { readByteArray() })
            assertTrue(FileSystem.SYSTEM.listRecursively(scratch.workspace).toList().isEmpty(), "Not in the workdir")
            if (!windows) {
                assertEquals(0x1C0, pathStatus((scratch.temporary / "ostrom-501").toString())!!.mode, "User dir 0700")
                assertEquals(0x1C0, pathStatus(directory.toString())!!.mode, "The session's directory is 0700")
                assertEquals(0x180, pathStatus(written.toString())!!.mode, "The file is 0600")
            }

            // The stream-json line the agent reads: the text, then one line per file with its absolute path.
            val (command, process) = started.single()
            val expected = "Look at this\n\n[Attached file: $written (image/png, 8 bytes)]"
            assertEquals(expected, textOf(process.written.single()))
            assertTrue(written.isAbsolute)
            // Claude Code reads the directory without a permission request.
            val addDir = command.arguments.indexOf("--add-dir")
            assertTrue(addDir >= 0, command.arguments.toString())
            assertEquals(directory.toString(), command.arguments[addDir + 1])
            job.cancel()
        } finally {
            scratch.close()
        }
    }

    @Test
    fun aRedeliveredMessageWritesItsFilesOnce() = runTest {
        val scratch = Scratch()
        try {
            val channel = FileChannel()
            val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
            val job = launch { fileHost(channel, scratch, started, remote(scratch)).run() }
            val input = message("in-1", "", channel.serve("f1", "a.txt", "hello".encodeToByteArray(), "text/plain"))
            channel.inputs.send(listOf(input))
            until { channel.acks.size == 1 }
            channel.inputs.send(listOf(input.copy(attempt = 2)))
            until { channel.acks.size == 2 }
            assertEquals(channel.acks[0], channel.acks[1], "Acknowledged again")
            assertEquals(1, channel.downloads, "Downloaded and written once")
            assertEquals(1, started.single().second.written.size, "Handed to the agent once")
            assertEquals(
                "[Attached file: ",
                textOf(started.single().second.written.single()).take(16),
                "A message of files alone carries their lines only",
            )
            // The same file in another message: written once per execution, its line given again.
            val again = channel.serve("f1", "a.txt", "hello".encodeToByteArray())
            channel.inputs.send(listOf(message("in-2", null, again)))
            until { channel.acks.size == 3 }
            assertEquals(CloudInputStatuses.CONSUMED, channel.acks[2].status)
            assertEquals(1, channel.downloads)
            channel.inputs.send(listOf(message("in-3", null)))
            until { channel.acks.size == 4 }
            assertEquals("invalid_input", channel.acks[3].reason, "No text and no file")
            job.cancel()
        } finally {
            scratch.close()
        }
    }

    @Test
    fun aFileThatDoesNotMatchOrCannotBeDownloadedRejectsTheMessageAndStartsNoTurn() = runTest {
        val scratch = Scratch()
        try {
            val channel = FileChannel()
            val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
            val uploads = remote(scratch)
            val job = launch { fileHost(channel, scratch, started, uploads).run() }
            val forged = channel.serve("f1", "shot.png", png, sha256 = "0".repeat(64))
            channel.inputs.send(listOf(message("in-1", "Look", forged)))
            until { channel.acks.size == 1 }
            assertEquals(RunnerInputAckView("in-1", rejected, "file_integrity_failed"), channel.acks[0])
            assertTrue(FileSystem.SYSTEM.list(uploads.directory!!).isEmpty(), "The mismatching file is removed")

            // Longer than announced: refused while it streams.
            val long = channel.serve("f1b", "long.png", png).copy(size = 3)
            channel.inputs.send(listOf(message("in-1b", "Look", long)))
            until { channel.acks.size == 2 }
            assertEquals("file_integrity_failed", channel.acks[1].reason)
            assertTrue(FileSystem.SYSTEM.list(uploads.directory!!).isEmpty(), "The partial file is removed")

            channel.failure = IllegalStateException("network")
            channel.inputs.send(listOf(message("in-2", "Again", channel.serve("f2", "b.png", png))))
            until { channel.acks.size == 3 }
            assertEquals(RunnerInputAckView("in-2", rejected, "file_download_failed"), channel.acks[2])
            assertEquals(2 + ConversationHost.FILE_ATTEMPTS, channel.downloads, "Retried, then the message is rejected")

            channel.failure = RunnerChannelException(503, "file_storage_unavailable")
            channel.inputs.send(listOf(message("in-2b", "Again", channel.serve("f2b", "b.png", png))))
            until { channel.acks.size == 4 }
            assertEquals("file_download_failed", channel.acks[3].reason)

            channel.failure = RunnerChannelException(404, "not_found")
            channel.inputs.send(listOf(message("in-3", "Once more", channel.serve("f3", "c.png", png))))
            until { channel.acks.size == 5 }
            assertEquals(RunnerInputAckView("in-3", rejected, "file_unavailable"), channel.acks[4])

            channel.failure = RunnerChannelException(410, "file_expired")
            channel.inputs.send(listOf(message("in-4", "Late", channel.serve("f4", "d.png", png))))
            until { channel.acks.size == 6 }
            assertEquals("file_download_failed", channel.acks[5].reason, "Refused: retrying cannot help")
            assertTrue(started.isEmpty(), "No agent process, so no turn")
            assertTrue(channel.uploaded.none { it.type == CloudEventTypes.TURN }, "No turn event")
            job.cancel()
        } finally {
            scratch.close()
        }
    }

    @Test
    fun aFileNameOrIdCannotLeaveTheUploadsDirectory() = runTest {
        val scratch = Scratch()
        try {
            val channel = FileChannel()
            val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
            val uploads = remote(scratch)
            val job = launch { fileHost(channel, scratch, started, uploads).run() }
            val names =
                listOf("../../workspace/evil.png", "..\\..\\evil.png", "/etc/passwd", "..", "-rf", ".hidden\u0000.png")
            names.forEachIndexed { index, name ->
                channel.inputs.send(listOf(message("in-$index", "x", channel.serve("f$index", name, png))))
            }
            until { channel.acks.size == names.size }
            assertTrue(channel.acks.all { it.status == CloudInputStatuses.CONSUMED }, channel.acks.toString())
            val directory = uploads.directory!!
            val files = FileSystem.SYSTEM.listRecursively(scratch.root)
                .filter { FileSystem.SYSTEM.metadata(it).isRegularFile }.toList()
            assertEquals(names.size, files.size)
            assertTrue(files.all { FileSystem.SYSTEM.canonicalize(it).parent == directory }, "In the directory: $files")
            assertEquals(
                listOf("f0-evil.png", "f1-evil.png", "f2-passwd", "f3-file", "f4-rf", "f5-hidden.png"),
                files.map { it.name }.sorted(),
            )
            assertTrue(FileSystem.SYSTEM.listRecursively(scratch.workspace).toList().isEmpty())

            // An id that is a path is refused, and so are a negative size and a malformed SHA-256.
            channel.inputs.send(listOf(message("in-id", "x", channel.serve("../f", "a.png", png))))
            channel.inputs.send(listOf(message("in-size", "x", channel.serve("fs", "a.png", png).copy(size = -1))))
            channel.inputs.send(listOf(message("in-sha", "x", channel.serve("fh", "a.png", png, sha256 = "x"))))
            until { channel.acks.size == names.size + 3 }
            assertEquals(List(3) { "invalid_file" }, channel.acks.takeLast(3).map { it.reason })
            if (!windows) {
                // A link planted at the target is never followed.
                val outside = scratch.workspace / "target.png"
                assertTrue(symbolicLink(outside.toString(), (directory / "fl-link.png").toString()))
                channel.inputs.send(listOf(message("in-link", "x", channel.serve("fl", "link.png", png))))
                until { channel.acks.size == names.size + 4 }
                assertEquals("file_write_failed", channel.acks.last().reason)
                assertFalse(FileSystem.SYSTEM.exists(outside), "Nothing written through the link")
            }
            job.cancel()
        } finally {
            scratch.close()
        }
    }

    @Test
    fun aMessageFileSurvivesAPauseAndAResumeOnTheRemoteHost() = runTest {
        val scratch = Scratch()
        try {
            val root = scratch.temporary / "ostrom-501"
            val first = FileChannel()
            val host = fileHost(first, scratch, mutableListOf(), remote(scratch))
            val job = launch { host.run() }
            first.inputs.send(listOf(message("in-1", "x", first.serve("f1", "a.png", png))))
            until { first.acks.isNotEmpty() }
            // The pause: the agent drains, the host stops (as the runner loop does on its way out).
            host.drain(null)
            host.stop()
            job.cancel()
            assertTrue(FileSystem.SYSTEM.exists(root / "s-1" / "f1-a.png"), "A pause keeps the file")

            // The resume: a new execution of the same session finds it, and writes the next file next to it.
            val second = FileChannel()
            val resumed = fileHost(second, scratch, mutableListOf(), remote(scratch))
            val resumedJob = launch { resumed.run() }
            second.inputs.send(listOf(message("in-2", "y", second.serve("f2", "b.png", png))))
            until { second.acks.isNotEmpty() }
            assertTrue(FileSystem.SYSTEM.exists(root / "s-1" / "f1-a.png"), "The resumed conversation reads it again")
            assertTrue(FileSystem.SYSTEM.exists(root / "s-1" / "f2-b.png"))
            // A message an earlier execution received but never acknowledged: the same bytes are reused, not rewritten.
            second.inputs.send(listOf(message("in-1", "x", second.serve("f1", "a.png", png), attempt = 2)))
            until { second.acks.size == 2 }
            assertEquals(CloudInputStatuses.CONSUMED, second.acks[1].status)
            assertEquals(1, second.downloads, "Reused from the disk")
            // Other bytes under the same name are never reused.
            second.inputs.send(listOf(message("in-3", "x", second.serve("f1", "a.png", byteArrayOf(1, 2, 3)))))
            until { second.acks.size == 3 }
            assertEquals(CloudInputStatuses.CONSUMED, second.acks[2].status, "Already written by this execution")
            resumed.stop()
            resumedJob.cancel()
            assertTrue(FileSystem.SYSTEM.exists(root / "s-1" / "f2-b.png"), "Stopping without the end keeps the files")

            val third = FileChannel()
            val other = fileHost(third, scratch, mutableListOf(), remote(scratch))
            val otherJob = launch { other.run() }
            third.inputs.send(listOf(message("in-4", "x", third.serve("f2", "b.png", byteArrayOf(1, 2, 3)))))
            until { third.acks.isNotEmpty() }
            assertEquals("file_write_failed", third.acks.single().reason, "Other bytes than the file on disk")
            otherJob.cancel()
        } finally {
            scratch.close()
        }
    }

    @Test
    fun messageFilesAreRemovedWhenTheSessionEnds() = runTest {
        val scratch = Scratch()
        try {
            val root = scratch.temporary / "ostrom-501"
            val channel = FileChannel()
            val host = fileHost(channel, scratch, mutableListOf(), remote(scratch))
            val job = launch { host.run() }
            channel.inputs.send(listOf(message("in-1", "x", channel.serve("f1", "a.png", png))))
            until { channel.acks.isNotEmpty() }
            assertTrue(FileSystem.SYSTEM.exists(root / "s-1" / "f1-a.png"))
            // The server terminated the session: the session's directory goes with it.
            host.sessionEnded()
            host.stop()
            job.cancel()
            assertFalse(FileSystem.SYSTEM.exists(root / "s-1"), "Removed at the end of the session")

            // A fenced host never removes anything, even for a session that ends: the execution that fenced it may use
            // the directory.
            val fencedChannel = FileChannel()
            val fencedHost = fileHost(fencedChannel, scratch, mutableListOf(), remote(scratch))
            val fencedJob = launch { fencedHost.run() }
            fencedChannel.inputs.send(listOf(message("in-1", "x", fencedChannel.serve("f2", "b.png", png))))
            until { fencedChannel.acks.isNotEmpty() }
            fencedChannel.failure = RunnerChannelException(409, "execution_fenced")
            fencedChannel.inputs.send(listOf(message("in-2", "x", fencedChannel.serve("f3", "c.png", png))))
            until { fencedHost.fenced }
            fencedJob.join()
            fencedHost.sessionEnded()
            fencedHost.stop()
            assertTrue(FileSystem.SYSTEM.exists(root / "s-1" / "f2-b.png"), "A fenced host leaves the files")
            assertEquals(1, fencedChannel.acks.size, "and acknowledges nothing more")
        } finally {
            scratch.close()
        }
    }

    @Test
    fun aRevokedCredentialFencesTheHostWhileItDownloads() = runTest {
        val scratch = Scratch()
        try {
            val channel = FileChannel()
            val host = fileHost(channel, scratch, mutableListOf(), remote(scratch))
            val job = launch { host.run() }
            channel.failure = RunnerChannelException(401, null)
            channel.inputs.send(listOf(message("in-1", "x", channel.serve("f1", "a.png", png))))
            until { host.fenced }
            job.join()
            assertTrue(channel.acks.isEmpty())
        } finally {
            scratch.close()
        }
    }

    @Test
    fun uploadsOlderThanAWeekAreSweptAtStart() = runTest {
        val scratch = Scratch()
        try {
            val root = scratch.temporary / "ostrom-501"
            // Left by earlier executions: this session's own files, and another session's (which may have ended while
            // no host ran).
            FileSystem.SYSTEM.createDirectories(root / "s-1")
            FileSystem.SYSTEM.write(root / "s-1" / "old-a.txt") { writeUtf8("a") }
            FileSystem.SYSTEM.createDirectories(root / "s-2")
            FileSystem.SYSTEM.write(root / "s-2" / "old-b.txt") { writeUtf8("b") }
            assertTrue(remote(scratch).prepare {})
            assertTrue(FileSystem.SYSTEM.exists(root / "s-1" / "old-a.txt"), "This session's recent files stay")
            assertTrue(FileSystem.SYSTEM.exists(root / "s-2" / "old-b.txt"), "Another session's recent files stay")
            val later = Clock.System.now().toEpochMilliseconds() + SessionUploads.MAX_AGE_MILLIS + 60_000
            assertTrue(remote(scratch) { later }.prepare {})
            assertFalse(FileSystem.SYSTEM.exists(root / "s-2"), "Anything older than a week is removed")
            assertFalse(FileSystem.SYSTEM.exists(root / "s-1" / "old-a.txt"), "This session's too: a week at most")
            assertTrue(FileSystem.SYSTEM.exists(root / "s-1"), "and its directory is ready again")
        } finally {
            scratch.close()
        }
    }

    @Test
    fun aFileWrittenAWeekAgoIsSweptBeforeTheNextOneAndWrittenAgain() = runTest {
        val scratch = Scratch()
        try {
            var clock = Clock.System.now().toEpochMilliseconds()
            val uploads = remote(scratch) { clock }
            assertTrue(uploads.prepare {})
            val channel = FileChannel()
            val file = channel.serve("f1", "a.png", png)
            val first = uploads.write(file) { receive -> channel.file("f1", 8, receive) }
            FileSystem.SYSTEM.createDirectories(scratch.temporary / "ostrom-501" / "s-2")
            clock += SessionUploads.MAX_AGE_MILLIS + 60_000
            uploads.write(channel.serve("f2", "b.png", png)) { receive -> channel.file("f2", 8, receive) }
            assertFalse(FileSystem.SYSTEM.exists(scratch.temporary / "ostrom-501" / "s-2"), "Others swept too")
            assertFalse(FileSystem.SYSTEM.exists(first), "Swept: older than a week")
            assertEquals(first, uploads.write(file) { receive -> channel.file("f1", 8, receive) }, "Written again")
            assertEquals(3, channel.downloads)
        } finally {
            scratch.close()
        }
    }

    @Test
    fun anExecutionWritesAtMostItsShare() = runTest {
        val scratch = Scratch()
        try {
            FileSystem.SYSTEM.createDirectories(scratch.temporary / "pod")
            val uploads = SessionUploads(
                (scratch.temporary / "pod").toString(),
                null,
                SessionUploads.SHARED_FILE_MODE,
                maxBytes = 10,
            )
            assertTrue(uploads.prepare {})
            val channel = FileChannel()
            uploads.write(channel.serve("f1", "a.png", png)) { receive -> channel.file("f1", 8, receive) }
            val second = channel.serve("f2", "b.png", png)
            val full = runCatchingRefusal { uploads.write(second) { channel.file("f2", 8, it) } }
            assertEquals("uploads_full", full)
            uploads.clear()
            assertTrue(FileSystem.SYSTEM.list(scratch.temporary / "pod").isEmpty(), "The volume's content is removed")
            uploads.write(channel.serve("f2", "b.png", png)) { receive -> channel.file("f2", 8, receive) }
        } finally {
            scratch.close()
        }
    }

    @Test
    fun aHostWithoutAUsableUploadsDirectoryAnnouncesNoFiles() = runTest {
        val scratch = Scratch()
        try {
            // Another user's (or a planted) path where this user's directory should be: files are off, never there.
            FileSystem.SYSTEM.write(scratch.temporary / "ostrom-501") { writeUtf8("not a directory") }
            val channel = FileChannel()
            val logs = mutableListOf<String>()
            val host = ConversationHost(
                channel,
                { _, _, _ -> FakeProcess() },
                MemoryState(),
                scratch.workspace.toString(),
                "1.0",
                log = { logs += it },
                uploads = remote(scratch),
            )
            val job = launch { host.run() }
            channel.inputs.send(listOf(message("in-1", "x")))
            until { channel.acks.isNotEmpty() }
            assertEquals(false, channel.announced.first())
            assertTrue(logs.any { "message files are off" in it }, logs.toString())
            // A message with files reaching such a host anyway (an older server) is refused, never delivered alone.
            channel.inputs.send(listOf(message("in-2", "x", channel.serve("f1", "a.png", png))))
            until { channel.acks.size == 2 }
            assertEquals("files_unavailable", channel.acks[1].reason)
            job.cancel()

            // A pod whose volume is missing announces nothing either; no uploads at all neither.
            assertFalse(SessionUploads.pod((scratch.root / "missing").toString()).prepare {})
            val bare = FileChannel()
            val bareJob = launch { fileHost(bare, scratch, mutableListOf(), null).run() }
            bare.inputs.send(listOf(message("in-1", "x", bare.serve("f1", "a.png", png))))
            until { bare.acks.isNotEmpty() }
            assertTrue(bare.announced.none { it }, "Never announced")
            assertEquals("files_unavailable", bare.acks.single().reason)
            bareJob.cancel()
            val ended = fileHost(FileChannel(), scratch, mutableListOf(), null)
            ended.sessionEnded()
            ended.stop()
        } finally {
            scratch.close()
        }
    }

    @Test
    fun aPodWritesItsFilesInTheUploadsVolumeReadableByTheAgentsGroup() = runTest {
        val scratch = Scratch()
        try {
            val volume = (scratch.root / "uploads").also { FileSystem.SYSTEM.createDirectories(it) }
            // Left by an earlier container of the same pod: swept at start, whatever its age.
            FileSystem.SYSTEM.write(volume / "old.png") { writeUtf8("old") }
            val channel = FileChannel()
            val uploads = SessionUploads.pod(volume.toString())
            val job = launch { fileHost(channel, scratch, mutableListOf(), uploads).run() }
            channel.inputs.send(listOf(message("in-1", "x", channel.serve("f1", "a.png", png))))
            until { channel.acks.isNotEmpty() }
            val written = FileSystem.SYSTEM.canonicalize(volume) / "f1-a.png"
            assertTrue(FileSystem.SYSTEM.exists(written))
            assertFalse(FileSystem.SYSTEM.exists(volume / "old.png"))
            if (!windows) assertEquals(0x1A0, pathStatus(written.toString())!!.mode, "0640: read through the group")
            job.cancel()
        } finally {
            scratch.close()
        }
    }

    @Test
    fun aWriteChecksItsDirectoryAndTheBytesItGets() = runTest {
        val scratch = Scratch()
        try {
            val channel = FileChannel()
            val volume = (scratch.root / "uploads").also { FileSystem.SYSTEM.createDirectories(it) }
            val uploads = SessionUploads.pod(volume.toString())
            val file = channel.serve("f1", "a.png", png)
            assertEquals("files_unavailable", runCatchingRefusal { uploads.write(file) { channel.file("f1", 8, it) } })
            assertTrue(uploads.prepare {})
            val first = uploads.write(file) { channel.file("f1", 8, it) }
            // Removed meanwhile: written again.
            FileSystem.SYSTEM.delete(first)
            assertEquals(first, uploads.write(file) { channel.file("f1", 8, it) })
            assertEquals(2, channel.downloads)
            // Fewer bytes than announced.
            val short = channel.serve("f2", "b.png", png).copy(size = 9)
            val integrity = runCatchingRefusal { uploads.write(short) { channel.file("f2", 9, it) } }
            assertEquals("file_integrity_failed", integrity)
            // A cancelled download leaves nothing.
            val cancelled = channel.serve("f3", "c.png", png)
            val error = try {
                uploads.write(cancelled) { throw CancellationException("stopped") }
                null
            } catch (stopped: CancellationException) {
                stopped
            }
            assertNotNull(error)
            assertFalse(FileSystem.SYSTEM.exists(uploads.directory!! / "f3-c.png"))
            // The directory gone after prepare: nothing can be written.
            FileSystem.SYSTEM.deleteRecursively(volume)
            val gone = channel.serve("f4", "d.png", png)
            assertEquals("file_write_failed", runCatchingRefusal { uploads.write(gone) { channel.file("f4", 8, it) } })
            if (!windows) {
                // The directory replaced by a link elsewhere: the file would land outside, so it is refused, removed.
                val elsewhere = (scratch.root / "elsewhere").also { FileSystem.SYSTEM.createDirectories(it) }
                assertTrue(symbolicLink(elsewhere.toString(), volume.toString()))
                val moved = channel.serve("f5", "e.png", png)
                assertEquals(
                    "file_write_failed",
                    runCatchingRefusal { uploads.write(moved) { channel.file("f5", 8, it) } },
                )
                assertTrue(FileSystem.SYSTEM.list(elsewhere).isEmpty())
            }
        } finally {
            scratch.close()
        }
    }

    @Test
    fun aFileOnDiskWithOtherBytesOfTheSameSizeIsNeverReused() = runTest {
        val scratch = Scratch()
        try {
            val channel = FileChannel()
            val uploads = remote(scratch)
            assertTrue(uploads.prepare {})
            FileSystem.SYSTEM.write(uploads.directory!! / "f1-a.png") { write(ByteArray(8)) }
            val file = channel.serve("f1", "a.png", png)
            assertEquals("file_write_failed", runCatchingRefusal { uploads.write(file) { channel.file("f1", 8, it) } })
            assertEquals(0, channel.downloads)
        } finally {
            scratch.close()
        }
    }

    @Test
    fun aPodVolumeMustBeARealDirectory() {
        val scratch = Scratch()
        try {
            FileSystem.SYSTEM.write(scratch.root / "file") { writeUtf8("x") }
            assertFalse(SessionUploads.pod((scratch.root / "file").toString()).prepare {})
            if (!windows) {
                assertTrue(symbolicLink(scratch.workspace.toString(), (scratch.root / "link").toString()))
                assertFalse(SessionUploads.pod((scratch.root / "link").toString()).prepare {})
            }
            val uploads = SessionUploads.remote("s-1", "a-b_c", scratch.temporary)
            assertTrue(uploads.prepare {})
            assertEquals(FileSystem.SYSTEM.canonicalize(scratch.temporary / "ostrom-a-bc" / "s-1"), uploads.directory)
        } finally {
            scratch.close()
        }
    }

    @Test
    fun aPausingHostDownloadsNothingAndAForbiddenDownloadFencesIt() = runTest {
        val scratch = Scratch()
        try {
            val channel = FileChannel()
            val host = fileHost(channel, scratch, mutableListOf(), remote(scratch))
            val job = launch { host.run() }
            host.drain(null)
            channel.inputs.send(listOf(message("in-1", "x", channel.serve("f1", "a.png", png))))
            channel.inputs.send(listOf(message("in-2", "x")))
            until { channel.announced.size == 3 }
            assertTrue(channel.acks.none { it.id == "in-1" }, "Left for the next execution")
            assertEquals(0, channel.downloads)
            host.resumeAfterDrain()
            channel.failure = RunnerChannelException(403, "forbidden")
            channel.inputs.send(listOf(message("in-3", "x", channel.serve("f3", "c.png", png))))
            until { host.fenced }
            job.join()
            assertTrue(channel.acks.none { it.id == "in-3" })
        } finally {
            scratch.close()
        }
    }

    @Test
    fun namesAreCleanedOnceMoreAndTheLineSaysTheTypeTheServerGave() {
        assertEquals("a.png", SessionUploads.safeName("dir/sub\\a.png"))
        assertEquals("file", SessionUploads.safeName("..."))
        assertEquals("ab", SessionUploads.safeName("a<>:\"|?*\u007Fb"))
        val emoji = "😀"
        assertEquals(emoji.repeat(37), SessionUploads.safeName(emoji.repeat(40)), "Cut whole at 150 bytes")
        assertEquals("ab", SessionUploads.safeName("a\uD800b"), "A lone surrogate is dropped")
        assertEquals("a", SessionUploads.safeName("a\uD800"))
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "f1-a"
        val file = RunnerFileView("f1", "a", 3, "text/x;evil", "0".repeat(64))
        assertEquals("[Attached file: $path (application/octet-stream, 3 bytes)]", SessionUploads.line(path, file))
        assertFailsWithMessage { SessionUploads.remote("../s", "501") }
    }

    private suspend fun runCatchingRefusal(block: suspend () -> Path): String? = try {
        block()
        null
    } catch (refused: SessionUploads.Refused) {
        refused.reason
    }

    private fun assertFailsWithMessage(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        fail("Expected a refusal")
    }
}
