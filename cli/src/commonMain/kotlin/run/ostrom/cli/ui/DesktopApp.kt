// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), cli/ui/DesktopApp.kt; see NOTICE.
package run.ostrom.cli.ui

import run.ostrom.cli.platform.OperatingSystem
import run.ostrom.cli.update.SemVer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/** An installed Ostrom Desktop: its folder (the `.app` on macOS), its version (null if unreadable), its launcher. */
internal data class DesktopInstallation(val root: Path, val version: String?, val executable: Path) {
    /** Whether this app can be used with a CLI of [cliVersion]: the same version or a newer one. */
    @Suppress("ReturnCount")
    fun usableWith(cliVersion: String): Boolean {
        val own = SemVer.of(version.orEmpty()) ?: return false
        val wanted = SemVer.of(cliVersion) ?: return false
        return own >= wanted
    }
}

/**
 * Where Ostrom Desktop is installed on each system, in the order `ostrom ui` looks:
 * - macOS: `~/Applications/Ostrom.app` (where it installs it), then `/Applications/Ostrom.app` (the disk image);
 * - Windows: `%LOCALAPPDATA%\Ostrom App` (the per-user installer);
 * - Linux: `$XDG_DATA_HOME/ostrom-desktop/current` (`~/.local/share/ostrom-desktop/current`), then the `.deb`'s
 *   `/opt/ostrom`. The folder is not inside the CLI's own folders.
 * Each app carries its version in `ostrom-desktop-version`, in its resources folder (written by the build).
 */
internal class DesktopLocations(
    private val os: OperatingSystem,
    private val env: (String) -> String?,
    private val fs: FileSystem,
) {
    private fun absolute(value: String?): Path? = value.orEmpty().toPath().takeIf { it.isAbsolute }

    private val home: Path? = absolute(env("USERPROFILE").takeIf { os == OperatingSystem.WINDOWS } ?: env("HOME"))

    private val dataHome: Path? = absolute(env("XDG_DATA_HOME")) ?: home?.resolve(".local/share")

    /** macOS: the folder the app is installed in. */
    val applications: Path? get() = home?.resolve("Applications")

    /** Linux: the folder holding one folder per version and the `current` link. */
    val linuxRoot: Path? get() = dataHome?.resolve("ostrom-desktop")

    /** Windows: the per-user installer's folder. */
    val windowsRoot: Path? get() = absolute(env("LOCALAPPDATA"))?.resolve("Ostrom App")

    fun candidates(): List<Path> = when (os) {
        OperatingSystem.MACOS -> listOfNotNull(applications?.resolve(APP), "/Applications".toPath().resolve(APP))
        OperatingSystem.WINDOWS -> listOfNotNull(windowsRoot)
        OperatingSystem.LINUX -> listOfNotNull(linuxRoot?.resolve("current"), "/opt/ostrom".toPath())
    }

    fun versionFile(root: Path): Path = when (os) {
        OperatingSystem.MACOS -> root / "Contents" / "app" / "resources" / VERSION_FILE
        OperatingSystem.WINDOWS -> root / "app" / "resources" / VERSION_FILE
        OperatingSystem.LINUX -> root / "lib" / "app" / "resources" / VERSION_FILE
    }

    fun executable(root: Path): Path = when (os) {
        OperatingSystem.MACOS -> root / "Contents" / "MacOS" / "Ostrom"
        OperatingSystem.WINDOWS -> root / "Ostrom.exe"
        OperatingSystem.LINUX -> root / "bin" / "Ostrom"
    }

    /** The installation at [root], when its launcher exists. */
    fun at(root: Path): DesktopInstallation? {
        val executable = executable(root)
        if (!fs.exists(executable)) return null
        return DesktopInstallation(root, versionAt(root), executable)
    }

    /** Every installed app, in [candidates] order. */
    fun installed(): List<DesktopInstallation> = candidates().mapNotNull(::at)

    /** The version the app at [root] reports (at most [MAX_VERSION_BYTES] bytes are read), or null. */
    fun versionAt(root: Path): String? {
        val file = versionFile(root)
        if (!fs.exists(file)) return null
        val text = fs.source(file).buffer().use {
            it.request(MAX_VERSION_BYTES + 1L)
            it.readUtf8(minOf(it.buffer.size, MAX_VERSION_BYTES + 1L))
        }
        return text.trim().takeIf { SemVer.of(it) != null }
    }

    companion object {
        const val APP = "Ostrom.app"
        const val VERSION_FILE = "ostrom-desktop-version"
        private const val MAX_VERSION_BYTES = 128
    }
}

/**
 * How the app is started: on macOS through `open` (Launch Services, so the Dock and the menu bar treat it as an app),
 * elsewhere its launcher directly, detached from the terminal.
 */
internal fun desktopLaunchCommand(os: OperatingSystem, installation: DesktopInstallation): List<String> =
    if (os == OperatingSystem.MACOS) {
        listOf("open", installation.root.toString())
    } else {
        listOf(installation.executable.toString())
    }

/** The menu entry of the app on Linux, pointing at the `current` link so that an update needs no new entry. */
internal fun linuxDesktopEntry(root: Path): String {
    val current = root / "current"
    require(current.toString().none { it.isISOControl() })
    fun quoted(path: Path) =
        "\"" + path.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("`", "\\`")
            .replace("$", "\\$").replace("%", "%%") + "\""
    return listOf(
        "[Desktop Entry]",
        "Type=Application",
        "Name=Ostrom",
        "Comment=Follow and drive your team's agent sessions",
        "Exec=${quoted(current / "bin" / "Ostrom")}",
        "Icon=${current / "lib" / "Ostrom.png"}",
        "Categories=Development;",
        "Terminal=false",
        "",
    ).joinToString("\n")
}
