// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.platform

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ConfigStoreTest {
    private val fileSystem = FakeFileSystem()
    private val store = ConfigStoreImpl(FileStoreImpl(fileSystem), "/home/maya/.config/ostrom/config.toml")

    @Test
    fun anAbsentFileHasNoValues() {
        assertEquals(emptyMap(), store.list())
        assertNull(store.get(ConfigKey.SERVER))
    }

    @Test
    fun setWritesTheFileAndCreatesItsFolder() {
        store.set(ConfigKey.DEFAULT_AGENT, "codex")
        store.set(ConfigKey.SERVER, "https://ostrom.run")
        assertEquals("codex", store.get(ConfigKey.DEFAULT_AGENT))
        assertEquals(
            "default_agent = \"codex\"\nserver = \"https://ostrom.run\"\n",
            fileSystem.read("/home/maya/.config/ostrom/config.toml".toPath()) { readUtf8() },
        )
        assertEquals(listOf(ConfigKey.SERVER, ConfigKey.DEFAULT_AGENT), store.list().keys.toList())
    }

    @Test
    fun unsetRemovesOnlyThatKey() {
        store.set(ConfigKey.SERVER, "https://ostrom.run")
        store.set(ConfigKey.EDITOR, "vim")
        store.unset(ConfigKey.SERVER)
        assertEquals(mapOf(ConfigKey.EDITOR to "vim"), store.list())
    }

    @Test
    fun valuesWithQuotesAndBackslashesSurviveARoundTrip() {
        store.set(ConfigKey.EDITOR, "C:\\Tools\\\"my editor\".exe")
        assertEquals("C:\\Tools\\\"my editor\".exe", store.get(ConfigKey.EDITOR))
    }

    @Test
    fun commentsTablesAndUnknownKeysAreReadAroundTheValues() {
        val values = parseToml(
            "# device\nserver = \"https://a.example\" # comment\ncolor=\"never\"\nbroken = 3\n" +
                "\"quoted\" = \"x\"\n[table]\neditor = \"in a table\"\n",
        )
        assertEquals(mapOf("server" to "https://a.example", "color" to "never"), values)
    }

    @Test
    fun unknownKeysAreKeptWhenTheFileIsRewritten() {
        fileSystem.createDirectories("/home/maya/.config/ostrom".toPath())
        fileSystem.write("/home/maya/.config/ostrom/config.toml".toPath()) { writeUtf8("future_key = \"x\"\n") }
        store.set(ConfigKey.COLOR, "always")
        val written = fileSystem.read("/home/maya/.config/ostrom/config.toml".toPath()) { readUtf8() }
        assertEquals(mapOf("future_key" to "x", "color" to "always"), parseToml(written))
    }

    @Test
    fun theTomlSubsetReadsEveryEscapeAndRejectsWhatItCannotRead() {
        assertEquals("\"a\\\\b\\nc\\td\\re\\\"q\"", quoteToml("a\\b\nc\td\re\"q"))
        assertEquals("a\\b\nc\td\re\"x", unquoteToml("\"a\\\\b\\nc\\td\\re\\\"x\""))
        assertNull(unquoteToml("bare"))
        assertNull(unquoteToml("\"unterminated"))
        assertNull(unquoteToml("\"ends with a backslash\\"))
        assertEquals(emptyMap(), parseToml("\n   \nno equals here\n= \"no key\"\n"))
    }

    @Test
    fun aRootCannotBeWritten() {
        assertFailsWith<IllegalArgumentException> { FileStoreImpl(fileSystem).write("/", "x") }
    }

    @Test
    fun keysAreFoundByName() {
        assertEquals(ConfigKey.DEFAULT_AGENT, ConfigKey.of("default_agent"))
        assertNull(ConfigKey.of("colour"))
    }

    @Test
    fun unsetWithoutAFileWritesAnEmptyOne() {
        store.unset(ConfigKey.COLOR)
        assertEquals(emptyMap(), store.list())
    }

    private val path = "/home/maya/.config/ostrom/config.toml".toPath()

    private fun file(text: String) {
        fileSystem.createDirectories(path.parent!!)
        fileSystem.write(path) { writeUtf8(text) }
    }

    private fun written() = fileSystem.read(path) { readUtf8() }

    @Test
    fun writingChangesOnlyTheLineOfTheKey() {
        val original = "# My device\n\nserver = 'https://old.example'  # from login\nretries = 3\n\n" +
            "[agents.claude]\ncolor = \"inside a table\"\n"
        file(original)
        store.set(ConfigKey.SERVER, "https://new.example")
        val replaced = original.replace(
            "server = 'https://old.example'  # from login",
            "server = \"https://new.example\"",
        )
        assertEquals(replaced, written())
        store.set(ConfigKey.COLOR, "never")
        assertEquals(
            "# My device\n\nserver = \"https://new.example\"\nretries = 3\ncolor = \"never\"\n\n" +
                "[agents.claude]\ncolor = \"inside a table\"\n",
            written(),
        )
        assertEquals("never", store.get(ConfigKey.COLOR))
        store.unset(ConfigKey.SERVER)
        store.unset(ConfigKey.COLOR)
        assertEquals("# My device\n\nretries = 3\n\n[agents.claude]\ncolor = \"inside a table\"\n", written())
    }

    @Test
    fun aKeyAddedToAFileWithOnlyTablesGoesFirstAndAnEmptiedFileIsEmpty() {
        file("[profile]\nname = \"x\"")
        store.set(ConfigKey.EDITOR, "vim")
        assertEquals("editor = \"vim\"\n[profile]\nname = \"x\"", written())
        file("editor = \"vim\"\n")
        store.unset(ConfigKey.EDITOR)
        assertEquals("", written())
    }

    @Test
    fun literalStringsAndUnicodeEscapesAreRead() {
        assertEquals("C:\\no\\escape", unquoteToml("'C:\\no\\escape' # literal"))
        assertNull(unquoteToml("'unterminated"))
        assertEquals("é😀", unquoteToml("\"\\u00e9\\U0001F600\""))
        assertEquals("\b\u000C", unquoteToml("\"\\b\\f\""))
        // An escape TOML does not have is kept as written rather than refusing the file.
        val invalid = "\"\\u12 \\U00110000 \\uzzzz \\uD800 \\u1\""
        assertEquals("u12 U00110000 uzzzz uD800 u1", unquoteToml(invalid))
    }

    @Test
    fun controlCharactersAreWrittenAsUnicodeEscapes() {
        assertEquals("\"a\\u0000b\\u007f\"", quoteToml("a\u0000b\u007F"))
        assertEquals("a\u0000b\u007F", unquoteToml(quoteToml("a\u0000b\u007F")))
    }

    @Test
    fun linesInsideMultiLineValuesAreNeverKeysNorTables() {
        val original = "agents = [\n  [\"a\", \"b]\"], # [not a table]\n  ['c]'],\n]\n" +
            "notes = \"\"\"\neditor = \"inside a string\"\n[fake]\nan escaped \\\"\"\" stays inside\n\"\"\"\n" +
            "raw = \'\'\'\ncolor = \"inside a literal\"\n\'\'\'\n" +
            "server = \"https://a.example\"\n[real]\ncolor = \"in a table\"\n"
        file(original)
        assertEquals(mapOf(ConfigKey.SERVER to "https://a.example"), store.list())
        store.set(ConfigKey.COLOR, "never")
        assertEquals(original.replace("[real]", "color = \"never\"\n[real]"), written())
        store.set(ConfigKey.EDITOR, "vim")
        store.unset(ConfigKey.EDITOR)
        assertEquals(original.replace("[real]", "color = \"never\"\n[real]"), written())
    }

    @Test
    fun onlyAnOpenBracketOrMultiLineStringHidesTheLinesAfterIt() {
        // A one-line string cannot span lines: the next line is read; an open bracket hides what follows.
        assertEquals(mapOf("server" to "https://a.example"), parseToml("a = \"open\nserver = \"https://a.example\""))
        assertEquals(mapOf("color" to "x"), parseToml("color = \"x\"\nb = \'open\n] ]\nc = [\nserver = \"y\""))
        assertEquals(emptyMap(), parseToml("editor = \"\"\"vim\"\"\"\nserver = \'\'\'x\'\'\'"))
    }

    @Test
    fun aWindowsFileKeepsItsLineEndings() {
        file("# notepad\r\nserver = \"https://a.example\"\r\n")
        store.set(ConfigKey.COLOR, "never")
        assertEquals("# notepad\r\nserver = \"https://a.example\"\r\ncolor = \"never\"\r\n", written())
        assertEquals("https://a.example", store.get(ConfigKey.SERVER))
    }

    @Test
    fun aByteOrderMarkMixedEndingsAndAMissingLastNewlineAreKept() {
        file("\uFEFF# notepad\nserver = \"https://a.example\"\r\nretries = 3")
        assertEquals("https://a.example", store.get(ConfigKey.SERVER))
        store.set(ConfigKey.SERVER, "https://b.example")
        store.set(ConfigKey.COLOR, "never")
        assertEquals("\uFEFF# notepad\nserver = \"https://b.example\"\r\nretries = 3\ncolor = \"never\"\n", written())
        assertEquals(mapOf(ConfigKey.SERVER to "https://b.example", ConfigKey.COLOR to "never"), store.list())
    }
}
