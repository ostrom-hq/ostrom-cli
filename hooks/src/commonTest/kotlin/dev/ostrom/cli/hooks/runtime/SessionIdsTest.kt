// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionIdsTest {
    @Test
    fun anEventIsSentOnTheSegmentOfThisLaunch() {
        val files = FakeFiles()
        val ids = SessionIds("/s/session", files)
        assertEquals("", ids.current())
        assertEquals("a1", ids.of("a1"), "not mapped: the agent's own id")
        ids.adopt("a1", "a1~2")
        assertEquals("a1~2", ids.of("a1"))
        assertEquals("a1~2", ids.of(""), "no id: the launch's session")
        assertEquals("a1~2", ids.of(null))
        ids.adopt(null, "a1~3")
        assertEquals("a1~3", ids.current())
        assertEquals("a1~2", ids.of("a1"), "the map is kept when no agent id is given")
        files.texts["/s/session.map.json"] = """{"a1":""}"""
        assertEquals("a1", ids.of("a1"), "an empty mapping is no mapping")
    }

    @Test
    fun withoutASessionFileNothingIsKept() {
        val files = FakeFiles()
        val ids = SessionIds(null, files)
        ids.adopt("a1", "x")
        ids.markRefused("git push", refused = true)
        assertEquals("a1", ids.of("a1"))
        assertEquals("", ids.current())
        assertFalse(ids.wasRefused("git push"))
        assertTrue(files.texts.isEmpty())
    }

    @Test
    fun aRefusedActionIsMarkedUntilItsAfterHook() {
        val files = FakeFiles()
        val ids = SessionIds("/s/session", files)
        ids.markRefused("git push", refused = false)
        assertTrue(files.texts.isEmpty(), "nothing to clear: nothing written")
        ids.markRefused("git push", refused = true)
        assertTrue(ids.wasRefused("git push"))
        assertFalse(ids.wasRefused("git status"))
        ids.markRefused("git push", refused = false)
        assertFalse(ids.wasRefused("git push"))
    }

    @Test
    fun aDamagedFileReadsAsEmpty() {
        val files = FakeFiles(mutableMapOf("/s/session.map.json" to "{", "/s/session.refused.json" to "[1]"))
        val ids = SessionIds("/s/session", files)
        assertEquals("a1", ids.of("a1"))
        assertFalse(ids.wasRefused("x"))
        files.texts["/s/session.map.json"] = """{"a1":{"x":1},"a2":"b"}"""
        assertEquals("a1", ids.of("a1"))
        assertEquals("b", ids.of("a2"))
    }
}
