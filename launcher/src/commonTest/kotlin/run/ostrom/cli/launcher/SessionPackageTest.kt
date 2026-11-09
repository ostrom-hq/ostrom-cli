// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionPackageTest {
    private val pack = SessionPackage(
        checkNotNull(parseObject(checkNotNull(checkNotNull(parseObject(PackageVector.ENVELOPE)).text("payload")))),
        PackageVector.DIGEST,
    )

    @Test
    fun theVectorReadsAsThePrototypeSummedItUp() {
        assertEquals("p202610300845-a216a112", pack.version)
        assertEquals(listOf("ostrom", "ostrom-workflow"), pack.mcpServers)
        assertEquals(10, pack.files.size)
        assertEquals(true, pack.gateCatalog.orEmpty().contains("\"slack_post\""), "ostrom/gates.json")
        assertEquals(true, pack.context.startsWith("[Ostrom] Workflows compiled into this session:"))
        assertEquals(
            "package p202610300845-a216a112 (1 workflow(s), 7 node(s): 3 hook(s), 1 script(s) of which 1 enforced, " +
                "1 notification(s), 10 file(s)), signature valid, digest ec5164a0a4ae",
            pack.summary(),
        )
    }

    @Test
    fun enforcedChecksAddUpAndShadowOnesNeverRun() {
        val scripts = listOf(
            mapOf("mode" to "enforce", "timeoutSec" to 120),
            mapOf("mode" to "enforce", "timeoutSec" to 60),
            mapOf("mode" to "shadow", "timeoutSec" to 500),
            mapOf("mode" to "enforce"),
            mapOf("mode" to "enforce", "timeoutSec" to "x"),
            "not a script",
        )
        assertEquals(180, SessionPackage(objectOf("scripts" to scripts), "d").enforcedCheckSeconds)
        val empty = SessionPackage(JsonObject(emptyMap()), "d")
        assertEquals(0, empty.enforcedCheckSeconds)
        assertEquals("?", empty.version)
        assertEquals(null, empty.gateCatalog)
        assertEquals("", empty.context)
        assertEquals(emptyList(), empty.mcpServers)
        val unnamed = SessionPackage(objectOf("mcp" to mapOf("servers" to listOf(mapOf("path" to "/x"), "s"))), "d")
        assertEquals(emptyList(), unnamed.mcpServers)
    }
}
