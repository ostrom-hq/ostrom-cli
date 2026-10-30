package run.ostrom.cli.platform

import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath

/** Small text files of the CLI (configuration, state), written atomically. */
interface FileStore {
    /** The text of the file at [path], or null when it does not exist. */
    fun read(path: String): String?

    /**
     * Replaces the file at [path] with [text]: written next to it, then moved over it, so that a reader never sees a
     * half-written file. Parent folders are created. On macOS and Linux the file is readable by its owner only.
     *
     * @throws FileWriteException When the folder or the file cannot be written.
     */
    fun write(path: String, text: String)

    /** Deletes the file at [path]; nothing happens when it does not exist. */
    fun delete(path: String)

    /** True when [path] exists. */
    fun exists(path: String): Boolean
}

/** A file of the CLI could not be written (exit code 73). */
class FileWriteException(val path: String, cause: Throwable) : Exception(cause)

/** [FileStore] over okio's [FileSystem.SYSTEM]. */
class FileStoreImpl(private val fileSystem: FileSystem = FileSystem.SYSTEM) : FileStore {
    override fun read(path: String): String? {
        val file = path.toPath()
        if (!fileSystem.exists(file)) return null
        return fileSystem.read(file) { readUtf8() }
    }

    override fun write(path: String, text: String) {
        val file = path.toPath()
        val temporary = "$path.tmp".toPath()
        try {
            file.parent?.let { fileSystem.createDirectories(it) }
            fileSystem.write(temporary) { writeUtf8(text) }
            restrictToOwner(temporary.toString())
            fileSystem.atomicMove(temporary, file)
        } catch (e: IOException) {
            try {
                fileSystem.delete(temporary, mustExist = false)
            } catch (_: IOException) {
                // The folder itself could not be created: there is no temporary file to remove.
            }
            throw FileWriteException(path, e)
        }
    }

    override fun delete(path: String) = fileSystem.delete(path.toPath(), mustExist = false)

    override fun exists(path: String): Boolean = fileSystem.exists(path.toPath())
}

/** Finds executables on the PATH, the way a shell would. */
interface ExecutableFinder {
    /** The full path of the executable [name] on the PATH, or null when it is not installed. */
    fun find(name: String): String?
}

/** [ExecutableFinder] reading PATH (and PATHEXT on Windows) from [environment]. */
class ExecutableFinderImpl(
    private val environment: Environment,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : ExecutableFinder {
    override fun find(name: String): String? {
        val windows = environment.operatingSystem == OperatingSystem.WINDOWS
        val extensions = if (windows) {
            listOf("") + (environment["PATHEXT"] ?: ".COM;.EXE;.BAT;.CMD").split(';').filter { it.isNotEmpty() }.map { it.lowercase() }
        } else {
            listOf("")
        }
        val folders = environment["PATH"]?.split(environment.pathListSeparator)?.filter { it.isNotEmpty() } ?: return null
        for (folder in folders) {
            for (extension in extensions) {
                val candidate: Path = folder.toPath() / "$name$extension"
                val metadata = fileSystem.metadataOrNull(candidate) ?: continue
                if (metadata.isRegularFile) return candidate.toString()
            }
        }
        return null
    }
}

/** Makes [path] readable and writable by its owner only (0600); a no-op on Windows, where the profile is private. */
internal expect fun restrictToOwner(path: String)
