// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.update

import okio.IOException
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import run.ostrom.cli.platform.Environment
import run.ostrom.cli.platform.EnvironmentImpl
import run.ostrom.cli.platform.InstallationFiles

/** [InstallationFiles] over a fake file system: locks held in memory, failures and foreign files on demand. */
internal class FakeInstallationFiles : InstallationFiles {
    /** The locks held, by path. */
    val held = mutableSetOf<String>()
    var lockFails = false
    var sealFails = false
    var flushFails = false

    /** The one folder whose flush fails, when set. */
    var flushFailsFor: String? = null

    /** Paths that are not this user's alone. */
    val foreign = mutableSetOf<String>()
    val sealed = mutableListOf<String>()

    override fun tryLock(path: String, exclusive: Boolean): AutoCloseable? {
        if (lockFails) throw IOException("no lock")
        if (!held.add(path)) return null
        return AutoCloseable { held.remove(path) }
    }

    override fun seal(path: String) {
        if (sealFails) throw IOException("no seal")
        sealed += path
    }

    override fun flushDirectory(path: String) {
        if (flushFails || path == flushFailsFor) throw IOException("no flush")
    }

    override fun isPrivate(path: String, directory: Boolean): Boolean = path !in foreign
}

/** An [Environment] of the [variables] given. */
internal fun variables(vararg variables: Pair<String, String>): Environment = EnvironmentImpl(mapOf(*variables)::get)

/** A fake file system that allows links, with the folder `/home/dev`. */
internal fun installationFileSystem(): FakeFileSystem = FakeFileSystem().apply {
    allowSymlinks = true
    createDirectories("/home/dev".toPath())
}

/** A store at [root] on [fileSystem], whose temporary names count up. */
internal fun storeOn(fileSystem: FakeFileSystem, files: FakeInstallationFiles, root: String = ROOT): InstallationStore {
    var next = 0
    val random = { (next++).toString().padStart(HEX_LENGTH, '0') }
    return InstallationStore(InstallationPaths(root.toPath(), "linux-x64"), fileSystem, files, random)
}

/** Stages the payload of the [ReleaseVectors] manifest [name] for [version] into [store], [binary] as its bytes. */
internal fun stageOn(
    fileSystem: FakeFileSystem,
    store: InstallationStore,
    name: String = "LATEST",
    version: String = "9.9.9",
    binary: String = "new",
): InstalledVersion = store.stage(
    ReleaseVectors.MANIFESTS.getValue(name).first.encodeToByteArray(),
    ReleaseVectors.MANIFESTS.getValue(name).second.encodeToByteArray(),
    version,
) { work ->
    val file = work / "ostrom"
    fileSystem.write(file) { writeUtf8(binary) }
    file
}

internal const val ROOT = "/home/dev/ostrom"
private const val HEX_LENGTH = 32
