// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.receipts

import run.ostrom.cli.launcher.PACKAGE_ALGORITHM
import run.ostrom.cli.launcher.PACKAGE_FORMAT
import run.ostrom.cli.launcher.ed25519Verify
import run.ostrom.cli.launcher.parseObject
import run.ostrom.cli.launcher.sha256Hex
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Checks 2 and 3 of a workflow receipt: its subject, and the package it cites (prototype verify.rs, lines 340-396).

/** The package a workflow receipt cites (`package: {version, digest}`); nothing for the other receipts. */
internal fun Verification.checkPackage() {
    val cited = receipt.field("package") as? JsonObject ?: return
    val version = cited.field("version").strOr("")
    val digest = cited.field("digest").strOr("")
    checkSubject(version, digest)
    val envelope = input.pack ?: kept.envelope(digest)
    if (envelope == null) {
        val text = "$version (digest ${short(digest)}…) is cited, but its envelope is neither in the file nor kept " +
            "on this machine"
        report.add(Mark.MISSING, "package", text)
    } else {
        checkEnvelope(envelope, version, digest)
    }
}

/** `subject.id` is `<session>@<version>`, and its digest is recomputed as the server computed it. */
private fun Verification.checkSubject(version: String, digest: String) {
    val subject = receipt.field("subject").field("id").strOr("")
    val at = subject.lastIndexOf('@')
    if (at < 0 || subject.substring(at + 1) != version) {
        report.add(Mark.BAD, "subject", "subject $subject does not name package version $version")
        return
    }
    val session = subject.substring(0, at)
    val nodes = receipt.field("nodes") ?: JsonNull
    val preimage = mapOf("session" to JsonPrimitive(session), "package" to JsonPrimitive(digest), "nodes" to nodes)
    val wanted = sha256Hex(CanonicalJson.bytes(JsonObject(preimage)))
    val signed = receipt.field("subject").field("digest").str()
    if (signed == wanted) {
        val text = "session $session under package $version: digest recomputed from the session, the package " +
            "digest and the ${nodes.items().size} node(s)"
        report.add(Mark.OK, "subject", text)
    } else {
        val text = "subject digest ${short(signed ?: "")}… does not match the session, package and nodes it reports " +
            "(${short(wanted)}…)"
        report.add(Mark.BAD, "subject", text)
    }
}

/** Why the launcher would refuse [envelope] whatever its bytes (`PackageTrust`), or null. */
private fun refusalOf(envelope: JsonObject): String? = when {
    envelope.field("format").str() != PACKAGE_FORMAT -> "the envelope is not $PACKAGE_FORMAT"
    envelope.field("alg").str() != PACKAGE_ALGORITHM -> "the envelope is not signed with $PACKAGE_ALGORITHM"
    envelope.field("revoked") == JsonPrimitive(true) -> "the package was revoked"
    else -> null
}

/**
 * The envelope is one the launcher would accept (format, algorithm, not revoked), has the cited digest over its exact
 * bytes, carries the cited version, and is signed by a trusted key.
 */
private fun Verification.checkEnvelope(envelope: JsonObject, version: String, digest: String) {
    val refusal = refusalOf(envelope)
    val payload = envelope.field("payload").strOr("")
    val bytes = payload.encodeToByteArray()
    val hashed = sha256Hex(bytes)
    val body = parseObject(payload)
    val kid = envelope.field("kid").strOr("")
    val key = keys.firstOrNull { it.kid == kid }
    val signature = decodeBase64(envelope.field("signature").strOr(""))
    val readable = signature != null && signature.size == ED25519_SIGNATURE_SIZE
    when {
        refusal != null -> report.add(Mark.BAD, "package", "$version: $refusal")
        hashed != digest || envelope.field("digest").str() != digest -> report.add(
            Mark.BAD,
            "package",
            "the envelope's bytes hash to ${short(hashed)}…, the receipt cites ${short(digest)}…",
        )
        body.field("version").str() != version -> report.add(
            Mark.BAD,
            "package",
            "the envelope is version ${body.field("version").strOr("?")}, the receipt cites $version",
        )
        key == null -> report.add(
            Mark.MISSING,
            "package",
            "$version: digest matches, but it is signed by key $kid, not pinned here",
        )
        signature == null || !readable -> report.add(Mark.BAD, "package", "$version: unreadable signature")
        !ed25519Verify(key.publicKeyPem, bytes, signature) ->
            report.add(Mark.BAD, "package", "$version: bad Ed25519 signature on the envelope")
        else -> checkPackagedNodes(body, version, kid, digest)
    }
}

/** Every node the receipt reports on is in the package (same id, same workflow). */
private fun Verification.checkPackagedNodes(body: JsonElement?, version: String, kid: String, digest: String) {
    val packaged = body.field("nodes").items()
    val missing = receipt.field("nodes").items().filter { node ->
        packaged.none { it.field("id") == node.field("id") && it.field("workflowId") == node.field("workflowId") }
    }
    if (missing.isEmpty()) {
        val workflows = body.field("workflows").items().map { it.field("name").str() ?: it.field("id").str() }
        val named = workflows.filterNotNull()
        val from = if (named.isEmpty()) "the decided rules" else named.joinToString("\", \"", "\"", "\"")
        val text = "$version: envelope signed by key $kid, digest ${short(digest)}… over its exact bytes, compiled " +
            "${body.field("compiledAt").strOr("?")} from $from"
        report.add(Mark.OK, "package", text)
    } else {
        val ids = missing.joinToString(", ") { it.field("id").strOr("?") }
        report.add(Mark.BAD, "package", "$version: node(s) $ids reported by the receipt are not in the package")
    }
}
