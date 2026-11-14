// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import okio.IOException
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreparationLogTest {
    @Test
    fun knownSecretsOfFourCharactersOrMoreAreMaskedTheLongestFirst() {
        val secrets = listOf("abc", "token", "token-and-more")
        assertEquals("a [REDACTED] b [REDACTED] abc", PreparationLog.mask("a token-and-more b token abc", secrets))
        assertFalse("token" in PreparationLog("/l", listOf("token")).toString())
        assertEquals("/workspace/.ostrom/logs/prepare-e3.log", PreparationLog.path("/workspace", 3))
    }

    @Test
    fun outputIsSplitIntoMaskedLinesWhateverItsChunks() {
        val lines = mutableListOf<String>()
        val splitter = MaskedLines(listOf("s3cret"), { lines += it }, maxLine = 12)
        splitter.write("one s3".encodeToByteArray())
        splitter.write("cret\r\ntwo\n\nlong-line-here".encodeToByteArray())
        splitter.close()
        splitter.close()
        assertEquals(listOf("one [REDACTED]", "two", "", "long-line-he", "re"), lines)
    }

    @Test
    fun theLogKeepsItsLastBytesAndAppendsToAnEarlierOne() {
        val files = FakeFileSystem()
        val path = "/workspace/.ostrom/logs/prepare-e1.log"
        PreparationLogFile(path, files, maxBytes = 10).apply {
            append("12345")
            close()
        }
        assertEquals("12345\n", files.read(path.toPath()) { readUtf8() })
        val log = PreparationLogFile(path, files, maxBytes = 10)
        // 6 + 6 + 6 + 6 bytes: trimmed to the last 10 once it reaches twice the bound.
        log.append("abcde")
        log.append("fghij")
        log.append("klmno")
        assertEquals("hij\nklmno\n", files.read(path.toPath()) { readUtf8() })
        log.append("p")
        log.close()
        assertEquals("j\nklmno\np\n", files.read(path.toPath()) { readUtf8() })
        assertFalse(files.exists("$path.tmp".toPath()))
        // Closing a log never written does nothing.
        PreparationLogFile("/other.log", files).close()
        assertFalse(files.exists("/other.log".toPath()))
        assertTrue(files.exists(path.toPath()))
    }

    @Test
    fun aDirectoryAtTheLogsPathFailsItsWritesAndNothingElse() {
        val files = FakeFileSystem()
        files.createDirectories("/workspace/log".toPath())
        val log = PreparationLogFile("/workspace/log", files)
        assertFailsWith<IOException> { log.append("line") }
        log.close()
    }
}
