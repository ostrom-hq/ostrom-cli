// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import okio.ByteString.Companion.encodeUtf8
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudInstallTest {
    private val fileSystem = FakeFileSystem()
    private val root = "/home/agent/.claude/skills".toPath()
    private val executables = mutableListOf<Path>()

    private fun installer(alwaysReplace: Boolean = false, entryFile: String? = DeliveryInstaller.SKILL_FILE) =
        DeliveryInstaller(root, fileSystem, { executables += it }, entryFile, alwaysReplace = alwaysReplace)

    private class Item(val name: String, val files: Map<String, Pair<String, String>>) {
        val digest = DeliveredItems.digestOf(
            files.entries.sortedBy { it.key }.joinToString("") { (path, file) ->
                val bytes = file.first.encodeUtf8()
                DeliveredItems.line(path, file.second, bytes.size.toLong(), bytes.sha256().hex())
            },
        )
        val entry get() = InstallMessage.Item(name, "1", digest)
    }

    private val review = Item(
        "review",
        mapOf("SKILL.md" to ("# Review" to "file"), "bin/run" to ("echo" to "executable")),
    )

    /** Streams [item] as the runner does, in chunks of [chunk] characters. */
    private fun DeliveryInstaller.stream(item: Item, chunk: Int = 3, digest: String = item.digest) {
        handle(InstallMessage(InstallMessage.BEGIN, name = item.name))
        for ((path, file) in item.files) {
            val parts = file.first.chunked(chunk)
            parts.forEachIndexed { index, part ->
                val last = if (index == parts.lastIndex) InstallMessage.LAST else null
                val mode = file.second.takeIf { index == 0 }
                handle(
                    InstallMessage(
                        InstallMessage.FILE,
                        path = path,
                        mode = mode,
                        data = part.encodeUtf8().base64(),
                        status = last,
                    ),
                )
            }
        }
        handle(InstallMessage(InstallMessage.COMMIT, name = item.name, digest = digest))
    }

    private fun List<InstallMessage>.statuses() =
        filter { it.t == InstallMessage.RESULT }.associate { it.name to it.status }

    @Test
    fun aNewSkillIsAskedForVerifiedAndMovedIn() {
        val installer = installer()
        val needs = installer.handle(InstallMessage(InstallMessage.MANIFEST, items = listOf(review.entry)))
        assertEquals(listOf(InstallMessage.NEED, InstallMessage.READY), needs.map { it.t })
        assertEquals("review", needs.first().name)
        installer.stream(review)
        // An item begun twice is streamed once.
        installer.handle(InstallMessage(InstallMessage.BEGIN, name = "review"))
        val done = installer.handle(InstallMessage(InstallMessage.END))
        assertEquals(mapOf<String?, String?>("review" to InstallMessage.INSTALLED), done.statuses())
        assertEquals(InstallMessage.DONE, done.last().t)
        assertEquals("# Review", fileSystem.read(root / "review/SKILL.md") { readUtf8() })
        assertEquals("echo", fileSystem.read(root / "review/bin/run") { readUtf8() })
        assertEquals("${review.digest}\n1\n", fileSystem.read(root / "review/.ostrom-skill") { readUtf8() })
        assertEquals(
            listOf("/home/agent/.claude/.ostrom-staging-skills/review/bin/run"),
            executables.map { it.toString() },
        )
        assertFalse(fileSystem.exists("/home/agent/.claude/.ostrom-staging-skills".toPath()))
    }

    @Test
    fun anInstalledSkillWithTheSameDigestIsKept() {
        installer().apply {
            handle(InstallMessage(InstallMessage.MANIFEST, items = listOf(review.entry)))
            stream(review)
            handle(InstallMessage(InstallMessage.END))
        }
        val again = installer()
        assertEquals(
            listOf(InstallMessage.READY),
            again.handle(InstallMessage(InstallMessage.MANIFEST, items = listOf(review.entry))).map { it.t },
        )
        assertEquals(
            mapOf<String?, String?>("review" to InstallMessage.UNCHANGED),
            again.handle(InstallMessage(InstallMessage.END)).statuses(),
        )
    }

    @Test
    fun hooksAreInstalledAgainAtEachStart() {
        installer(alwaysReplace = true).apply {
            handle(InstallMessage(InstallMessage.MANIFEST, items = listOf(review.entry)))
            stream(review)
            handle(InstallMessage(InstallMessage.END))
        }
        fileSystem.write(root / "review/bin/run") { writeUtf8("tampered") }
        val again = installer(alwaysReplace = true)
        assertEquals(
            InstallMessage.NEED,
            again.handle(InstallMessage(InstallMessage.MANIFEST, items = listOf(review.entry))).first().t,
        )
        again.stream(review)
        assertEquals(
            mapOf<String?, String?>("review" to InstallMessage.INSTALLED),
            again.handle(InstallMessage(InstallMessage.END)).statuses(),
        )
        assertEquals("echo", fileSystem.read(root / "review/bin/run") { readUtf8() })
    }

    @Test
    fun theMembersOwnFolderIsLeftAloneAndWhatIsNoLongerDeliveredIsRemoved() {
        fileSystem.createDirectories(root / "review")
        fileSystem.write(root / "review/SKILL.md") { writeUtf8("mine") }
        fileSystem.createDirectories(root / "old")
        fileSystem.write(root / "old/.ostrom-skill") { writeUtf8("sha256:x\n1\n") }
        fileSystem.createDirectories(root / "kept")
        fileSystem.write(root / ".hidden") { writeUtf8("x") }
        val installer = installer()
        assertEquals(
            listOf(InstallMessage.READY),
            installer.handle(
                InstallMessage(
                    InstallMessage.MANIFEST,
                    items = listOf(review.entry, InstallMessage.Item("Bad Name", "1", "d")),
                ),
            ).map { it.t },
        )
        val done = installer.handle(InstallMessage(InstallMessage.END))
        assertEquals(
            mapOf<String?, String?>("review" to InstallMessage.CONFLICT, "old" to InstallMessage.REMOVED),
            done.statuses(),
        )
        assertEquals("mine", fileSystem.read(root / "review/SKILL.md") { readUtf8() })
        assertFalse(fileSystem.exists(root / "old"))
        assertTrue(fileSystem.exists(root / "kept"))
    }

    @Test
    fun anEmptyManifestWithoutAFolderInstallsNothing() {
        val installer = installer()
        installer.handle(InstallMessage(InstallMessage.MANIFEST))
        assertEquals(listOf(InstallMessage.DONE), installer.handle(InstallMessage(InstallMessage.END)).map { it.t })
    }

    @Test
    fun anItemThatDoesNotMatchItsDigestOrBoundsFails() {
        val noEntry = Item("plain", mapOf("README.md" to ("x" to "file")))
        val other = Item("other", mapOf("SKILL.md" to ("y" to "file")))
        val badPath = Item("path", mapOf("SKILL.md" to ("z" to "file")))
        val installer = installer()
        installer.handle(
            InstallMessage(
                InstallMessage.MANIFEST,
                items = listOf(
                    review.entry,
                    noEntry.entry,
                    other.entry,
                    badPath.entry,
                    InstallMessage.Item("tampered", "1", "sha256:t"),
                ),
            ),
        )
        installer.stream(review, digest = "sha256:wrong")
        // Files that differ from the digest the manifest gave.
        installer.handle(InstallMessage(InstallMessage.BEGIN, name = "tampered"))
        installer.handle(
            InstallMessage(InstallMessage.FILE, path = "SKILL.md", data = "eA==", status = InstallMessage.LAST),
        )
        installer.handle(InstallMessage(InstallMessage.COMMIT, name = "tampered", digest = "sha256:t"))
        installer.stream(noEntry)
        installer.handle(InstallMessage(InstallMessage.BEGIN, name = "other"))
        installer.handle(InstallMessage(InstallMessage.COMMIT, name = "other", digest = other.digest))
        installer.handle(InstallMessage(InstallMessage.BEGIN, name = "path"))
        installer.handle(
            InstallMessage(InstallMessage.FILE, path = "../x", data = "eA==", status = InstallMessage.LAST),
        )
        // Lines for an item that was not begun, or failed, are ignored.
        installer.handle(InstallMessage(InstallMessage.FILE, path = "SKILL.md", data = "eA=="))
        installer.handle(InstallMessage(InstallMessage.COMMIT, name = "path"))
        installer.handle(InstallMessage(InstallMessage.BEGIN, name = "path"))
        installer.handle(InstallMessage(InstallMessage.BEGIN, name = "unlisted"))
        installer.handle(InstallMessage("unknown"))
        val statuses = installer.handle(InstallMessage(InstallMessage.END)).statuses()
        assertEquals(
            mapOf<String?, String?>(
                "review" to "failed",
                "tampered" to "failed",
                "plain" to "failed",
                "other" to "failed",
                "path" to "failed",
            ),
            statuses,
        )
        assertFalse(fileSystem.exists("/home/agent/.claude/.ostrom-staging-skills".toPath()))
    }

    @Test
    fun hooksNeedNoEntryFileAndAChunkWithoutDataFails() {
        val hook = Item("guard", mapOf("run.sh" to ("echo" to "executable")))
        val installer = installer(entryFile = null)
        installer.handle(InstallMessage(InstallMessage.MANIFEST, items = listOf(hook.entry, review.entry)))
        installer.stream(hook)
        installer.handle(InstallMessage(InstallMessage.BEGIN, name = "review"))
        installer.handle(InstallMessage(InstallMessage.FILE, path = "SKILL.md"))
        val statuses = installer.handle(InstallMessage(InstallMessage.END)).statuses()
        assertEquals(mapOf<String?, String?>("guard" to "installed", "review" to "failed"), statuses)
    }

    @Test
    fun linesWithoutANameAPathOrBase64FailOrAreIgnored() {
        val a = Item("a", mapOf("SKILL.md" to ("x" to "file")))
        val b = Item("b", mapOf("SKILL.md" to ("y" to "file")))
        val c = Item("c", mapOf("SKILL.md" to ("abcdef" to "file"), "two.md" to ("ghijkl" to "file")))
        val installer = installer()
        installer.handle(InstallMessage(InstallMessage.MANIFEST, items = listOf(a.entry, b.entry, c.entry)))
        installer.handle(InstallMessage(InstallMessage.BEGIN))
        installer.handle(InstallMessage(InstallMessage.BEGIN, name = "a"))
        installer.handle(InstallMessage(InstallMessage.FILE, data = "eA=="))
        installer.handle(InstallMessage(InstallMessage.BEGIN, name = "b"))
        installer.handle(InstallMessage(InstallMessage.FILE, path = "SKILL.md", data = "!!"))
        // A file left without its last chunk, then another: each file's chunks are its own.
        installer.handle(InstallMessage(InstallMessage.BEGIN, name = "c"))
        installer.handle(InstallMessage(InstallMessage.FILE, path = "SKILL.md", data = "YWJj"))
        installer.handle(InstallMessage(InstallMessage.FILE, path = "two.md", data = "Z2hp"))
        val last = InstallMessage.LAST
        installer.handle(InstallMessage(InstallMessage.FILE, path = "two.md", data = "amts", status = last))
        installer.handle(InstallMessage(InstallMessage.FILE, path = "SKILL.md", data = "ZGVm", status = last))
        installer.handle(InstallMessage(InstallMessage.COMMIT, name = "c", digest = c.digest))
        val statuses = installer.handle(InstallMessage(InstallMessage.END)).statuses()
        assertEquals(mapOf<String?, String?>("c" to "failed", "a" to "failed", "b" to "failed"), statuses)
    }

    @Test
    fun anInstallationOverItsBoundsFails() {
        val many = Item("many", (0..DeliveryInstaller.MAX_FILES).associate { "f$it" to ("x" to "file") })
        val installer = installer(entryFile = null)
        installer.handle(InstallMessage(InstallMessage.MANIFEST, items = listOf(many.entry)))
        installer.stream(many)
        assertEquals(
            mapOf<String?, String?>("many" to "failed"),
            installer.handle(InstallMessage(InstallMessage.END)).statuses(),
        )
        val big = "a".repeat(1 shl 20).encodeUtf8().base64()
        val heavy = installer(entryFile = null)
        heavy.handle(InstallMessage(InstallMessage.MANIFEST, items = listOf(InstallMessage.Item("heavy", "1", "d"))))
        heavy.handle(InstallMessage(InstallMessage.BEGIN, name = "heavy"))
        repeat((DeliveryInstaller.MAX_TOTAL_BYTES shr 20).toInt() + 1) {
            heavy.handle(InstallMessage(InstallMessage.FILE, path = "f$it", data = big, status = InstallMessage.LAST))
        }
        assertEquals(
            mapOf<String?, String?>("heavy" to "failed"),
            heavy.handle(InstallMessage(InstallMessage.END)).statuses(),
        )
    }

    @Test
    fun aFileSystemThatRefusesFailsTheItemOnly() {
        val installer = DeliveryInstaller(root, fileSystem, { throw IOException("read-only") })
        installer.handle(InstallMessage(InstallMessage.MANIFEST, items = listOf(review.entry)))
        installer.stream(review)
        assertEquals(
            mapOf<String?, String?>("review" to "failed"),
            installer.handle(InstallMessage(InstallMessage.END)).statuses(),
        )
        // A marker that cannot be read is a folder of another version.
        fileSystem.createDirectories(root / "review/.ostrom-skill")
        val again = installer()
        assertEquals(
            InstallMessage.NEED,
            again.handle(InstallMessage(InstallMessage.MANIFEST, items = listOf(review.entry))).first().t,
        )
        again.stream(review)
        assertEquals(
            mapOf<String?, String?>("review" to "installed"),
            again.handle(InstallMessage(InstallMessage.END)).statuses(),
        )
    }

    @Test
    fun aMoveThatFailsIsReported() {
        val installer = installer()
        installer.handle(InstallMessage(InstallMessage.MANIFEST, items = listOf(review.entry)))
        installer.stream(review)
        // The skills folder is a file: nothing can be moved into it.
        fileSystem.write(root) { writeUtf8("not a folder") }
        assertEquals(
            mapOf<String?, String?>("review" to "failed"),
            installer.handle(InstallMessage(InstallMessage.END)).statuses(),
        )
    }

    @Test
    fun theInstallerIsChosenByCommandAndAgent() {
        val home = mapOf("HOME" to "/h/")
        fun chosen(command: String, agent: String?, environment: Map<String, String> = home) =
            CloudInstall.installer(command, agent, environment, fileSystem) {}
        assertNotNull(chosen(CloudProcess.INSTALL_SKILLS, "claude"))
        assertNotNull(chosen(CloudProcess.INSTALL_SKILLS, "codex"))
        assertNotNull(chosen(CloudProcess.INSTALL_SKILLS, "codex", home + ("CODEX_HOME" to "/c")))
        assertNotNull(chosen(CloudProcess.INSTALL_HOOKS, "claude"))
        assertNull(chosen(CloudProcess.INSTALL_HOOKS, "codex"))
        assertNull(chosen(CloudProcess.INSTALL_SKILLS, "gemini"))
        assertNull(chosen(CloudProcess.INSTALL_SKILLS, null))
        assertNull(chosen(CloudProcess.AGENT_STATE, "claude"))
        assertNull(chosen(CloudProcess.INSTALL_SKILLS, "claude", mapOf("HOME" to " ")))
        assertNull(chosen(CloudProcess.INSTALL_SKILLS, "claude", emptyMap()))
    }

    @Test
    fun claudeSkillsGoUnderItsConfigFolder() {
        val environment = mapOf("HOME" to "/h", "CLAUDE_CONFIG_DIR" to "/cfg/")
        val claude =
            assertNotNull(CloudInstall.installer(CloudProcess.INSTALL_SKILLS, "claude", environment, fileSystem) {})
        claude.handle(InstallMessage(InstallMessage.MANIFEST, items = listOf(review.entry)))
        claude.stream(review)
        claude.handle(InstallMessage(InstallMessage.END))
        assertTrue(fileSystem.exists("/cfg/skills/review/SKILL.md".toPath()))
    }

    @Test
    fun anItemBegunButNeverCommittedKeepsItsInstalledVersion() {
        installer(alwaysReplace = true).apply {
            handle(InstallMessage(InstallMessage.MANIFEST, items = listOf(review.entry)))
            stream(review)
            handle(InstallMessage(InstallMessage.END))
        }
        val again = installer(alwaysReplace = true)
        again.handle(InstallMessage(InstallMessage.MANIFEST, items = listOf(review.entry)))
        again.handle(InstallMessage(InstallMessage.BEGIN, name = "review"))
        val statuses = again.handle(InstallMessage(InstallMessage.END)).statuses()
        assertEquals(mapOf<String?, String?>("review" to "failed"), statuses)
        assertEquals("# Review", fileSystem.read(root / "review/SKILL.md") { readUtf8() })
    }

    @Test
    fun codexSkillsGoUnderCodexHome() {
        val codex = assertNotNull(
            CloudInstall.installer(
                CloudProcess.INSTALL_SKILLS,
                "codex",
                mapOf("HOME" to "/h", "CODEX_HOME" to "/c/"),
                fileSystem,
            ) {},
        )
        codex.handle(InstallMessage(InstallMessage.MANIFEST, items = listOf(review.entry)))
        codex.stream(review)
        codex.handle(InstallMessage(InstallMessage.END))
        assertTrue(fileSystem.exists("/c/skills/review/SKILL.md".toPath()))
        val hooks = assertNotNull(
            CloudInstall.installer(CloudProcess.INSTALL_HOOKS, "claude", mapOf("HOME" to "/h"), fileSystem) {},
        )
        hooks.handle(InstallMessage(InstallMessage.MANIFEST, items = listOf(review.entry)))
        hooks.stream(review)
        hooks.handle(InstallMessage(InstallMessage.END))
        assertEquals(
            "${review.digest}\n1\n",
            fileSystem.read("/h/.ostrom/hooks/review/.ostrom-hook".toPath()) { readUtf8() },
        )
    }

    @Test
    fun theExchangeSaysHelloThenAnswersUntilTheEnd() {
        val lines = ArrayDeque(
            listOf(
                "not json",
                InstallMessage(InstallMessage.MANIFEST).encode() + "\r",
                InstallMessage(InstallMessage.END).encode(),
                "after",
            ),
        )
        val written = mutableListOf<String>()
        assertTrue(CloudInstall.serve(installer(), { lines.removeFirstOrNull() }, written::add))
        assertEquals(listOf("hello", "ready", "done"), written.map { assertNotNull(InstallMessage.decode(it)).t })
        assertEquals(listOf("after"), lines.toList())
        assertFalse(CloudInstall.serve(installer(), { null }, {}))
    }
}
