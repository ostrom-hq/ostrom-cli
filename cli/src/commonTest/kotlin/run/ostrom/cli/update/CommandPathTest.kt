// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.update

import okio.Path.Companion.toPath
import run.ostrom.cli.platform.UserPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** `ostrom install` makes the launcher reachable by name (TeamCode's CommandPathTest). */
class CommandPathTest {
    private val fake = installationFileSystem()
    private val launcher = "/home/dev/ostrom/bin/ostrom".toPath()
    private var userPath: UserPath? = null
    private val written = mutableListOf<UserPath>()

    private fun posix(vararg variables: Pair<String, String>) = CommandPath(
        "linux-x64", launcher, variables(*variables), fake, { error("not Windows") }, { error("not Windows") },
    ).ensure()

    private fun windows(vararg variables: Pair<String, String>) = CommandPath(
        "windows-x64", "C:/Users/dev/AppData/Local/Ostrom/bin/ostrom.exe".toPath(), variables(*variables), fake,
        { userPath }, { written += it },
    ).ensure()

    @Test
    fun aLinkInLocalBinNamesTheLauncher() {
        val outcome = posix("HOME" to "/home/dev/", "PATH" to "/usr/bin:/home/dev/.local/bin/")
        assertEquals(
            listOf("Command: /home/dev/.local/bin/ostrom -> $launcher", "ostrom is on your PATH."),
            outcome.lines,
        )
        assertEquals(launcher, fake.metadata("/home/dev/.local/bin/ostrom".toPath()).symlinkTarget)
        assertIs<CommandPathOutcome.Ready>(posix("HOME" to "/home/dev", "PATH" to "/home/dev/.local/bin"), "again")
    }

    @Test
    fun aFolderNotOnThePathIsSaidWithTheLineToAdd() {
        assertEquals(
            listOf(
                "Command: /home/dev/.local/bin/ostrom -> $launcher",
                "/home/dev/.local/bin is not in your PATH, so 'ostrom' will not be found yet. Add this line to your " +
                    "shell profile (for example ~/.zshrc or ~/.bashrc), then open a new terminal:",
                "  export PATH=\"\$HOME/.local/bin:\$PATH\"",
            ),
            posix("HOME" to "/home/dev", "PATH" to "::/usr/bin").lines,
        )
        fake.createDirectories("/.local/bin".toPath())
        assertEquals("  export PATH=\"/.local/bin:\$PATH\"", posix("HOME" to "/").lines.last(), "a home of /")
    }

    @Test
    fun aRelativeLinkToTheLauncherIsOurs() {
        fake.createDirectories("/home/dev/.local/bin".toPath())
        fake.createSymlink("/home/dev/.local/bin/ostrom".toPath(), "../../ostrom/bin/ostrom".toPath())
        assertIs<CommandPathOutcome.Ready>(posix("HOME" to "/home/dev"))
    }

    @Test
    fun aNameSomeoneElseHoldsIsLeftUntouched() {
        fake.createDirectories("/home/dev/.local/bin".toPath())
        fake.write("/home/dev/.local/bin/ostrom".toPath()) { writeUtf8("mine") }
        val outcome = assertIs<CommandPathOutcome.Conflict>(posix("HOME" to "/home/dev"))
        assertEquals("/home/dev/.local/bin/ostrom".toPath(), outcome.path)
        assertEquals(2, outcome.lines.size)
        assertEquals("mine", fake.read(outcome.path) { readUtf8() })
        fake.delete(outcome.path)
        fake.createSymlink(outcome.path, "/usr/bin/other".toPath())
        assertIs<CommandPathOutcome.Conflict>(posix("HOME" to "/home/dev"))
    }

    @Test
    fun aLinkThatCannotBeMadeIsAFailure() {
        fake.write("/home/dev/.local".toPath()) { writeUtf8("a file") }
        val refused = assertFailsWith<InstallationException> { posix("HOME" to "/home/dev") }
        assertEquals("release_command_link_failed", refused.code)
        assertEquals(HOME_UNAVAILABLE, assertFailsWith<InstallationException> { posix() }.code)
    }

    @Test
    fun aLinkMadeMeanwhileByAnotherInstallerIsKept() {
        val racing = FailingFileSystem(fake)
        racing.fail = { function, path ->
            if (function == "createSymlink" && path.name == "ostrom") {
                fake.createSymlink(path, launcher)
                true
            } else {
                false
            }
        }
        val home = variables("HOME" to "/home/dev")
        val outcome = CommandPath("linux-x64", launcher, home, racing, { null }, { }).ensure()
        assertIs<CommandPathOutcome.Ready>(outcome)
    }

    @Test
    fun onWindowsTheLauncherFolderIsAppendedToTheUserPathOnce() {
        val folder = "C:/Users/dev/AppData/Local/Ostrom/bin"
        assertEquals("Added $folder to your user PATH. Open a new terminal to use 'ostrom'.", windows().lines.last())
        assertEquals(UserPath(folder, expandable = true), written.single())
        userPath = UserPath("C:\\tools;", expandable = false)
        windows()
        assertEquals(UserPath("C:\\tools;$folder", expandable = false), written.last())
        userPath = UserPath(";;%LOCAL%\\Ostrom\\bin\\;%UNSET%", expandable = true)
        assertEquals(
            "$folder is already in your user PATH.",
            windows("LOCAL" to "c:/users/dev/appdata/local").lines.last(),
        )
        assertEquals(2, written.size)
        assertNull(fake.metadataOrNull("C:/Users".toPath()))
    }
}
