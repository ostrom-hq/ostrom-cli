// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.platform

import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The test binary (or the JVM) knows where it runs from: the hooks of an agent are started by that path. */
class ExecutableTest {
    @Test
    fun theRunningBinaryIsAnExistingFile() {
        val path = assertNotNull(currentExecutable())
        assertTrue(FileSystem.SYSTEM.exists(path.toPath()), path)
    }
}
