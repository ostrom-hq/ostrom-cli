// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.receipts

import dev.ostrom.cli.launcher.sha256Hex
import kotlinx.serialization.json.JsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * An Ed25519 public key this machine trusts to sign receipts and packages. A key carried by the file being verified
 * is never one: only a key pinned on first use, or one the developer gives with `--key`.
 *
 * @property kid The first 16 hex characters of the SHA-256 of the key's SPKI DER, as the server names its keys.
 * @property publicKeyPem The key, SPKI PEM.
 * @property from Where the trust comes from, said in the report (`pinned for https://…`, `given by --key …`).
 */
data class TrustedKey(val kid: String, val publicKeyPem: String, val from: String)

/** The keys read from a file: some, or why none could be. */
sealed interface KeyReading {
    /**
     * The keys of the file.
     *
     * @property keys The keys, in the order of the file.
     */
    data class Keys(val keys: List<TrustedKey>) : KeyReading

    /**
     * The file gives no key.
     *
     * @property reason Why, said to the developer (exit 64).
     */
    data class Unreadable(val reason: String) : KeyReading
}

/** The keys a verification may trust (prototype `verify.rs`: `key_from_pem`, `pinned_keys`, `key_from_file`). */
object TrustedKeys {
    private const val SPKI_ED25519_SIZE = 44
    private const val KID_LENGTH = 16

    /** The key of an SPKI PEM [pem], its kid computed as the server computes it. */
    @OptIn(ExperimentalEncodingApi::class)
    fun fromPem(pem: String, from: String): KeyReading {
        val body = pem.lines().filterNot { it.startsWith("-----") }.joinToString("").trim()
        val der = try {
            Base64.decode(body)
        } catch (_: IllegalArgumentException) {
            null
        }
        return when {
            der == null -> KeyReading.Unreadable("not a PEM public key")
            der.size != SPKI_ED25519_SIZE -> KeyReading.Unreadable("not an Ed25519 public key")
            else -> KeyReading.Keys(listOf(TrustedKey(sha256Hex(der).take(KID_LENGTH), pem, from)))
        }
    }

    /**
     * The keys pinned on this machine, one per Ostrom server: `<config>/server-keys.json`, written by the launcher on
     * the first package of a server (`PackageTrust`). Its kid is recomputed from the key, never read from the file.
     *
     * @param serverKeys The text of `server-keys.json`, or null when there is none.
     */
    fun pinned(serverKeys: String?): List<TrustedKey> {
        val servers = parseDocument(serverKeys) as? JsonObject ?: JsonObject(emptyMap())
        return servers.entries.sortedBy { it.key }.flatMap { (server, entry) ->
            val pem = entry.field("publicKeyPem").str()
            if (pem == null) emptyList() else keysOf(fromPem(pem, "pinned for $server"))
        }
    }

    /**
     * The keys of a `--key FILE`: a PEM public key, a JSON `{"publicKeyPem": …}`, or the instance's key list as
     * `GET /api/v1/receipts/keys` returns it (`{"keys": [{"publicKeyPem": …}, …]}`).
     *
     * @param text The content of the file.
     * @param path The file, as the developer gave it.
     */
    fun fromFile(text: String, path: String): KeyReading {
        val from = "given by --key $path"
        val document = parseDocument(text)
        val listed = document.field("keys").items().mapNotNull { it.field("publicKeyPem").str() }
        val single = document.field("publicKeyPem").str()
        return when {
            document == null -> fromPem(text, from)
            listed.isNotEmpty() -> all(listed.map { fromPem(it, from) })
            single == null -> KeyReading.Unreadable("no publicKeyPem in the JSON key file")
            else -> fromPem(single, from)
        }
    }

    private fun keysOf(reading: KeyReading): List<TrustedKey> =
        if (reading is KeyReading.Keys) reading.keys else emptyList()

    private fun all(readings: List<KeyReading>): KeyReading =
        readings.firstOrNull { it is KeyReading.Unreadable } ?: KeyReading.Keys(readings.flatMap(::keysOf))
}
