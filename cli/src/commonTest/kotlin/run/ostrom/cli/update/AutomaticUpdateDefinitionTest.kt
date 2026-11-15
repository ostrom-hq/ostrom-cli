// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.update

import kotlinx.coroutines.test.runTest
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The definitions of the hourly user job (TeamCode's AutomaticUpdateDefinitionTest). */
class AutomaticUpdateDefinitionTest {
    @Test
    fun definitionsUseStableLauncherAndIndependentHourlyUserJobsWithoutCredentials() {
        val id = "a".repeat(32)
        for (os in listOf(MACOS, LINUX, WINDOWS)) {
            val label = "run.ostrom.updates.$id"
            val extension = mapOf(MACOS to ".plist", LINUX to ".timer", WINDOWS to ".xml").getValue(os)
            val settings = AutomaticUpdateSettings(
                id = id,
                configuration = "/private/config/ostrom",
                executable = "/private/application's $ directory/bin/ostrom",
                os = os,
                userId = if (os == WINDOWS) "ACME\\maya" else "1000",
                registration = "/private/registration/$label$extension",
            )
            val files = automaticUpdateDefinitions(settings)
            assertEquals(if (os == LINUX) 2 else 1, files.size)
            val text = files.values.joinToString("\n") { if (os == WINDOWS) utf16(it) else it.decodeToString() }
            assertTrue("--automatic-run" in text && "--root" in text, text)
            if (os != LINUX) assertTrue("/private/application&apos;s $ directory" in text, text)
            assertFalse("credentials" in text || "OSTROM_" in text || "login" in text.lowercase())
            assertJob(os, text, files)
        }
    }

    private fun assertJob(os: String, text: String, files: Map<Path, ByteArray>) {
        when (os) {
            LINUX -> {
                assertTrue("OnCalendar=hourly" in text && "Persistent=true" in text)
                assertTrue("ExecStart=\"/usr/bin/env\" \"--\"" in text, text)
                assertTrue("$$ directory" in text, text)
                val service = "/private/registration/run.ostrom.updates.${"a".repeat(32)}.service"
                assertTrue(files.keys.any { it.toString() == service })
            }
            MACOS -> {
                assertTrue("3600" in text && "&apos;" in text)
                assertFalse("KeepAlive" in text)
            }
            else -> {
                assertTrue("PT1H" in text && "InteractiveToken" in text && "IgnoreNew" in text)
                assertFalse("<UserId>" in text, "the task runs as the user who registers it")
                assertEquals(listOf(0xff.toByte(), 0xfe.toByte()), files.values.single().take(2))
            }
        }
    }

    private fun utf16(bytes: ByteArray) = (2 until bytes.size step 2).map { index ->
        (bytes[index].toInt().and(255) + bytes[index + 1].toInt().and(255) * 256).toChar()
    }.joinToString("")

    @Test
    fun aSystemdExecutableWithoutSpecialCharactersIsQuotedAsItIs() {
        assertEquals("\"/opt/ostrom\" \"100%%\"", systemdCommand(listOf("/opt/ostrom", "100%")))
        assertEquals("\"/usr/bin/env\" \"--\" \"/o\\\\x\"", systemdCommand(listOf("/o\\x")))
        assertEquals("&amp;&lt;&gt;&quot;&apos;", xml("&<>\"'"))
    }

    @Test
    fun modifiedRegistrationIsRejectedBeforeMissingFilesAreWrittenOrAnyManagerIsCalled() = runTest {
        val fs = installationFileSystem()
        val store = storeOn(fs, FakeInstallationFiles()).also { it.initialize() }
        val policy = AutomaticUpdateStore(store)
        val settings = automaticUpdateSettings(
            policy.paths,
            AutomaticUpdatePlatform(LINUX, "1000"),
            variables("HOME" to "/home/dev"),
        )
        val files = automaticUpdateDefinitions(settings)
        files.forEach { (path, bytes) ->
            fs.createDirectories((path / "..").normalized())
            fs.write(path) { write(bytes) }
        }
        val calls = mutableListOf<List<String>>()
        val schedule = AutomaticUpdateScheduleImpl(fs, "C:\\Windows") { _, arguments -> 0.also { calls += arguments } }
        assertTrue(schedule.owns(settings))
        val service = files.keys.single { it.name.endsWith(".service") }
        fs.write(service) { writeUtf8("external registration") }
        val modified = assertFailsWith<InstallationException> { schedule.owns(settings) }
        assertEquals("automatic_registration_modified", modified.code)
        fs.delete(settings.registration.toPath())
        val install = assertFailsWith<InstallationException> { schedule.install(settings) }
        assertEquals("automatic_registration_modified", install.code)
        assertFalse(fs.exists(settings.registration.toPath()))
        assertTrue(calls.isEmpty())
    }
}
