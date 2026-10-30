// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PackageLayoutTest {
    private val files = MemoryFiles()
    private val temporary = join("/tmp", "ostrom-run-1")
    private val plugin = join(temporary, "plugin")

    @Test
    fun scriptsGoOutsideThePluginAndUnsafePathsNowhere() {
        val pack = SessionPackage(
            objectOf(
                "repo" to "acme-api",
                "context" to "ctx",
                "nodes" to listOf(
                    mapOf("id" to "n1", "kind" to "check", "label" to "Tests", "mode" to "enforce", "x" to 1),
                    2,
                ),
                "files" to listOf(
                    mapOf("path" to "skills/a/SKILL.md", "content" to "skill"),
                    mapOf("path" to "scripts/check.sh", "content" to "#!/bin/sh"),
                    mapOf("path" to "bin/run", "mode" to "executable", "content" to "run"),
                    mapOf("path" to "../escape", "content" to "x"),
                    mapOf("path" to "/etc/passwd", "content" to "x"),
                    mapOf("path" to "a\\b", "content" to "x"),
                    mapOf("path" to "C:x", "content" to "x"),
                    mapOf("content" to "no path"),
                    mapOf("path" to "empty"),
                ),
            ),
            "abc",
        )
        val view = PackageLayout(files).layDown(pack, plugin, temporary)
        assertEquals("skill", files.texts[join(plugin, "skills", "a", "SKILL.md")])
        assertEquals(FileAccess.EXECUTABLE, files.access[join(temporary, "scripts", "check.sh")])
        assertEquals(FileAccess.EXECUTABLE, files.access[join(temporary, "bin", "run")])
        assertEquals("", files.texts[join(plugin, "empty")])
        assertNull(files.texts.keys.firstOrNull { "escape" in it || "passwd" in it || "C:x" in it || "a\\b" in it })
        assertEquals(join(temporary, "package.json"), view)
        assertEquals(FileAccess.PRIVATE, files.access[view])
        val written = checkNotNull(parseObject(files.texts[view]))
        assertEquals("abc", written.text("digest"))
        assertEquals("acme-api", written.text("repo"))
        assertEquals(join(temporary, "scripts"), written.text("scriptsDir"))
        assertEquals("""[{"id":"n1","kind":"check","label":"Tests","mode":"enforce"}]""", written["nodes"].toString())
        assertEquals(5, files.texts.size, "4 files and the view, nothing else")
    }
}
