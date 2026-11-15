// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.update

import run.ostrom.cli.ExitCode
import run.ostrom.cli.api.Download
import run.ostrom.cli.api.Downloads
import run.ostrom.cli.api.ReleaseDownloadException
import run.ostrom.cli.api.ServerUnreachableException
import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.parseObject
import run.ostrom.cli.launcher.sha256Hex
import run.ostrom.cli.launcher.text
import run.ostrom.core.api.models.ServerFeatures
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/**
 * What `ostrom update` was asked.
 *
 * @property current The version running.
 * @property target The release target of this binary (`linux-x64`, `macos-arm64`, `windows-x64`…).
 * @property executable This binary, or null when the system does not say.
 * @property releases The releases' base URL (`…/ostrom-hq/ostrom-cli/releases`).
 * @property version The version asked for, or null for the latest.
 * @property checkOnly True to only say whether an update exists.
 * @property json With [checkOnly]: says it as one JSON line (what a desktop banner reads) instead of text.
 * @property bundle `ostrom install --bundle`: a folder holding the signed `latest.json`, its `.sig` and the archive,
 * installed offline.
 * @property root `ostrom install --root`: the installation's folder instead of this user's default one.
 * @property noPath `ostrom install --no-path`: the command is not made reachable by name.
 */
data class UpdateRequest(
    val current: String,
    val target: String,
    val executable: String?,
    val releases: String,
    val version: String? = null,
    val checkOnly: Boolean = false,
    val json: Boolean = false,
    val bundle: String? = null,
    val root: String? = null,
    val noPath: Boolean = false,
)

/** A step of [SelfUpdate] that stops it: the exit code and what is said. */
internal open class Stop(val code: ExitCode, val text: String) : Exception(text)

/** No release is published at an address asked for (HTTP 404), told apart from a release without this target. */
private class NoRelease(text: String) : Stop(ExitCode.NOT_FOUND, text)

/** Stops [SelfUpdate] with [code], saying [text]. */
internal fun stop(code: ExitCode, text: String): Nothing = throw Stop(code, text)


/** A release manifest that does not read as one. */
private fun unreadable(): Nothing = stop(ExitCode.FAILED, "✗ The release manifest cannot be read. $UNCHANGED")

/** Stops [SelfUpdate] when an agent's session runs (S2-39): the agent never updates the ostrom of its own hooks. */
private fun refuseIn(sessions: AgentSessions) {
    sessions.running()?.let {
        stop(
            ExitCode.REFUSED,
            "✗ ostrom does not update itself while an agent's session runs ($it): its hooks run this binary. " +
                "Nothing was changed. Run ostrom update once the sessions have ended.",
        )
    }
}

/** The text of [path], or null when it cannot be read. */
private fun FileSystem.textOf(path: Path): String? = try {
    read(path) { readUtf8() }
} catch (_: IOException) {
    null
}

/**
 * The signed release manifest's entry for one target, the version floor the manifest publishes, if any, and the id of
 * the root that signed it ([ReleaseTrust]), with the exact bytes of the manifest and its signature (kept by a managed
 * installation beside its payload).
 */
@Suppress("LongParameterList") // The signed fields, and the bytes they were read from.
internal class Asset(
    val version: String,
    val url: String,
    val sha256: String,
    val size: Long,
    val floor: String?,
    val keyId: String,
    val manifest: ByteArray,
    val signature: ByteArray,
)

/**
 * `ostrom update`: the signed self-update. The manifest's Ed25519 signature is checked over its exact bytes before
 * anything in it is read, the archive against the signed size and SHA-256, and the binary is swapped by two renames
 * in its own folder, the previous one kept beside it for `--rollback` (CLI.md `ostrom update`).
 *
 * Never below the floor (security audit S2-39): `--version` and `--rollback` refuse a version under the floor the
 * latest release publishes in its signed manifest (`floor`), and any move back when it publishes none. Neither an
 * update nor a rollback runs inside an agent's session ([AgentSessions]): the agent could otherwise put back an
 * `ostrom` whose gate it knows how to get around. The floor this binary was built with (`release-floor.txt`) holds too:
 * an old manifest served again, still signed but with its own lower floor, never lowers it.
 *
 * Neither puts in place a binary that does not run here or does not speak the server's API: the new binary, once
 * verified and unpacked, and the kept one before a rollback, are run with [INSTALLATION_PROBE] and must answer that
 * they are that very version and speak that API ([probe]); otherwise nothing is changed (as TeamCode's update).
 *
 * @param downloads Fetches the manifest and the archive.
 * @param fileSystem Where the binary is.
 * @param unpack Unpacks an archive into a folder; false when it could not.
 * @param say Writes a line for the developer (stdout).
 * @param sessions Says whether this runs inside an agent's session.
 * @param probe Runs a binary with [INSTALLATION_PROBE].
 * @param trust The release key and the floor this binary was built with.
 * @param installations The managed installations of `ostrom install` ([ManagedUpdate]); null where there are none.
 * @param link Makes an installation's launcher reachable by name ([CommandPath]).
 */
@Suppress("LongParameterList", "TooManyFunctions") // One parameter per collaborator, each replaced in tests.
class SelfUpdate internal constructor(
    private val downloads: Downloads,
    private val fileSystem: FileSystem,
    private val unpack: Unpacker,
    private val say: (String) -> Unit,
    private val sessions: AgentSessions,
    private val probe: InstallationProbe,
    private val trust: ReleaseTrust = ReleaseTrust(),
    private val installations: Installations? = null,
    private val link: (InstallationPaths) -> CommandPathOutcome = { CommandPathOutcome.Ready(emptyList()) },
) {
    private val managed = ManagedUpdate(fileSystem, unpack, say, probe)

    /**
     * `ostrom install`: the signed release asked for (the latest, a [UpdateRequest.version], or the offline
     * [UpdateRequest.bundle]) installed into a managed installation ([InstallationStore]): its immutable launcher,
     * one payload per release, the selection switched atomically. The same checks as [update] hold: the signature,
     * the floor, the archive's checksum, the probe of the new binary, and never inside an agent's session.
     *
     * @param apiVersion The API version of the server this device works with.
     */
    suspend fun install(
        request: UpdateRequest,
        apiVersion: suspend () -> Int = { ServerFeatures.API_VERSION },
    ): Int = try {
        if (request.version != null && SemVer.of(request.version) == null) {
            stop(ExitCode.USAGE, "ostrom install: ${request.version} is not a version (1.2.3 or v1.2.3).")
        }
        refuseIn(sessions)
        val all = installations ?: stop(ExitCode.CANNOT_WRITE, "ostrom cannot install itself on this system.")
        val store = managed.guard { all.at(request.root) }
        val code = managed.writing(store) { installInto(store, request, apiVersion) }
        linked(store.paths, request.noPath)
        code
    } catch (stopped: Stop) {
        say(stopped.text)
        stopped.code.code
    }

    /** [install], holding the writer lock of [store]. */
    private suspend fun installInto(
        store: InstallationStore,
        request: UpdateRequest,
        apiVersion: suspend () -> Int,
    ): Int {
        val bundle = request.bundle?.toPath()
        val asset = bundle?.let {
            signed(it.toString(), read(it / MANIFEST), read(it / SIGNATURE, MAX_SIGNATURE_BYTES), request)
        } ?: manifest(request)
        val before = managed.guard { store.state() }?.current
        val asked = request.version != null || bundle != null
        if (before != null && !asked && !newer(asset.version, before.version)) {
            say("✓ ostrom ${before.version} is already installed.")
            return ExitCode.OK.code
        }
        val floor = if (bundle != null || request.version == null) asset.floor else latestFloor(request)
        floorRefusal(asset.version, before?.version ?: request.current, floor, trust.floor)
            ?.let { stop(ExitCode.REFUSED, it) }
        val api = apiVersion()
        val archive = bundle?.let { read(it / asset.url.substringAfterLast('/'), MAX_BINARY_BYTES) }
            ?: fetch(asset.url, MAX_BINARY_BYTES, request)
        checked(asset, archive)
        managed.put(store, asset, archive, api)
        say("✓ Installed ostrom ${asset.version} (signature checked).")
        say("  Launcher: ${store.paths.launcher}")
        return ExitCode.OK.code
    }

    /** Makes the launcher at [paths] reachable by name, unless [noPath]; stops when the name is someone else's. */
    private fun linked(paths: InstallationPaths, noPath: Boolean) {
        if (noPath) {
            say("Not changing PATH. Run ${paths.launcher}, or add ${paths.launcherDirectory} to your PATH yourself.")
            return
        }
        val outcome = managed.guard { link(paths) }
        outcome.lines.forEach(say)
        if (outcome is CommandPathOutcome.Conflict) {
            stop(ExitCode.REFUSED, "ostrom itself is installed; once the name is free, run ostrom install again.")
        }
    }

    private suspend fun latestFloor(request: UpdateRequest) = manifest(request.copy(version = null)).floor

    /** The file [path] of a bundle, a regular file of at most [maxBytes], or stop. */
    private fun read(path: Path, maxBytes: Long = MAX_MANIFEST_BYTES): ByteArray {
        val metadata = fileSystem.metadataOrNull(path)
        if (metadata == null || !metadata.isRegularFile || metadata.symlinkTarget != null) {
            stop(ExitCode.NOT_FOUND, "✗ $path is not a file of the release (links are refused). $UNCHANGED")
        }
        val bytes = managed.guard {
            fileSystem.source(path).buffer().use { source ->
                if (source.request(maxBytes + 1)) null else source.readByteArray()
            }
        }
        return bytes ?: stop(ExitCode.FAILED, "✗ $path is larger than $maxBytes bytes.")
    }

    /** Stops when [archive] is not the one [asset] signs. */
    private fun checked(asset: Asset, archive: ByteArray) {
        if (archive.size.toLong() != asset.size || sha256Hex(archive) != asset.sha256) {
            stop(ExitCode.SIGNATURE_REFUSED, "✗ The download does not match its signed checksum. $UNCHANGED")
        }
    }
    /**
     * Runs [request]; the exit code.
     *
     * @param apiVersion The API version of the server this device works with, asked only once a binary is to be
     * installed: the new binary must speak it.
     */
    suspend fun update(
        request: UpdateRequest,
        apiVersion: suspend () -> Int = { ServerFeatures.API_VERSION },
    ): Int = try {
        // the version goes into the release's address: only a version, never a path or a query
        if (request.version != null && SemVer.of(request.version) == null) {
            stop(ExitCode.USAGE, "ostrom update: ${request.version} is not a version (1.2.3 or v1.2.3).")
        }
        if (!request.checkOnly) refuseIn(sessions)
        val asset = manifestOrNone(request)
        if (asset == null) {
            say(checkJson(request.current, null, null))
            ExitCode.OK.code
        } else {
            answer(request, asset, apiVersion)
        }
    } catch (stopped: Stop) {
        say(stopped.text)
        stopped.code.code
    }

    /**
     * The signed manifest, or null for `--check --json` of a repository with no release yet: nothing newer, said as
     * data (never an error). A version asked for that is not there stays an error.
     */
    private suspend fun manifestOrNone(request: UpdateRequest): Asset? = try {
        manifest(request)
    } catch (none: NoRelease) {
        if (request.checkOnly && request.json && request.version == null) null else throw none
    }

    /** What to do with the signed [asset]: say it, or install it. */
    private suspend fun answer(request: UpdateRequest, asset: Asset, apiVersion: suspend () -> Int): Int = when {
        request.checkOnly && request.json -> ExitCode.OK.code.also {
            say(checkJson(request.current, asset.version, asset.keyId))
        }
        request.version == null && !newer(asset.version, request.current) ->
            ExitCode.OK.code.also { say("✓ ostrom ${request.current} is up to date.") }
        asset.version == request.current ->
            ExitCode.OK.code.also { say("✓ ostrom ${request.current} is already installed.") }
        request.checkOnly -> ExitCode.OK.code.also {
            val run = if (request.version == null) "ostrom update" else "ostrom update --version ${asset.version}"
            say("! ostrom ${asset.version} is available. Run $run.")
            say("  Release key: ${asset.keyId}")
        }
        else -> installAsset(request, asset, apiVersion)
    }

    /**
     * The version of the latest signed release, or null when it cannot be read or its signature does not match: what
     * the update notice is told, never more (nothing is said, nothing is installed).
     */
    suspend fun latest(request: UpdateRequest): String? = try {
        manifest(request.copy(version = null)).version
    } catch (_: Stop) {
        null
    }

    /**
     * `ostrom update --rollback`: back to the version kept by the last update, which is kept in turn. The version kept
     * is checked against the latest release's signed floor first, so the release must be reachable, then run with
     * [INSTALLATION_PROBE]: it must speak the server's API too.
     *
     * @param apiVersion The API version of the server this device works with.
     */
    suspend fun rollback(
        request: UpdateRequest,
        apiVersion: suspend () -> Int = { ServerFeatures.API_VERSION },
    ): Int = try {
        refuseIn(sessions)
        val store = request.executable?.let { executable -> installations?.let { managed.guard { it.of(executable) } } }
        if (store != null) return managed.writing(store) { rollbackIn(store, request, apiVersion) }
        val binary = request.executable?.toPath()
        if (binary == null || !fileSystem.exists(previousOf(binary))) {
            stop(ExitCode.NOT_FOUND, "No previous version of ostrom is kept on this device.")
        }
        val recorded = fileSystem.textOf(keptVersionOf(binary))
            ?: stop(ExitCode.REFUSED, "✗ The version of the ostrom kept is not recorded. $UNCHANGED $REINSTALL")
        floorRefusal(recorded.trim(), request.current, manifest(request.copy(version = null)).floor, trust.floor)
            ?.let { stop(ExitCode.REFUSED, it) }
        probe.refusal(previousOf(binary), recorded.trim(), apiVersion())?.let { stop(ExitCode.REFUSED, it) }
        swapBack(binary, request.current)
    } catch (stopped: Stop) {
        say(stopped.text)
        stopped.code.code
    }

    /** `--rollback` of a managed installation: its previous selection, holding its writer lock. */
    private suspend fun rollbackIn(
        store: InstallationStore,
        request: UpdateRequest,
        apiVersion: suspend () -> Int,
    ): Int {
        val previous = managed.guard { store.state() }?.previous
            ?: stop(ExitCode.NOT_FOUND, "No previous version of ostrom is kept on this device.")
        floorRefusal(previous.version, request.current, latestFloor(request), trust.floor)
            ?.let { stop(ExitCode.REFUSED, it) }
        managed.guard { store.validate(previous) }
        probe.refusal(store.paths.binary(previous), previous.version, apiVersion())?.let { stop(ExitCode.REFUSED, it) }
        managed.select(store, previous, rollback = true)
        say("✓ Back to ostrom ${previous.version}. Run ostrom update to come back.")
        return ExitCode.OK.code
    }

    private fun swapBack(binary: Path, current: String): Int {
        val previous = previousOf(binary)
        val swap = "$binary.swap".toPath()
        return try {
            fileSystem.atomicMove(binary, swap)
            try {
                fileSystem.atomicMove(previous, binary)
            } catch (failure: IOException) {
                // never an installation without its binary: the running one goes back
                fileSystem.atomicMove(swap, binary)
                throw failure
            }
            fileSystem.atomicMove(swap, previous)
            keep(binary, current)
            say("✓ Back to the previous ostrom. Run ostrom update to come back.")
            ExitCode.OK.code
        } catch (failure: IOException) {
            say("ostrom could not swap its binary: ${failure.message}")
            ExitCode.CANNOT_WRITE.code
        }
    }

    /**
     * Records [version] as the one kept for `--rollback`. When it cannot be written, the old record is removed: a
     * rollback then refuses (version not recorded) rather than checking the floor against the wrong version.
     */
    private fun keep(binary: Path, version: String) {
        try {
            fileSystem.write(keptVersionOf(binary)) { writeUtf8(version) }
        } catch (failure: IOException) {
            fileSystem.delete(keptVersionOf(binary), mustExist = false)
            say("! The version kept for ostrom update --rollback could not be recorded: ${failure.message}")
        }
    }

    private suspend fun manifest(request: UpdateRequest): Asset {
        val folder = if (request.version == null) "latest/download" else "download/${request.version.removePrefix("v")}"
        val url = "${request.releases.trimEnd('/')}/$folder/latest.json"
        val manifest = fetch(url, MAX_MANIFEST_BYTES, request)
        val signature = fetch("$url.sig", MAX_SIGNATURE_BYTES, request)
        return signed(url, manifest, signature, request)
    }

    /** The asset of [request]'s target in the [manifest] read at [url], once its [signature] matches. */
    private fun signed(url: String, manifest: ByteArray, signature: ByteArray, request: UpdateRequest): Asset {
        val keyId = trust.verify(manifest, signature.decodeToString())
            ?: stop(ExitCode.SIGNATURE_REFUSED, "✗ The release signature does not match. Nothing was changed.")
        val body = parseObject(manifest.decodeToString()) ?: unreadable()
        val version = body.text("version") ?: unreadable()
        // a signed manifest of another release, served where this one was asked, is a replay
        if (request.version != null && version.removePrefix("v") != request.version.removePrefix("v")) {
            stop(
                ExitCode.SIGNATURE_REFUSED,
                "✗ The release at $url is signed for ostrom $version, not ${request.version}. Nothing was changed.",
            )
        }
        val entry = body.child("assets")?.child(request.target)
            ?: stop(ExitCode.NOT_FOUND, "No ostrom $version is built for ${request.target}.")
        val url = entry.text("url")
        val sha256 = entry.text("sha256")
        val size = (entry["size"] as? JsonPrimitive)?.longOrNull
        if (url == null || sha256 == null || size == null) unreadable()
        return Asset(version, url, sha256, size, body.text("floor"), keyId, manifest, signature)
    }

    /** Installs [asset] when the latest release's floor allows it (S2-39), and the binary speaks the server's API. */
    private suspend fun installAsset(request: UpdateRequest, asset: Asset, apiVersion: suspend () -> Int): Int {
        val floor = if (request.version == null) asset.floor else latestFloor(request)
        floorRefusal(asset.version, request.current, floor, trust.floor)?.let { stop(ExitCode.REFUSED, it) }
        val executable = request.executable
            ?: stop(ExitCode.CANNOT_WRITE, "ostrom cannot find its own binary. Reinstall it with the installer.")
        val binary = executable.toPath()
        managerOf(binary)?.let { stop(ExitCode.USAGE, "This ostrom is managed by $it.") }
        val api = apiVersion()
        val archive = fetch(asset.url, MAX_BINARY_BYTES, request)
        checked(asset, archive)
        val store = installations?.let { managed.guard { it.of(executable) } }
        if (store != null) {
            managed.writing(store) { managed.put(store, asset, archive, api) }
            say("✓ Updated ostrom ${request.current} → ${asset.version} (signature checked).")
            say("  Running sessions keep ${request.current} until they end.")
            return ExitCode.OK.code
        }
        val work = "$binary.update".toPath()
        try {
            fileSystem.deleteRecursively(work)
            fileSystem.createDirectories(work)
            val file = work / asset.url.substringAfterLast('/')
            fileSystem.write(file) { write(archive) }
            val fresh = work / binary.name
            if (!unpack.unpack(file.toString(), work.toString()) || !fileSystem.exists(fresh)) {
                stop(ExitCode.FAILED, "✗ The archive does not hold ${binary.name}. Nothing was changed.")
            }
            probe.refusal(fresh, asset.version, api)?.let { stop(ExitCode.REFUSED, it) }
            swap(binary, fresh)
        } catch (failure: IOException) {
            stop(ExitCode.CANNOT_WRITE, "ostrom could not write next to $binary: ${failure.message}")
        } finally {
            fileSystem.deleteRecursively(work, mustExist = false)
        }
        keep(binary, request.current)
        val running = request.current
        say("✓ Updated ostrom $running → ${asset.version} (signature checked).")
        say("  Running sessions keep $running until they end.")
        return ExitCode.OK.code
    }

    /**
     * [fresh] in place of [binary], which is kept as the previous one. The version kept before stays aside until the
     * swap is done, so a failure leaves both as they were.
     */
    private fun swap(binary: Path, fresh: Path) {
        val previous = previousOf(binary)
        val older = "$previous.old".toPath()
        val hadPrevious = fileSystem.exists(previous)
        if (hadPrevious) fileSystem.atomicMove(previous, older)
        try {
            fileSystem.atomicMove(binary, previous)
            try {
                fileSystem.atomicMove(fresh, binary)
            } catch (failure: IOException) {
                fileSystem.atomicMove(previous, binary)
                throw failure
            }
        } catch (failure: IOException) {
            if (hadPrevious) fileSystem.atomicMove(older, previous)
            throw failure
        }
        fileSystem.delete(older, mustExist = false)
    }

    private suspend fun fetch(url: String, maxBytes: Long, request: UpdateRequest): ByteArray {
        val answer = try {
            downloads.fetch(url, maxBytes)
        } catch (e: ServerUnreachableException) {
            val why = e.family?.let { " (${it.cause})" }.orEmpty()
            stop(ExitCode.UNREACHABLE, "ostrom's releases are unreachable at ${request.releases}$why. $CHECK_NETWORK")
        } catch (e: ReleaseDownloadException) {
            stop(ExitCode.FAILED, "✗ $url was refused: ${e.detail ?: refusal(e.code)}.")
        }
        return when (answer) {
            is Download.Bytes -> answer.bytes
            is Download.Missing -> if (answer.status == NOT_FOUND) {
                throw NoRelease("No release at $url (HTTP ${answer.status}).")
            } else {
                stop(ExitCode.UNREACHABLE, "No release at $url (HTTP ${answer.status}).")
            }
            is Download.TooLarge -> stop(ExitCode.FAILED, "✗ $url is larger than ${answer.limit} bytes.")
        }
    }

    private companion object {
        const val MAX_MANIFEST_BYTES = 16L * 1024
        const val MAX_SIGNATURE_BYTES = 2048L
        const val MAX_BINARY_BYTES = MAX_PAYLOAD_BYTES
        const val MANIFEST = "latest.json"
        const val SIGNATURE = "latest.json.sig"
        const val NOT_FOUND = 404
        /** Why a release answer was refused, for the person reading. */
        fun refusal(code: String): String = when (code) {
            "release_redirect_invalid" -> "it redirected somewhere a release is never served from"
            "release_encoding_invalid" -> "it came compressed or encoded"
            "release_size_invalid" -> "its size is not the one announced"
            else -> code
        }

        const val CHECK_NETWORK = "Check your network."
        const val REINSTALL = "Install the version you want with ostrom update --version."
    }
}

/**
 * `ostrom update --check --json`: one line, from the signed manifest only. [latest] and [keyId] are null when no
 * release is published.
 */
internal fun checkJson(current: String, latest: String?, keyId: String?): String = JsonObject(
    mapOf(
        "currentVersion" to JsonPrimitive(current),
        "latestVersion" to JsonPrimitive(latest),
        "updateAvailable" to JsonPrimitive(latest != null && newer(latest, current)),
        "keyId" to JsonPrimitive(keyId),
    ),
).toString()

/** Where the version of the binary kept for `--rollback` is recorded. */
internal fun keptVersionOf(binary: Path): Path = "$binary.previous.version".toPath()

/**
 * Why [target] may not be installed over [current] given the latest release's signed [floor] and the floor this binary
 * was [built] with, or null when it may: a version below either floor, a version that does not parse, or any move
 * back when the release publishes no floor (security audit S2-39).
 */
internal fun floorRefusal(target: String, current: String, floor: String?, built: String? = null): String? {
    val wanted = SemVer.of(target)
    val bottom = floor?.let { SemVer.of(it) }
    val ownBottom = built?.let { SemVer.of(it) }
    return when {
        wanted == null ->
            "✗ ostrom cannot read the version $target to check it against the release's floor. $UNCHANGED"
        ownBottom != null && wanted < ownBottom ->
            "✗ ostrom $target is below $built, the lowest version this ostrom allows. $UNCHANGED"
        bottom != null && wanted < bottom ->
            "✗ ostrom $target is below $floor, the lowest version the latest release allows. $UNCHANGED"
        bottom == null && newer(current, target) ->
            "✗ The latest release publishes no version floor, so ostrom does not go back to $target. $UNCHANGED"
        else -> null
    }
}

private const val UNCHANGED = "Nothing was changed."

/** Where the binary of [binary] is kept for `--rollback`. */
internal fun previousOf(binary: Path): Path = "$binary.previous".toPath()

/** The package manager that installed [binary] (it updates it itself), or null. */
internal fun managerOf(binary: Path): String? {
    val path = binary.toString().replace('\\', '/')
    return when {
        "/Cellar/" in path -> "Homebrew: run brew upgrade ostrom"
        "/scoop/apps/" in path -> "Scoop: run scoop update ostrom"
        else -> null
    }
}

/**
 * True when [candidate] is a later version than [current]: SemVer precedence, a pre-release before its release. A
 * version that does not parse is never later.
 */
internal fun newer(candidate: String, current: String): Boolean {
    val a = SemVer.of(candidate)
    val b = SemVer.of(current)
    return a != null && (b == null || a > b)
}

/** A `MAJOR.MINOR.PATCH[-pre]` version, ordered by SemVer precedence. */
internal data class SemVer(val numbers: List<Long>, val pre: List<String>) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        val core = numbers.zip(other.numbers).map { (x, y) -> x.compareTo(y) }.firstOrNull { it != 0 }
        return core ?: comparePre(other)
    }

    private fun comparePre(other: SemVer): Int = when {
        pre.isEmpty() || other.pre.isEmpty() -> other.pre.size.coerceAtMost(1) - pre.size.coerceAtMost(1)
        else -> pre.zip(other.pre).map { (x, y) -> identifier(x, y) }.firstOrNull { it != 0 }
            ?: pre.size.compareTo(other.pre.size)
    }

    private fun identifier(x: String, y: String): Int {
        val m = x.toLongOrNull()
        val n = y.toLongOrNull()
        return when {
            m != null && n != null -> m.compareTo(n)
            m != null -> -1
            n != null -> 1
            else -> x.compareTo(y)
        }
    }

    companion object {
        private val PATTERN = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z.-]+))?")

        /** [text] as a version, or null. */
        fun of(text: String): SemVer? {
            val groups = (PATTERN.matchEntire(text.removePrefix("v")) ?: return null).groupValues
            val pre = groups[CORE_END].split('.').filter { it.isNotEmpty() }
            return SemVer(groups.subList(1, CORE_END).map { it.toLong() }, pre)
        }

        private const val CORE_END = 4
    }
}

/** Unpacks a release archive. */
fun interface Unpacker {
    /** Unpacks [archive] into the folder [into]; false when it could not. */
    fun unpack(archive: String, into: String): Boolean
}
