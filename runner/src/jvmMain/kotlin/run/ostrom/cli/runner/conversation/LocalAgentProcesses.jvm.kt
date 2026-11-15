// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner.conversation

import run.ostrom.cli.platform.ExcludeFromCoverage
import run.ostrom.cli.runner.ProcessStarter

/** The JVM runs the tests only, which give the host their own starter. */
// coverage: test-only JVM target; the shipped starter is the POSIX one, tested in posixTest.
@ExcludeFromCoverage
actual fun localAgentProcesses(): ProcessStarter? = null

/** The test JVM's environment. */
// coverage: test-only JVM target; the shipped reader is the POSIX one.
@ExcludeFromCoverage
actual fun processEnvironment(): Map<String, String> = System.getenv()
