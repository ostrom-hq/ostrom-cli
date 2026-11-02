// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

import dev.ostrom.cli.platform.ProcessCapture
import dev.ostrom.cli.platform.RemoteUrls

/**
 * The repository a launch registers its session for when none is named: `owner/name` of the `origin` remote of the
 * repository at [top] (https or ssh), as the gate hook names it, so that a gate set on `owner/name` covers the
 * session. Else, as a last resort, the folder's name, said, since such a gate does not cover it.
 *
 * @param top The git top-level folder of the launch (the working folder outside a repository).
 * @param capture Runs `git remote get-url origin`.
 * @param say Tells the developer.
 * @return `owner/name`, or the folder's name.
 */
internal fun launchRepository(top: String, capture: ProcessCapture, say: (String) -> Unit): String {
    val origin = RemoteUrls.origin(top, capture, GIT_MILLIS)
    if (origin != null) return origin
    val name = RemoteUrls.folderName(top)
    // never the URL itself: it may carry a token
    say(
        "ostrom: no origin with an owner/name here: the session is registered for \"$name\", this folder's name, and " +
            "a gate set on owner/name does not cover it (--repo owner/name names the repository)",
    )
    return name
}

private const val GIT_MILLIS = 5_000
