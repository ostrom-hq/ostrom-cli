// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import kotlinx.coroutines.runBlocking
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.service.InstalledAt

/**
 * Runs the local service step once an install or an update went through, and says its one line (on the error stream
 * when it failed). A failure of the step never changes the exit code of the command that ran before it.
 */
internal fun CliDependencies.reportServiceStep(noService: Boolean, installed: InstalledAt? = null) {
    val setup = runBlocking { serviceStep.setUp(this@reportServiceStep, noService, installed) } ?: return
    if (setup.failed) console.err(setup.line + "\n") else console.out(setup.line + "\n")
}
