// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner.conversation

import run.ostrom.cli.platform.ExcludeFromCoverage
import run.ostrom.cli.runner.ProcessStarter
import run.ostrom.cli.runner.Terminal

/** The JVM runs the tests only, which give the host their own starter: this one starts nothing. */
// coverage: test-only JVM target; the shipped starters are the POSIX and Windows ones (posixTest, mingwTest).
@ExcludeFromCoverage
actual fun localAgentProcesses(): ProcessStarter = NoAgentProcesses

/** Refuses every start. */
// coverage: test-only JVM target, as above.
@ExcludeFromCoverage
private object NoAgentProcesses : ProcessStarter {
    override suspend fun start(
        script: String,
        arguments: List<String>,
        environment: Map<String, String>,
        directory: String,
    ): Terminal = throw UnsupportedOperationException("The test JVM starts no agent.")
}

/** The test JVM's environment. */
// coverage: test-only JVM target; the shipped readers are the POSIX and Windows ones.
@ExcludeFromCoverage
actual fun processEnvironment(): Map<String, String> = System.getenv()
