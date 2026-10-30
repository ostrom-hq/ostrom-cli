// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

import kotlinx.serialization.json.JsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** What became of the session package of a repository. */
sealed interface PackageOutcome {
    /** Verified: signature, digest and every file. */
    data class Verified(val pack: SessionPackage) : PackageOutcome

    /** The server has no package for this repository (404): the session runs with the gates only. */
    data object None : PackageOutcome

    /** Refused: nothing is laid down and the agent is not started (exit code 78). */
    data class Refused(val reason: String) : PackageOutcome
}

/**
 * Fetches the session package and verifies it before anything is written (prototype `package::fetch`): the format,
 * the SHA-256 of the exact payload bytes, the Ed25519 signature with the instance key pinned on first use per server
 * (`<config>/server-keys.json`), that the signed payload is the package of the repository asked for (security audit
 * S2-33: another repository's package is not this session's), then the SHA-256 of every file. The envelope is kept in
 * `<config>/packages/` so that `ostrom verify` can check the package a receipt cites without the network.
 *
 * @param now The time of a pin, ISO 8601.
 * @param say Writes a line for the developer (stderr).
 */
class PackageTrust(
    private val server: ServerJson,
    private val files: LaunchFiles,
    private val now: () -> String,
    private val say: (String) -> Unit,
) {
    /**
     * @param serverUrl The server, the key of the pin.
     * @param token The device token.
     * @param repo The repository of the session.
     * @param configDirectory The CLI's folder.
     */
    suspend fun fetch(serverUrl: String, token: String, repo: String, configDirectory: String): PackageOutcome {
        val route = "${Routes.PACKAGE}?repo=${Routes.encode(repo)}"
        val outcome = when (val answer = server.get(route, token, FETCH_MILLIS)) {
            is ServerAnswer.Failed ->
                if (answer.status == NOT_FOUND) PackageOutcome.None else PackageOutcome.Refused(answer.message)
            is ServerAnswer.Ok -> verify(answer.body, serverUrl, repo, configDirectory).also { verified ->
                if (verified is PackageOutcome.Verified) {
                    val kept = join(configDirectory, "packages", "${verified.pack.digest}.json")
                    files.write(kept, answer.body.toString(), FileAccess.PRIVATE)
                }
            }
        }
        return outcome
    }

    private suspend fun verify(
        envelope: JsonObject,
        serverUrl: String,
        repo: String,
        configDirectory: String,
    ): PackageOutcome {
        val payload = envelope.text("payload")
        val refusal = payload?.let { refusalOf(envelope, it) }
        return when {
            payload == null -> PackageOutcome.Refused("no payload")
            refusal != null -> PackageOutcome.Refused(refusal)
            else -> when (val pin = pinnedKey(serverUrl, configDirectory, envelope.text("kid").orEmpty())) {
                is Pin.Refused -> PackageOutcome.Refused(pin.reason)
                is Pin.Key -> signed(envelope, payload, pin.pem, repo)
            }
        }
    }

    private fun refusalOf(envelope: JsonObject, payload: String): String? = when {
        envelope.text("format") != FORMAT -> "not an $FORMAT envelope"
        envelope.text("alg") != ALGORITHM -> "unknown signature algorithm"
        envelope.flag("revoked") == true -> "the package was revoked"
        sha256Hex(payload.encodeToByteArray()) != envelope.text("digest") ->
            "the package digest does not match its bytes"
        else -> null
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun signed(envelope: JsonObject, payload: String, publicKeyPem: String, repo: String): PackageOutcome {
        val bytes = payload.encodeToByteArray()
        val signature = try {
            Base64.decode(envelope.text("signature").orEmpty())
        } catch (_: IllegalArgumentException) {
            ByteArray(0)
        }
        val body = parseObject(payload)
        return when {
            !ed25519Verify(publicKeyPem, bytes, signature) ->
                PackageOutcome.Refused("the package signature is not valid")
            body == null -> PackageOutcome.Refused("the payload is not a JSON object")
            body.text("repo") != repo ->
                PackageOutcome.Refused("the package was signed for ${body.text("repo")}, not $repo")
            else -> checkFiles(SessionPackage(body, sha256Hex(bytes)))
        }
    }

    private fun checkFiles(pack: SessionPackage): PackageOutcome {
        val tampered = pack.files.firstOrNull { file ->
            sha256Hex(file.text("content").orEmpty().encodeToByteArray()) != file.text("sha256")
        }
        return if (tampered == null) {
            PackageOutcome.Verified(pack)
        } else {
            PackageOutcome.Refused("file ${tampered.text("path")} does not match its digest")
        }
    }

    private sealed interface Pin {
        data class Key(val pem: String) : Pin

        data class Refused(val reason: String) : Pin
    }

    /** The key of [serverUrl], pinned on first use: a new key of the server is refused until unpinned on purpose. */
    private suspend fun pinnedKey(serverUrl: String, configDirectory: String, kid: String): Pin {
        val path = join(configDirectory, "server-keys.json")
        val all = parseObject(files.read(path)) ?: JsonObject(emptyMap())
        val pinned = all.child(serverUrl)
        return if (pinned != null) checkPin(pinned, kid, serverUrl, path) else pin(all, serverUrl, kid, path)
    }

    private fun checkPin(pinned: JsonObject, kid: String, serverUrl: String, path: String): Pin {
        val pem = pinned.text("publicKeyPem")
        return if (pinned.text("kid") == kid && pem != null) {
            Pin.Key(pem)
        } else {
            Pin.Refused(
                "the package is signed with key $kid, but this device pinned key ${pinned.text("kid")} for " +
                    "$serverUrl. A new Ostrom key is pinned again on purpose: delete its entry in $path",
            )
        }
    }

    private suspend fun pin(all: JsonObject, serverUrl: String, kid: String, path: String): Pin {
        val keys = (server.get(Routes.KEYS, null) as? ServerAnswer.Ok)?.let { it.body.list("keys") }.orEmpty()
        val key = keys.filterIsInstance<JsonObject>().firstOrNull { it.text("kid") == kid }
        val pem = key?.text("publicKeyPem") ?: return Pin.Refused("the server does not publish the key $kid")
        val entry = objectOf("kid" to kid, "publicKeyPem" to pem, "pinnedAt" to now())
        files.write(path, pretty(JsonObject(all + (serverUrl to entry))), FileAccess.PRIVATE)
        say("pinned Ostrom's signing key $kid for $serverUrl (first use)")
        return Pin.Key(pem)
    }

    private companion object {
        const val FORMAT = "ostrom.package/1"
        const val ALGORITHM = "ed25519"
        const val NOT_FOUND = 404
        const val FETCH_MILLIS = 30_000L
    }
}
