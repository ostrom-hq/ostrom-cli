// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks.gate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommandLocationTest {
    @Test
    fun aRepositoryIsLabelledByItsOriginElseItsFolder() {
        assertEquals("o/n", RealRepository("/w/n", "o/n").label)
        assertEquals("n", RealRepository("/w/n", "").label)
        assertEquals("/", RealRepository("/", "").label)
    }

    @Test
    fun theFolderOfACommandFollowsALeadingCdThenGitDashC() {
        val location = CommandLocation(FakeGit(), "/home/me")
        assertEquals("/w", location.directory("/w", "git push"))
        assertEquals("/w/sub", location.directory("/w", "cd sub && git status"))
        assertEquals("/w/sub/x", location.directory("/w", "cd sub; git -C x push"))
        assertEquals("/abs/y", location.directory("/w", "cd /abs && git --no-pager -c a=b -C y push"))
        assertEquals("/home/me", location.directory("/w", "cd ~"))
        assertEquals("/home/me", location.directory("/w", "cd"), "cd alone goes home, as the shell does")
        assertEquals("/other/repo", location.directory("/w", "cd . && cd /other/repo && git push origin main"))
        assertEquals("/other", location.directory("/w", "cd -P /other && git push"), "options are skipped")
        assertEquals("/other", location.directory("/w", "cd -- /other && git push"))
        assertEquals("/w/-", location.directory("/w", "cd - && git push"), "the previous folder is unknown")
        assertEquals("/w", location.directory("/w", ""))
    }

    @Test
    fun theRepositoryOfACommandIsAskedOfGitOncePerFolder() {
        val git = FakeGit(
            mapOf(
                "/w|rev-parse --show-toplevel" to "/w",
                "/w|remote get-url origin" to "git@github.com:o/core.git",
                "/other|rev-parse --show-toplevel" to "/other",
            ),
        )
        val location = CommandLocation(git, "/h")
        assertEquals(RealRepository("/w", "o/core"), location.repository("/w", "git push"))
        assertEquals(RealRepository("/other", ""), location.repository("/w", "git -C /other push"))
        assertNull(location.repository("/nowhere", "ls"))
        location.top("/w")
        assertEquals(1, git.calls.count { it == "/w|rev-parse --show-toplevel" })
    }

    @Test
    fun aCommandInAnotherCloneIsForeign() {
        val git = FakeGit(mapOf("/a|rev-parse --show-toplevel" to "/a", "/b|rev-parse --show-toplevel" to "/b"))
        val location = CommandLocation(git, "/h")
        val canonical = { path: String -> path.removeSuffix("/.") }
        assertNull(location.foreign("/a", "git push", "/a", canonical))
        assertNull(location.foreign("/a", "git push", "/a/.", canonical), "same folder spelled otherwise")
        assertEquals(RealRepository("/b", ""), location.foreign("/a", "cd /b && git push", "/a", canonical))
        assertNull(location.foreign("/x", "git push", "/a", canonical), "outside any repository")
        assertNull(location.foreign("/b", "git push", "", canonical), "nothing to compare")
    }
}
