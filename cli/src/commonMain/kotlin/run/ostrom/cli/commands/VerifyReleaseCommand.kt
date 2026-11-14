// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.update.ReleaseRefused

/**
 * `ostrom verify-release --manifest latest.json --signature latest.json.sig [--archive FILE --target T]`: a release
 * downloaded by hand, checked offline against the roots compiled into this binary. Exit 0 verified, 1 a manifest that
 * does not read, 66 a file or a target that is not there, 64 bad usage, 78 a signature or a checksum that does not match.
 */
internal class VerifyReleaseCommand(private val dependencies: CliDependencies) : CliktCommand(name = "verify-release") {
    override fun help(context: Context) =
        "Verify a downloaded release against the keys shipped in this ostrom (offline, no login or server)"

    val manifest by option("--manifest", help = "The release's latest.json").required()
    val signature by option("--signature", help = "The release's latest.json.sig").required()
    val archive by option("--archive", "--binary", help = "A downloaded archive to check against the manifest")
    val target by option("--target", help = "The target of that archive: linux-x64, linux-arm64, macos-arm64…")

    override fun run() {
        val console = dependencies.console
        try {
            val verified = dependencies.releaseVerification.verify(manifest, signature, archive, target)
            console.out("Verified ostrom ${verified.version}.\n")
            console.out("Release key: ${verified.keyId}\n")
            verified.verifiedTarget?.let { console.out("Verified archive for $it.\n") }
        } catch (refused: ReleaseRefused) {
            console.err("${refused.text}\n")
            throw ProgramResult(refused.code.code)
        }
    }
}
