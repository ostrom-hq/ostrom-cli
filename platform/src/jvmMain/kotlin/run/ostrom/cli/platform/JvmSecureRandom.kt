// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.platform

import java.security.SecureRandom

// coverage: test-only JVM target; the shipped native actuals are tested by SecureRandomTest on each target.
@ExcludeFromCoverage
internal actual fun fillSecureRandom(bytes: ByteArray) {
    SecureRandom().nextBytes(bytes)
}
