// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.receipts

/** The organisation a receipt must have been signed for, as `ostrom verify` knows it offline. */
sealed interface ExpectedOrg {
    /** None named: no `--org`, no `OSTROM_ORG`, no organisation kept by `ostrom init`. */
    data object None : ExpectedOrg

    /**
     * Named, but its id is not known on this machine offline.
     *
     * @property named The short name given, never looked up online here.
     */
    data class Unknown(val named: String) : ExpectedOrg

    /**
     * Named, and its id known.
     *
     * @property id The organisation's id, compared with the receipt's signed `orgId`.
     * @property named What was given (`--org`, `OSTROM_ORG` or `ostrom init`'s), said in the report.
     */
    data class Known(val id: String, val named: String) : ExpectedOrg
}
