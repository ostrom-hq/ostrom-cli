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

/** The signed release manifest's entry for one target. */
private class Asset(val version: String, val url: String, val sha256: String, val size: Long)

/**
 * `ostrom update`: the signed self-update. The manifest's Ed25519 signature is checked over its exact bytes before
 * anything in it is read, the archive against the signed size and SHA-256, and the binary is swapped by two renames
 * in its own folder, the previous one kept beside it for `--rollback` (CLI.md `ostrom update`).
 *
 * @param downloads Fetches the manifest and the archive.
 * @param fileSystem Where the binary is.
 * @param unpack Unpacks an archive into a folder; false when it could not.
 * @param say Writes a line for the developer (stdout).
 * @param releaseKeyPem The key the manifest must be signed with.
 */
class SelfUpdate(
    private val downloads: Downloads,
    private val fileSystem: FileSystem,
    private val unpack: Unpacker,
    private val say: (String) -> Unit,
    private val releaseKeyPem: String = RELEASE_KEY_PEM,
) {
    /** Runs [request]; the exit code. */
    suspend fun update(request: UpdateRequest): Int = try {
        val asset = manifest(request)
        when {
            request.version == null && !newer(asset.version, request.current) ->
                ExitCode.OK.code.also { say("✓ ostrom ${request.current} is up to date.") }
            asset.version == request.current ->
                ExitCode.OK.code.also { say("✓ ostrom ${request.current} is already installed.") }
            request.checkOnly ->
                ExitCode.OK.code.also { say("! ostrom ${asset.version} is available. Run ostrom update.") }
            else -> install(request, asset)
        }
    } catch (stopped: Stop) {
        say(stopped.text)
        stopped.code.code
    }

    /** `ostrom update --rollback`: back to the version kept by the last update, which is kept in turn. */
    fun rollback(executable: String?): Int {
        val binary = executable?.toPath()
        if (binary == null || !fileSystem.exists(previousOf(binary))) {
            say("No previous version of ostrom is kept on this device.")
            return ExitCode.NOT_FOUND.code
        }
        val previous = previousOf(binary)
        val swap = "$binary.swap".toPath()
        return try {
            fileSystem.atomicMove(binary, swap)
            fileSystem.atomicMove(previous, binary)
            fileSystem.atomicMove(swap, previous)
            say("✓ Back to the previous ostrom. Run ostrom update to come back.")
            ExitCode.OK.code
        } catch (failure: IOException) {
            say("ostrom could not swap its binary: ${failure.message}")
            ExitCode.CANNOT_WRITE.code
        }
    }

    private suspend fun manifest(request: UpdateRequest): Asset {
        val folder = if (request.version == null) "latest/download" else "download/${request.version}"
        val url = "${request.releases.trimEnd('/')}/$folder/latest.json"
        val manifest = fetch(url, MAX_MANIFEST_BYTES, request)
        val signature = fetch("$url.sig", MAX_SIGNATURE_BYTES, request)
        if (!ed25519Verify(releaseKeyPem, manifest, decoded(signature.decodeToString().trim()))) {
            stop(ExitCode.FAILED, "✗ The release signature does not match. Nothing was changed.")
        }
        val body = parseObject(manifest.decodeToString()) ?: unreadable()
        val version = body.text("version") ?: unreadable()
        val entry = body.child("assets")?.child(request.target)
            ?: stop(ExitCode.NOT_FOUND, "No ostrom $version is built for ${request.target}.")
        return assetOf(version, entry)
    }

    private fun assetOf(version: String, entry: JsonObject): Asset {
        val url = entry.text("url")
        val sha256 = entry.text("sha256")
        val size = (entry["size"] as? JsonPrimitive)?.longOrNull
        if (url == null || sha256 == null || size == null) unreadable()
        return Asset(version, url, sha256, size)
    }

    private fun unreadable() = Stop(ExitCode.FAILED, "✗ The release manifest cannot be read. Nothing was changed.")

    private suspend fun install(request: UpdateRequest, asset: Asset): Int {
        val binary = request.executable?.toPath()
            ?: stop(ExitCode.CANNOT_WRITE, "ostrom cannot find its own binary. Reinstall it with the installer.")
        managerOf(binary)?.let { stop(ExitCode.USAGE, "This ostrom is managed by $it.") }
        val archive = fetch(asset.url, MAX_BINARY_BYTES, request)
        if (archive.size.toLong() != asset.size || sha256Hex(archive) != asset.sha256) {
            stop(ExitCode.FAILED, "✗ The download does not match its signed checksum. Nothing was changed.")
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
        val running = request.current
        say("✓ Updated ostrom $running → ${asset.version} (signature checked).")
        say("  Running sessions keep $running until they end.")
        return ExitCode.OK.code
    }

    private fun swap(binary: Path, fresh: Path) {
        val previous = previousOf(binary)
        fileSystem.delete(previous, mustExist = false)
        fileSystem.atomicMove(binary, previous)
        try {
            fileSystem.atomicMove(fresh, binary)
        } catch (failure: IOException) {
            fileSystem.atomicMove(previous, binary)
            throw failure
        }
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
    }
}

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
