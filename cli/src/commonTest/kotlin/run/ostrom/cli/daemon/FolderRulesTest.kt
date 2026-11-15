// SPDX-License-Identifier: Apache-2.0
// Dense assertions and fakes, in the style of the ported TeamCode tests.
@file:Suppress(
    "MaxLineLength",
)

package run.ostrom.cli.daemon

import com.github.ajalt.clikt.core.PrintMessage
import okio.FileMetadata
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import run.ostrom.cli.platform.FileStoreImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `ostrom folders` (TeamCode's `FolderRulesTest`): what may become a folder, and how the daemon checks an order again. */
class FolderRulesTest {
    private val fs = FakeFileSystem().apply { allowSymlinks = true }
    private val home = "/home/maya".toPath()

    init {
        fs.createDirectories(home)
    }

    private fun refused(block: () -> Unit) = assertFailsWith<PrintMessage> { block() }.message.orEmpty()

    private fun store(windows: Boolean = false) = FolderStore("/cfg/folders.json", FileStoreImpl(fs), fs, windows)

    @Test
    fun theHomeDirectoryItsParentsAndSystemDirectoriesAreRefused() {
        fs.createDirectories(home / "git" / "ostrom")
        fs.createDirectories("/usr/bin".toPath())
        assertEquals(home / "git" / "ostrom", folderPath(fs, (home / "git" / "ostrom").toString(), home, false))
        assertTrue("may not run in" in refused { folderPath(fs, home.toString(), home, false) })
        assertTrue("contains your home directory" in refused { folderPath(fs, "/home", home, false) })
        assertTrue("may not run in" in refused { folderPath(fs, "/", home, false) })
        assertTrue("system directory" in refused { folderPath(fs, "/usr/bin", home, false) })
        assertTrue("does not exist" in refused { folderPath(fs, (home / "missing").toString(), home, false) })
        fs.write(home / "file") { writeUtf8("x") }
        assertTrue("not a directory" in refused { folderPath(fs, (home / "file").toString(), home, false) })
        assertEquals("/srv".toPath(), folderPath(fs, "/srv".also { fs.createDirectories(it.toPath()) }, null, false))
    }

    @Test
    fun aParentOfTheHomeIsRefusedOnWindowsWhateverTheCase() {
        val windowsHome = "C:\\Users\\Me".toPath()
        assertTrue("contains" in refused { refuseFolder("c:\\users".toPath(), windowsHome, windows = true) })
        refuseFolder("D:\\work".toPath(), windowsHome, windows = true)
    }

    @Test
    fun aSymbolicLinkIsResolvedAndCannotLeadOutOfTheRules() {
        fs.createDirectories("/outside/work".toPath())
        fs.createSymlink(home / "work", "/outside/work".toPath())
        fs.createSymlink(home / "me", home)
        assertEquals("/outside/work".toPath(), folderPath(fs, (home / "work").toString(), home, false), "Where it points")
        assertTrue("may not run in" in refused { folderPath(fs, (home / "me").toString(), home, false) })

        // A folder added as a directory, later replaced by a link pointing elsewhere, is refused at order time.
        fs.createDirectories(home / "project")
        val file = FoldersFile(listOf(LocalFolder("p1", (home / "project").toString())))
        val accepted = OrderCheck.Accepted((home / "project").toString())
        assertEquals(accepted, checkOrder(fs, "p1", "project", "claude", file, home, false))
        fs.delete(home / "project")
        fs.createSymlink(home / "project", "/outside/work".toPath())
        assertEquals(OrderCheck.Refused(RefusalReasons.FOLDER_MOVED), checkOrder(fs, "p1", "project", "claude", file, home, false))
    }

    @Test
    fun anOrderIsCheckedAgainstTheDaemonsOwnFolders() {
        fs.createDirectories(home / "project")
        fs.write(home / "plain") { writeUtf8("x") }
        val file = FoldersFile(
            listOf(
                LocalFolder("p1", (home / "project").toString()),
                LocalFolder("gone", (home / "gone").toString()),
                LocalFolder("home", home.toString()),
                LocalFolder("plain", (home / "plain").toString()),
            ),
        )
        fun check(id: String, name: String, agent: String = "claude") = checkOrder(fs, id, name, agent, file, home, false)
        assertEquals(OrderCheck.Accepted((home / "project").toString()), check("p1", "project", "codex"))
        assertEquals(OrderCheck.Refused(RefusalReasons.FOLDER_NOT_CONFIGURED), check("other", "project"))
        assertEquals(OrderCheck.Refused(RefusalReasons.FOLDER_MISSING), check("gone", "gone"))
        assertEquals(OrderCheck.Refused(RefusalReasons.FOLDER_MISSING), check("plain", "plain"), "No longer a directory")
        assertEquals(OrderCheck.Refused(RefusalReasons.FOLDER_REFUSED), check("home", home.name), "A hand-edited file")
        assertEquals(OrderCheck.Refused(RefusalReasons.FOLDER_MISMATCH), check("p1", "other"))
        assertEquals(OrderCheck.Refused(RefusalReasons.AGENT_UNSUPPORTED), check("p1", "project", "gemini"))
        RefusalReasons.run {
            listOf(FOLDER_NOT_CONFIGURED, FOLDER_MISSING, FOLDER_MOVED, FOLDER_REFUSED, FOLDER_MISMATCH, AGENT_UNSUPPORTED, HOST_FAILED, RESUME_NOT_FOUND)
        }.forEach { assertTrue(Regex("[a-z_]{1,64}").matches(it), "The server's reason format: $it") }
    }

    @Test
    fun foldersKeepAStableIdAndPublishOnlyNamesAndLabels() {
        val store = store()
        fs.createDirectories(home / "git" / "ostrom")
        fs.createDirectories("/srv".toPath())
        assertEquals(FoldersFile(), store.read(), "No file yet")
        val (first, added) = store.add(home / "git" / "ostrom", "/usr/bin:/opt/bin")
        assertTrue(added)
        assertTrue(Regex("[a-f0-9]{16}").matches(first.id), "The server's id format")
        val (again, addedAgain) = store.add(home / "git" / "ostrom", null)
        assertFalse(addedAgain)
        assertEquals(first, again, "The same folder keeps its id")
        val other = store.add("/srv".toPath(), " ").first
        assertEquals("/usr/bin:/opt/bin", store.read().searchPath, "A blank PATH keeps the saved one")
        assertEquals(listOf(first.id to "ostrom", other.id to "srv"), published(store.read(), home).map { it.id to it.name })
        assertEquals("~/git/ostrom", published(store.read(), home).first().label)
        assertEquals("/srv", published(store.read(), home).last().label)
        assertEquals("/srv", published(store.read(), null).last().label, "Without a home, the path")
        assertEquals("~\\git", folderLabel("C:\\Users\\me\\git", "C:\\Users\\me".toPath()))
        assertEquals(first, store.remove(first.id))
        assertEquals(other, store.remove("/srv"), "By path too")
        assertNull(store.remove("unknown"))
        assertNull(store.remove("/no/such/dir"))
        assertTrue(store.read().folders.isEmpty())
    }

    @Test
    fun aDeviceOffersAtMostSixtyFourFolders() {
        val store = store()
        repeat(FolderStore.MAX_FOLDERS) {
            fs.createDirectories("/w/f$it".toPath())
            store.add("/w/f$it".toPath(), null)
        }
        fs.createDirectories("/w/more".toPath())
        assertTrue("at most 64 folders" in refused { store.add("/w/more".toPath(), null) })
        assertFalse(store.add("/w/f0".toPath(), null).second, "One already offered is found")
    }

    @Test
    fun aDamagedLinkedOrLargeFileStopsTheCommand() {
        val store = store()
        fs.createDirectories("/cfg".toPath())
        fs.write("/cfg/folders.json".toPath()) { writeUtf8("{damaged") }
        assertTrue("cannot read" in refused { store.read() })
        fs.delete("/cfg/folders.json".toPath())
        fs.write("/cfg/real.json".toPath()) { writeUtf8("{}") }
        fs.createSymlink("/cfg/folders.json".toPath(), "/cfg/real.json".toPath())
        assertTrue("not a symbolic link" in refused { store.read() })
        fs.delete("/cfg/folders.json".toPath())
        fs.write("/cfg/folders.json".toPath()) { writeUtf8(" ".repeat(300_000)) }
        assertTrue("small regular file" in refused { store.read() })
        fs.delete("/cfg/folders.json".toPath())
        fs.createDirectories("/cfg/folders.json".toPath())
        assertTrue("small regular file" in refused { store.read() })
    }

    @Test
    fun onWindowsAFolderKeepsItsIdWhateverTheSeparatorSpelling() {
        val store = store(windows = true)
        val (first, added) = store.add("C:\\Users\\me\\git\\app".toPath(), null)
        assertTrue(added)
        val (again, addedAgain) = store.add("C:/Users/me/git/app".toPath(), null)
        assertFalse(addedAgain, "The same folder spelled with forward slashes")
        assertEquals(first.id, again.id)
        assertEquals(first, store.remove("C:/Users/me/git/app/"), "Removed by either spelling")
        assertTrue(store.read().folders.isEmpty())
    }

    @Test
    fun theDaemonsSessionsAreKeptPrivatelyAndAnUnreadableFileIsEmpty() {
        val sessions = DaemonSessions("/cfg/daemon-sessions.json", FileStoreImpl(fs), fs)
        assertEquals(emptyList(), sessions.read())
        sessions.put(DaemonSession("s1", 1, DaemonSession.HOSTING))
        sessions.put(DaemonSession("s1", 2, DaemonSession.PAUSED))
        sessions.put(DaemonSession("s2", 1, DaemonSession.HOSTING))
        assertEquals(listOf(DaemonSession("s1", 2, DaemonSession.PAUSED), DaemonSession("s2", 1, DaemonSession.HOSTING)), sessions.read())
        sessions.remove("s3")
        sessions.remove("s1")
        assertEquals(listOf("s2"), sessions.read().map { it.sessionId })
        fs.write("/cfg/daemon-sessions.json".toPath()) { writeUtf8("{damaged") }
        assertEquals(emptyList(), sessions.read())
        fs.delete("/cfg/daemon-sessions.json".toPath())
        fs.createSymlink("/cfg/daemon-sessions.json".toPath(), "/cfg/elsewhere.json".toPath())
        assertEquals(emptyList(), sessions.read(), "A link is not read")
        fs.delete("/cfg/daemon-sessions.json".toPath())
        fs.write("/cfg/daemon-sessions.json".toPath()) { writeUtf8(" ".repeat(300_000)) }
        assertEquals(emptyList(), sessions.read(), "Nor a large file")
    }

    @Test
    fun aFolderOrSessionsFileThatCannotBeReadIsDamaged() {
        fs.createDirectories("/cfg".toPath())
        fs.write("/cfg/folders.json".toPath()) { writeUtf8("{}") }
        val unreadable = FolderStore("/cfg/folders.json", UnreadableFiles(FileStoreImpl(fs)), fs, windows = false)
        assertTrue("cannot read" in refused { unreadable.read() })
        fs.write("/cfg/daemon-sessions.json".toPath()) { writeUtf8("""{"sessions":[]}""") }
        assertEquals(emptyList(), DaemonSessions("/cfg/daemon-sessions.json", UnreadableFiles(FileStoreImpl(fs)), fs).read())
    }

    @Test
    fun aFileIsReadOnlyWhenItIsSmallRegularAndNoLink() {
        val file = FileMetadata(isRegularFile = true, size = 10, lastModifiedAtMillis = 5)
        assertTrue(regularFile(file))
        assertFalse(regularFile(null))
        assertFalse(regularFile(FileMetadata(isDirectory = true)))
        assertFalse(regularFile(FileMetadata(isRegularFile = true, symlinkTarget = "/x".toPath())))
        assertTrue(smallRegularFile(file, 10))
        assertTrue(smallRegularFile(FileMetadata(isRegularFile = true), 10), "A size not told counts as none")
        assertFalse(smallRegularFile(file, 9))
        assertFalse(smallRegularFile(null, 10))
        assertFalse(smallRegularFile(FileMetadata(isDirectory = true), 10))
        assertFalse(smallRegularFile(FileMetadata(isRegularFile = true, symlinkTarget = "/x".toPath()), 10))
        assertEquals(5L, lastModified(file))
        assertEquals(0L, lastModified(FileMetadata(isRegularFile = true)), "A time not told")
        assertEquals(0L, lastModified(null))
        fs.write(home / "plain") { writeUtf8("x") }
        assertTrue(fs.isDirectoryAt(home))
        assertFalse(fs.isDirectoryAt(home / "plain"))
        assertFalse(fs.isDirectoryAt(home / "missing"))
    }

    @Test
    fun theAgentsSearchPathAddsTheTerminalsAndTheUsualDirectories() {
        val existing = setOf("/home/me/.local/bin", "/opt/homebrew/bin")
        assertEquals(
            "/home/me/.nvm/bin:/usr/bin:/bin:/home/me/.local/bin:/opt/homebrew/bin",
            agentSearchPath("/usr/bin:/bin", "/home/me/.nvm/bin:relative:/usr/bin", "/home/me".toPath(), false) { it in existing },
        )
        assertNull(agentSearchPath("/usr/bin", null, null, false) { false }, "Nothing to add")
        fs.createDirectories("/opt/homebrew/bin".toPath())
        assertEquals("/usr/bin:/opt/homebrew/bin", agentSearchPath("/usr/bin", null, null, false, fs))
    }

    @Test
    fun theAgentsSearchPathOnWindowsUsesSemicolonsAndDriveLetters() {
        val home = "C:\\Users\\me".toPath()
        val existing = setOf("C:\\Users\\me\\.local\\bin", "C:\\Users\\me\\AppData\\Roaming\\npm")
        // The terminal's entries come first, a relative one is dropped, c:/windows and C:\Windows are one directory, and
        // only the usual directories that exist are added.
        assertEquals(
            "D:\\tools;\\\\server\\share;C:\\Windows;C:\\Windows\\System32;C:\\Users\\me\\.local\\bin;C:\\Users\\me\\AppData\\Roaming\\npm",
            agentSearchPath(
                "C:\\Windows;c:/windows;C:\\Windows\\System32;bin;\"C:\\Windows\"",
                "D:\\tools;relative\\dir;\\\\server\\share;C:x;1:\\no",
                home,
                windows = true,
                appData = "C:\\Users\\me\\AppData\\Roaming",
                localAppData = "C:\\Users\\me\\AppData\\Local",
            ) { it in existing },
        )
        assertNull(agentSearchPath("C:\\Windows", null, null, windows = true) { false }, "Nothing to add")
        // A POSIX-looking entry is not a Windows directory.
        assertNull(agentSearchPath(null, "/usr/bin", null, windows = true) { false })
    }
}
