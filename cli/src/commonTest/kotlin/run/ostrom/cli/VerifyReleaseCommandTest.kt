// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import run.ostrom.cli.update.ReleaseVectors
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ostrom verify-release`: a release downloaded by hand, checked offline against the shipped roots. */
class VerifyReleaseCommandTest : CliKit() {
    private fun put(path: String, text: String) {
        fileSystem.createDirectories(path.toPath().parent!!)
        fileSystem.write(path.toPath()) { writeUtf8(text) }
    }

    private fun release(name: String = "LATEST") {
        val (manifest, signature) = ReleaseVectors.MANIFESTS.getValue(name)
        put("/dl/latest.json", manifest)
        put("/dl/latest.json.sig", signature)
    }

    private val files = arrayOf("verify-release", "--manifest", "/dl/latest.json", "--signature", "/dl/latest.json.sig")

    @Test
    fun aSignedManifestIsVerifiedOffline() {
        release()
        assertEquals(0, cli().run(files))
        assertOut("Verified ostrom 9.9.9.\n")
        assertOut("Release key: ")
        assertEquals(emptyList(), releaseUrls, "nothing is fetched")
    }

    @Test
    fun anArchiveIsCheckedAgainstTheSizeAndSha256TheManifestSigns() {
        release()
        put("/dl/ostrom-9.9.9-linux-x64.tar.gz", ReleaseVectors.ARCHIVE)
        val archive = arrayOf("--archive", "/dl/ostrom-9.9.9-linux-x64.tar.gz", "--target", "linux-x64")
        assertEquals(0, cli().run(files + archive))
        assertOut("Verified archive for linux-x64.\n")
        put("/dl/other.tar.gz", "something else, of another size")
        assertEquals(78, cli().run(files + arrayOf("--binary", "/dl/other.tar.gz", "--target", "linux-x64")))
        assertTrue(console.err.contains("does not match the size and SHA-256"), console.err.toString())
        put("/dl/same-size.tar.gz", "fake ostrom release archivf")
        console.err.clear()
        assertEquals(78, cli().run(files + arrayOf("--archive", "/dl/same-size.tar.gz", "--target", "linux-x64")))
        assertEquals(66, cli().run(files + arrayOf("--archive", "/dl/missing.tar.gz", "--target", "linux-x64")))
        assertEquals(66, cli().run(files + arrayOf("--archive", "/dl/same-size.tar.gz", "--target", "plan9-mips")))
        assertTrue(console.err.contains("No ostrom 9.9.9 is built for plan9-mips"), console.err.toString())
    }

    @Test
    fun anArchiveAndItsTargetGoTogether() {
        release()
        assertEquals(64, cli().run(files + arrayOf("--archive", "/dl/a.tar.gz")))
        assertEquals(64, cli().run(files + arrayOf("--target", "linux-x64")))
        assertTrue(console.err.contains("--archive and --target go together"), console.err.toString())
    }

    @Test
    fun aManifestThatIsNotSignedByARootIsRefusedBeforeItIsRead() {
        put("/dl/latest.json", ReleaseVectors.MANIFESTS.getValue("LATEST").first)
        put("/dl/latest.json.sig", ReleaseVectors.MANIFESTS.getValue("OLDER").second)
        assertEquals(78, cli().run(files))
        assertTrue(console.err.contains("does not match any root"), console.err.toString())
        // signed, but not a manifest: refused only once its signature holds
        release("NOT_JSON")
        assertEquals(1, cli().run(files))
        release("NO_VERSION")
        assertEquals(1, cli().run(files))
        release("NO_URL")
        put("/dl/a.tar.gz", ReleaseVectors.ARCHIVE)
        assertEquals(1, cli().run(files + arrayOf("--archive", "/dl/a.tar.gz", "--target", "linux-x64")))
        release("NO_ASSETS")
        assertEquals(66, cli().run(files + arrayOf("--archive", "/dl/a.tar.gz", "--target", "linux-x64")))
    }

    @Test
    fun metadataThatIsMissingHugeOrALinkIsRefused() {
        assertEquals(66, cli().run(files), "no file")
        put("/dl/latest.json", "x".repeat(16 * 1024 + 1))
        put("/dl/latest.json.sig", "sig")
        assertEquals(1, cli().run(files), "a manifest larger than any release's")
        assertTrue(console.err.contains("is larger than"), console.err.toString())
        release()
        fileSystem.delete("/dl/latest.json.sig".toPath())
        fileSystem.createSymlink("/dl/latest.json.sig".toPath(), "/dl/latest.json".toPath())
        assertEquals(66, cli().run(files), "a link is no regular file")
        assertEquals(64, cli().run(arrayOf("verify-release", "--manifest", "/dl/latest.json")), "the signature is needed")
    }
}
