// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.update

import run.ostrom.cli.ExitCode
import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.parseObject
import run.ostrom.cli.launcher.text
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okio.Buffer
import okio.FileSystem
import okio.HashingSource
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath

/** What `ostrom verify-release` checked: the release [version], the id of the root that signed it, the binary's target. */
class VerifiedRelease(val version: String, val keyId: String, val verifiedTarget: String?)

/** A release that is not what its signature says, or a file that cannot be read: why, and the exit code for it. */
class ReleaseRefused(val code: ExitCode, val text: String) : Exception(text)

/**
 * `ostrom verify-release` (TeamCode's `verify-release`): checks a downloaded `latest.json` against the roots compiled
 * into this binary, and optionally a downloaded archive against the manifest's size and SHA-256. Offline: no login,
 * no server, nothing is executed or installed. The signature is checked over the exact bytes before any of them is
 * read as a manifest.
 *
 * @param fileSystem Where the files are.
 * @param trust The roots a manifest may be signed with.
 */
class ReleaseVerification(
    private val fileSystem: FileSystem,
    private val trust: ReleaseTrust = ReleaseTrust(),
) {
    /**
     * Verifies [manifest] against [signature]; when [archive] and [target] are given, the archive too.
     *
     * @throws ReleaseRefused With the exit code that says why.
     */
    fun verify(manifest: String, signature: String, archive: String?, target: String?): VerifiedRelease {
        if ((archive == null) != (target == null)) {
            throw ReleaseRefused(ExitCode.USAGE, "ostrom verify-release: --archive and --target go together.")
        }
        val bytes = read(manifest.toPath(), MAX_MANIFEST_BYTES)
        val signed = read(signature.toPath(), MAX_SIGNATURE_BYTES)
        val keyId = trust.verify(bytes, signed.decodeToString())
            ?: throw ReleaseRefused(ExitCode.SIGNATURE_REFUSED, "✗ The release signature does not match any root this ostrom trusts.")
        val body = parseObject(bytes.decodeToString())
        val version = body?.text("version")?.takeIf { SemVer.of(it) != null }
            ?: throw ReleaseRefused(ExitCode.FAILED, "✗ The release manifest cannot be read.")
        if (archive != null && target != null) checkArchive(archive.toPath(), version, target, body.child("assets"))
        return VerifiedRelease(version, keyId, target)
    }

    private fun checkArchive(path: Path, version: String, target: String, assets: kotlinx.serialization.json.JsonObject?) {
        val entry = assets?.child(target)
            ?: throw ReleaseRefused(ExitCode.NOT_FOUND, "No ostrom $version is built for $target.")
        val sha256 = entry.text("sha256")
        val size = (entry["size"] as? JsonPrimitive)?.longOrNull
        if (sha256 == null || size == null) throw ReleaseRefused(ExitCode.FAILED, "✗ The release manifest cannot be read.")
        val (count, hash) = digest(path, size)
        if (count != size || hash != sha256) {
            throw ReleaseRefused(ExitCode.SIGNATURE_REFUSED, "✗ $path does not match the size and SHA-256 the manifest signs.")
        }
    }

    /** The size (counted up to one byte over [limit]) and SHA-256 of [path], streamed: never held whole. */
    private fun digest(path: Path, limit: Long): Pair<Long, String> {
        regular(path)
        try {
            HashingSource.sha256(fileSystem.source(path)).use { source ->
                val sink = Buffer()
                var total = 0L
                while (true) {
                    val read = source.read(sink, BLOCK)
                    if (read == -1L) break
                    total += read
                    sink.clear()
                    if (total > limit) return total to ""
                }
                return total to source.hash.hex()
            }
        } catch (_: IOException) {
            throw unreadable(path)
        }
    }

    /** The bytes of the regular file [path], at most [limit] of them. */
    private fun read(path: Path, limit: Long): ByteArray {
        val size = regular(path)
        if (size > limit) throw ReleaseRefused(ExitCode.FAILED, "✗ $path is larger than $limit bytes.")
        return try {
            fileSystem.read(path) { readByteArray() }
        } catch (_: IOException) {
            throw unreadable(path)
        }
    }

    /** The size of [path], which must be a regular file and no link. */
    private fun regular(path: Path): Long {
        val metadata = try {
            fileSystem.metadataOrNull(path)
        } catch (_: IOException) {
            null
        }
        if (metadata == null || !metadata.isRegularFile || metadata.symlinkTarget != null) throw unreadable(path)
        return metadata.size ?: 0
    }

    private fun unreadable(path: Path) = ReleaseRefused(ExitCode.NOT_FOUND, "✗ $path is not a readable file.")

    private companion object {
        const val MAX_MANIFEST_BYTES = 16L * 1024
        const val MAX_SIGNATURE_BYTES = 2048L
        const val BLOCK = 65_536L
    }
}
