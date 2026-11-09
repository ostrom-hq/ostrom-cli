// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class UrlsTest {
    @Test
    fun unreservedCharactersStayAndEverythingElseIsPercentEncoded() {
        assertEquals("acme-app_1.2~x", Urls.encode("acme-app_1.2~x"))
        assertEquals("acme%2Fapp%20%3F%26", Urls.encode("acme/app ?&"))
        // a letter outside ASCII is encoded byte by byte, even though each byte reads as a letter
        assertEquals("caf%C3%A9", Urls.encode("café"))
    }
}
