// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.team

import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import kotlin.random.Random

/**
 * A team hook a launch runs: on [events] (those the agent has), [command] the absolute path of its installed, verified
 * executable, run with the event's JSON on standard input.
 */
class TeamHook(val name: String, val events: List<String>, val matcher: String?, val command: String, val seconds: Int)

/**
 * Installs verified versions of team hooks under `<root>/<name>/<digest hex>/` (root: `hooks/installed` in the CLI's
 * private folder), one folder per version, never changed once written: a running agent keeps the version it started
 * with, and the same digest is reused. Folders are the owner's only (0700), and so are the executables. A version is
 * reused only once every file on disk matches the marker ([MARKER]: the digest, the version, then one line per file),
 * and the marker gives back the digest: a file changed since (by an agent, say) gets the version downloaded again.
 * TeamCode's `LocalHookInstaller` (cli/…/hooks/TeamHooks.kt).
 *
 * @param restrict Sets the owner-only mode of a path (`executable`: 0700, folders included).
 */
class HookInstaller(
    private val fileSystem: FileSystem,
    root: String,
    private val restrict: (String, Boolean) -> Unit,
) {
    private val root = root.toPath()

    /** The installed executable of [hook]'s version, when an intact installation is there; else null. */
    fun installed(hook: EffectiveHook): String? {
        val folder = folderOf(hook)
        val lines = try {
            fileSystem.read(folder / MARKER) { readUtf8() }.lines().filter { it.isNotEmpty() }
        } catch (_: IOException) {
            emptyList()
        }
        val files = lines.drop(2)
        val intact = lines.firstOrNull() == hook.digest &&
            Delivered.digestOf(hook.definition.digestLine + files.joinToString("") { "$it\n" }) == hook.digest &&
            files.all { matches(folder, it) }
        val command = folder / hook.definition.command
        val listed = files.any { it.startsWith(hook.definition.command + "\u0000") }
        return if (intact && listed) command.toString() else null
    }

    /** Writes [files] of [hook], verified by [TeamHooks.files], and returns its executable. */
    fun install(hook: EffectiveHook, files: List<DeliveredFile>): String {
        installed(hook)?.let { return it }
        val name = root / hook.name
        listOfNotNull(root.parent, root, name).forEach(::private)
        // a staging folder of this launch only: two launches installing the same version never share one
        val staging = root / STAGING / "${hook.name}-${Random.nextLong().toULong().toString(HEX)}"
        try {
            private(staging)
            for (file in files) {
                val names = file.path.split('/')
                val parent = names.dropLast(1).fold(staging) { folder, part -> folder / part }
                private(parent)
                val path = parent / names.last()
                fileSystem.write(path) { write(file.content) }
                if (file.executable) restrict(path.toString(), true)
            }
            // sorted as the digest is: the marker gives it back
            val lines = files.sortedBy { it.path }.joinToString("") { file ->
                val mode = if (file.executable) Delivered.EXECUTABLE else Delivered.FILE
                Delivered.line(file.path, mode, file.content.size.toLong(), file.content.sha256().hex())
            }
            fileSystem.write(staging / MARKER) { writeUtf8("${hook.digest}\n${hook.version}\n$lines") }
            // another launch may have installed it meanwhile: its folder is kept, an agent may be running from it
            if (installed(hook) == null) {
                fileSystem.deleteRecursively(folderOf(hook), mustExist = false)
                fileSystem.atomicMove(staging, folderOf(hook))
            }
        } finally {
            fileSystem.deleteRecursively(staging, mustExist = false)
        }
        return installed(hook) ?: throw IOException("the hook ${hook.name} was not installed")
    }

    private fun folderOf(hook: EffectiveHook): Path = root / hook.name / hook.digest.removePrefix("sha256:")

    /** Whether the file a marker [line] names, in [folder], has the size and sha256 the line says. */
    private fun matches(folder: Path, line: String): Boolean {
        val parts = line.split('\u0000')
        val path = parts.first()
        return parts.size == PARTS && Delivered.validPath(path) && try {
            val bytes = fileSystem.read(path.split('/').fold(folder) { at, part -> at / part }) { readByteString() }
            bytes.size.toString() == parts[2] && bytes.sha256().hex() == parts[PARTS - 1]
        } catch (_: IOException) {
            false
        }
    }

    private fun private(folder: Path) {
        fileSystem.createDirectories(folder)
        restrict(folder.toString(), true)
    }

    /** The marker of an installed version. */
    companion object {
        /** The file in a version's folder that lists its files: the digest, the version, then one line per file. */
        const val MARKER = ".ostrom-hook"
        private const val STAGING = ".staging"
        private const val HEX = 16
        private const val PARTS = 4
    }
}
