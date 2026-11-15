// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.platform

import okio.FileSystem
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServiceHostTest {
    private val folder = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ostrom-service-host-${Random.nextLong().toULong()}"

    @AfterTest
    fun cleanUp() = FileSystem.SYSTEM.deleteRecursively(folder, mustExist = false)

    @Test
    fun theExclusiveLockHasOneHolderAtATimeAndIsFreedWhenClosed() {
        FileSystem.SYSTEM.createDirectories(folder)
        val path = (folder / "daemon.lock").toString()
        val first = assertNotNull(holdExclusiveLock(path))
        assertNull(holdExclusiveLock(path), "a second holder, even in this process, is refused")
        first.close()
        holdExclusiveLock(path)!!.close()
    }

    @Test
    fun aFileIsPublishedOnlyWhereNoneExistsAndItsTemporaryNameIsGoneEitherWay() {
        val fs = FileSystem.SYSTEM
        fs.createDirectories(folder)
        val target = folder / "service.json"
        fs.write(folder / "one.tmp") { writeUtf8("one") }
        assertTrue(publishFileIfAbsent((folder / "one.tmp").toString(), target.toString()))
        fs.write(folder / "two.tmp") { writeUtf8("two") }
        assertFalse(publishFileIfAbsent((folder / "two.tmp").toString(), target.toString()))
        assertEquals("one", fs.read(target) { readUtf8() })
        assertEquals(listOf("service.json"), fs.list(folder).map { it.name })
    }

    @Test
    fun theAccountIsNamedAndNoStopIsRequestedBeforeASignal() {
        assertTrue(currentServiceAccount().userId.isNotEmpty())
        assertFalse(serviceStopRequested())
    }
}
