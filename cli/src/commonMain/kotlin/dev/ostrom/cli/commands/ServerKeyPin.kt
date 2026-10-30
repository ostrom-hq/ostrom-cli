// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.commands

import dev.ostrom.cli.launcher.Routes
import dev.ostrom.cli.launcher.ServerAnswer
import dev.ostrom.cli.launcher.child
import dev.ostrom.cli.launcher.join
import dev.ostrom.cli.launcher.list
import dev.ostrom.cli.launcher.objectOf
import dev.ostrom.cli.launcher.parseObject
import dev.ostrom.cli.launcher.pretty
import dev.ostrom.cli.launcher.receipts.KeyReading
import dev.ostrom.cli.launcher.receipts.TrustedKeys
import dev.ostrom.cli.launcher.text
import dev.ostrom.cli.platform.Console
import dev.ostrom.cli.platform.FileStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock

/**
 * Pins, on `ostrom receipts pull`, the key a receipt is signed with for its server, so that `ostrom verify` checks it
 * offline afterwards: the same first-use pin as the launcher's (`PackageTrust`), in `<config>/server-keys.json`. A key
 * already pinned for the server is never replaced: a receipt signed with another key is said, and `ostrom verify`
 * refuses it until the entry is deleted on purpose. Only an Ed25519 key whose computed kid is the receipt's is pinned:
 * a wrong answer of the server never becomes a pin that only a hand edit undoes.
 */
internal class ServerKeyPin(
    private val files: FileStore,
    private val console: Console,
    private val now: () -> String = { Clock.System.now().toString() },
) {
    /**
     * Pins the key [receipt] names, for the server of [signed], in the CLI folder [directory].
     *
     * @return True when the key the receipt names is pinned for its server (now or before): `ostrom verify` can check
     * it offline. False, said, when it is not: no key named, not published, or another key pinned.
     * @throws dev.ostrom.cli.platform.FileWriteException When `server-keys.json` cannot be written.
     */
    fun pin(signed: SignedIn, receipt: JsonObject, directory: String): Boolean {
        val kid = kidOf(receipt)
        val path = join(directory, KEYS_FILE)
        val pinned = parseObject(files.read(path))?.child(signed.serverUrl)
        val problem = when {
            kid == null -> "this receipt names no signing key: `ostrom verify` will say its signature is not valid"
            pinned == null -> firstUse(signed, kid, path)
            pinned.text("kid") != kid || pinned.text("publicKeyPem") == null -> {
                val other = pinned.text("kid")
                val what = if (other == null) "no usable key" else "key $other"
                "this receipt is signed with key $kid, but this device pinned $what for ${signed.serverUrl}. The " +
                    "pin is kept: a new Ostrom key is pinned again on purpose, by deleting its entry in $path"
            }
            else -> null
        }
        if (problem != null) console.err("ostrom: $problem\n")
        return problem == null
    }

    /** Pins [kid] for the server of [signed] from its public key list; the problem said, or null once pinned. */
    private fun firstUse(signed: SignedIn, kid: String, path: String): String? {
        // read before the lock: a slow server never holds the file for the other ostrom processes
        val answer = runBlocking { signed.server.get(Routes.KEYS, null) }
        val keys = if (answer is ServerAnswer.Ok) answer.body.list("keys") else emptyList()
        val pem = keys.filterIsInstance<JsonObject>().firstOrNull { it.text("kid") == kid }?.text("publicKeyPem")
        return if (pem == null || !isKey(pem, kid)) {
            "${signed.serverUrl} does not publish the key $kid of this receipt: `ostrom verify` needs it with " +
                "--key FILE (GET ${Routes.KEYS} of your Ostrom)"
        } else {
            files.exclusive(path) {
                // read again under the lock: another process may have pinned servers meanwhile, and a pin it made for
                // this one wins (the entries read come last, over this one)
                val all = parseObject(files.read(path)) ?: JsonObject(emptyMap())
                val entry = objectOf("kid" to kid, "publicKeyPem" to pem, "pinnedAt" to now())
                files.write(path, pretty(JsonObject(mapOf(signed.serverUrl to entry) + all)))
            }
            console.out("✓ Pinned Ostrom's signing key $kid for ${signed.serverUrl} (first use).\n")
            null
        }
    }

    private companion object {
        const val KEYS_FILE = "server-keys.json"
        const val KID = "kid="

        /** The kid of `ed25519;kid=…;sha256=…;sig=…` (ostrom-core `SignatureField`); null when none or empty. */
        fun kidOf(receipt: JsonObject): String? {
            val parts = receipt.text("signature")?.split(';').orEmpty()
            val part = parts.firstOrNull { it.startsWith(KID) }
            val kid = part?.removePrefix(KID)
            return if (kid.isNullOrEmpty()) null else kid
        }

        /** True when [pem] is an Ed25519 public key whose kid, computed as the server computes it, is [kid]. */
        fun isKey(pem: String, kid: String): Boolean {
            val reading = TrustedKeys.fromPem(pem, "")
            return reading is KeyReading.Keys && reading.keys.any { it.kid == kid }
        }
    }
}
