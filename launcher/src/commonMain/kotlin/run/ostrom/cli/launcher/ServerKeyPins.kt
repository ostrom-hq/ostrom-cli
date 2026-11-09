// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher

import run.ostrom.cli.launcher.receipts.KeyReading
import run.ostrom.cli.launcher.receipts.TrustedKeys
import kotlinx.serialization.json.JsonObject

/**
 * `<config>/server-keys.json`: the signing key pinned for each Ostrom server on first use, by the launcher on a
 * server's first package (`PackageTrust`) and by `ostrom receipts pull`, and read by `ostrom verify`
 * (`TrustedKeys.pinned`). One rule for all of them: only a key that is the one asked for is pinned, and a pin is
 * never replaced.
 */
object ServerKeyPins {
    /** The file, in the CLI's folder. */
    const val FILE = "server-keys.json"

    /** The pins file of the CLI folder [configDirectory]. */
    fun path(configDirectory: String): String = join(configDirectory, FILE)

    /** The pins of the file's [text], by server; none when there is no file or it is not a JSON object. */
    fun all(text: String?): JsonObject = parseObject(text) ?: JsonObject(emptyMap())

    /**
     * [pins] with [kid] and its [pem] pinned for [serverUrl] at [at]. A pin already there for it wins; an entry that is
     * not a pin (null, a string) is replaced, or the first use would say it pinned what it never wrote.
     */
    fun withPin(pins: JsonObject, serverUrl: String, kid: String, pem: String, at: String): JsonObject =
        if (pins[serverUrl] is JsonObject) {
            pins
        } else {
            JsonObject(pins + (serverUrl to objectOf("kid" to kid, "publicKeyPem" to pem, "pinnedAt" to at)))
        }

    /**
     * The PEM of a server's [pinned] entry when it is the pin of [kid] with its key, an Ed25519 key whose computed kid
     * is [kid]; null for any other pin, a wrong key pinned by an older ostrom included.
     */
    fun pinnedPem(pinned: JsonObject, kid: String): String? {
        val pem = pinned.text("publicKeyPem")
        return if (pinned.text("kid") == kid && pem != null && isKey(pem, kid)) pem else null
    }

    /**
     * The PEM of [kid] in a server's key list (`GET /api/v1/receipts/keys`: `{"keys": [{"kid", "publicKeyPem"}]}`):
     * the first listed under [kid] that is an Ed25519 key whose kid, computed as the server computes it, is [kid]; null
     * when none is. A wrong answer of the server never becomes a pin that only a hand edit undoes.
     */
    fun publishedKey(keyList: JsonObject, kid: String): String? =
        keyList.list("keys").filterIsInstance<JsonObject>()
            .filter { it.text("kid") == kid }
            .mapNotNull { it.text("publicKeyPem") }
            .firstOrNull { isKey(it, kid) }

    private fun isKey(pem: String, kid: String): Boolean {
        val reading = TrustedKeys.fromPem(pem, "")
        return reading is KeyReading.Keys && reading.keys.any { it.kid == kid }
    }
}
