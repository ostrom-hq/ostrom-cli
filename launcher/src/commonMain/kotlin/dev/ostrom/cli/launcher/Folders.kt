// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

/** Folders compared as Gemini CLI compares them (`isSubpath`), whichever separator they are written with. */
object Folders {
    /**
     * True when [folder] is [parent] or under it, case ignored when [foldsCase] (macOS, Windows). Both are already
     * resolved ([LaunchFiles.canonical]); a trailing separator is not a name.
     */
    fun within(folder: String, parent: String, foldsCase: Boolean): Boolean {
        val child = fold(folder.trimEnd('/', '\\'), foldsCase)
        val above = fold(parent.trimEnd('/', '\\'), foldsCase)
        return child == above || child.startsWith("$above/") || child.startsWith("$above\\")
    }

    private fun fold(path: String, foldsCase: Boolean) = if (foldsCase) path.lowercase() else path
}
