// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), cli/ui/DesktopInstaller.kt; see NOTICE.
package run.ostrom.cli.ui

import run.ostrom.cli.ExitCode
import run.ostrom.cli.api.Download
import run.ostrom.cli.api.Downloads
import run.ostrom.cli.api.ReleaseDownloadException
import run.ostrom.cli.api.ServerUnreachableException
import run.ostrom.cli.platform.OperatingSystem
import run.ostrom.cli.update.SemVer
import okio.FileSystem
import okio.HashingSource
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.blackholeSink
import okio.buffer
import okio.use

/** Where the desktop release comes from: the GitHub release of a version, or an offline directory (`--bundle`). */
internal interface DesktopSource {
    /** The exact bytes of `desktop-manifest.json` and of its signature, for [version]. */
    suspend fun metadata(version: String): Pair<ByteArray, ByteArray>

    /** Writes the package [asset] of [version] to [destination] (a new file); its hash is checked by the caller. */
    suspend fun download(version: String, asset: DesktopAsset, destination: Path)
}

/** The release of ostrom-app at [releases] (`…/ostrom-hq/ostrom-app/releases`), tag [version]. */
@Suppress("ThrowsCount", "SwallowedException") // every refusal is a message and an exit code
internal class OnlineDesktopSource(
    private val downloads: Downloads,
    private val releases: String,
    private val fs: FileSystem,
) : DesktopSource {
    override suspend fun metadata(version: String): Pair<ByteArray, ByteArray> {
        val manifest = fetch(version, DesktopFormat.MANIFEST, DesktopFormat.MAX_MANIFEST_BYTES)
        return manifest to fetch(version, DesktopFormat.SIGNATURE, DesktopFormat.MAX_SIGNATURE_BYTES)
    }

    override suspend fun download(version: String, asset: DesktopAsset, destination: Path) {
        val bytes = fetch(version, asset.fileName, asset.size)
        if (bytes.size.toLong() != asset.size) {
            throw DesktopFailure(ExitCode.SIGNATURE_REFUSED, "✗ The download does not match its signed length.")
        }
        fs.write(destination, mustCreate = true) { write(bytes) }
    }

    private suspend fun fetch(version: String, file: String, maxBytes: Long): ByteArray {
        val url = "${releases.trimEnd('/')}/download/$version/$file"
        val answer = try {
            downloads.fetch(url, maxBytes)
        } catch (failure: ServerUnreachableException) {
            throw DesktopFailure(
                ExitCode.UNREACHABLE,
                "ostrom's releases are unreachable at $releases (${failure.message}).",
            )
        } catch (failure: ReleaseDownloadException) {
            throw DesktopFailure(ExitCode.FAILED, "✗ $url was refused: ${failure.detail ?: failure.code}.")
        }
        return when (answer) {
            is Download.Bytes -> answer.bytes
            is Download.Missing -> throw if (answer.status == NOT_FOUND) {
                DesktopFailure(ExitCode.NOT_FOUND, "No Ostrom Desktop was published for version $version.")
            } else {
                DesktopFailure(ExitCode.UNREACHABLE, "No release file at $url (HTTP ${answer.status}).")
            }
            is Download.TooLarge -> throw DesktopFailure(
                ExitCode.FAILED,
                "✗ $url is larger than ${answer.limit} bytes.",
            )
        }
    }

    private companion object {
        const val NOT_FOUND = 404
    }
}

/** `desktop-manifest.json`, its signature and the package, in one directory (release acceptance, offline use). */
internal class BundleDesktopSource(private val directory: Path, private val fs: FileSystem) : DesktopSource {
    override suspend fun metadata(version: String) =
        read(DesktopFormat.MANIFEST, DesktopFormat.MAX_MANIFEST_BYTES) to
            read(DesktopFormat.SIGNATURE, DesktopFormat.MAX_SIGNATURE_BYTES)

    override suspend fun download(version: String, asset: DesktopAsset, destination: Path) {
        val source = directory / asset.fileName
        if (regularSize(source) != asset.size) {
            throw DesktopFailure(
                ExitCode.NOT_FOUND,
                "✗ ${asset.fileName} is not a file of the bundle, or not the signed length.",
            )
        }
        fs.copy(source, destination)
    }

    /** The size of [file] when it is a regular file (okio never follows a link, so a link is not one), else null. */
    private fun regularSize(file: Path): Long? = fs.metadataOrNull(file)?.takeIf { it.isRegularFile }?.size

    private fun read(name: String, limit: Long): ByteArray {
        val file = directory / name
        val size = regularSize(file)
        if (size == null || size > limit) {
            throw DesktopFailure(ExitCode.NOT_FOUND, "✗ $file is not a file of the release (links are refused).")
        }
        return fs.read(file) { readByteArray() }
    }
}

/** The authenticated release of a version and the package of this system in it. */
internal class DesktopRelease(val release: VerifiedDesktop, val target: String, val asset: DesktopAsset) {
    val version: String get() = release.version
}

/** A package downloaded, checked and unpacked in [directory]: what [DesktopInstaller.activate] puts in place. */
internal class StagedDesktop(val version: String, val directory: Path)

/**
 * Installs Ostrom Desktop of the CLI's own version, as the current user, without administrator rights:
 * 1. the signed `desktop-manifest.json` of that version is authenticated with the release keys compiled into the CLI
 *    (those of `ostrom verify-release`), and must name that version;
 * 2. the package of this system is downloaded to a private temporary folder, then its exact length and SHA-256 are
 *    checked against the manifest before anything opens it;
 * 3. macOS: the zipped `.app` is extracted next to `~/Applications/Ostrom.app`, its Developer ID signature is checked
 *    (`codesign --verify --deep --strict` against the expected Team ID), then it replaces the previous app;
 *    Windows: `msiexec` installs the per-user package; Linux: the archive becomes a versioned folder, `current` points
 *    at it and a menu entry is written. Each checks that the installed app reports the expected version.
 * Nothing is run from the package before step 2 (and on macOS before the signature check); a failure leaves the
 * previously installed app in place.
 *
 * @param run Runs a program to the end and returns its exit code (-1 when it did not start or did not end in time).
 * @param macosTeamId The Apple Team ID the macOS app must be signed by; null: this build does not install on macOS.
 * @param restrict Makes a folder the owner's alone.
 * @param random A fresh name part (16 hex digits).
 */
@Suppress("LongParameterList", "TooManyFunctions", "ThrowsCount")
// one collaborator each, replaced in tests
internal class DesktopInstaller(
    private val os: OperatingSystem,
    private val target: String,
    private val locations: DesktopLocations,
    private val source: DesktopSource,
    private val temporary: Path,
    private val run: (List<String>, Int) -> Int,
    private val verifier: DesktopManifestVerifier,
    private val macosTeamId: String?,
    private val fs: FileSystem,
    private val random: () -> String,
    private val restrict: (Path) -> Unit,
    private val report: (String) -> Unit = {},
) {
    /** The authenticated release of [version] and this system's package in it. */
    suspend fun release(version: String): DesktopRelease {
        val (manifest, signature) = source.metadata(version)
        val verified = verifier.verify(manifest, signature)
        if (verified.version != version) {
            throw DesktopFailure(
                ExitCode.SIGNATURE_REFUSED,
                "✗ The desktop manifest is signed for Ostrom Desktop ${verified.version}, not $version.",
            )
        }
        val asset = verified.assets[target]
            ?: throw DesktopFailure(ExitCode.NOT_FOUND, "Ostrom Desktop $version has no package for $target.")
        return DesktopRelease(verified, target, asset)
    }

    suspend fun install(desktop: DesktopRelease): DesktopInstallation {
        val staged = stage(desktop)
        try {
            return activate(staged)
        } finally {
            discard(staged)
        }
    }

    /**
     * Downloads the package, checks its length and SHA-256, and on macOS and Linux unpacks it next to the installed app
     * with its signature and version checked; Windows keeps the checked package for `msiexec`. The installed app is not
     * touched; a failure leaves nothing behind.
     */
    suspend fun stage(desktop: DesktopRelease): StagedDesktop {
        if (os == OperatingSystem.MACOS && macosTeamId == null) {
            throw DesktopFailure(
                ExitCode.REFUSED,
                "This build of ostrom cannot check who signed the desktop app, so it does not install it on macOS. " +
                    "Download Ostrom Desktop from " +
                    "https://github.com/ostrom-hq/ostrom-app/releases/tag/${desktop.version}.",
            )
        }
        fs.createDirectories(temporary)
        val download = temporary / "$WINDOWS_STAGE${random()}"
        makePrivate(download, "desktop_staging_unavailable")
        val staged = cleaning(download) {
            val file = download / desktop.asset.fileName
            report("Downloading Ostrom Desktop ${desktop.version} (${megabytes(desktop.asset.size)})…")
            source.download(desktop.version, desktop.asset, file)
            checkPackage(file, desktop.asset)
            when (os) {
                OperatingSystem.MACOS -> stageMacos(file, desktop.version)
                OperatingSystem.WINDOWS -> StagedDesktop(desktop.version, download)
                OperatingSystem.LINUX -> stageLinux(file, desktop.version)
            }
        }
        // Windows keeps the checked package for msiexec; the others only needed it to unpack
        if (os != OperatingSystem.WINDOWS) deleteQuietly(download)
        return staged
    }

    /** Runs [block]; when it fails, removes [directory] and lets the failure through. */
    private suspend fun <T> cleaning(directory: Path, block: suspend () -> T): T = try {
        block()
    } catch (failure: DesktopFailure) {
        deleteQuietly(directory)
        throw failure
    } catch (failure: IOException) {
        deleteQuietly(directory)
        throw failure
    }

    /** Puts [staged] in place of the installed app (which must not be running) and returns it as installed. */
    fun activate(staged: StagedDesktop): DesktopInstallation = when (os) {
        OperatingSystem.MACOS -> activateMacos(staged)
        OperatingSystem.WINDOWS -> activateWindows(staged)
        OperatingSystem.LINUX -> activateLinux(staged)
    }

    /** Removes what [stage] left: the stage itself and, once activated, the previous app moved into it. */
    fun discard(staged: StagedDesktop) = deleteQuietly(staged.directory)

    /** The exact length and SHA-256 the signed manifest gives, checked before the package is opened. */
    private fun checkPackage(file: Path, asset: DesktopAsset) {
        val (size, hash) = HashingSource.sha256(fs.source(file)).use { source ->
            source.buffer().readAll(blackholeSink()) to source.hash.hex()
        }
        if (size != asset.size || hash != asset.sha256) {
            throw DesktopFailure(
                ExitCode.SIGNATURE_REFUSED,
                "✗ The download does not match its signed checksum. Nothing was changed.",
            )
        }
    }

    private suspend fun stageMacos(zip: Path, version: String): StagedDesktop {
        val applications = home(locations.applications)
        fs.createDirectories(applications)
        // Next to the app it replaces, on the same volume, so that putting it in place is a rename.
        val unpacked = applications / "$MACOS_STAGE${random()}"
        makePrivate(unpacked, "desktop_staging_unavailable")
        return cleaning(unpacked) {
            ran(listOf("ditto", "-x", "-k", zip.toString(), unpacked.toString()), EXTRACT_MILLIS, "extract the package")
            val app = unpacked / DesktopLocations.APP
            if (fs.list(unpacked) != listOf(app)) throw layout()
            report("Checking the signature of Ostrom Desktop $version…")
            val requirement = "=${developerIdRequirement(macosTeamId!!)}"
            val verify = listOf("codesign", "--verify", "--deep", "--strict", "-R", requirement, app.toString())
            if (run(verify, CODESIGN_MILLIS) != 0) {
                throw DesktopFailure(
                    ExitCode.SIGNATURE_REFUSED,
                    "✗ The desktop app is not signed by the expected Developer ID. Nothing was changed.",
                )
            }
            expectVersion(app, version)
            StagedDesktop(version, unpacked)
        }
    }

    private fun activateMacos(staged: StagedDesktop): DesktopInstallation {
        val applications = home(locations.applications)
        val destination = applications / DesktopLocations.APP
        val kept = staged.directory / "previous.app"
        val hadPrevious = fs.exists(destination)
        return swapped(hadPrevious, destination, kept) {
            fs.atomicMove(staged.directory / DesktopLocations.APP, destination)
            installed(destination, staged.version)
        }
    }

    private fun activateWindows(staged: StagedDesktop): DesktopInstallation {
        val root = home(locations.windowsRoot)
        val msi = staged.directory / DesktopFormat.fileName(staged.version, target)
        // Per-user package: no administrator rights; 3010 = installed, restart suggested. No window: the CLI has no
        // person to click. Its verbose log is kept in the temporary folder, and its wait is bounded.
        val log = temporary / "ostrom-desktop-msiexec.log"
        val arguments =
            listOf(
                "msiexec.exe", "/i", msi.toString(), "/qn", "/norestart", "/l*v", log.toString(),
                "MSIINSTALLPERUSER=1",
            )
        val code = run(arguments, WINDOWS_INSTALL_MILLIS)
        if (code != 0 && code != WINDOWS_RESTART_SUGGESTED) {
            report("The Windows installer did not finish (code ${code.toUInt()}); its log: $log")
            throw DesktopFailure(ExitCode.FAILED, "✗ The Windows installer failed. Nothing else was changed.")
        }
        return installed(root, staged.version)
    }

    private suspend fun stageLinux(archive: Path, version: String): StagedDesktop {
        val root = home(locations.linuxRoot)
        fs.createDirectories(root)
        val unpacked = root / "$LINUX_STAGE${random()}"
        makePrivate(unpacked, "desktop_staging_unavailable")
        return cleaning(unpacked) {
            val extract = listOf("tar", "-xzf", archive.toString(), "-C", unpacked.toString(), "--no-same-owner")
            ran(extract, EXTRACT_MILLIS, "extract the package")
            val app = unpacked / LINUX_APP
            if (fs.list(unpacked) != listOf(app) || !fs.exists(locations.executable(app))) throw layout()
            expectVersion(app, version)
            StagedDesktop(version, unpacked)
        }
    }

    private fun activateLinux(staged: StagedDesktop): DesktopInstallation {
        val root = home(locations.linuxRoot)
        val version = staged.version
        val destination = root / version
        val kept = staged.directory / "previous"
        val hadPrevious = fs.exists(destination)
        return swapped(hadPrevious, destination, kept) { linkCurrent(staged, root, destination) }
    }

    private fun linkCurrent(staged: StagedDesktop, root: Path, destination: Path): DesktopInstallation {
        val version = staged.version
        fs.atomicMove(staged.directory / LINUX_APP, destination)
        val current = root / "current"
        val previous = fs.metadataOrNull(current)?.symlinkTarget?.name
        // A new link renamed over the old one: `current` always names a complete version.
        val link = root / ".current-${random()}"
        fs.createSymlink(link, version.toPath())
        fs.atomicMove(link, current)
        for (entry in fs.list(root)) {
            val kept = entry.name == "current" || entry.name == version || entry.name == previous
            if (!kept && SemVer.of(entry.name) != null) deleteQuietly(entry)
        }
        // the root is `<data home>/ostrom-desktop`: the menu entry goes in `<data home>/applications`
        @Suppress("UnsafeCallOnNullableType")
        val entry = root.parent!! / "applications" / "ostrom-desktop.desktop"
        fs.createDirectories(entry.parent!!)
        fs.write(entry) { writeUtf8(linuxDesktopEntry(root)) }
        return installed(current, version)
    }

    /**
     * Moves the app at [destination] to [kept] (when there is one), runs [put] and, when it fails, puts the app back:
     * the previous app is only removed with the stage once the new one is in place and reports its version.
     */
    @Suppress("SwallowedException") // its message is in the failure
    private fun swapped(hadPrevious: Boolean, destination: Path, kept: Path, put: () -> DesktopInstallation) =
        try {
            if (hadPrevious) fs.atomicMove(destination, kept)
            put()
        } catch (failure: IOException) {
            restore(hadPrevious, destination, kept)
            throw DesktopFailure(ExitCode.CANNOT_WRITE, "✗ ostrom could not put the app in place: ${failure.message}")
        } catch (failure: DesktopFailure) {
            restore(hadPrevious, destination, kept)
            throw failure
        }

    private fun restore(hadPrevious: Boolean, destination: Path, kept: Path) {
        deleteQuietly(destination)
        if (hadPrevious) fs.atomicMove(kept, destination)
    }

    private fun ran(command: List<String>, timeoutMillis: Int, what: String) {
        if (run(command, timeoutMillis) != 0) {
            throw DesktopFailure(ExitCode.FAILED, "✗ Could not $what. Nothing was changed.")
        }
    }

    private fun makePrivate(directory: Path, why: String) {
        try {
            fs.createDirectory(directory, mustCreate = true)
            restrict(directory)
        } catch (_: IOException) {
            throw DesktopFailure(ExitCode.CANNOT_WRITE, "✗ ostrom cannot make a private folder ($why) at $directory.")
        }
    }

    private fun deleteQuietly(path: Path) {
        try {
            fs.deleteRecursively(path, mustExist = false)
        } catch (_: IOException) {
            // a stage that cannot be removed is left for the person to delete
        }
    }

    private fun expectVersion(root: Path, version: String) {
        if (locations.versionAt(root) != version) {
            throw DesktopFailure(
                ExitCode.SIGNATURE_REFUSED,
                "✗ The package holds another version than the signed $version. Nothing was changed.",
            )
        }
    }

    private fun installed(root: Path, version: String): DesktopInstallation =
        locations.at(root)?.takeIf { it.version == version }
            ?: throw DesktopFailure(ExitCode.FAILED, "✗ The installed app does not report version $version.")

    private fun home(folder: Path?): Path = folder ?: throw DesktopFailure(
        ExitCode.CANNOT_WRITE,
        "ostrom cannot tell where to install the desktop app: no home folder.",
    )

    private fun layout() = DesktopFailure(
        ExitCode.SIGNATURE_REFUSED,
        "✗ The package does not hold the Ostrom app alone. Nothing was changed.",
    )

    companion object {
        /** A Developer ID Application signature of [teamId], the one notarized apps outside the App Store carry. */
        fun developerIdRequirement(teamId: String): String {
            require(Regex("[A-Z0-9]{10}").matches(teamId))
            return "anchor apple generic and certificate 1[field.1.2.840.113635.100.6.2.6] exists and " +
                "certificate leaf[field.1.2.840.113635.100.6.1.13] exists and " +
                "certificate leaf[subject.OU] = \"$teamId\""
        }

        fun megabytes(bytes: Long): String =
            if (bytes < MEGA) "less than 1 MB" else "about ${(bytes + MEGA / 2) / MEGA} MB"

        private const val MEGA = 1_000_000L
        private const val MACOS_STAGE = ".ostrom-desktop-"
        private const val LINUX_STAGE = ".staging-"
        private const val WINDOWS_STAGE = "ostrom-desktop-"
        private const val LINUX_APP = "Ostrom"
        private const val EXTRACT_MILLIS = 300_000
        private const val CODESIGN_MILLIS = 120_000
        private const val WINDOWS_INSTALL_MILLIS = 600_000
        private const val WINDOWS_RESTART_SUGGESTED = 3010
    }
}
