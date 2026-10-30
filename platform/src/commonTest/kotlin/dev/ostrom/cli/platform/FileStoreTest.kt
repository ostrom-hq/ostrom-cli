package dev.ostrom.cli.platform

import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** On the real file system of each target: the config files of the CLI are written and read back. */
class FileStoreTest {
    private val folder = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ostrom-test-${Random.nextLong().toULong()}")
    private val store = FileStoreImpl()

    @AfterTest
    fun cleanUp() = FileSystem.SYSTEM.deleteRecursively(folder, mustExist = false)

    @Test
    fun writeCreatesFoldersAndReadsBack() {
        val path = (folder / "nested" / "config.toml").toString()
        assertNull(store.read(path))
        store.write(path, "server = \"https://ostrom.run\"\n")
        assertEquals("server = \"https://ostrom.run\"\n", store.read(path))
        assertEquals(listOf("config.toml"), FileSystem.SYSTEM.list(folder / "nested").map { it.name })
        store.write(path, "color = \"never\"\n")
        assertEquals("color = \"never\"\n", store.read(path))
        store.delete(path)
        assertFalse(store.exists(path))
    }

    @Test
    fun writeIntoAFileInsteadOfAFolderFailsWithTheFileWriteError() {
        val blocker = (folder / "blocker").toString()
        store.write(blocker, "x")
        val failure = runCatching { store.write("$blocker/config.toml".toPath().toString(), "y") }.exceptionOrNull()
        assertTrue(failure is FileWriteException, "got $failure")
    }
}
