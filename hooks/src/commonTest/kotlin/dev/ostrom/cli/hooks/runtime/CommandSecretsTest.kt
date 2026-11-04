// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

/** A command the hooks report after a local refusal leaves the workstation without the secrets it carried. */
class CommandSecretsTest {
    @Test
    fun eachKindOfSecretIsMasked() {
        val cases = mapOf(
            "curl -H 'Authorization: Bearer abcdefgh1234'" to "curl -H 'Authorization: Bearer ***'",
            "git push https://ada:hunter22@github.com/acme/api.git" to "git push https://***@github.com/acme/api.git",
            "GITHUB_TOKEN=s3cr3t git push" to "GITHUB_TOKEN=*** git push",
            "deploy --password hunter22 --api-key=k3y" to "deploy --password *** --api-key=***",
            "echo ghp_0123456789abcdefghijABCDEFGHIJ" to "echo ***",
        )
        cases.forEach { (text, masked) -> assertEquals(masked, CommandSecrets.masked(text), text) }
    }

    @Test
    fun aCommandWithoutSecretsIsUnchanged() {
        val command = "git --git-dir=../other/.git push origin main"
        assertEquals(command, CommandSecrets.masked(command))
    }
}
