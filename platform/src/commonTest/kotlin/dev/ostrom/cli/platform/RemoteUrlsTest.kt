// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RemoteUrlsTest {
    @Test
    fun slugReadsEveryRemoteSpelling() {
        assertEquals("ostrom-hq/ostrom", RemoteUrls.slug("git@github.com:ostrom-hq/ostrom.git"))
        assertEquals("ostrom-hq/ostrom", RemoteUrls.slug("https://github.com/ostrom-hq/ostrom.git"))
        assertEquals("ostrom-hq/ostrom", RemoteUrls.slug("https://github.com/ostrom-hq/ostrom/"))
        assertEquals("acme/core", RemoteUrls.slug("ssh://git@github.com/acme/core.git"))
        assertEquals("gate-demo", RemoteUrls.slug("/tmp/remotes/gate-demo.git"))
        assertEquals("gate-demo", RemoteUrls.slug("../gate-demo"))
        assertEquals("repo", RemoteUrls.slug("C:\\remotes\\repo.git"))
        assertEquals("r:x", RemoteUrls.slug("\\\\server\\share\\r:x"), "a UNC path is a folder")
        assertEquals("solo", RemoteUrls.slug("https://host/solo"))
        assertEquals("", RemoteUrls.slug("https://host"))
        assertEquals("a/b", RemoteUrls.slug("host:x/a/b"))
        assertEquals("x:y", RemoteUrls.slug("dir/x:y"), "a colon after a slash is part of a path")
        assertEquals("acme/api", RemoteUrls.slug("file:///srv/git/acme/api.git"), "the gate's label is unchanged")
    }

    @Test
    fun ownerNameIsReadFromEveryUrlOfAHost() {
        val urls = listOf(
            "https://github.com/acme/api.git",
            "https://github.com/acme/api",
            "https://github.com/acme/api/",
            "https://x-access-token:secret@github.com/acme/api.git",
            "http://git.example:8080/acme/api",
            "git@github.com:acme/api.git",
            "git@github.com:acme/api",
            "github.com:acme/api.git",
            "ssh://git@github.com/acme/api.git",
            "ssh://git@github.com:22/acme/api.git",
            "git://github.com/acme/api.git",
            "  https://gitlab.example/group/acme/api.git\n",
            "https://github.com/acme/api.git\r\n",
            "file:acme/api.git",
        )
        for (url in urls) assertEquals("acme/api", RemoteUrls.ownerName(url), url)
    }

    @Test
    fun aLocalPathOrAUrlWithoutOwnerAndNameHasNoOwnerName() {
        for (url in listOf("", " ", "/srv/git/api.git", "../api", "C:\\git\\api.git", "\\\\server\\git\\a:b",
            "dir/x:y", ":acme/api", "https://host", "https://host/solo", "git@host:solo",
            "file:///srv/git/acme/api.git", "FILE:///srv/git/acme/api")) {
            assertNull(RemoteUrls.ownerName(url), url)
        }
    }

    @Test
    fun theOriginOfARepositoryIsAskedOfGit() {
        val asked = mutableListOf<Pair<List<String>, Int>>()
        fun git(answer: CaptureResult) = object : ProcessCapture {
            override fun capture(command: List<String>, timeoutMillis: Int): CaptureResult {
                asked += command to timeoutMillis
                return answer
            }

            override fun detach(command: List<String>) = false
        }
        fun origin(answer: CaptureResult) = RemoteUrls.origin("/w/api", git(answer), MILLIS)
        assertEquals("acme/api", origin(CaptureResult.Exited(0, "git@github.com:acme/api\n")))
        assertEquals(listOf("git", "-C", "/w/api", "remote", "get-url", "origin") to MILLIS, asked.single())
        assertNull(origin(CaptureResult.Exited(2, "error: No such remote 'origin'\n")))
        assertNull(origin(CaptureResult.Exited(0, "/srv/git/api.git\n")), "a local origin")
        assertNull(origin(CaptureResult.NotFound), "no git")
    }

    @Test
    fun aFolderIsNamedByItsLastPart() {
        assertEquals("api", RemoteUrls.folderName("/work/api/"))
        assertEquals("api", RemoteUrls.folderName("C:\\work\\api\\"))
        assertEquals("", RemoteUrls.folderName("/"))
    }

    private companion object {
        const val MILLIS = 4_321
    }
}
