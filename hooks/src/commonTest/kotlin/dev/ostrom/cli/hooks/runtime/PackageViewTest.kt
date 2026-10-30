// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import dev.ostrom.cli.hooks.gate.PatternCache
import dev.ostrom.cli.hooks.gate.ToolSubject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackageViewTest {
    @Test
    fun theViewOfTheLauncherIsRead() {
        val view = PackageView.parse(
            """
            {"version":"v3","digest":"sha256:p","repo":"acme/app","context":"Rules.","tmp":"/t",
             "scriptsDir":"/t/scripts","nodes":[],"notifications":[],
             "hooks":[{"id":"h1","nodeId":"n1","workflowId":"w","kind":"check","event":"PreToolUse","tools":["Bash"],
                       "patterns":["npm test"],"classes":[],"mode":"enforce","scriptId":"s1","order":3},
                      {"id":"h2","nodeId":"n2","kind":"notify","event":"PostToolUse","mode":"shadow","notifyId":"x",
                       "order":"late","scriptId":null},
                      {}],
             "scripts":[{"id":"s1","label":"Tests","path":"scripts/s1.sh","sha256":"ab","command":"npm test",
                         "timeoutSec":45,"network":true,"mode":"enforce","where":"workstation"},
                        {"id":"s2","timeoutSec":"soon","network":"maybe"},
                        {"id":"s3","network":false},
                        {}]}
            """.trimIndent(),
        )
        val tools = listOf("Bash")
        val check = CompiledHook("h1", "n1", "check", "PreToolUse", tools, listOf("npm test"), "enforce", "s1", null, 3)
        val notify = CompiledHook("h2", "n2", "notify", "PostToolUse", emptyList(), emptyList(), "shadow", null, "x", 0)
        val blank = CompiledHook("", "", "", "", emptyList(), emptyList(), "", null, null, 0)
        val tests = CompiledScript("s1", "Tests", "scripts/s1.sh", "ab", "npm test", 45, network = true)
        val defaults = CompiledScript("s2", "check", "", "", "", 120, network = false)
        assertEquals(
            PackageView(
                "sha256:p", "Rules.", "/t", listOf(check, notify, blank),
                listOf(tests, defaults, defaults.copy("s3"), defaults.copy("")),
            ),
            view,
        )
        assertEquals(tests, view?.script("s1"))
        assertNull(view?.script("gone"))
        assertNull(view?.script(null))
        assertTrue(check.enforced)
        assertFalse(notify.enforced)
        assertTrue(blank.enforced, "a hook without a mode blocks")
        assertTrue(check.copy(mode = "audit").enforced, "an unknown mode blocks")
    }

    @Test
    fun aViewWithoutItsPartsIsEmptyNotBroken() {
        assertEquals(PackageView("", null, "", emptyList(), emptyList()), PackageView.parse("{}"))
        val nulls = """{"digest":"d","context":"","hooks":null,"scripts":null,"tmp":null}"""
        assertEquals(PackageView("d", null, "", emptyList(), emptyList()), PackageView.parse(nulls))
    }

    @Test
    fun aViewThatIsNotOneIsUnreadable() {
        assertNull(PackageView.parse("not json"))
        assertNull(PackageView.parse("""{"hooks":{"id":"h"}}"""))
        assertNull(PackageView.parse("""{"scripts":[{"id":["s"]}]}"""))
        assertNull(PackageView.parse("[]"))
    }

    @Test
    fun aHookMatchesTheToolsAndPatternsItNames() {
        val patterns = PatternCache()
        val hook =
            CompiledHook("h", "n", "check", "PreToolUse", listOf("Edit"), listOf("\\.env$"), "enforce", "s", null, 0)
        val edit = ToolSubject.of("Write", JsonObject(mapOf("file_path" to JsonPrimitive("/w/.env"))))
        assertTrue(hook.matches(edit, patterns), "a file written matches a hook on any of the edit tools")
        val bash = ToolSubject.of("Bash", JsonObject(mapOf("command" to JsonPrimitive("cat .env"))))
        assertFalse(hook.matches(bash, patterns))
        assertFalse(hook.copy(tools = listOf("Bash"), patterns = listOf("(")).matches(bash, patterns))
    }
}
