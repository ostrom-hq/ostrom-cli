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
        )
        for (url in urls) assertEquals("acme/api", RemoteUrls.ownerName(url), url)
    }

    @Test
    fun aLocalPathOrAUrlWithoutOwnerAndNameHasNoOwnerName() {
        for (url in listOf("", " ", "/srv/git/api.git", "../api", "C:\\git\\api.git", "\\\\server\\git\\a:b",
            "dir/x:y", ":acme/api", "https://host", "https://host/solo", "git@host:solo")) {
            assertNull(RemoteUrls.ownerName(url), url)
        }
    }
}
