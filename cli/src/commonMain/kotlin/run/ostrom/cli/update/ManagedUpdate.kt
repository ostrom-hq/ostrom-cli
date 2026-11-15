// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.update

import okio.FileSystem
import okio.IOException
import okio.Path
import run.ostrom.cli.ExitCode

/**
 * The steps [SelfUpdate] takes on a managed installation (TeamCode's `InstallationOperations`, without its service):
 * stage the verified archive's binary as a payload, probe it, publish the launcher once, then select it.
 *
 * @param fileSystem Where the archive is unpacked.
 * @param unpack Unpacks the archive.
 * @param say Writes a line for the developer.
 * @param probe Runs a binary with [INSTALLATION_PROBE].
 */
internal class ManagedUpdate(
    private val fileSystem: FileSystem,
    private val unpack: Unpacker,
    private val say: (String) -> Unit,
    private val probe: InstallationProbe,
) {
    /** Runs [block]; a refusal of the installation, or a file it cannot write, stops the update with its words. */
    inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (refused: InstallationException) {
        val (code, text) = installationRefusal(refused.code)
        stop(code, text)
    } catch (failure: IOException) {
        stop(ExitCode.CANNOT_WRITE, "ostrom could not write its installation: ${failure.message}")
    }

    /**
     * Runs [block] holding the writer lock of [store], once a selection a crash interrupted is put back: one
     * installer at a time, the others refused at once.
     */
    suspend fun <T> writing(store: InstallationStore, block: suspend () -> T): T {
        val lock = guard {
            store.initialize()
            store.writerLock()
        }
        return lock.use {
            guard { if (store.journal() != null) store.lock(exclusive = true).use { store.recoverInterrupted() } }
            block()
        }
    }

    /**
     * Installs [archive], the checked archive of [asset], into [store] and selects it, once the binary it holds
     * answers the probe as that version speaking [apiVersion]. Caller holds the writer lock.
     */
    fun put(store: InstallationStore, asset: Asset, archive: ByteArray, apiVersion: Int) {
        val name = store.paths.executableName
        val staged = guard {
            store.stage(asset.manifest, asset.signature, asset.version) { work -> unpacked(work, asset, archive, name) }
        }
        probe.refusal(store.paths.binary(staged), asset.version, apiVersion)?.let { stop(ExitCode.REFUSED, it) }
        guard { store.ensureLauncher(staged) }
        select(store, staged, rollback = false)
    }

    /** Selects [version] ([rollback]: the previous one) under the exclusive activity lock, then collects garbage. */
    fun select(store: InstallationStore, version: InstalledVersion, rollback: Boolean) = guard {
        store.lock(exclusive = true).use {
            store.activate(version, rollback)
            try {
                store.collectGarbage()
            } catch (ignored: InstallationException) {
                // a cleanup: retried by the next selection, never failing this one
                say("! Old versions of ostrom could not be removed yet (${ignored.code}).")
            } catch (ignored: IOException) {
                say("! Old versions of ostrom could not be removed yet: ${ignored.message}")
            }
        }
    }

    /** Unpacks [archive] into [work]; the binary [name] it holds, or stop. */
    private fun unpacked(work: Path, asset: Asset, archive: ByteArray, name: String): Path {
        val file = work / asset.url.substringAfterLast('/')
        fileSystem.write(file) { write(archive) }
        val into = work / "unpack"
        fileSystem.createDirectories(into)
        val binary = into / name
        if (!unpack.unpack(file.toString(), into.toString()) || !fileSystem.exists(binary)) {
            stop(ExitCode.FAILED, "✗ The archive does not hold $name. Nothing was changed.")
        }
        return binary
    }
}

/** The exit code and the words of the installation's refusal [code]. */
internal fun installationRefusal(code: String): Pair<ExitCode, String> = when (code) {
    InstallationStore.WRITER_BUSY, InstallationStore.BUSY -> ExitCode.FAILED to
        "✗ Another ostrom install or update is running. Nothing was changed. Try again once it has ended."
    HOME_UNAVAILABLE -> ExitCode.USAGE to
        "ostrom cannot tell where your home folder is. Give the installation's folder with --root."
    "release_root_invalid" -> ExitCode.USAGE to "ostrom install: --root must be an absolute folder."
    InstallationStore.NOT_OWNED -> ExitCode.REFUSED to
        "✗ The installation's folder already holds files that are not ostrom's. Nothing was changed. " +
        "Give another folder with --root."
    else -> ExitCode.FAILED to
        "✗ The installation is damaged or cannot be written ($code). Nothing was changed. Run the installer again."
}
