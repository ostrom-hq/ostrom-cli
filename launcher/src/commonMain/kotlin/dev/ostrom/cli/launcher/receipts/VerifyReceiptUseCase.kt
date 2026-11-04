// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.receipts

import dev.ostrom.cli.launcher.parseObject
import kotlinx.serialization.json.JsonObject

/**
 * Verifies a receipt offline, with no network call (prototype `verify.rs`, `ostrom verify`), and says what holds:
 *
 * 1. signature: the SHA-256 of the canonical form matches the signed digest, and the Ed25519 signature is valid for a
 *    key this machine trusts (pinned at first use, or given by `--key`; never a key carried by the file);
 *    organisation: the `orgId` it was signed for (server#327) is the one expected; a receipt without one is not bound
 *    to an organisation and never valid for one;
 * 2. package (workflow receipt): the envelope it cites has that digest over its exact bytes, is signed by a trusted
 *    key, carries that version and every node the receipt reports on;
 * 3. subject (workflow receipt): `<session>@<version>`, its digest recomputed from the session, the package digest and
 *    the nodes;
 * 4. chain: every yes the receipt cites is backed by the gate's own signed receipt, the same approval, person, time,
 *    result and self-approval; the session receipt, when present, cites the same yeses.
 */
interface VerifyReceiptUseCase {
    /**
     * @param input The receipt and what its file carries.
     * @param keys The keys this machine trusts.
     * @param expected The organisation it must have been signed for.
     * @return The report; its code is the exit code of `ostrom verify` (0, 1 or 2).
     */
    operator fun invoke(input: ReceiptInput, keys: List<TrustedKey>, expected: ExpectedOrg = ExpectedOrg.None): Report
}

/**
 * [VerifyReceiptUseCase] that also reads the copies this machine kept: the package a bare receipt cites, the gate
 * receipts its nodes cite.
 */
class VerifyReceiptUseCaseImpl(private val kept: KeptCopies) : VerifyReceiptUseCase {
    override fun invoke(input: ReceiptInput, keys: List<TrustedKey>, expected: ExpectedOrg): Report {
        val verification = Verification(input, keys, kept, Report(titleOf(input)))
        verification.signatureLine("signature", "this receipt", input.receipt)
        verification.checkOrg(expected)
        verification.checkPackage()
        verification.checkDecisions()
        verification.checkNodes()
        verification.checkSession()
        return verification.report
    }

    /**
     * `Receipt <id> · <kind> of @<initiator> · <repository>` (CLI.md § 2.2); the subject when no package names it. The
     * repository is the one of the signed payload, never the envelope's unsigned header.
     */
    private fun titleOf(input: ReceiptInput): String {
        val receipt = input.receipt
        val subject = receipt.field("subject")
        val repository = parseObject(input.pack.field("payload").str()).field("repo").str()
        val where = repository ?: "subject ${subject.field("id").strOr("?")}"
        val title = "Receipt ${receipt.field("id").strOr("?")} · ${subject.field("kind").strOr("?")} of " +
            "@${receipt.field("initiator").strOr("?")} · $where · ${receipt.field("createdAt").strOr("?")}"
        val bare = "\n  a bare receipt: what it cites is looked up among the copies kept on this machine"
        return if (input.bundle) title else title + bare
    }
}

/** One verification under way: what it reads, and the report it writes. */
internal class Verification(
    val input: ReceiptInput,
    val keys: List<TrustedKey>,
    val kept: KeptCopies,
    val report: Report,
) {
    /** The receipt being verified. */
    val receipt: JsonObject get() = input.receipt

    /** The kind of its subject (`workflow`, `gate`, `session`…), or `?`. */
    val kind: String get() = receipt.field("subject").field("kind").strOr("?")

    /**
     * Checks the signature of [signed] and writes it as a [what] line; the hint to pin or give a key is said once.
     *
     * @return True when the signature is valid.
     */
    fun signatureLine(what: String, label: String, signed: JsonObject): Boolean {
        val check = checkSignature(signed, keys)
        when (check) {
            is SignatureCheck.Valid -> report.add(
                Mark.OK,
                what,
                "$label: Ed25519 valid, key ${check.kid} (${check.from}), digest ${short(check.digest)}…",
            )
            is SignatureCheck.Invalid -> report.add(Mark.BAD, what, "$label: ${check.why}")
            is SignatureCheck.UnknownKey -> report.add(Mark.MISSING, what, unknownKey(label, check.kid))
        }
        return check is SignatureCheck.Valid
    }

    /**
     * Writes the `org` line: the organisation the receipt was signed for against [expected], never naming it; a
     * receipt without one proves no organisation, whatever is expected.
     */
    fun checkOrg(expected: ExpectedOrg) {
        val signedFor = receipt.field("orgId").str()?.takeIf { it.isNotEmpty() }
        report.orgBound = signedFor != null
        val (mark, text) = when {
            signedFor == null -> Mark.MISSING to UNBOUND
            else -> when (expected) {
                ExpectedOrg.None -> Mark.MISSING to NONE_NAMED
                is ExpectedOrg.Unknown -> Mark.MISSING to unknownOrg(expected.named)
                is ExpectedOrg.Known -> if (signedFor.equals(expected.id, ignoreCase = true)) {
                    Mark.OK to "signed for the organisation named here (${expected.named})"
                } else {
                    Mark.BAD to "signed for another organisation than the one named here"
                }
            }
        }
        report.add(mark, "org", text)
    }

    private fun unknownOrg(named: String) = "bound to an organisation, but \"$named\" is not known on this device " +
        "offline: run ostrom init, or pass --org <organisation id>"

    private fun unknownKey(label: String, kid: String): String {
        if (report.hinted) return "$label: signed by key $kid, not pinned here"
        report.hinted = true
        val carried = if (kid in input.bundleKids) CARRIED else ""
        return "$label: signed by key $kid, which this machine has not pinned$carried. $PIN_HINT"
    }

    private companion object {
        const val CARRIED = " (the file carries it, but a key from the file being checked is not trusted)"
        const val PIN_HINT = "Pass --key FILE (GET /api/v1/receipts/keys of your Ostrom) or log in once to pin it"
        const val UNBOUND = "not bound to an organisation (orgBound false): signed before receipts carried theirs, " +
            "it does not prove which organisation it belongs to"
        const val NONE_NAMED = "bound to an organisation, none named here to check it against: pass --org " +
            "<organisation id>, or run ostrom init"
    }
}
