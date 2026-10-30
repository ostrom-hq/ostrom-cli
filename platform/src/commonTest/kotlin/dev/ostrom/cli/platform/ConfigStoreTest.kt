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
            "# device\n[ignored]\nserver = \"https://a.example\" # comment\ncolor=\"never\"\nbroken = 3\n",
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
}
