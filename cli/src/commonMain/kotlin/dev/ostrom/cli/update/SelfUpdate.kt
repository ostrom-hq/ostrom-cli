// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.update

import dev.ostrom.cli.ExitCode
import dev.ostrom.cli.api.Download
import dev.ostrom.cli.api.Downloads
import dev.ostrom.cli.api.ServerUnreachableException
import dev.ostrom.cli.launcher.child
import dev.ostrom.cli.launcher.ed25519Verify
import dev.ostrom.cli.launcher.parseObject
import dev.ostrom.cli.launcher.sha256Hex
import dev.ostrom.cli.launcher.text
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The Ed25519 key that signs `latest.json` (ostrom-cli `docs/RELEASES.md`; the private half is the organisation secret
 * `RELEASE_SIGNING_KEY`).
 */
internal const val RELEASE_KEY_PEM = "-----BEGIN PUBLIC KEY-----\n" +
    "MCowBQYDK2VwAyEAgWVuEFjR9qPX6DfIJ6Bhs+jT7j00KiwFY6bNwP0fexA=\n" +
    "-----END PUBLIC KEY-----\n"

/**
 * What `ostrom update` was asked.
 *
 * @property current The version running.
 * @property target The release target of this binary (`linux-x64`, `macos-arm64`, `windows-x64`…).
 * @property executable This binary, or null when the system does not say.
 * @property releases The releases' base URL (`…/ostrom-hq/ostrom-cli/releases`).
 * @property version The version asked for, or null for the latest.
 * @property checkOnly True to only say whether an update exists.
 */
data class UpdateRequest(
    val current: String,
    val target: String,
    val executable: String?,
    val releases: String,
    val version: String? = null,
    val checkOnly: Boolean = false,
)

/** A step of [SelfUpdate] that stops it: the exit code and what is said. */
private class Stop(val code: ExitCode, val text: String) : Exception(text)

/** Stops [SelfUpdate] with [code], saying [text]. */
private fun stop(code: ExitCode, text: String): Nothing = throw Stop(code, text)

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

/** The signed release manifest's entry for one target, and the version floor the manifest publishes, if any. */
private class Asset(val version: String, val url: String, val sha256: String, val size: Long, val floor: String?)

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
 * @param downloads Fetches the manifest and the archive.
 * @param fileSystem Where the binary is.
 * @param unpack Unpacks an archive into a folder; false when it could not.
 * @param say Writes a line for the developer (stdout).
 * @param sessions Says whether this runs inside an agent's session.
 * @param trust The release key and the floor this binary was built with.
 */
class SelfUpdate(
    private val downloads: Downloads,
    private val fileSystem: FileSystem,
    private val unpack: Unpacker,
    private val say: (String) -> Unit,
    private val sessions: AgentSessions,
    private val trust: ReleaseTrust = ReleaseTrust(),
) {
    /** Runs [request]; the exit code. */
    suspend fun update(request: UpdateRequest): Int = try {
        // the version goes into the release's address: only a version, never a path or a query
        if (request.version != null && SemVer.of(request.version) == null) {
            stop(ExitCode.USAGE, "ostrom update: ${request.version} is not a version (1.2.3 or v1.2.3).")
        }
        if (!request.checkOnly) refuseIn(sessions)
        val asset = manifest(request)
        when {
            request.version == null && !newer(asset.version, request.current) ->
                ExitCode.OK.code.also { say("✓ ostrom ${request.current} is up to date.") }
            asset.version == request.current ->
                ExitCode.OK.code.also { say("✓ ostrom ${request.current} is already installed.") }
            request.checkOnly -> ExitCode.OK.code.also {
                val run = if (request.version == null) "ostrom update" else "ostrom update --version ${asset.version}"
                say("! ostrom ${asset.version} is available. Run $run.")
            }
            else -> install(request, asset)
        }
    } catch (stopped: Stop) {
        say(stopped.text)
        stopped.code.code
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
     * is checked against the latest release's signed floor first, so the release must be reachable.
     */
    suspend fun rollback(request: UpdateRequest): Int = try {
        refuseIn(sessions)
        val binary = request.executable?.toPath()
        if (binary == null || !fileSystem.exists(previousOf(binary))) {
            stop(ExitCode.NOT_FOUND, "No previous version of ostrom is kept on this device.")
        }
        val recorded = fileSystem.textOf(keptVersionOf(binary))
            ?: stop(ExitCode.REFUSED, "✗ The version of the ostrom kept is not recorded. $UNCHANGED $REINSTALL")
        floorRefusal(recorded.trim(), request.current, manifest(request.copy(version = null)).floor, trust.floor)
            ?.let { stop(ExitCode.REFUSED, it) }
        swapBack(binary, request.current)
    } catch (stopped: Stop) {
        say(stopped.text)
        stopped.code.code
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
        if (!ed25519Verify(trust.keyPem, manifest, decoded(signature.decodeToString().trim()))) {
            stop(ExitCode.SIGNATURE_REFUSED, "✗ The release signature does not match. Nothing was changed.")
        }
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
        return assetOf(version, entry, body.text("floor"))
    }

    private fun assetOf(version: String, entry: JsonObject, floor: String?): Asset {
        val url = entry.text("url")
        val sha256 = entry.text("sha256")
        val size = (entry["size"] as? JsonPrimitive)?.longOrNull
        if (url == null || sha256 == null || size == null) unreadable()
        return Asset(version, url, sha256, size, floor)
    }

    /** Installs [asset] when the latest release's floor allows it (S2-39). */
    private suspend fun install(request: UpdateRequest, asset: Asset): Int {
        val floor = if (request.version == null) asset.floor else manifest(request.copy(version = null)).floor
        floorRefusal(asset.version, request.current, floor, trust.floor)?.let { stop(ExitCode.REFUSED, it) }
        val executable = request.executable
            ?: stop(ExitCode.CANNOT_WRITE, "ostrom cannot find its own binary. Reinstall it with the installer.")
        val binary = executable.toPath()
        managerOf(binary)?.let { stop(ExitCode.USAGE, "This ostrom is managed by $it.") }
        val archive = fetch(asset.url, MAX_BINARY_BYTES, request)
        if (archive.size.toLong() != asset.size || sha256Hex(archive) != asset.sha256) {
            stop(ExitCode.SIGNATURE_REFUSED, "✗ The download does not match its signed checksum. $UNCHANGED")
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
        } catch (_: ServerUnreachableException) {
            stop(ExitCode.UNREACHABLE, "ostrom's releases are unreachable at ${request.releases}. $CHECK_NETWORK")
        }
        return when (answer) {
            is Download.Bytes -> answer.bytes
            is Download.Missing -> stop(
                if (answer.status == NOT_FOUND) ExitCode.NOT_FOUND else ExitCode.UNREACHABLE,
                "No release at $url (HTTP ${answer.status}).",
            )
            is Download.TooLarge -> stop(ExitCode.FAILED, "✗ $url is larger than ${answer.limit} bytes.")
        }
    }

    private companion object {
        const val MAX_MANIFEST_BYTES = 16L * 1024
        const val MAX_SIGNATURE_BYTES = 2048L
        const val MAX_BINARY_BYTES = 256L * 1024 * 1024
        const val NOT_FOUND = 404
        const val CHECK_NETWORK = "Check your network."
        const val REINSTALL = "Install the version you want with ostrom update --version."
    }
}

/** Where the version of the binary kept for `--rollback` is recorded. */
internal fun keptVersionOf(binary: Path): Path = "$binary.previous.version".toPath()

/**
 * What a release is checked against.
 *
 * @param keyPem The key the manifest must be signed with.
 * @param floor The floor of the release this binary was built for (`release-floor.txt`), or null for none.
 */
data class ReleaseTrust(val keyPem: String = RELEASE_KEY_PEM, val floor: String? = null)

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

/** [text] decoded from base64, or no bytes when it is not base64 (the signature check then fails). */
@OptIn(ExperimentalEncodingApi::class)
internal fun decoded(text: String): ByteArray = try {
    Base64.decode(text)
} catch (_: IllegalArgumentException) {
    ByteArray(0)
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
