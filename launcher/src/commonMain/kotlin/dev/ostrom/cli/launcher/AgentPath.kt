// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

import okio.Path.Companion.toPath

/**
 * The `PATH` an agent gets: this binary's folder first, so that `ostrom wait` is found, then the developer's entries
 * that name a folder outside the working folder and the repository, links followed. An empty or relative entry, or one
 * inside the repository, would let the repository supply the programs the agent's hooks run, `bash` first (security
 * audit of the CLI n°2, H3).
 */
internal object AgentPath {
    /** The agent's `PATH`, or null when there is nothing to put on it. */
    fun of(site: LaunchSite, top: String, files: LaunchFiles): String? {
        // only an absolute path names a folder: a bare name would put the working folder on the agent's PATH
        val binary = site.executable.toPath()
        val folder = if (binary.isAbsolute) binary.parent else null
        val repository = listOf(site.cwd, top).map { files.canonical(it) }
        val inherited = site.variable("PATH").orEmpty().split(site.pathSeparator).filter { entry ->
            entry.isNotEmpty() && entry.toPath().isAbsolute &&
                repository.none { Folders.within(files.canonical(entry), it, site.foldsCase) }
        }
        val entries = listOfNotNull(folder?.toString()) + inherited
        return entries.joinToString(site.pathSeparator.toString()).ifEmpty { null }
    }
}
