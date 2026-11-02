// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import dev.ostrom.cli.commands.notImplemented
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An option of a built command that this version does not have yet is a usage error (64), not a failure (70). No
 * option is in that state since `--cloud` was built (CloudCommandsTest): the rule is checked on its own.
 */
class OptionNotBuiltTest : CliKit() {
    @Test
    fun aCommandNotBuiltIs70AndAnOptionNotBuilt64() {
        assertEquals(70, notImplemented(listOf("ostrom", "status")).statusCode)
        val option = notImplemented(listOf("ostrom", "claude", "--cloud"))
        assertEquals(64, option.statusCode)
        assertEquals("ostrom claude --cloud: not in this version of ostrom yet.", option.message)
    }
}
