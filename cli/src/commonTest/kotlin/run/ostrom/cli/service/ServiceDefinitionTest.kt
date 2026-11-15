// SPDX-License-Identifier: Apache-2.0
// Dense assertions of the service tests, in the style of the ported ones.
@file:Suppress(
    "MaxLineLength",
)

package run.ostrom.cli.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServiceDefinitionTest {
    private fun definition(executable: String) = serviceDefinition(
        ServiceSettings("a".repeat(32), executable, "/tmp/ostrom/config.toml", "/tmp/ostrom.service", "linux", "1000"),
    )

    @Test
    fun ordinaryLinuxRegistrationsKeepTheirExistingCommandBytes() {
        assertEquals(
            "ExecStart=\"/opt/team code/é/ostrom\" \"service\" \"serve\" \"--config\" \"/tmp/ostrom/config.toml\"",
            definition("/opt/team code/é/ostrom").lineSequence().single { it.startsWith("ExecStart=") },
        )
    }

    @Test
    fun executablePathsRejectedBySystemdArePassedAsAnEnvArgument() {
        for (path in listOf("/opt/O'Neill/ostrom", "/opt/a\"b/ostrom", "/opt/back\\slash/ostrom", "/opt/\$HOME/ostrom")) {
            val command = definition(path).lineSequence().single { it.startsWith("ExecStart=") }
            assertTrue(command.startsWith("ExecStart=\"/usr/bin/env\" \"--\" "), "Unsafe systemd executable token: $path")
            assertTrue(command.endsWith(" \"service\" \"serve\" \"--config\" \"/tmp/ostrom/config.toml\""))
        }
    }
}
