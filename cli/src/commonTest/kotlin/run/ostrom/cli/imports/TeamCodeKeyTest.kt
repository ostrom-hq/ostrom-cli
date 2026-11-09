// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.imports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TeamCodeKeyTest {
    private val key = assertNotNull(TeamCodeKey.of(" ${Sealed.KEY}\n"), "the key file's line break is ignored")

    @Test
    fun anEnvelopeOpensWithItsKeyForItsContextOnly() {
        assertEquals("sk-shared", key.open(Sealed.SHARED, "variant:default:v1"))
        assertEquals(null, key.open(Sealed.SHARED, "variant:other:v1"), "another team")
        assertEquals(null, key.open(Sealed.SHARED.removePrefix("v1:"), "variant:default:v1"), "no version")
        assertEquals(null, key.open("v1:!!", "variant:default:v1"), "not base64url")
        assertEquals(null, key.open("v1:AAAA", "variant:default:v1"), "shorter than a nonce and a tag")
    }

    @Test
    fun aKeyIsThirtyTwoBytesOfBase64() {
        assertEquals(null, TeamCodeKey.of("not base64!"))
        assertEquals(null, TeamCodeKey.of("AAAA"), "three bytes")
    }
}
