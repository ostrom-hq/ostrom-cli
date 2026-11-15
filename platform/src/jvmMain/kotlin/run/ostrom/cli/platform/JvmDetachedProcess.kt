// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.platform

import java.io.File

/** A child of the test JVM, its output appended to [log]. */
// coverage: test-only JVM target; the shipped native actual is tested in posixTest.
@ExcludeFromCoverage
actual fun startDetachedProcess(command: List<String>, log: String): DetachedProcess {
    val process = ProcessBuilder(command)
        .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        .redirectOutput(ProcessBuilder.Redirect.appendTo(File(log)))
        .redirectErrorStream(true)
        .start()
    return DetachedProcess { if (process.isAlive) null else process.exitValue() }
}
