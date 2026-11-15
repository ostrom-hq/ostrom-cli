// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import run.ostrom.cli.ExitCode
import run.ostrom.cli.platform.Environment
import run.ostrom.cli.platform.OperatingSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The scheduled attempt and the opt-in of `ostrom update --automatic` (TeamCode's AutomaticUpdateTest). */
class AutomaticUpdateTest {
    private val fake = installationFileSystem()
    private val files = FakeInstallationFiles()
    private val installation = storeOn(fake, files).also { it.initialize() }
    private val policy = AutomaticUpdateStore(installation)
    private val platform = AutomaticUpdatePlatform(LINUX, "1000")
    private val settings = automaticUpdateSettings(policy.paths, platform, variables("HOME" to "/home/dev"), ID)

    private fun release(version: String = "9.9.9", current: String = "1.0.0") = ReleaseCheck(
        current,
        Asset(version, "https://releases.example/a.tar.gz", "0".repeat(64), 1, null, "key", ByteArray(1), ByteArray(1)),
    )

    private fun installed(candidate: ReleaseCheck) =
        InstalledVersion(candidate.asset.version, "linux-x64", "a".repeat(64), "b".repeat(64), 1)

    private suspend fun attempt(owner: AutomaticUpdatePlatform = platform, disabled: Boolean = false) =
        AutomaticUpdateRunner(policy, owner, disabled).run(
            fetch = { error("No automatic opt-in") },
            install = { _, _ -> error("No automatic installation") },
        )

    @Test
    fun disabledRootAndEnvironmentOverrideDoNoNetworkOrWorkerWork() = runTest {
        assertEquals(fake.canonicalize(installation.paths.root), policy.paths.root)
        assertEquals("$ROOT/bin/ostrom", settings.executable)
        assertNull(attempt())
        policy.save(settings)
        assertNull(attempt())
        policy.save(settings.copy(enabled = true))
        assertNull(attempt(platform.copy(userId = ROOT_USER)))
        assertNull(attempt(platform.copy(userId = null)))
        assertNull(attempt(disabled = true))
        assertFailsWith<IllegalArgumentException> { attempt(platform.copy(userId = "1001")) }
        assertFailsWith<IllegalArgumentException> { attempt(platform.copy(os = MACOS)) }
        assertTrue(files.held.isEmpty())
        assertNull(policy.result())
    }

    @Test
    fun newerStableReleaseUsesStoredConfigurationAndBusyActivationCanRetry() = runTest {
        policy.save(settings.copy(enabled = true))
        var calls = 0
        val worker = AutomaticUpdateRunner(policy, platform, now = { 123L })
        for (busy in listOf(InstallationStore.BUSY, InstallationStore.WRITER_BUSY, SESSION_RUNNING)) {
            val deferred = assertNotNull(worker.run({ release() }) { _, _ -> stop(ExitCode.FAILED, "busy", busy) })
            assertEquals(AutomaticUpdateResult.DEFERRED, deferred.outcome)
            assertEquals(busy, deferred.diagnostic)
        }
        val refused = assertNotNull(worker.run({ release() }) { _, _ -> refuseInstallation(InstallationStore.BUSY) })
        assertEquals(AutomaticUpdateResult.DEFERRED, refused.outcome)
        val damaged = assertNotNull(worker.run({ release() }) { _, _ -> refuseInstallation("release_binary_invalid") })
        assertEquals(AutomaticUpdateResult.FAILED to "release_binary_invalid", damaged.outcome to damaged.diagnostic)
        val unreachable = assertNotNull(worker.run({ stop(ExitCode.UNREACHABLE, "down") }) { _, _ -> null })
        assertEquals("release_unreachable", unreachable.diagnostic)
        assertEquals(AutomaticUpdateResult.FAILED, unreachable.outcome)
        val updated = assertNotNull(
            worker.run({ release() }) { candidate, actual ->
                calls++
                assertEquals(settings.configuration, actual.configuration)
                installed(candidate)
            },
        )
        assertEquals(AutomaticUpdateResult(123L, AutomaticUpdateResult.UPDATED, "9.9.9"), updated)
        assertEquals(1, calls)
        assertEquals(updated, AutomaticUpdateStore(installation).result())
        assertFalse(fake.read(policy.paths.root / "automatic-update-result.json") { readUtf8() }.contains("token"))
    }

    @Test
    fun absentOlderAndPrereleaseCandidatesNeverInstall() = runTest {
        policy.save(settings.copy(enabled = true))
        for (candidate in listOf(null, release(current = "99.0.0"), release(version = "9.0.0-beta.1"))) {
            val result = assertNotNull(
                AutomaticUpdateRunner(policy, platform).run({ candidate }) { _, _ -> error("Not newer and stable") },
            )
            assertEquals(AutomaticUpdateResult.NO_UPDATE, result.outcome)
            assertNull(result.version)
        }
        val selected = assertNotNull(AutomaticUpdateRunner(policy, platform).run({ release() }) { _, _ -> null })
        assertEquals(AutomaticUpdateResult.NO_UPDATE to "9.9.9", selected.outcome to selected.version)
    }

    @Test
    fun workerLockPreventsDuplicateWorkAndCancellationPropagatesWithoutSuccess() = runTest {
        policy.save(settings.copy(enabled = true))
        val worker = AutomaticUpdateRunner(policy, platform)
        assertFailsWith<CancellationException> {
            worker.run({
                val competing = assertNotNull(worker.run({ error("Worker is busy") }) { _, _ -> error("busy") })
                assertEquals(WORKER_BUSY, competing.diagnostic)
                assertEquals(AutomaticUpdateResult.DEFERRED, competing.outcome)
                assertEquals(AutomaticUpdateResult.STARTED, policy.result()?.outcome)
                release()
            }) { _, _ -> throw CancellationException("fixture") }
        }
        assertEquals(AutomaticUpdateResult.STARTED, policy.result()?.outcome, "interrupted work is not updated")
        val failed = assertNotNull(worker.run({ release() }) { _, _ -> error("DO_NOT_LOG_REMOTE_BODY") })
        assertEquals(AutomaticUpdateResult.FAILED, failed.outcome)
        assertEquals("automatic_update_failed", failed.diagnostic)
        val recorded = fake.read(policy.paths.root / "automatic-update-result.json") { readUtf8() }
        assertFalse(recorded.contains("DO_NOT_LOG_REMOTE_BODY"))
        assertEquals(AutomaticUpdateResult.UPDATED, worker.run({ release() }) { c, _ -> installed(c) }?.outcome)
    }

    @Test
    fun anAttemptPastItsDeadlineFails() = runTest {
        policy.save(settings.copy(enabled = true))
        val worker = AutomaticUpdateRunner(policy, platform, timeoutMillis = 1_000)
        val result = assertNotNull(worker.run({ delay(5_000).let { release() } }) { _, _ -> error("too late") })
        assertEquals(AutomaticUpdateResult.FAILED to "automatic_update_timeout", result.outcome to result.diagnostic)
    }

    @Test
    fun registrationSetupFailsClosedAndOffDoesNotStopAnInflightWorker() = runTest {
        val scheduler = FakeSchedule(policy)
        val manager = AutomaticUpdateManager(policy, scheduler, platform) { settings }
        assertNull(manager.configure("off"), "nothing to turn off")
        assertNull(manager.configure("uninstall"), "nothing to remove")
        scheduler.cancelInstall = true
        assertFailsWith<CancellationException> { manager.configure("on") }
        assertFalse(assertNotNull(policy.settings()).enabled)
        scheduler.cancelInstall = false
        assertTrue(assertNotNull(manager.configure("on")).enabled)
        assertTrue(assertNotNull(manager.configure("on")).enabled, "on again keeps the recorded settings")
        val worker = assertNotNull(policy.tryLock(policy.workerLock))
        worker.use {
            assertFalse(assertNotNull(manager.configure("off")).enabled)
            assertEquals(0, scheduler.removed)
            assertEquals(WORKER_BUSY, assertFailsWith<InstallationException> { manager.configure("uninstall") }.code)
        }
        val settingsLock = assertNotNull(policy.tryLock(policy.managementLock))
        settingsLock.use {
            val busy = assertFailsWith<InstallationException> { manager.configure("off") }
            assertEquals("automatic_settings_busy", busy.code)
        }
        assertNull(manager.configure("uninstall"))
        assertNull(policy.settings())
        assertEquals(1, scheduler.removed)
        assertFailsWith<IllegalArgumentException> { manager.configure("status") }
    }

    @Test
    fun theOptInBelongsToOneUserNeverToRoot() = runTest {
        val scheduler = FakeSchedule(policy)
        for ((user, code) in listOf(ROOT_USER to USER_INSTALLATION_REQUIRED, null to USER_UNAVAILABLE)) {
            val manager = AutomaticUpdateManager(policy, scheduler, platform.copy(userId = user)) { settings }
            assertEquals(code, assertFailsWith<InstallationException> { manager.configure("on") }.code)
        }
        policy.save(settings)
        val other = AutomaticUpdateManager(policy, scheduler, platform.copy(userId = "1001")) { settings }
        assertFailsWith<IllegalArgumentException> { other.configure("off") }
        val elsewhere = AutomaticUpdateManager(policy, scheduler, platform.copy(os = MACOS)) { settings }
        assertFailsWith<IllegalArgumentException> { elsewhere.configure("off") }
    }

    @Test
    fun thePolicyAndTheResultAreThisInstallationsPrivateFiles() {
        val invalid = AutomaticUpdateStore.SETTINGS_INVALID
        policy.save(settings)
        assertEquals(settings, policy.settings())
        assertFailsWith<IllegalArgumentException> { policy.save(settings.copy(executable = "/elsewhere/bin/ostrom")) }
        fake.write(policy.policy) { writeUtf8(settings.copy(executable = "/elsewhere/bin/ostrom").json().toString()) }
        assertEquals(invalid, assertFailsWith<InstallationException> { policy.settings() }.code)
        for (text in listOf("not json", "{}", "x".repeat(9000))) {
            fake.write(policy.policy) { writeUtf8(text) }
            assertEquals(invalid, assertFailsWith<InstallationException> { policy.settings() }.code, text)
        }
        policy.save(settings)
        files.foreign += policy.policy.toString()
        assertEquals(invalid, assertFailsWith<InstallationException> { policy.settings() }.code, "not private")
        files.foreign.clear()
        policy.remove()
        policy.remove()
        assertNull(policy.settings())
        fake.createDirectories(policy.policy)
        assertEquals(invalid, assertFailsWith<InstallationException> { policy.settings() }.code, "a folder")
        val written = assertFailsWith<InstallationException> { policy.save(settings) }
        assertEquals("release_write_failed", written.code)
        files.foreign += policy.paths.root.toString()
        val record = AutomaticUpdateResult(1, AutomaticUpdateResult.STARTED)
        assertEquals(InstallationStore.INVALID, assertFailsWith<InstallationException> { policy.record(record) }.code)
        files.foreign.clear()
        files.lockFails = true
        val lock = assertFailsWith<InstallationException> { policy.tryLock(policy.workerLock) }
        assertEquals("release_lock_failed", lock.code)
    }

    @Test
    fun settingsAndResultsReadOnlyWhatTheyWrote() {
        assertEquals(settings, AutomaticUpdateSettings.of(settings.json()))
        val result = AutomaticUpdateResult(5, AutomaticUpdateResult.FAILED, "1.2.3", "release_unreachable")
        assertEquals(result, AutomaticUpdateResult.of(result.json()))
        val json = settings.json()
        val broken = listOf(
            null,
            JsonObject(json + ("schemaVersion" to JsonPrimitive(2))),
            JsonObject(json - "schemaVersion"),
            JsonObject(json + ("enabled" to JsonPrimitive("true"))),
            JsonObject(json + ("enabled" to JsonPrimitive("x"))),
            JsonObject(json - "enabled"),
        ) + listOf("id", "configuration", "executable", "registration", "os", "userId").map {
            JsonObject(json - it)
        }
        broken.forEach { assertFailsWith<IllegalArgumentException> { AutomaticUpdateSettings.of(it) } }
        val recorded = result.json()
        listOf(
            null,
            JsonObject(recorded - "schemaVersion"),
            JsonObject(recorded - "attemptedAtMillis"),
            JsonObject(
                recorded + ("attemptedAtMillis" to JsonPrimitive("5")),
            ),
            JsonObject(recorded - "outcome"),
        ).forEach { assertFailsWith<IllegalArgumentException> { AutomaticUpdateResult.of(it) } }
        val started = AutomaticUpdateResult(5, "STARTED")
        assertEquals(started, AutomaticUpdateResult.of(started.json()))
    }

    @Test
    fun settingsAndResultsRefuseWhatNoInstallationWrites() {
        val bad = listOf<() -> Any>(
            { settings.copy(id = "A".repeat(32)) },
            { settings.copy(os = "plan9") },
            { settings.copy(userId = ROOT_USER) },
            { settings.copy(userId = "maya") },
            { settings.copy(os = WINDOWS) },
            { settings.copy(configuration = "relative/ostrom") },
            { settings.copy(executable = "/home/dev/\u0001/ostrom") },
            { settings.copy(registration = "/home/dev/\u007f/x") },
            { settings.copy(registration = "/home/dev/other.timer") },
            { AutomaticUpdateResult(-1, AutomaticUpdateResult.STARTED) },
            { AutomaticUpdateResult(1, "LOST") },
            { AutomaticUpdateResult(1, AutomaticUpdateResult.UPDATED, version = "nine") },
            { AutomaticUpdateResult(1, AutomaticUpdateResult.FAILED, diagnostic = "with space") },
        )
        bad.forEachIndexed { index, make -> assertFailsWith<IllegalArgumentException>("case $index") { make() } }
        val windows = settings.copy(os = WINDOWS, userId = "ACME\\maya", registration = "/r/${settings.label}.xml")
        assertEquals(WINDOWS, windows.os)
        assertFailsWith<IllegalArgumentException> { windows.copy(userId = "") }
        assertFailsWith<IllegalArgumentException> { windows.copy(userId = "ACME\u0000maya") }
    }

    @Test
    fun newSettingsPutTheJobWhereEachSystemLooksForIt() {
        val home = onLinux("HOME" to "/home/dev")
        val label = "run.ostrom.updates.$ID"
        val mac = automaticUpdateSettings(policy.paths, AutomaticUpdatePlatform(MACOS, "501"), home, ID)
        assertEquals("/home/dev/Library/LaunchAgents/$label.plist", mac.registration)
        val windows = automaticUpdateSettings(policy.paths, AutomaticUpdatePlatform(WINDOWS, "ACME\\maya"), home, ID)
        assertEquals("$ROOT/$label.xml", windows.registration)
        assertEquals("/home/dev/.config/systemd/user/$label.timer", settings.registration)
        assertFalse(settings.enabled)
        val xdg = onLinux("HOME" to "/home/dev", "XDG_CONFIG_HOME" to "/home/dev/cfg")
        val linux = automaticUpdateSettings(policy.paths, platform, xdg)
        assertEquals("/home/dev/cfg/systemd/user/${linux.label}.timer", linux.registration)
        assertEquals("/home/dev/cfg/ostrom", linux.configuration)
        assertTrue(linux.id != ID && linux.id.length == 32)
        val noUser = assertFailsWith<InstallationException> {
            automaticUpdateSettings(policy.paths, platform.copy(userId = null), home)
        }
        assertEquals(USER_UNAVAILABLE, noUser.code)
        for (environment in listOf(onLinux(), onLinux("OSTROM_HOME" to "/home/dev/o"))) {
            val code = assertFailsWith<InstallationException> {
                automaticUpdateSettings(policy.paths, platform, environment)
            }.code
            assertEquals(HOME_UNAVAILABLE, code)
        }
    }

    @Test
    fun thePlatformNamesTheUserTheJobBelongsTo() {
        fun on(system: OperatingSystem, vararg values: Pair<String, String>) =
            object : Environment {
                override fun get(name: String): String? = values.toMap()[name]
                override val operatingSystem = system
                override val architecture = "x64"
            }
        val os = OperatingSystem.entries
        assertEquals(
            listOf(AutomaticUpdatePlatform(MACOS, "501"), AutomaticUpdatePlatform(LINUX, "501")),
            os.take(2).map { automaticUpdatePlatform(on(it)) { "501" } },
        )
        val windows = OperatingSystem.WINDOWS
        assertEquals("ACME\\maya", automaticUpdatePlatform(on(windows, "USERNAME" to "maya", "USERDOMAIN" to "ACME")) {
            error("no uid on Windows")
        }.userId)
        assertEquals("maya", automaticUpdatePlatform(on(windows, "USERNAME" to "maya")) { "" }.userId)
        assertNull(automaticUpdatePlatform(on(windows, "USERDOMAIN" to "ACME")) { "" }.userId)
    }

    private companion object {
        val ID = "a".repeat(32)
    }
}

/** The [variables] given, on Linux whatever system runs the tests (the config folder follows the system). */
private fun onLinux(vararg variables: Pair<String, String>): Environment =
    object : Environment {
        override fun get(name: String): String? = variables.toMap()[name]?.takeIf { it.isNotEmpty() }
        override val operatingSystem = OperatingSystem.LINUX
        override val architecture = "x64"
    }

private class FakeSchedule(private val policy: AutomaticUpdateStore) : AutomaticUpdateSchedule {
    var cancelInstall = false
    var removed = 0

    override fun owns(settings: AutomaticUpdateSettings) = true

    override suspend fun install(settings: AutomaticUpdateSettings) {
        assertFalse(assertNotNull(policy.settings()).enabled)
        if (cancelInstall) throw CancellationException("fixture")
    }

    override suspend fun start(settings: AutomaticUpdateSettings) {
        assertTrue(assertNotNull(policy.settings()).enabled)
    }

    override suspend fun disable(settings: AutomaticUpdateSettings) {
        assertFalse(assertNotNull(policy.settings()).enabled)
    }

    override suspend fun uninstall(settings: AutomaticUpdateSettings) {
        assertFalse(assertNotNull(policy.settings()).enabled)
        removed++
    }
}
