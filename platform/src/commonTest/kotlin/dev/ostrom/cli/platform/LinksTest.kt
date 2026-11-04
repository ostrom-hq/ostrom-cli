// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.platform

import okio.FileSystem
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Links made and followed by the system, Windows included: okio does neither there. */
class LinksTest {
    private val folder = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ostrom-links-${Random.nextLong().toULong()}"

    @AfterTest
    fun cleanUp() = FileSystem.SYSTEM.deleteRecursively(folder, mustExist = false)

    @Test
    fun aLinkIsMadeOnceAndFollowedToWhereItLeads() {
        FileSystem.SYSTEM.createDirectories(folder / "real" / "inside")
        val real = checkNotNull(realPath((folder / "real").toString()))
        // no skip: a system that cannot make the link fails here (GitHub's Windows runners make it as administrator)
        assertTrue(symbolicLink(real, (folder / "link").toString()), "links can be made here")
        assertFalse(symbolicLink(real, (folder / "link").toString()), "already there")
        val inside = realPath((folder / "real" / "inside").toString())
        assertEquals(inside, realPath((folder / "link" / "inside").toString()))
        assertEquals(real, realPath((folder / "link").toString()))
        assertNull(realPath((folder / "nowhere").toString()))
    }
}
