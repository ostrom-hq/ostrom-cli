// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The common half of the launcher, on every target; the real children are in posixTest and mingwTest. */
class ProcessLauncherTest {
    @Test
    fun aCommandNeedsItsExecutable() {
        assertFailsWith<IllegalArgumentException> { ProcessLauncherImpl().run(emptyList()) }
    }

    @Test
    fun aMissingExecutableIsNotFoundEverywhere() {
        assertEquals(ProcessResult.NotFound, ProcessLauncherImpl().run(listOf("ostrom-no-such-executable-4f2c")))
    }

    @Test
    fun anOutcomeCarriesItsCodeOrItsReason() {
        assertEquals(3, ProcessResult.Exited(3).code)
        assertEquals("no permission", ProcessResult.Failed("no permission").reason)
    }
}
