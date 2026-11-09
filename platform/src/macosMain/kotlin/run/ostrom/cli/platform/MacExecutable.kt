// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.platform

import platform.Foundation.NSBundle

/** The running binary, as this platform says it. */
actual fun currentExecutable(): String? = NSBundle.mainBundle.executablePath
