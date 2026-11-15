// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), DesktopReleaseFormat and VerifyReleaseManifest.verifyDesktop; see NOTICE.
package run.ostrom.cli.ui

import run.ostrom.cli.ExitCode
import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.parseObject
import run.ostrom.cli.launcher.text
import run.ostrom.cli.update.ReleaseTrust
import run.ostrom.cli.update.SemVer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Why `ostrom ui` stops: the exit code and what is said. */
internal class DesktopFailure(val code: ExitCode, val text: String) : Exception(text)

/** The desktop app's release files, published by ostrom-app's `release-desktop.yml` next to the packages. */
internal object DesktopFormat {
    const val MANIFEST = "desktop-manifest.json"
    const val SIGNATURE = "desktop-manifest.json.sig"
    const val PRODUCT = "desktop"
    const val MAX_MANIFEST_BYTES = 16L * 1024
    const val MAX_SIGNATURE_BYTES = 2048L

    /** The largest package accepted (512 MiB, as the release script's bound). */
    const val MAX_PACKAGE_BYTES = 512L * 1024 * 1024
    val TARGETS = listOf("macos-arm64", "linux-x64", "linux-arm64", "windows-x64")
    private val SHA256 = Regex("[0-9a-f]{64}")

    /** The name of the package `ostrom ui` installs for [target]: the zipped `.app`, the `.msi`, the `.tar.gz`. */
    fun fileName(version: String, target: String): String {
        val extension = when {
            target.startsWith("macos-") -> "zip"
            target.startsWith("windows-") -> "msi"
            else -> "tar.gz"
        }
        return "ostrom-desktop-$version-$target.$extension"
    }

    fun validSha256(value: String) = SHA256.matches(value)
}

/** A package the signed manifest lists: its exact name, SHA-256 and length. */
internal class DesktopAsset(val fileName: String, val sha256: String, val size: Long)

/** A desktop manifest whose signature matched a trusted root: the [version] it names and the [assets] it signs. */
internal class VerifiedDesktop(val version: String, val keyId: String, val assets: Map<String, DesktopAsset>)

/**
 * Authenticates `desktop-manifest.json` with the roots compiled into this binary (the CLI's own release keys): the
 * signature is checked over the exact bytes before any of them is read. The manifest must carry `product: desktop`, so
 * the CLI's `latest.json` cannot stand in for it, and every package must be named as the release names it.
 */
internal class DesktopManifestVerifier(private val trust: ReleaseTrust) {
    @Suppress("ThrowsCount")
    fun verify(manifest: ByteArray, signature: ByteArray): VerifiedDesktop {
        val keyId = trust.verify(manifest, signature.decodeToString())
            ?: throw DesktopFailure(
                ExitCode.SIGNATURE_REFUSED,
                "✗ The desktop manifest's signature matches none of the trusted roots.",
            )
        return parse(manifest.decodeToString(), keyId)
    }

    /** The manifest [text], once its signature matched the root [keyId]: its version and the packages it signs. */
    @Suppress("ThrowsCount", "CyclomaticComplexMethod") // each field of the manifest is checked, or it is unreadable
    internal fun parse(text: String, keyId: String): VerifiedDesktop {
        val body = parseObject(text) ?: throw unreadable()
        val version = body.text("version")?.takeIf { SemVer.of(it) != null } ?: throw unreadable()
        val schema = (body["schemaVersion"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        val own = schema == 1L && body.text("product") == DesktopFormat.PRODUCT && body.text("tag") == version
        if (!own) throw unreadable()
        val assets = body.child("assets") ?: throw unreadable()
        // a target this CLI does not know is ignored: only its own is used
        val listed = assets.keys.filter { it in DesktopFormat.TARGETS }.associateWith { target ->
            val entry = assets.child(target) ?: throw unreadable()
            val name = entry.text("fileName")
            val sha256 = entry.text("sha256")
            val size = (entry["size"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            val valid = name == DesktopFormat.fileName(version, target) &&
                sha256 != null && DesktopFormat.validSha256(sha256) && size != null &&
                size in 1..DesktopFormat.MAX_PACKAGE_BYTES
            // the checks above say these are set
            @Suppress("UnsafeCallOnNullableType")
            if (valid) DesktopAsset(name!!, sha256!!, size!!) else throw unreadable()
        }
        return VerifiedDesktop(version, keyId, listed)
    }

    private fun unreadable() = DesktopFailure(ExitCode.FAILED, "✗ The desktop manifest cannot be read.")
}
