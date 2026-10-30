// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import dev.ostrom.cli.commands.notImplemented
import kotlin.test.Test
import kotlin.test.assertEquals

/** An option of a built command that this version does not have yet is a usage error (64), not a failure (70). */
class OptionNotBuiltTest : CliKit() {
    @Test
    fun anOptionNotBuiltYetExitsWith64() {
        assertEquals(64, cli().run(arrayOf("claude", "--cloud", "-p", "hello")))
        assertEquals("ostrom claude --cloud: not implemented yet.\n", console.err.toString())
    }

    @Test
    fun aCommandNotBuiltIs70AndAnOptionNotBuilt64() {
        assertEquals(70, notImplemented(listOf("ostrom", "status")).statusCode)
        assertEquals(64, notImplemented(listOf("ostrom", "claude", "--cloud")).statusCode)
    }
}
