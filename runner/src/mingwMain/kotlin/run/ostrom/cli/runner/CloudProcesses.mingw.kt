// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import run.ostrom.cli.platform.ExcludeFromCoverage
import kotlin.system.exitProcess

/**
 * Says that a cloud session's processes run on Linux, and exits with 2.
 *
 * @param command One of [CloudProcess]'s commands.
 * @param args The arguments after it.
 */
// coverage: a cloud session's containers run Linux; this target only says so.
@ExcludeFromCoverage
actual fun runCloudProcess(command: String, args: List<String>): Nothing {
    println("ostrom cloud $command runs in a cloud session's Linux containers only.")
    exitProcess(EXIT_UNSUPPORTED)
}

/** The exit code of a bad configuration, as on Linux. */
private const val EXIT_UNSUPPORTED = 2
