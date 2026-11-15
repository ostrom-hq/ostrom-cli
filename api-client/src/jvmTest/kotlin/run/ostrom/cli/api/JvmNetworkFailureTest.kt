// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The JVM types an OkHttp engine raises, and local I/O errors that share java.io.IOException with them. */
class JvmNetworkFailureTest {
    @Test
    fun jvmNetworkTypesMapToTheirFamily() {
        assertEquals(NetworkFailure.DNS, networkFailure(java.net.UnknownHostException("releases.example")))
        assertEquals(NetworkFailure.REFUSED, networkFailure(java.net.ConnectException("Connection refused")))
        val tls = javax.net.ssl.SSLHandshakeException("PKIX path building failed")
        assertEquals(NetworkFailure.TLS, networkFailure(tls))
        assertEquals(NetworkFailure.TIMEOUT, networkFailure(java.net.SocketTimeoutException("Read timed out")))
    }

    @Test
    fun localDiskAndPermissionFailuresAreNotNetworkErrors() {
        for (error in listOf(java.io.IOException("No space left on device"), java.io.EOFException("truncated"))) {
            assertNull(networkFailure(error), error.toString())
        }
    }
}
