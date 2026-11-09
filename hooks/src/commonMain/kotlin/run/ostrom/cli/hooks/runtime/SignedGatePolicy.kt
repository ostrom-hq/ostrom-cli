// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks.runtime

import run.ostrom.cli.hooks.gate.GateCatalogue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Instant

/** What the gate matches an action against, or why it cannot (security audit S2-20, S2-38). */
sealed interface CatalogueRead {
    /** The organisation's verified policy, with what the session's package adds to it. */
    data class Verified(val catalogue: GateCatalogue) : CatalogueRead

    /** No policy can be trusted: every tool call the gate sees is refused, and [reason] is said to the agent. */
    data class Held(val reason: String) : CatalogueRead
}

/**
 * One signing key of the instance (`GET /api/v1/instance/keys`), the key of its receipts.
 *
 * @property kid The key id (`keyId` on the wire).
 * @property publicKeyPem The SPKI PEM public key.
 */
data class InstanceKey(val kid: String, val publicKeyPem: String)

/**
 * The organisation's gate policy as Ostrom signs it (`ostrom.gates/1`): `{format, alg, kid, signature, payload}`, the
 * signature over the payload's UTF-8 bytes, the payload `{repo, issuedAt, expiresAt, failMode, actions}`.
 *
 * Nothing here comes from a file the agent can write: the keys are the instance's, read by the launcher before the
 * agent started and handed to the hooks in their environment, the dates are the server's own, inside what it signed,
 * and the clock is the machine's (S2-20: an emptied copy dated in the future no longer stands). A policy signed with
 * another key, for another repository, expired, issued ahead of the clock, or valid for longer than Ostrom signs is not
 * trusted.
 *
 * @param keys The instance's keys.
 * @param verify Checks one signature ([ed25519Verify]).
 */
class SignedGatePolicy(
    private val keys: List<InstanceKey>,
    private val verify: (String, ByteArray, ByteArray) -> Boolean = ::ed25519Verify,
) {
    /**
     * @param envelope What the relay served for the session's policy.
     * @param repository The session's repository.
     * @param nowMillis Now, in milliseconds since the epoch.
     */
    fun read(envelope: JsonObject, repository: String, nowMillis: Long): CatalogueRead {
        val payload = envelope.string("payload")
        val kid = envelope.string("kid")
        val key = keys.firstOrNull { it.kid == kid }
        return when {
            envelope.string("format") != FORMAT -> held("it is not an $FORMAT envelope")
            envelope.string("alg") != ALGORITHM -> held("its signature algorithm is not $ALGORITHM")
            payload == null -> held("it has no payload")
            key == null -> held("it is signed with the key ${kid ?: "(none)"}, which this instance does not publish")
            !verify(key.publicKeyPem, payload.encodeToByteArray(), signatureOf(envelope)) ->
                held("its signature is not valid")
            else -> payloadOf(payload, repository, nowMillis)
        }
    }

    private fun payloadOf(payload: String, repository: String, nowMillis: Long): CatalogueRead {
        val body = parseObject(payload)
        val catalogue = GateCatalogue.parse(payload)
        val issued = body?.let { millisOf(it.string("issuedAt")) }
        val expires = body?.let { millisOf(it.string("expiresAt")) }
        return when {
            body == null || catalogue == null -> held("its payload is not a gate policy")
            body.string("repo") != repository -> held("it was signed for ${body.string("repo")}, not $repository")
            issued == null || expires == null -> held("it does not say when it was issued and when it expires")
            expires <= nowMillis -> held("it expired at ${body.string("expiresAt")}")
            issued > nowMillis + CLOCK_SKEW_MILLIS -> held("it was issued ahead of this machine's clock")
            expires - issued > MAX_LIFETIME_MILLIS -> held("it is valid for longer than Ostrom signs a policy")
            else -> CatalogueRead.Verified(catalogue)
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun signatureOf(envelope: JsonObject): ByteArray = try {
        Base64.decode(envelope.string("signature").orEmpty())
    } catch (_: IllegalArgumentException) {
        ByteArray(0)
    }

    private fun millisOf(text: String?): Long? = try {
        text?.let { Instant.parse(it).toEpochMilliseconds() }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun held(why: String) = CatalogueRead.Held("Ostrom's gate policy cannot be trusted: $why")

    /** Reads the instance's keys. */
    companion object {
        private const val FORMAT = "ostrom.gates/1"
        private const val ALGORITHM = "ed25519"

        /** A clock a little behind the server's still reads a fresh policy. */
        private const val CLOCK_SKEW_MILLIS = 300_000L

        /** Ostrom signs a policy for minutes: one valid for longer than an hour was not signed for a session. */
        private const val MAX_LIFETIME_MILLIS = 3_600_000L

        /**
         * The keys in [text], the body of `GET /api/v1/instance/keys` (`{"keys":[{"keyId","publicKeyPem"}]}`, `kid`
         * read too); none when it cannot be read, and then no policy is trusted.
         */
        fun keysOf(text: String?): List<InstanceKey> {
            val keys = text?.let(::parseObject)?.get("keys") as? JsonArray
            return keys.orEmpty().mapNotNull { element ->
                val fields = element as? JsonObject
                val kid = fields?.string("keyId") ?: fields?.string("kid")
                val pem = fields?.string("publicKeyPem")
                if (kid == null || pem == null) null else InstanceKey(kid, pem)
            }
        }

        private fun parseObject(text: String): JsonObject? = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
