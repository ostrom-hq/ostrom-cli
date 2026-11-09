// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher

import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals

class RoutesTest {
    @Test
    fun valuesArePercentEncoded() {
        assertEquals("acme%2Fapi%20x", Routes.encode("acme/api x"))
        assertEquals("A-z_0.9~", Routes.encode("A-z_0.9~"))
        assertEquals("%C3%A9%3F%26", Routes.encode("é?&"))
    }

    @Test
    fun sessionRoutesEncodeTheirId() {
        assertEquals("/api/v1/sessions/a%2Fb/alive", Routes.alive("a/b"))
        assertEquals("/api/v1/sessions/s1/reopen", Routes.reopen("s1"))
        assertEquals("/api/v1/sessions/s1/end", Routes.end("s1"))
        assertEquals("/api/v1/sessions/s1/workflow-receipt", Routes.workflowReceipt("s1"))
        assertEquals("/api/v1/receipts/r%201/bundle", Routes.bundle("r 1"))
        assertEquals("/api/v1/launches/L%201", Routes.launch("L 1"))
    }

    @Test
    fun pathsAreJoinedForThisPlatform() {
        assertEquals(("/tmp".toPath() / "a" / "b").toString(), join("/tmp", "a", "b"))
        assertEquals("/tmp".toPath().toString(), join("/tmp"))
    }
}
