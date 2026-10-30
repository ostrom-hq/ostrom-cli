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
 * refuses it until the entry is deleted on purpose.
 */
internal class ServerKeyPin(
    private val files: FileStore,
    private val console: Console,
    private val now: () -> String = { Clock.System.now().toString() },
) {
    /**
     * Pins the key [receipt] names, for the server of [signed], in the CLI folder [directory]. A receipt that names no
     * key pins nothing: `ostrom verify` says its signature is not valid.
     *
     * @throws dev.ostrom.cli.platform.FileWriteException When `server-keys.json` cannot be written.
     */
    fun pin(signed: SignedIn, receipt: JsonObject, directory: String) {
        val kid = kidOf(receipt) ?: return
        val path = join(directory, KEYS_FILE)
        files.exclusive(path) {
            val all = parseObject(files.read(path)) ?: JsonObject(emptyMap())
            val pinned = all.child(signed.serverUrl)
            when {
                pinned == null -> firstUse(signed, kid, all, path)
                pinned.text("kid") != kid -> console.err(
                    "ostrom: this receipt is signed with key $kid, but this device pinned key " +
                        "${pinned.text("kid")} for ${signed.serverUrl}. The pin is kept: a new Ostrom key is pinned " +
                        "again on purpose, by deleting its entry in $path\n",
                )
            }
        }
    }

    private fun firstUse(signed: SignedIn, kid: String, all: JsonObject, path: String) {
        val answer = runBlocking { signed.server.get(Routes.KEYS, null) }
        val keys = if (answer is ServerAnswer.Ok) answer.body.list("keys") else emptyList()
        val pem = keys.filterIsInstance<JsonObject>().firstOrNull { it.text("kid") == kid }?.text("publicKeyPem")
        if (pem == null) {
            console.err(
                "ostrom: ${signed.serverUrl} does not publish the key $kid of this receipt: `ostrom verify` needs " +
                    "it with --key FILE (GET ${Routes.KEYS} of your Ostrom)\n",
            )
        } else {
            val entry = objectOf("kid" to kid, "publicKeyPem" to pem, "pinnedAt" to now())
            files.write(path, pretty(JsonObject(all + (signed.serverUrl to entry))))
            console.out("✓ Pinned Ostrom's signing key $kid for ${signed.serverUrl} (first use).\n")
        }
    }

    private companion object {
        const val KEYS_FILE = "server-keys.json"
        const val KID = "kid="

        /** The kid of `ed25519;kid=…;sha256=…;sig=…` (ostrom-core `SignatureField`), or null. */
        fun kidOf(receipt: JsonObject): String? =
            receipt.text("signature")?.split(';').orEmpty().firstOrNull { it.startsWith(KID) }?.removePrefix(KID)
    }
}
