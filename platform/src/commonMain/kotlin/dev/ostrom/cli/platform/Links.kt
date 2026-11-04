// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.platform

/**
 * [path] with its links followed, as the system resolves it (`realpath`; on Windows, the final path of its handle), or
 * null when it does not resolve. okio's `canonicalize` does not follow links on Windows (`_wfullpath`).
 */
expect fun realPath(path: String): String?

/**
 * Makes [link] a symbolic link to [target], a folder or a file; false when it cannot (it exists, no right). okio
 * cannot make one on Windows.
 */
expect fun symbolicLink(target: String, link: String): Boolean
