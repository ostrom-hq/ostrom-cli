// SPDX-License-Identifier: Apache-2.0
// Dense assertions and fakes, in the style of the ported TeamCode tests.
@file:Suppress(
    "MaxLineLength",
)

package run.ostrom.cli.daemon

import okio.Buffer
import okio.FileMetadata
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import run.ostrom.core.api.models.ResumableSessionLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The daemon's reading of Claude Code transcripts (TeamCode's `ClaudeTranscriptsTest`): what a folder offers to
 * resume, and nothing more than a title leaves.
 */
class ClaudeTranscriptsTest {
    private val fs = FakeFileSystem().apply { allowSymlinks = true }
    private val ids = (1..9).map { "0000000$it-aaaa-4bbb-8ccc-00000000000$it" }
    private val root = "/scratch".toPath()
    private val folder = (root / "work" / "my_app.v2").toString()
    private val writes = mutableMapOf<String, Long>()
    private val project = root / "claude" / "projects" / ClaudeTranscripts.projectName(folder)
    private val transcripts = ClaudeTranscripts(root / "claude" / "projects", fs = fs, lastWrite = { path, _ -> writes[path.name] ?: 0L })

    init {
        fs.createDirectories(project)
    }

    private fun write(id: String, at: Long, lines: List<String>, into: Path = project) {
        fs.write(into / "$id.jsonl") { writeUtf8(lines.joinToString("\n", postfix = "\n")) }
        writes["$id.jsonl"] = at
    }

    private fun user(text: String, cwd: String, extra: String = "") =
        """{"type":"user","sessionId":"s","cwd":${quote(cwd)},"timestamp":"2026-10-25T10:00:00Z"$extra,""" +
            """"message":{"role":"user","content":${quote(text)}}}"""

    private fun quote(text: String) = buildString {
        append('"')
        for (char in text) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }

    private fun assistant(cwd: String) =
        """{"type":"assistant","cwd":${quote(cwd)},"message":{"role":"assistant","content":[{"type":"text","text":"Sure."}]}}"""

    private fun toolResult(cwd: String) =
        """{"type":"user","cwd":${quote(cwd)},"message":{"role":"user","content":[{"type":"tool_result",""" +
            """"tool_use_id":"t","content":"file body"}]}}"""

    private fun iso(millis: Long) = Instant.fromEpochMilliseconds(millis).toString()

    @Test
    fun projectNamesFollowClaudeCode() {
        assertEquals("-Users-ada-git-ostrom", ClaudeTranscripts.projectName("/Users/ada/git/ostrom"))
        assertEquals("-home-ada-my-app-v2--x-", ClaudeTranscripts.projectName("/home/ada/my_app.v2/ x\u00e9"))
        assertEquals("C--Users-ada-app", ClaudeTranscripts.projectName("C:\\Users\\ada\\app"))
        assertEquals("/cfg/projects", ClaudeTranscripts.projectsDirectory("/cfg", null).toString(), "CLAUDE_CONFIG_DIR")
        assertEquals("/home/ada/.claude/projects", ClaudeTranscripts.projectsDirectory(null, "/home/ada".toPath()).toString())
        assertNull(ClaudeTranscripts.projectsDirectory("", null))
        assertEquals("/home/ada/.claude/projects", ClaudeTranscripts.projectsDirectory(" ", "/home/ada".toPath()).toString(), "A blank CLAUDE_CONFIG_DIR")
    }

    @Test
    fun theRootDirectoryOffersItsOwnConversations() {
        val rootProject = root / "claude" / "projects" / "-"
        fs.createDirectories(rootProject)
        write(ids[0], 1_000, listOf(user("At the root", "/")), rootProject)
        assertEquals(listOf(ids[0]), transcripts.list("/", 20).items.map { it.id })
    }

    @Test
    fun aTimeTheFileSystemDoesNotTellIsTheEpoch() {
        write(ids[0], 1_000, listOf(user("Undated", folder)))
        val untimed = object : ForwardingFileSystem(fs) {
            override fun metadataOrNull(path: Path): FileMetadata? =
                super.metadataOrNull(path)?.let { it.copy(lastModifiedAtMillis = null) }
        }
        val page = ClaudeTranscripts(root / "claude" / "projects", fs = untimed).list(folder, 20)
        assertEquals(listOf(iso(0)), page.items.map { it.lastActiveAt })
    }

    @Test
    fun aFolderWithoutConversationsOffersNone() {
        assertEquals(emptyList(), transcripts.list(folder, 20).items)
        assertEquals(emptyList(), transcripts.list((root / "elsewhere").toString(), 20).items, "No project directory")
        assertEquals(emptyList(), ClaudeTranscripts(null, fs = fs).list(folder, 20).items, "No home directory")
        assertFalse(ClaudeTranscripts(null, fs = fs).belongs(folder, ids[0]))
    }

    @Test
    fun theMostRecentComeFirstWithTitleDateAndTurns() {
        write(ids[0], 1_000, listOf(user("Fix the login bug", folder), assistant(folder), toolResult(folder), user("And add a test", folder)))
        write(ids[1], 3_000, listOf(user("Second conversation", folder)))
        write(
            ids[2],
            2_000,
            listOf(
                user("Third", folder),
                """{"type":"custom-title","customTitle":"Old name","sessionId":"s"}""",
                """{"type":"custom-title","customTitle":"Release  checklist","sessionId":"s"}""",
            ),
        )
        val page = transcripts.list(folder, 20)
        assertEquals(listOf(ids[1], ids[2], ids[0]), page.items.map { it.id })
        assertEquals(listOf("Second conversation", "Release checklist", "Fix the login bug"), page.items.map { it.title })
        assertEquals(listOf(1, 1, 2), page.items.map { it.turns }, "Tool results are not messages")
        assertEquals(listOf(iso(3_000), iso(2_000), iso(1_000)), page.items.map { it.lastActiveAt })
        assertNull(page.next)
    }

    @Test
    fun pagesAreBoundedAndContinueBeforeTheLastOne() {
        for (i in 0 until 5) write(ids[i], 1_000L * (i + 1), listOf(user("Message $i", folder)))
        val first = transcripts.list(folder, 2)
        assertEquals(listOf(ids[4], ids[3]), first.items.map { it.id })
        assertEquals(iso(4_000), first.next)
        val second = transcripts.list(folder, 2, before = first.next)
        assertEquals(listOf(ids[2], ids[1]), second.items.map { it.id })
        val last = transcripts.list(folder, 2, before = second.next)
        assertEquals(listOf(ids[0]), last.items.map { it.id })
        assertNull(last.next)
        assertEquals(5, transcripts.list(folder, 500).items.size, "The limit is bounded, not refused")
        assertTrue(ResumableSessionLimits.MAX_LIMIT < 500)
    }

    @Test
    fun emptyUnreadableAndMessageLessFilesAreNotOffered() {
        write(ids[0], 1_000, listOf(""))
        write(ids[1], 2_000, listOf("not json at all", "{\"type\":\"user\",", "\u0000\u0001"))
        write(ids[2], 3_000, listOf(assistant(folder), toolResult(folder), """{"type":"custom-title","customTitle":"Only a name"}"""))
        write(
            ids[3],
            4_000,
            listOf(
                user("<task-notification>done</task-notification>", folder),
                user("Meta", folder, ",\"isMeta\":true"),
                user("Side", folder, ",\"isSidechain\":true"),
                user("<local-command-stdout>x</local-command-stdout>", folder),
                """{"type":"user","cwd":"/w","message":{"content":42}}""",
                """{"type":"user","cwd":"/w","message":{"content":[]}}""",
                """{"type":"user","cwd":"/w"}""",
                """["user"]""",
            ),
        )
        write(ids[4], 5_000, listOf("garbage", user("Kept despite a bad line", folder), "{broken"))
        assertEquals(listOf(ids[4]), transcripts.list(folder, 20).items.map { it.id }, "A corrupted line is skipped")
        assertFalse(transcripts.belongs(folder, ids[1]))
        assertTrue(transcripts.belongs(folder, ids[4]))
    }

    @Test
    fun anotherFolderWithTheSameProjectNameIsNotOffered() {
        // `/work/my_app.v2` and `/work/my-app-v2` share Claude Code's project name; only cwd tells them apart.
        val twin = (root / "work" / "my-app-v2").toString()
        assertEquals(ClaudeTranscripts.projectName(folder), ClaudeTranscripts.projectName(twin))
        write(ids[0], 1_000, listOf(user("Mine", folder)))
        write(ids[1], 2_000, listOf(user("The twin's", twin)))
        write(ids[2], 3_000, listOf("""{"type":"user","message":{"content":"No cwd"}}"""))
        assertEquals(listOf(ids[0]), transcripts.list(folder, 20).items.map { it.id })
        assertEquals(listOf(ids[1]), transcripts.list(twin, 20).items.map { it.id })
        assertFalse(transcripts.belongs(folder, ids[1]), "An id of another folder is refused")
        assertTrue(transcripts.belongs(twin, ids[1]))
        assertTrue(transcripts.belongs("$twin/", ids[1]), "A trailing separator names the same folder")
    }

    @Test
    fun aWindowsFolderOffersItsConversations() {
        // A Windows cwd is written with escaped backslashes; the fixtures must escape it too.
        val windowsFolder = "C:\\Users\\Ada\\my_app.v2"
        val windowsProject = root / "windows" / ClaudeTranscripts.projectName(windowsFolder)
        fs.createDirectories(windowsProject)
        write(ids[0], 1_000, listOf(user("Fix the build", windowsFolder)), windowsProject)
        val windows = ClaudeTranscripts(root / "windows", windows = true, fs = fs)
        assertEquals(listOf(ids[0]), windows.list(windowsFolder, 20).items.map { it.id })
        assertTrue(windows.belongs("C:/Users/Ada/my_app.v2/", ids[0]), "Either separator")
    }

    @Test
    fun aLongDirectoryIsLookedForAmongTheProjectsItsNameStartsWith() {
        val long = "/" + "deep/".repeat(50) + "app"
        val cut = ClaudeTranscripts.projectName(long).take(ClaudeTranscripts.PROJECT_NAME_LIMIT)
        val hashed = root / "claude" / "projects" / "$cut-1a2b3c"
        fs.createDirectories(hashed)
        write(ids[0], 1_000, listOf(user("Deep", long)), hashed)
        assertEquals(listOf(ids[0]), transcripts.list(long, 20).items.map { it.id })
        assertEquals(emptyList(), ClaudeTranscripts("/nowhere".toPath(), fs = fs).list(long, 20).items)
    }

    @Test
    fun onlyUuidTranscriptsAreReadAndIdsAreChecked() {
        write(ids[0], 1_000, listOf(user("Kept", folder)))
        write("agent-1234", 2_000, listOf(user("A subagent file of an older version", folder)))
        fs.write(project / "notes.txt") { writeUtf8("x") }
        fs.createDirectories(project / "${ids[6]}.jsonl")
        assertEquals(listOf(ids[0]), transcripts.list(folder, 20).items.map { it.id })
        assertFalse(transcripts.belongs(folder, "../${ids[0]}"))
        assertFalse(transcripts.belongs(folder, ids[0].uppercase()))
        assertFalse(transcripts.belongs(folder, ids[5]), "No such transcript")
        assertFalse(transcripts.belongs(folder, ids[6]), "A directory is no transcript")
    }

    @Test
    fun aSymbolicLinkIsNeverFollowed() {
        write(ids[0], 1_000, listOf(user("Real", folder)))
        fs.write(root / "outside.jsonl") { writeUtf8(user("Outside", folder) + "\n") }
        fs.createSymlink(project / "${ids[1]}.jsonl", root / "outside.jsonl")
        assertEquals(listOf(ids[0]), transcripts.list(folder, 20).items.map { it.id })
        assertFalse(transcripts.belongs(folder, ids[1]))
        // A project directory that is a link is not read either.
        val linked = (root / "linked").toString()
        fs.createSymlink(root / "claude" / "projects" / ClaudeTranscripts.projectName(linked), project)
        assertEquals(emptyList(), transcripts.list(linked, 20).items)
    }

    @Test
    fun aPageReadsAtMostItsBoundOfFiles() {
        for (i in 0 until ClaudeTranscripts.MAX_READ_PER_PAGE + 1) {
            val id = "%08x-aaaa-4bbb-8ccc-000000000000".replace("%08x", i.toString(16).padStart(8, '0'))
            write(id, i.toLong(), listOf("""{"type":"assistant"}"""))
        }
        write(ids[0], -1, listOf(user("Oldest", folder)))
        assertEquals(emptyList(), transcripts.list(folder, 20).items, "Past the bound, nothing more is opened")
    }

    @Test
    fun titlesAreOneShortLineWithoutControlOrFormatCharacters() {
        fun title(vararg lines: String) = summarizeTranscript(Buffer().writeUtf8(lines.joinToString("\n")))?.title
        val long = "word ".repeat(100)
        val cut = title(user(long, "/w"))!!
        assertEquals(ResumableSessionLimits.TITLE_LENGTH, cut.length)
        assertTrue(cut.endsWith("…"))
        assertEquals("Line one line two", title(user("Line one\n\n\tline two  ", "/w")))
        assertEquals("évite le 🚀 \"quoted\" <b>", title(user("évite le \u202e🚀 \"quoted\" <b>\u200b", "/w")))
        assertEquals("x", title(user("\uE000x", "/w")), "Private use characters dropped")
        val emoji = "a" + "🚀".repeat(60)
        val trimmed = title(user(emoji, "/w"))!!
        assertFalse(trimmed.dropLast(1).last().isHighSurrogate(), "A surrogate pair is never cut in two")
        val emojiAtEnd = "ab" + "🚀".repeat(60)
        assertFalse(title(user(emojiAtEnd, "/w"))!!.dropLast(1).last().isHighSurrogate())
        val command = "<command-message>review</command-message>\n<command-name>/review</command-name>\n" +
            "<command-args>42</command-args>"
        assertEquals("/review 42", title(user(command, "/w")))
        assertEquals("/clear", title(user("<command-name>/clear</command-name><command-args> </command-args>", "/w")))
        assertNull(title(user("<command-name>/broken", "/w")), "An unclosed tag is no command")
        assertEquals(
            "Look at this",
            title("""{"type":"user","cwd":"/w","message":{"content":[{"type":"image","source":{}},{"type":"text","text":"Look at this"}]}}"""),
        )
        assertNull(title("""{"type":"user","cwd":"/w","message":{"content":[{"type":"image","source":{}}]}}"""), "Images only")
        assertEquals("From summary", title(user("first", "/w"), """{"type":"summary","summary":"From summary","leafUuid":"x"}"""))
        assertEquals("first", title(user("first", "/w"), """{"type":"summary","summary":"  "}"""), "A blank summary")
        assertNull(clean(" \n\t"))
        assertEquals("a b", clean("a\u0001b"), "A control character is a space")
        assertEquals("/clear", title(user("<command-name>/clear</command-name>", "/w")), "A command without arguments")
        assertEquals("first", title(user("first", "/w"), """{"type":"custom-title"}""", """{"type":"custom-title","customTitle":" "}"""), "No name")
        assertEquals("first", title(user("first", "/w"), """{"type":"summary"}"""), "No summary")
        assertEquals("Hi", title("""{"type":"user","cwd":"/w","message":{"content":["loose",{"type":"text","text":"Hi"}]}}"""), "Only blocks count")
        assertNull(title("""{"type":"user","cwd":"/w","message":{"content":[{"type": "tool_result","content":"x"}]}}"""), "A tool result spaced otherwise")
        val noCwd = summarizeTranscript(Buffer().writeUtf8("""{"type":"user","cwd":7,"message":{"content":"Hi"}}"""))
        assertNull(noCwd?.cwd, "A cwd that is not text")
        assertEquals("Hi", noCwd?.title)
    }

    @Test
    fun aLineOverTheBoundIsSkippedWithoutStoppingTheFile() {
        val huge = """{"type":"user","cwd":"/w","message":{"content":"${"x".repeat(3 * 1024 * 1024)}"}}"""
        val summary = summarizeTranscript(Buffer().writeUtf8(listOf(huge, user("After the large line", "/w")).joinToString("\n")))
        assertEquals("After the large line", summary?.title)
        assertEquals(1, summary?.turns)
        assertNull(summarizeTranscript(Buffer().writeUtf8(huge)), "A last line over the bound ends the file")
        assertEquals("No newline", summarizeTranscript(Buffer().writeUtf8(user("No newline", "/w")))?.title)
        val two = listOf(user("First", "/w"), user("Second", "/w")).joinToString("\n", postfix = "\n")
        assertEquals(1, summarizeTranscript(Buffer().writeUtf8(two), maxBytes = 10)?.turns, "Nothing past the file's bound")
    }
}
