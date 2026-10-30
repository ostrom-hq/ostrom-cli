package dev.ostrom.cli.platform

import kotlin.random.Random
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath

/** Small text files of the CLI (configuration, state), written atomically. */
interface FileStore {
    /** The text of the file at [path], or null when it does not exist or cannot be read (a file in its path). */
    fun read(path: String): String?

    /**
     * Replaces the file at [path] with [text]: written next to it, then moved over it, so that a reader never sees a
     * half-written file. Parent folders are created. On macOS and Linux the file is readable by its owner only.
     *
     * @throws FileWriteException When the folder or the file cannot be written.
     * @throws IllegalArgumentException When [path] is a root, which no file can replace.
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
    override fun read(path: String): String? = try {
        fileSystem.read(path.toPath()) { readUtf8() }
    } catch (_: IOException) {
        // Missing, or unreadable: the next write says why, with exit code 73.
        null
    }

    override fun write(path: String, text: String) {
        val file = path.toPath()
        val parent = requireNotNull(file.parent) { "$path is a root, not a file." }
        // One temporary name per write, so two concurrent writers never share it.
        val temporary = "$path.${Random.nextLong().toULong().toString(RADIX)}.tmp".toPath()
        try {
            fileSystem.createDirectories(parent)
            // Owner-only before the text goes in: the content is never readable by others, even for an instant.
            fileSystem.write(temporary) { }
            restrictToOwner(temporary.toString())
            fileSystem.write(temporary) { writeUtf8(text) }
            fileSystem.atomicMove(temporary, file)
        } catch (e: IOException) {
            // A temporary file left behind is never read; it only costs its few bytes.
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
        val extensions = if (environment.operatingSystem == OperatingSystem.WINDOWS) {
            // Like cmd.exe: the bare name only when it already has an extension, so npm's extensionless sh shim
            // (which CreateProcess cannot run) loses to the claude.cmd beside it.
            val pathExt = environment["PATHEXT"] ?: DEFAULT_PATHEXT
            val bare = if ('.' in name) listOf("") else emptyList()
            bare + pathExt.split(';').filter { it.isNotEmpty() }.map { it.lowercase() }
        } else {
            listOf("")
        }
        val folders = environment["PATH"].orEmpty().split(environment.pathListSeparator).filter { it.isNotEmpty() }
        return folders.asSequence()
            .flatMap { folder -> extensions.asSequence().map { folder.toPath() / "$name$it" } }
            .firstOrNull(::isFile)
            ?.toString()
    }

    /** True when [path] is a file, or a link to one (npm and Homebrew install agents as links). */
    private fun isFile(path: Path): Boolean = try {
        fileSystem.metadata(fileSystem.canonicalize(path)).isRegularFile
    } catch (_: IOException) {
        false
    }

    private companion object {
        /** What Windows tries when PATHEXT is unset. */
        const val DEFAULT_PATHEXT = ".COM;.EXE;.BAT;.CMD"
    }
}

/** Makes [path] readable and writable by its owner only (0600); a no-op on Windows, where the profile is private. */
internal expect fun restrictToOwner(path: String)

/** Base of the random part of a temporary file name. */
private const val RADIX = 36
