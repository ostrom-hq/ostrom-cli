// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.commands

import dev.ostrom.cli.platform.ConfigKey
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigValuesTest {
    @Test
    fun aServerIsAnHttpUrlWithAHostAndNothingElse() {
        val accepted = listOf(
            "https://ostrom.example", "HTTPS://Ostrom.Example/", "https://ostrom.example./base/a@b",
            "http://localhost:8080", "http://ci_host", "https://10.0.0.7:65535/ostrom", "http://[::1]:8080",
            "http://[::]", "http://[2001:db8::7]/", "http://[1:2:3:4:5:6:7:8]", "http://[::ffff:10.0.0.7]",
            "http://[fe80::1:2]:1",
        )
        for (url in accepted) assertTrue(isServerUrl(url), url)
    }

    @Test
    fun anythingElseIsRefused() {
        val refused = listOf(
            "ostrom.example", "ftp://ostrom.example", "https://", "http://-", "http://a-", "http://a..b",
            "http://${"a".repeat(64)}", "http://é.example", "https://user@ostrom.example",
            "https://ostrom.example/a b", "https://ostrom.example/?q", "https://ostrom.example/#f",
            "https://ostrom.example:", "https://ostrom.example:0", "https://ostrom.example:99999",
            "https://ostrom.example:123456", "https://ostrom.example:8a", "http://999.1.1.1", "http://1234.1.1.1",
            "http://1.2.3", "http://[::1", "http://[::1]x", "http://[:::]", "http://[1::2::3]", "http://[12345::1]",
            "http://[g::1]", "http://[1:2:3]", "http://[1::2:3:4:5:6:7:8]", "http://[::ffff:1..2.3]",
            "http://[::ffff:a.b.c.d]",
        )
        for (url in refused) assertFalse(isServerUrl(url), url)
    }

    @Test
    fun theTelemetryKeysAreLeftToTheirCommand() {
        assertNull(configValueProblem(ConfigKey.TELEMETRY, "on"))
        assertNull(configValueProblem(ConfigKey.TELEMETRY_ID, "any"))
    }
}
