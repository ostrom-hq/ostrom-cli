// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.receipts

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The verdict of one check.
 *
 * @property symbol How the text report marks it.
 * @property word How the JSON report says it.
 */
enum class Mark(val symbol: String, val word: String) {
    /** Checked, and it holds. */
    OK("✓", "ok"),

    /** Checked, and it does not hold: the receipt is not valid. */
    BAD("✗", "failed"),

    /** Could not be checked offline: nothing is wrong, nothing is proven. */
    MISSING("?", "not checked"),
}

/**
 * One check of the report.
 *
 * @property mark Its verdict.
 * @property what What was checked: `signature`, `subject`, `package`, `decision`, `chain`, `node`, `session`.
 * @property text What was found, for a person.
 */
data class ReportLine(val mark: Mark, val what: String, val text: String)

/**
 * What `ostrom verify` found, check by check, and its exit code (prototype `verify.rs` `Report`).
 *
 * @property title The first line: which receipt, of what, by whom.
 */
class Report(val title: String) {
    private val entries = mutableListOf<ReportLine>()

    /** The checks, in the order they ran. */
    val lines: List<ReportLine> get() = entries

    /** True once the hint to pin or give a key was said: it is said once per report. */
    internal var hinted = false

    /** Adds a check. */
    fun add(mark: Mark, what: String, text: String) {
        entries += ReportLine(mark, what, text)
    }

    /** 0 verified, 1 something is wrong, 2 nothing is wrong but a link could not be checked offline. */
    val code: Int
        get() = when {
            entries.any { it.mark == Mark.BAD } -> INVALID
            entries.any { it.mark == Mark.MISSING } -> INCOMPLETE
            else -> VERIFIED
        }

    /** The text report, the prototype's to the character. */
    fun render(): String = buildString {
        append(title).append('\n')
        for (line in entries) append("  ${line.mark.symbol} ${line.what.padEnd(WHAT_WIDTH)} ${line.text}\n")
        append(
            when (code) {
                VERIFIED -> "VERIFIED offline: signature, package and chain hold. No network call was made.\n"
                INVALID -> "NOT VALID: see the ✗ lines above.\n"
                else -> "INCOMPLETE: nothing is wrong, but the ? lines could not be checked offline.\n"
            },
        )
    }

    /** The JSON report (`--json`), shaped as the prototype's: `checks`, `result`, `title`, two-space indented. */
    fun toJson(): String {
        val checks = entries.map {
            JsonObject(
                mapOf(
                    "check" to JsonPrimitive(it.what.trim()),
                    "detail" to JsonPrimitive(it.text),
                    "result" to JsonPrimitive(it.mark.word),
                ),
            )
        }
        val result = when (code) {
            VERIFIED -> "verified"
            INVALID -> "invalid"
            else -> "incomplete"
        }
        val report = mapOf(
            "checks" to JsonArray(checks),
            "result" to JsonPrimitive(result),
            "title" to JsonPrimitive(title),
        )
        return prettyJson.encodeToString(JsonObject.serializer(), JsonObject(report))
    }

    /** The report's wording. */
    companion object {
        private const val VERIFIED = 0
        private const val INVALID = 1
        private const val INCOMPLETE = 2
        private const val WHAT_WIDTH = 9

        @OptIn(ExperimentalSerializationApi::class)
        private val prettyJson = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }
    }
}
