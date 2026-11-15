// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), cli/ui/DesktopApp.kt; see NOTICE.
package run.ostrom.cli.ui

import run.ostrom.cli.platform.OperatingSystem
import run.ostrom.cli.update.SemVer
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/** An installed Ostrom Desktop: its folder (the `.app` on macOS), its version (null if unreadable), its launcher. */
internal data class DesktopInstallation(val root: Path, val version: String?, val executable: Path) {
    /** Whether this app can be used with a CLI of [cliVersion]: the same version or a newer one. */
    @Suppress("ReturnCount")
    fun usableWith(cliVersion: String): Boolean {
        val own = version?.let { SemVer.of(it) } ?: return false
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
    private val home: Path? = (if (os == OperatingSystem.WINDOWS) env("USERPROFILE") ?: env("HOME") else env("HOME"))
        ?.toPath()?.takeIf { it.isAbsolute }

    private val dataHome: Path? = env("XDG_DATA_HOME")?.toPath()?.takeIf { it.isAbsolute }
        ?: home?.resolve(".local")?.resolve("share")

    /** macOS: the folder the app is installed in. */
    val applications: Path? get() = home?.resolve("Applications")

    /** Linux: the folder holding one folder per version and the `current` link. */
    val linuxRoot: Path? get() = dataHome?.resolve("ostrom-desktop")

    /** Linux: the menu entry `ostrom ui` writes. */
    val linuxDesktopEntry: Path? get() = dataHome?.resolve("applications")?.resolve("ostrom-desktop.desktop")

    /** Windows: the per-user installer's folder. */
    val windowsRoot: Path? get() = env("LOCALAPPDATA")?.toPath()?.takeIf { it.isAbsolute }?.resolve("Ostrom App")

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
        if (fs.metadataOrNull(executable)?.isRegularFile != true) return null
        return DesktopInstallation(root, readVersion(versionFile(root)), executable)
    }

    /** Every installed app, in [candidates] order. */
    fun installed(): List<DesktopInstallation> = candidates().mapNotNull(::at)

    private fun readVersion(file: Path): String? = try {
        val metadata = fs.metadataOrNull(file)
        if (metadata?.isRegularFile != true || (metadata.size ?: 0) > MAX_VERSION_BYTES) {
            null
        } else {
            fs.source(file).buffer().use { it.readUtf8() }.trim().takeIf { SemVer.of(it) != null }
        }
    } catch (_: IOException) {
        null
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
            .replace("$", "\\$") + "\""
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
