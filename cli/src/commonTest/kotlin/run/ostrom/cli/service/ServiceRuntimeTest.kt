// SPDX-License-Identifier: Apache-2.0
// Dense assertions of the service tests, in the style of the ported ones.
@file:Suppress(
    "MaxLineLength",
)

package run.ostrom.cli.service

import com.github.ajalt.clikt.core.CliktError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import okio.FileSystem
import okio.Path.Companion.toPath
import run.ostrom.cli.platform.holdExclusiveLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServiceRuntimeTest {
    private fun log(settings: ServiceSettings) = FileSystem.SYSTEM.read(serviceLogPath(settings.config).toPath()) { readUtf8() }

    @Test
    fun aSignalStopsTheServiceAndOnWindowsSoDoesItsOwnStopMarker() = serviceScratch { scratch ->
        val linux = scratch.install(scratch.settings("linux"))
        assertFalse(stopRequested(linux) { false })
        assertTrue(stopRequested(linux) { true })

        val task = scratch.install(scratch.settings("windows"))
        assertFalse(stopRequested(task) { false }, "no marker")
        writePrivateFile(serviceStopPath(task.config), "another service")
        assertFalse(stopRequested(task) { false }, "another service's marker")
        writePrivateFile(serviceStopPath(task.config), task.id)
        assertTrue(stopRequested(task) { false })
        assertTrue(stopRequested(task) { true })
    }

    @Test
    fun theServiceHoldsItsLockLogsAndRunsTheDaemonUntilItStops() = serviceScratch { scratch ->
        val settings = scratch.install(scratch.settings("linux"))
        var lockedWhileRunning = false
        serve(settings, { _, report, stop ->
            lockedWhileRunning = holdExclusiveLock(daemonLockPath(settings.config)) == null
            report("hosting nothing")
            assertTrue(stop())
        }) { true }
        assertTrue(lockedWhileRunning, "daemon.lock is held for the whole run")
        val lines = log(settings).lines().filter { it.isNotEmpty() }
        assertEquals(listOf("Service started.", "hosting nothing", "Service stopped."), lines.map { it.substringAfter(' ') })
        assertNotNull(holdExclusiveLock(daemonLockPath(settings.config)), "the lock is released at the end").close()
    }

    @Test
    fun aSecondServiceOfTheSameConfigurationRefusesBeforeWritingAnything() = serviceScratch { scratch ->
        val settings = scratch.install(scratch.settings("linux"))
        val first = assertNotNull(holdExclusiveLock(daemonLockPath(settings.config)))
        try {
            var ran = false
            assertFailsWith<CliktError> { serve(settings, { _, _, _ -> ran = true }) { false } }
            assertFalse(ran)
            assertFalse(FileSystem.SYSTEM.exists(serviceLogPath(settings.config).toPath()), "not even the log is replaced")
            assertTrue(serviceRunning(settings.config))
        } finally {
            first.close()
        }
        assertFalse(serviceRunning(settings.config))
    }

    @Test
    fun aFailureIsLoggedWithoutItsDetailsAndPassedOn() = serviceScratch { scratch ->
        val settings = scratch.install(scratch.settings("linux"))
        assertFailsWith<IllegalStateException> { serve(settings, { _, _, _ -> error("secret detail") }) { false } }
        val text = log(settings)
        assertTrue("Service failed." in text)
        assertFalse("secret detail" in text)
        assertFailsWith<CancellationException> { serve(settings, { _, _, _ -> throw CancellationException("stop") }) { false } }
    }

    @Test
    fun theLogKeepsTheLastLinesAndCutsLongOnes() = serviceScratch { scratch ->
        val settings = scratch.install(scratch.settings("linux"))
        serve(settings, { _, report, _ ->
            repeat(200) {
                report("line $it")
                yield()
            }
            report("x".repeat(1_000))
        }) { false }
        val lines = log(settings).lines().filter { it.isNotEmpty() }
        assertEquals(128, lines.size, "the last 128 lines")
        assertTrue("line 199" in lines[lines.size - 3], lines.toString())
        assertTrue(lines[lines.size - 2].length < 300, "a line is cut")
    }

    @Test
    fun withNothingToHostTheDaemonWaitsForTheStopRequest() = serviceScratch {
        val reports = mutableListOf<String>()
        var polls = 0
        runDaemon(ServiceSettings("a".repeat(32), "/opt/ostrom", "/tmp/config.toml", "/tmp/reg", "linux", "501"), reports::add) { ++polls > 3 }
        assertEquals(4, polls)
        assertEquals(1, reports.size)
    }

    @Test
    fun withTheDefaultsTheServiceRunsUntilItIsCancelled() = serviceScratch { scratch ->
        val settings = scratch.install(scratch.settings("linux"))
        assertFalse(stopRequested(settings), "no signal on the test's JVM")
        val running = launch { serve(settings) }
        testScheduler.advanceTimeBy(1_000)
        running.cancelAndJoin()
        assertTrue("Service started." in log(settings))
    }

    @Test
    fun aStopMarkerThatIsNotAPlainSmallFileIsRefused() = serviceScratch { scratch ->
        val task = scratch.install(scratch.settings("windows"))
        val marker = serviceStopPath(task.config).toPath()
        FileSystem.SYSTEM.write(marker) { writeUtf8("x".repeat(100)) }
        assertFailsWith<IllegalArgumentException> { stopRequested(task) { false } }
        FileSystem.SYSTEM.delete(marker)
        FileSystem.SYSTEM.createDirectory(marker)
        assertFailsWith<IllegalArgumentException> { stopRequested(task) { false } }
        FileSystem.SYSTEM.delete(marker)
        if (hostOs() == "windows") return@serviceScratch
        FileSystem.SYSTEM.write(scratch.home / "elsewhere") { writeUtf8(task.id) }
        FileSystem.SYSTEM.createSymlink(marker, scratch.home / "elsewhere")
        assertFailsWith<IllegalArgumentException> { stopRequested(task) { false } }
    }

    @Test
    fun aLockFileThatCannotBeOpenedIsNoRunningService() {
        assertFalse(serviceRunning("/nowhere-ostrom/config.toml"))
    }
}
