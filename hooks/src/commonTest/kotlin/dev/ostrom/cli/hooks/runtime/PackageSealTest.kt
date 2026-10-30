// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PackageSealTest {
    private val gates = """{"actions":[]}"""
    private val view = """{"version":"7"}"""
    private val manifest = "${Sha256.hex(gates.encodeToByteArray())}  /s/plugin/ostrom/gates.json\n" +
        "${Sha256.hex(view.encodeToByteArray())}  /s/package.json\n"

    private fun files() = FakeFiles(
        mutableMapOf(
            "/s/plugin/ostrom/gates.json" to gates,
            "/s/package.json" to view,
            "/s/package.sha256" to manifest,
        ),
    )

    private fun seal(files: FakeFiles, digest: String? = Sha256.hex(manifest.encodeToByteArray())) =
        PackageSeal(digest, "/s/package.sha256", files)

    private val prefix = "The session's package is not the one Ostrom signed and laid down: "

    @Test
    fun theLaidDownPackageIsIntactAndASessionWithoutOneHasNothingToCheck() {
        assertNull(seal(files()).broken())
        assertNull(PackageSeal(null, null, FakeFiles()).broken())
    }

    @Test
    fun aFileOfThePackageChangedAfterTheLaunchIsSeenAtTheNextHook() {
        // S2-33: the package was checked once, then package.json, scripts/ and plugin/ stayed the agent's to rewrite
        val changed = files().apply { texts["/s/package.json"] = """{"version":"8"}""" }
        assertEquals(prefix + "/s/package.json was changed", seal(changed).broken())
        val gone = files().apply { texts.remove("/s/plugin/ostrom/gates.json") }
        assertEquals(prefix + "/s/plugin/ostrom/gates.json is missing", seal(gone).broken())
    }

    @Test
    fun aRewrittenOrUnreadableManifestBreaksTheSeal() {
        val rewritten = files().apply { texts["/s/package.sha256"] = manifest.lines().first() }
        assertEquals(prefix + "its manifest was changed", seal(rewritten).broken())
        val noSeparator = "garbage\n"
        val unreadable = files().apply { texts["/s/package.sha256"] = noSeparator }
        assertEquals(
            prefix + "its manifest is unreadable",
            seal(unreadable, Sha256.hex(noSeparator.encodeToByteArray())).broken(),
        )
        val incomplete = prefix + "its seal is incomplete or its manifest cannot be read"
        assertEquals(incomplete, PackageSeal(null, "/s/package.sha256", files()).broken())
        assertEquals(incomplete, PackageSeal("abc", null, files()).broken())
        assertEquals(incomplete, PackageSeal("abc", "/s/nowhere", files()).broken())
    }
}
