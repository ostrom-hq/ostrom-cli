// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import run.ostrom.cli.launcher.Routes
import run.ostrom.cli.launcher.ServerAnswer
import run.ostrom.cli.launcher.ServerKeyPins
import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.pretty
import run.ostrom.cli.launcher.text
import run.ostrom.cli.platform.Console
import run.ostrom.cli.platform.FileStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock

/**
 * Pins, on `ostrom receipts pull`, the key a receipt is signed with for its server, so that `ostrom verify` checks it
 * offline afterwards: the same first-use pin as the launcher's (`PackageTrust`), both by the rule of `ServerKeyPins`,
 * in `<config>/server-keys.json`. A key already pinned for the server is never replaced: a receipt signed with another
 * key is said, and `ostrom verify` refuses it until the entry is deleted on purpose. Only an Ed25519 key whose computed
 * kid is the receipt's is pinned: a wrong answer of the server never becomes a pin that only a hand edit undoes.
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
     * @throws run.ostrom.cli.platform.FileWriteException When `server-keys.json` cannot be written.
     */
    fun pin(signed: SignedIn, receipt: JsonObject, directory: String): Boolean {
        val kid = kidOf(receipt)
        val path = ServerKeyPins.path(directory)
        val pinned = ServerKeyPins.all(files.read(path)).child(signed.serverUrl)
        val problem = when {
            kid == null -> "this receipt names no signing key: `ostrom verify` will say its signature is not valid"
            pinned == null -> firstUse(signed, kid, path)
            ServerKeyPins.pinnedPem(pinned, kid) == null -> {
                val other = pinned.text("kid")
                val what = when (other) {
                    null -> "no usable key"
                    kid -> "an unusable key $kid"
                    else -> "key $other"
                }
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
        val pem = if (answer is ServerAnswer.Ok) ServerKeyPins.publishedKey(answer.body, kid) else null
        return if (pem == null) {
            "${signed.serverUrl} does not publish the key $kid of this receipt: `ostrom verify` needs it with " +
                "--key FILE (GET ${Routes.KEYS} of your Ostrom)"
        } else {
            files.exclusive(path) {
                // read again under the lock: another process may have pinned servers meanwhile, and a pin it made for
                // this one wins (the entries read come last, over this one)
                val all = ServerKeyPins.all(files.read(path))
                files.write(path, pretty(ServerKeyPins.withPin(all, signed.serverUrl, kid, pem, now())))
            }
            console.out("✓ Pinned Ostrom's signing key $kid for ${signed.serverUrl} (first use).\n")
            null
        }
    }

    private companion object {
        const val KID = "kid="

        /** The kid of `ed25519;kid=…;sha256=…;sig=…` (ostrom-core `SignatureField`); null when none or empty. */
        fun kidOf(receipt: JsonObject): String? {
            val parts = receipt.text("signature")?.split(';').orEmpty()
            val part = parts.firstOrNull { it.startsWith(KID) }
            val kid = part?.removePrefix(KID)
            return if (kid.isNullOrEmpty()) null else kid
        }
    }
}
