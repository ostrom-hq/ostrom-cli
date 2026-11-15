// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package run.ostrom.cli.platform

import platform.posix.SIGTERM
import platform.posix.getpid
import platform.posix.kill
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServiceSignalPosixTest {
    @Test
    fun sigtermRaisesTheStopRequestInsteadOfEndingTheProcess() {
        installServiceSignalHandlers()
        assertEquals(0, kill(getpid(), SIGTERM))
        assertTrue(serviceStopRequested())
    }

    @Test
    fun theAccountHasANameAndAHomeOnPosix() {
        val account = currentServiceAccount()
        assertNotNull(account.userName)
        assertTrue(account.userId.all { it.isDigit() })
    }
}
