// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.adapters

import run.ostrom.cli.launcher.parseObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InertKeysTest {
    private val keys = InertKeys(setOf("\$schema", "ui", "general.vimMode", "general.editor.theme"))

    private fun outside(text: String) = keys.firstOutside(checkNotNull(parseObject(text)))

    @Test
    fun aKeyIsLetThroughWithEverythingUnderItOrOnlyThatOne() {
        assertNull(outside("""{"${'$'}schema":"x","ui":{"theme":"dark","anything":{"a":1}}}"""))
        assertNull(outside("""{"general":{"vimMode":true,"editor":{"theme":"x"}}}"""))
        assertEquals("general.preferredEditor", outside("""{"general":{"vimMode":true,"preferredEditor":"vim"}}"""))
        assertEquals("general.editor.command", outside("""{"general":{"editor":{"command":"x"}}}"""))
        // a key some of whose children are let through, but which is not an object, is not let through
        assertEquals("general", outside("""{"general":"vim"}"""))
        assertEquals("mcpServers", outside("""{"ui":{},"mcpServers":{}}"""))
    }
}
