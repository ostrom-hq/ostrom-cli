// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecureRandomTest {
    @Test
    fun theRequestedNumberOfBytesComesBack() {
        assertEquals(32, secureRandomBytes(32).size)
        assertEquals(1, secureRandomBytes(1).size)
    }

    @Test
    fun twoDrawsDifferAndAreNotAllZero() {
        val first = secureRandomBytes(32)
        val second = secureRandomBytes(32)
        assertFalse(first.contentEquals(second), "Two 256-bit draws never collide")
        assertTrue(first.any { it != 0.toByte() }, "The source wrote into the buffer")
    }

    @Test
    fun aCountBelowOneIsRefused() {
        assertFailsWith<IllegalArgumentException> { secureRandomBytes(0) }
        assertFailsWith<IllegalArgumentException> { secureRandomBytes(-1) }
    }
}
