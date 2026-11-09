// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.receipts

import kotlin.test.Test
import kotlin.test.assertEquals

/** The report of `ostrom verify`, as text and as `--json`, shaped as the prototype's. */
class ReportTest {
    @Test
    fun anEmptyReportIsVerified() {
        val report = Report("Receipt r")
        assertEquals(0, report.code)
        val text = "Receipt r\nVERIFIED offline: signature, package and chain hold. No network call was made.\n"
        assertEquals(text, report.render())
        val json = "{\n  \"checks\": [],\n  \"result\": \"verified\",\n  \"title\": \"Receipt r\"\n}"
        assertEquals(json, report.toJson())
    }

    @Test
    fun aMissingLineMakesItIncompleteAndAFailedLineInvalid() {
        val report = Report("Receipt r")
        report.add(Mark.OK, "signature", "fine")
        report.add(Mark.MISSING, "chain", "unknown")
        assertEquals(2, report.code)
        val incomplete = "Receipt r\n  ✓ signature fine\n  ? chain     unknown\n" +
            "INCOMPLETE: nothing is wrong, but the ? lines could not be checked offline.\n"
        assertEquals(incomplete, report.render())
        report.add(Mark.BAD, "node", "wrong")
        assertEquals(1, report.code)
        assertEquals("NOT VALID: see the ✗ lines above.", report.render().lines().dropLast(1).last())
        assertEquals("  ✗ node      wrong", report.render().lines()[3])
        val json = """
            |{
            |  "checks": [
            |    {
            |      "check": "signature",
            |      "detail": "fine",
            |      "result": "ok"
            |    },
            |    {
            |      "check": "chain",
            |      "detail": "unknown",
            |      "result": "not checked"
            |    },
            |    {
            |      "check": "node",
            |      "detail": "wrong",
            |      "result": "failed"
            |    }
            |  ],
            |  "result": "invalid",
            |  "title": "Receipt r"
            |}
        """.trimMargin()
        assertEquals(json, report.toJson())
    }

    @Test
    fun theJsonSaysWhetherTheReceiptIsBoundToItsOrganisationOnceLookedAt() {
        val report = Report("t")
        report.orgBound = false
        val json = "{\n  \"checks\": [],\n  \"orgBound\": false,\n  \"result\": \"verified\",\n  \"title\": \"t\"\n}"
        assertEquals(json, report.toJson())
        report.orgBound = true
        assertEquals("\"orgBound\": true,", report.toJson().lines().first { "orgBound" in it }.trim())
    }

    @Test
    fun anIncompleteReportSaysSoInJson() {
        val report = Report("t")
        report.add(Mark.MISSING, "package", "p")
        assertEquals("\"result\": \"incomplete\",", report.toJson().lines().first { "result\": \"i" in it }.trim())
    }
}
