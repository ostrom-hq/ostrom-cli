// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.update

import run.ostrom.cli.ExitCode
import okio.FileMetadata
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** `ostrom verify-release` on files that are links or cannot be read. */
class ReleaseVerificationTest {
    private val fake = FakeFileSystem()
    private val link = "/dl/latest.json.sig".toPath()

    private val system = object : ForwardingFileSystem(fake) {
        override fun metadataOrNull(path: Path): FileMetadata? {
            if (path == "/dl/broken".toPath()) throw IOException("unreadable")
            val metadata = super.metadataOrNull(path)
            return if (path == link) metadata?.copy(symlinkTarget = "/dl/latest.json".toPath()) else metadata
        }

        override fun source(file: Path) =
            if (file.name.startsWith("cut")) throw IOException("cut") else super.source(file)
    }

    private fun put(path: String, text: String) {
        fake.createDirectories(path.toPath().parent!!)
        fake.write(path.toPath()) { writeUtf8(text) }
    }

    private fun verify(signature: String, archive: String? = null) = ReleaseVerification(
        system,
        ReleaseTrust(ReleaseVectors.KEY_PEM),
    ).verify("/dl/latest.json", signature, archive, archive?.let { "linux-x64" })

    @Test
    fun aLinkIsNoRegularFileAndAnUnreadableOneIsNotThere() {
        val (manifest, signature) = ReleaseVectors.MANIFESTS.getValue("LATEST")
        put("/dl/latest.json", manifest)
        put("/dl/latest.json.sig", signature)
        assertEquals(ExitCode.NOT_FOUND, assertFailsWith<ReleaseRefused> { verify("/dl/latest.json.sig") }.code)
        fake.createDirectories("/dl/folder".toPath())
        assertEquals(ExitCode.NOT_FOUND, assertFailsWith<ReleaseRefused> { verify("/dl/folder") }.code)
        put("/dl/broken", "x")
        assertEquals(ExitCode.NOT_FOUND, assertFailsWith<ReleaseRefused> { verify("/dl/broken") }.code)
    }

    @Test
    fun anArchiveThatCannotBeReadIsNotThere() {
        val (manifest, signature) = ReleaseVectors.MANIFESTS.getValue("LATEST")
        put("/dl/latest.json", manifest)
        put("/dl/sig", signature)
        put("/dl/cut.tar.gz", ReleaseVectors.ARCHIVE)
        assertEquals(
            ExitCode.NOT_FOUND,
            assertFailsWith<ReleaseRefused> { verify("/dl/sig", "/dl/cut.tar.gz") }.code,
        )
        put("/dl/cut.sig", signature)
        assertEquals(ExitCode.NOT_FOUND, assertFailsWith<ReleaseRefused> { verify("/dl/cut.sig") }.code)
        put("/dl/a.tar.gz", ReleaseVectors.ARCHIVE)
        assertEquals("9.9.9", verify("/dl/sig", "/dl/a.tar.gz").version)
    }
}
