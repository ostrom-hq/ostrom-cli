// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launch

import kotlin.test.Test
import kotlin.test.assertEquals

/** The local `CLAUDE_ENV_FILE`, as TeamCode's `AgentEnvironmentTest` (localScript, refused). */
class LocalEnvironmentTest {
    @Test
    fun theScriptSourcesTheValuesFileAfterTheDevelopersOwn() {
        assertEquals(
            "# Written by Ostrom: reads your current assigned environment variables before each command.\n" +
                "[ -f '/home/me/env.sh' ] && . '/home/me/env.sh'\n" +
                "[ -f '/tmp/r/values.sh' ] && . '/tmp/r/values.sh'\n",
            LocalEnvironment.script("/tmp/r/values.sh", "/home/me/env.sh"),
        )
        assertEquals(
            "# Written by Ostrom: reads your current assigned environment variables before each command.\n" +
                "[ -f \"/tmp/it's/v.sh\" ] && . \"/tmp/it's/v.sh\"\n",
            LocalEnvironment.script("/tmp/it's/v.sh"),
        )
    }

    @Test
    fun theRefusedNamesAreThoseTheRunnerWithholdsSorted() {
        val variables = mapOf("PATH" to "x", "API" to "y", "CLAUDE_X" to "z", "lower" to "w", "NUL" to "a\u0000b")
        assertEquals(listOf("CLAUDE_X", "NUL", "PATH", "lower"), LocalEnvironment.refused(variables))
    }
}
