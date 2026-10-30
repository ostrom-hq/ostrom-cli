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
        val laid = PackageLayout(files).layDown(pack, plugin, temporary)
        val view = laid.view
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
        // S2-33: every file laid down, by SHA-256, in a manifest whose own SHA-256 the hooks get in their environment
        val manifest = checkNotNull(files.texts[laid.manifest])
        assertEquals(join(temporary, "package.sha256"), laid.manifest)
        assertEquals(FileAccess.PRIVATE, files.access[laid.manifest])
        assertEquals(sha256Hex(manifest.encodeToByteArray()), laid.seal)
        val lines = manifest.lines().filter { it.isNotEmpty() }
        assertEquals(
            listOf(
                join(plugin, "skills", "a", "SKILL.md"),
                join(temporary, "scripts", "check.sh"),
                join(temporary, "bin", "run"),
                join(plugin, "empty"),
                view,
            ),
            lines.map { it.substringAfter("  ") },
        )
        for (line in lines) {
            val path = line.substringAfter("  ")
            assertEquals(sha256Hex(files.texts.getValue(path).encodeToByteArray()), line.substringBefore("  "), path)
        }
        assertEquals(6, files.texts.size, "4 files, the view and the manifest, nothing else")
    }
}
