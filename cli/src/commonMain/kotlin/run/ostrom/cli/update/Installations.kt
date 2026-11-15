// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.update

import okio.FileSystem
import okio.IOException
import run.ostrom.cli.platform.Environment
import run.ostrom.cli.platform.InstallationFiles

/**
 * The managed installations of `ostrom install` on this device (TeamCode's `ManagedInstallation`): the one a binary
 * runs from, and the one a root names.
 *
 * @param target The release target of this binary (`linux-x64`, `windows-x64`…).
 */
internal class Installations(
    private val fileSystem: FileSystem,
    private val files: InstallationFiles,
    val target: String,
    private val environment: Environment,
) {
    private val windows = target.startsWith("windows-")

    /** The installation at [root], or at this user's default place when null. */
    fun at(root: String?): InstallationStore {
        val paths = try {
            InstallationPaths(installationPath(root ?: defaultRoot()), target)
        } catch (_: IllegalArgumentException) {
            refuseInstallation("release_root_invalid")
        }
        return InstallationStore(paths, fileSystem, files)
    }

    /**
     * The default root: `%LOCALAPPDATA%\Ostrom` on Windows, `~/Library/Application Support/Ostrom` on macOS,
     * `$XDG_DATA_HOME/ostrom` (`~/.local/share/ostrom`) on Linux.
     */
    private fun defaultRoot(): String {
        fun home() = environment["HOME"] ?: environment["USERPROFILE"] ?: refuseInstallation(HOME_UNAVAILABLE)
        val xdg = environment["XDG_DATA_HOME"]
        return when {
            windows -> (environment["LOCALAPPDATA"] ?: "${home()}/AppData/Local") + "/Ostrom"
            target.startsWith("macos-") -> (xdg ?: "${home()}/Library/Application Support") + "/Ostrom"
            else -> (xdg ?: "${home()}/.local/share") + "/ostrom"
        }
    }

    /**
     * The installation [executable] runs from, as its launcher (`bin/`) or one of its payloads
     * (`versions/<digest>/`), or null: only that layout is ever recognised, never a setting or a PATH entry.
     */
    @Suppress("ReturnCount") // One early return per thing that makes it another binary than ours.
    fun of(executable: String): InstallationStore? {
        val binary = installationPath(executable)
        val metadata = fileSystem.metadataOrNull(binary) ?: return null
        if (!metadata.isRegularFile && metadata.symlinkTarget == null) return null
        val actual = fileSystem.canonicalize(binary)
        val parent = (actual / "..").normalized()
        val grandparent = (parent / "..").normalized()
        val root = when {
            parent.name.equals("bin", ignoreCase = windows) -> grandparent
            grandparent.name == "versions" && DIGEST.matches(parent.name) -> (grandparent / "..").normalized()
            else -> return null
        }
        val store = InstallationStore(InstallationPaths(root, target), fileSystem, files)
        val named = actual.name.equals(store.paths.executableName, ignoreCase = windows)
        return store.takeIf { named && store.launcherVersion() != null }
    }

    /** True when [executable] is the launcher of [store], not one of its payloads. */
    fun isLauncher(store: InstallationStore, executable: String): Boolean =
        fileSystem.canonicalize(installationPath(executable)).toString()
            .equals(fileSystem.canonicalize(store.paths.launcher).toString(), ignoreCase = windows)

    /**
     * The path the hooks and MCP servers an agent starts must name: the stable launcher of the installation
     * [executable] runs from (a payload is deleted two updates later), else [executable] itself.
     */
    fun stable(executable: String?): String? = try {
        executable?.let { of(it)?.paths?.launcher?.toString() } ?: executable
    } catch (_: InstallationException) {
        executable
    } catch (_: IOException) {
        executable
    }

    private companion object {
        val DIGEST = Regex("[0-9a-f]{64}")
    }
}

/** The installation's lock is held by an installer: an interrupted selection is recovered by that one. */
private fun recoverIfIdle(store: InstallationStore) {
    if (store.journal() == null) return
    try {
        store.lock(exclusive = true)
    } catch (_: InstallationException) {
        null
    }?.use { store.recoverInterrupted() }
}

/**
 * When [executable] is the launcher of a managed installation, runs the selected payload with [arguments] in its place
 * ([run]); its exit code, or null when this binary is not a launcher and goes on as itself. A payload that does not
 * match its attestation is never run (exit 1, said on [err]). With nothing selected yet, only `install` and `update`
 * run, from the launcher itself.
 */
internal fun launchInstalled(
    arguments: List<String>,
    executable: String?,
    installations: Installations,
    run: (String, List<String>) -> Int,
    err: (String) -> Unit,
): Int? = executable?.let { launched(arguments, it, installations, run, err) }

private fun launched(
    arguments: List<String>,
    executable: String,
    installations: Installations,
    run: (String, List<String>) -> Int,
    err: (String) -> Unit,
): Int? = try {
    installations.of(executable)?.let { store ->
        recoverIfIdle(store)
        val selected = store.state()?.current
        when {
            !installations.isLauncher(store, executable) -> null
            selected == null && arguments.dropWhile { it == "--debug" }.firstOrNull() in SELF_COMMANDS -> null
            selected == null -> refuseInstallation("release_installation_missing")
            else -> {
                store.validate(selected)
                run(store.paths.binary(selected).toString(), arguments)
            }
        }
    }
} catch (refused: InstallationException) {
    err("ostrom: the installation cannot start (${refused.code}). Run the installer again.\n")
    1
} catch (failure: IOException) {
    err("ostrom: the installation cannot start: ${failure.message}. Run the installer again.\n")
    1
}

/** The commands a launcher with nothing selected still runs itself. */
private val SELF_COMMANDS = setOf("install", "update")

internal const val HOME_UNAVAILABLE = "release_home_unavailable"
