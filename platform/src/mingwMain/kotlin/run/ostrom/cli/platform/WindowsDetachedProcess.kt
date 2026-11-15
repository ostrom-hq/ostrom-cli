// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.platform

import okio.IOException

/** Windows hosts no remote session in this version, so nothing starts one in the background. */
actual fun startDetachedProcess(command: List<String>, log: String): DetachedProcess =
    throw IOException("Starting a background host is not supported on Windows in this version.")
