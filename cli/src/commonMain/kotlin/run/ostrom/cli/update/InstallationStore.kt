// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.update

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.HashingSource
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.blackholeSink
import okio.buffer
import okio.use
import run.ostrom.cli.launcher.objectOf
import run.ostrom.cli.launcher.parseObject
import run.ostrom.cli.launcher.text
import run.ostrom.cli.platform.FileStoreImpl
import run.ostrom.cli.platform.FileWriteException
import run.ostrom.cli.platform.InstallationFiles
import run.ostrom.cli.platform.restrictToOwner
import run.ostrom.cli.platform.secureRandomBytes

/**
 * A step of the managed installation refused, named by [code] (TeamCode's `release_*` codes): what
 * [installationRefusal] says.
 */
internal class InstallationException(val code: String) : Exception(code)

/** Throws the [InstallationException] [code]. */
internal fun refuseInstallation(code: String): Nothing = throw InstallationException(code)

internal val HEX_DIGEST = Regex("[0-9a-f]{64}")
private val TARGET = Regex("[a-z0-9]+-[a-z0-9]+")

/**
 * One payload of the managed installation, attested locally once its signed release and archive were checked: the
 * SHA-256 of the exact `latest.json` that signed it, and of the binary unpacked from the archive it names.
 */
internal data class InstalledVersion(
    val version: String,
    val target: String,
    val manifestSha256: String,
    val binarySha256: String,
    val size: Long,
) {
    init {
        require(SemVer.of(version) != null && TARGET.matches(target))
        require(HEX_DIGEST.matches(manifestSha256) && HEX_DIGEST.matches(binarySha256))
        require(size in 1..MAX_PAYLOAD_BYTES)
    }

    /** As `current.json` and `launcher.json` write it. */
    fun json(): JsonObject = objectOf(
        "version" to version,
        "target" to target,
        "manifestSha256" to manifestSha256,
        "binarySha256" to binarySha256,
        "size" to size,
    )

    companion object {
        /** The version written as [json] wrote it; refused ([IllegalArgumentException]) when it does not read so. */
        fun of(json: JsonObject?): InstalledVersion {
            requireNotNull(json)
            val size = (json["size"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            return InstalledVersion(
                requireNotNull(json.text("version")),
                requireNotNull(json.text("target")),
                requireNotNull(json.text("manifestSha256")),
                requireNotNull(json.text("binarySha256")),
                requireNotNull(size),
            )
        }
    }
}

/** `current.json`: the payload every launch runs, and the one `ostrom update --rollback` goes back to. */
internal data class InstallationState(val current: InstalledVersion, val previous: InstalledVersion? = null) {
    init {
        require(previous == null || previous.target == current.target)
    }

    /** As `current.json` writes it. */
    fun json(): JsonObject = objectOf(
        "schemaVersion" to SCHEMA_VERSION,
        "current" to current.json(),
        "previous" to previous?.json(),
    )

    companion object {
        /** The state written as [json] wrote it; refused ([IllegalArgumentException]) when it does not read so. */
        fun of(json: JsonObject?): InstallationState {
            requireNotNull(json)
            require(json.schemaVersion() == SCHEMA_VERSION)
            val previous = json["previous"]?.takeUnless { it is JsonNull }
            return InstallationState(
                InstalledVersion.of(json["current"] as? JsonObject),
                previous?.let { InstalledVersion.of(it as? JsonObject) },
            )
        }
    }
}

/**
 * `transaction.json`: a selection under way, from [before] to [after]. Found by the next launch or installer, it puts
 * [before] back (TeamCode's `InstallationJournal`, without its service).
 */
internal data class InstallationJournal(val before: InstallationState?, val after: InstallationState) {
    /** As `transaction.json` writes it. */
    fun json(): JsonObject = objectOf(
        "schemaVersion" to SCHEMA_VERSION,
        "before" to before?.json(),
        "after" to after.json(),
    )

    companion object {
        /** The journal written as [json] wrote it; refused ([IllegalArgumentException]) when it does not read so. */
        fun of(json: JsonObject?): InstallationJournal {
            requireNotNull(json)
            require(json.schemaVersion() == SCHEMA_VERSION)
            val before = json["before"]?.takeUnless { it is JsonNull }
            return InstallationJournal(
                before?.let { InstallationState.of(it as? JsonObject) },
                InstallationState.of(json["after"] as? JsonObject),
            )
        }
    }
}

private fun JsonObject.schemaVersion(): Long? = (this["schemaVersion"] as? JsonPrimitive)?.longOrNull

/**
 * The layout of a managed installation under [root]: the immutable launcher in `bin/`, one folder per payload in
 * `versions/<SHA-256 of its latest.json>/`, the selection in `current.json`, and the locks.
 */
internal class InstallationPaths(root: Path, val target: String) {
    val root: Path = root.normalized().also {
        require(it.isAbsolute && it.toString().none { c -> c.code < CONTROL_END || c.code == DELETE })
    }

    init {
        require(TARGET.matches(target))
    }

    val executableName = if (target.startsWith("windows-")) "ostrom.exe" else "ostrom"
    val state = this.root / "current.json"
    val journal = this.root / "transaction.json"
    val lock = this.root / "installation.lock"
    val writerLock = this.root / "writer.lock"
    val launcherDirectory = this.root / "bin"
    val launcher = launcherDirectory / executableName
    val launcherRecord = launcherDirectory / "launcher.json"
    val versions = this.root / "versions"

    /** The folder of the payload [version]. */
    fun directory(version: InstalledVersion): Path = versions / version.manifestSha256

    /** The binary of the payload [version]. */
    fun binary(version: InstalledVersion): Path = directory(version) / executableName
}

/**
 * The private version store of `ostrom install` (TeamCode's `InstallationStore`, without its service and agent
 * session leases). Hold the writer lock to stage and publish, and the exclusive activity lock to select or recover.
 * Payloads and the launcher are immutable once published; only `current.json` changes, atomically, with a journal
 * that puts the old selection back when a selection was interrupted.
 *
 * @param files The locks, flushes and ownership checks of the system.
 * @param random A fresh random hex suffix, for the names of the folders being written.
 */
@Suppress("TooManyFunctions") // TeamCode's store, one function per step.
internal class InstallationStore(
    val paths: InstallationPaths,
    private val fileSystem: FileSystem,
    private val files: InstallationFiles,
    private val random: () -> String = { secureRandomBytes(RANDOM_BYTES).toByteString().hex() },
) {
    private val texts = FileStoreImpl(fileSystem)

    /** Creates the root and `versions/`, owner-only, or refuses a folder that is not this installation's. */
    fun initialize() {
        privateDirectory(paths.root)
        privateDirectory(paths.versions)
    }

    /** The writer lock: one installer at a time. */
    fun writerLock(): AutoCloseable = tryLock(paths.writerLock, exclusive = true) ?: refuseInstallation(WRITER_BUSY)

    /** The activity lock, held exclusively to select a payload or recover an interrupted selection. */
    fun lock(exclusive: Boolean): AutoCloseable = tryLock(paths.lock, exclusive) ?: refuseInstallation(BUSY)

    private fun tryLock(path: Path, exclusive: Boolean): AutoCloseable? = try {
        files.tryLock(path.toString(), exclusive)
    } catch (_: IOException) {
        refuseInstallation("release_lock_failed")
    }

    /** The selection, or null before the first installation. */
    fun state(): InstallationState? {
        if (fileSystem.metadataOrNull(paths.state) == null) return null
        val state = decode(paths.state, InstallationState::of)
        val targets = listOfNotNull(state.current.target, state.previous?.target)
        if (targets.any { it != paths.target }) refuseInstallation(INVALID)
        return state
    }

    /** The version the launcher was copied from, or null when there is no launcher yet. */
    fun launcherVersion(): InstalledVersion? {
        if (fileSystem.metadataOrNull(paths.launcherRecord) == null) return null
        return decode(paths.launcherRecord, InstalledVersion::of).also {
            if (it.target != paths.target) refuseInstallation(INVALID)
        }
    }

    /** The selection under way, or null. */
    fun journal(): InstallationJournal? {
        if (fileSystem.metadataOrNull(paths.journal) == null) return null
        val journal = decode(paths.journal, InstallationJournal::of)
        val targets = listOfNotNull(journal.after.current.target, journal.before?.let { it.current.target })
        if (targets.any { it != paths.target }) refuseInstallation(INVALID)
        return journal
    }

    /** Puts back the selection an interrupted one started from. Caller holds the exclusive activity lock. */
    fun recoverInterrupted(): Boolean {
        val pending = journal() ?: return false
        val selected = state()
        if (selected != pending.before && selected != pending.after) refuseInstallation("release_recovery_conflict")
        pending.before?.let { validate(it.current) }
        restore(pending.before)
        fileSystem.delete(paths.journal)
        flush(paths.root)
        return true
    }

    /**
     * Publishes the payload of the verified [manifest] (and its [signature]) for [version]: [produce] writes the
     * binary into the empty private folder it is given and returns its path. The payload is attested by the SHA-256 of
     * the manifest and of that binary; an already published one is validated and kept.
     */
    fun stage(manifest: ByteArray, signature: ByteArray, version: String, produce: (Path) -> Path): InstalledVersion {
        val manifestSha256 = manifest.toByteString().sha256().hex()
        val published = paths.versions / manifestSha256
        if (fileSystem.metadataOrNull(published) != null) {
            val existing = decode(published / ATTESTATION, InstalledVersion::of)
            validate(existing)
            return existing
        }
        val temporary = paths.root / (".staging-" + random())
        privateDirectory(temporary)
        try {
            val work = temporary / "work"
            privateDirectory(work)
            val binary = temporary / paths.executableName
            fileSystem.atomicMove(produce(work), binary)
            fileSystem.deleteRecursively(work)
            val (sha256, size) = digest(binary)
            val staged = try {
                InstalledVersion(version, paths.target, manifestSha256, sha256, size)
            } catch (_: IllegalArgumentException) {
                // an empty or too large binary, or a version that does not read
                refuseInstallation(BINARY_INVALID)
            }
            seal(binary)
            writePrivate(temporary / "latest.json", manifest.decodeToString())
            writePrivate(temporary / "latest.json.sig", signature.decodeToString())
            writePrivate(temporary / ATTESTATION, staged.json().toString())
            flush(temporary)
            fileSystem.atomicMove(temporary, published)
            flush(paths.versions)
            validate(staged)
            return staged
        } finally {
            fileSystem.deleteRecursively(temporary, mustExist = false)
        }
    }

    /** Publishes the launcher, a copy of [version], once: an existing one is validated against its own record. */
    fun ensureLauncher(version: InstalledVersion) {
        val existing = launcherVersion()
        if (existing != null) return validateFile(paths.launcher, existing)
        if (fileSystem.metadataOrNull(paths.launcherDirectory) != null) refuseInstallation("release_launcher_conflict")
        val temporary = paths.root / (".launcher-" + random())
        privateDirectory(temporary)
        try {
            val executable = temporary / paths.executableName
            fileSystem.copy(paths.binary(version), executable)
            restrictToOwner(executable.toString(), executable = true)
            validateFile(executable, version)
            seal(executable)
            writePrivate(temporary / "launcher.json", version.json().toString())
            flush(temporary)
            fileSystem.atomicMove(temporary, paths.launcherDirectory)
            flush(paths.root)
        } finally {
            fileSystem.deleteRecursively(temporary, mustExist = false)
        }
    }

    /** Checks the payload [version] against its attestation: its manifest's and its binary's SHA-256, its size. */
    fun validate(version: InstalledVersion) {
        if (version.target != paths.target) refuseInstallation("release_target_unsupported")
        regularDirectory(paths.directory(version))
        val manifest = paths.directory(version) / "latest.json"
        regular(manifest)
        val bytes = fileSystem.read(manifest) { readByteArray() }
        if (bytes.toByteString().sha256().hex() != version.manifestSha256) {
            refuseInstallation("release_installed_metadata_invalid")
        }
        validateFile(paths.binary(version), version)
    }

    /**
     * Deletes the payloads that are neither selected nor the rollback target. Each folder is first renamed aside, so
     * a failure never leaves half a payload in `versions/` (Windows refuses to rename a folder whose binary runs: that
     * payload is kept). Caller holds the writer lock and the exclusive activity lock.
     */
    fun collectGarbage(): List<String> {
        val selected = state()
        if (journal() != null || selected == null) return emptyList()
        val retained = setOfNotNull(selected.current.manifestSha256, selected.previous?.manifestSha256)
        val removed = fileSystem.list(paths.versions).filter { HEX_DIGEST.matches(it.name) && it.name !in retained }
            .filter { directory -> moved(directory, paths.root / (".trash-" + random())) }
            .map { it.name }
        if (removed.isNotEmpty()) flush(paths.versions)
        fileSystem.list(paths.root).filter { it.name.startsWith(".trash-") }.forEach { trash ->
            try {
                fileSystem.deleteRecursively(trash)
            } catch (_: IOException) {
                // retried by the next collection
            }
        }
        return removed
    }

    private fun moved(from: Path, to: Path): Boolean = try {
        fileSystem.atomicMove(from, to)
        true
    } catch (_: IOException) {
        false
    }

    /**
     * Selects [version]; [rollback] only to the previous one. The journal is written first, so an interruption puts
     * the old selection back. Caller holds the writer lock and the exclusive activity lock.
     */
    fun activate(version: InstalledVersion, rollback: Boolean = false) {
        if (journal() != null) refuseInstallation("release_recovery_required")
        validate(version)
        val before = state()
        before?.let { validate(it.current) }
        if (before?.current == version) return
        if (rollback && before?.previous != version) refuseInstallation(ROLLBACK_UNAVAILABLE)
        val after = InstallationState(current = version, previous = before?.current)
        writePrivate(paths.journal, InstallationJournal(before, after).json().toString())
        flush(paths.root)
        try {
            save(after)
        } catch (failure: InstallationException) {
            restore(before)
            fileSystem.delete(paths.journal)
            throw failure
        }
        fileSystem.delete(paths.journal)
        flush(paths.root)
    }

    private fun restore(before: InstallationState?) {
        if (before == null) fileSystem.delete(paths.state, mustExist = false) else save(before)
    }

    private fun save(state: InstallationState) {
        writePrivate(paths.state, state.json().toString())
        flush(paths.root)
    }

    private fun <T> decode(path: Path, read: (JsonObject?) -> T): T {
        regular(path)
        val text = fileSystem.source(path).buffer().use { source ->
            if (source.request(MAX_RECORD_BYTES + 1)) refuseInstallation(INVALID)
            source.readUtf8()
        }
        return try {
            read(parseObject(text))
        } catch (_: IllegalArgumentException) {
            refuseInstallation(INVALID)
        }
    }

    private fun validateFile(path: Path, version: InstalledVersion) {
        regular(path)
        val (sha256, size) = digest(path)
        if (size != version.size || sha256 != version.binarySha256) refuseInstallation(BINARY_INVALID)
    }

    /** The SHA-256 of the file [path], and its size. */
    private fun digest(path: Path): Pair<String, Long> = HashingSource.sha256(fileSystem.source(path)).use { hash ->
        val size = hash.buffer().readAll(blackholeSink())
        hash.hash.hex() to size
    }

    private fun regular(path: Path) {
        val metadata = fileSystem.metadataOrNull(path)
        // okio's metadata never follows a link: a link is not a regular file
        val file = metadata != null && metadata.isRegularFile
        if (!file || !files.isPrivate(path.toString(), directory = false)) refuseInstallation("release_file_unreadable")
    }

    private fun regularDirectory(path: Path) {
        val metadata = fileSystem.metadataOrNull(path)
        val directory = metadata != null && metadata.isDirectory
        if (!directory || !files.isPrivate(path.toString(), directory = true)) refuseInstallation(INVALID)
    }

    /**
     * Creates [path] owner-only with its marker, or checks an existing one: a folder already holding files without
     * the marker is not this installation's, and is never written into.
     */
    private fun privateDirectory(path: Path) {
        val marker = path / ".directory"
        val existing = fileSystem.metadataOrNull(path)
        if (existing != null) {
            if (!existing.isDirectory) refuseInstallation(INVALID)
            val marked = fileSystem.metadataOrNull(marker) != null
            if (!marked && fileSystem.list(path).isNotEmpty()) refuseInstallation(NOT_OWNED)
            if (marked) {
                regular(marker)
                if (texts.read(marker.toString()) != MARKER) refuseInstallation(NOT_OWNED)
            }
        }
        fileSystem.createDirectories(path)
        restrictToOwner(path.toString(), executable = true)
        writePrivate(marker, MARKER)
        regularDirectory(path)
    }

    private fun writePrivate(path: Path, text: String) = try {
        texts.write(path.toString(), text)
    } catch (_: FileWriteException) {
        refuseInstallation("release_write_failed")
    }

    private fun seal(path: Path) = try {
        files.seal(path.toString())
    } catch (_: IOException) {
        refuseInstallation("release_executable_permissions")
    }

    private fun flush(path: Path) = try {
        files.flushDirectory(path.toString())
    } catch (_: IOException) {
        refuseInstallation("release_sync_failed")
    }

    companion object {
        /** The attestation a payload folder keeps of itself, read when the same release is staged again. */
        const val ATTESTATION = "installed.json"
        const val MARKER = "Ostrom private installation directory\n"
        const val WRITER_BUSY = "release_writer_busy"
        const val BUSY = "release_installation_busy"
        const val INVALID = "release_installation_invalid"
        const val NOT_OWNED = "release_directory_not_owned"
        const val BINARY_INVALID = "release_binary_invalid"
        const val ROLLBACK_UNAVAILABLE = "release_rollback_unavailable"
        private const val RANDOM_BYTES = 16
        private const val MAX_RECORD_BYTES = 8192L
    }
}

/** The largest binary a payload holds (as the largest archive `ostrom update` downloads). */
internal const val MAX_PAYLOAD_BYTES = 256L * 1024 * 1024

private const val SCHEMA_VERSION = 1L
private const val CONTROL_END = 32
private const val DELETE = 127

/** [path] as okio reads it, for the installation's folders. */
internal fun installationPath(path: String): Path = path.toPath(normalize = true)
