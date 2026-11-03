// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Gate policies signed with a TEST ONLY Ed25519 key (seed: SHA-256 of `ostrom-cli test only: gate policy key`), made
 * once with Python's `cryptography` and written here literally.
 */
object PolicyVectors {
    /** The test key's public half, as the instance publishes it. */
    const val PEM = "-----BEGIN PUBLIC KEY-----\nMCowBQYDK2VwAyEAuUJdkQqWdTklg09DATFI07VwRRI9mLsv7T1cecruUqQ=\n" +
        "-----END PUBLIC KEY-----\n"

    /** `GET /api/v1/instance/keys` with the test key. */
    val KEYS = """{"keys":[{"keyId":"k1","algorithm":"ed25519","publicKeyPem":${JsonPrimitive(PEM)}}]}"""

    /** 2026-10-30T05:05:00Z: inside the policies' validity (05:00 to 05:10). */
    const val NOW = 1_793_336_700_000L

    /** 2026-10-30T05:10:00Z: the policies' expiry. */
    const val EXPIRY = 1_793_337_000_000L

    private const val ACTIONS = """"failMode":"closed","actions":[{"class":"push-main","label":"Push to main",""" +
        """"tools":["Bash"],"patterns":["git push"],"gate":{"enforce":true,"label":"Main","approvers":["omar"]}}]}"""

    /** The policy of `acme/app`. */
    const val PAYLOAD = """{"repo":"acme/app","issuedAt":"2026-10-30T05:00:00Z","expiresAt":"2026-10-30T05:10:00Z",""" +
        ACTIONS

    /** The test key's signature of [PAYLOAD]. */
    const val SIGNATURE = "1HrdiU7PSPccnXDMEoB5dI9wkGr1GF3cQQ/oZKwykoHSHDdPa5FuSR6CP7KFuT6e/kJQdeHa1qizON9GW3dKAQ=="

    /** Another key's signature of [PAYLOAD]. */
    const val OTHER_KEY_SIGNATURE =
        "trVbzdlvnzJ4G7zIBFl9mkFR2T59SxAelax3kp6ST7APX0EWLNRkHMd5EUFT3UlcyAkx+BF4d3hsmjgQZNq0Dw=="

    /** The policy of the repository `r`: one class observed, nothing gated. */
    const val PAYLOAD_R = """{"repo":"r","issuedAt":"2026-10-30T05:00:00Z",""" +
        """"expiresAt":"2026-10-30T05:10:00Z","actions":[{"class":"x"}]}"""

    /** The test key's signature of [PAYLOAD_R]. */
    const val SIGNATURE_R = "kenp9rP3yWoTQAk9/bw5UL2QXYlUJxFri4xuJzohtJk7Y6QaOIu/XDLdP0QEHV3E/GWeBBhe/uO/YYnorkGDCg=="

    /** The envelope Ostrom serves for [payload]. */
    fun envelope(
        payload: String = PAYLOAD,
        signature: String? = SIGNATURE,
        kid: String? = "k1",
        format: String = "ostrom.gates/1",
        alg: String = "ed25519",
    ): JsonObject = JsonObject(
        listOfNotNull(
            "format" to JsonPrimitive(format),
            "alg" to JsonPrimitive(alg),
            kid?.let { "kid" to JsonPrimitive(it) },
            signature?.let { "signature" to JsonPrimitive(it) },
            "payload" to JsonPrimitive(payload),
        ).toMap(),
    )
}
