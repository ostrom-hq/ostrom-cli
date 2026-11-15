// SPDX-License-Identifier: Apache-2.0
// A failure of the network or of the keychain is said in a sentence and the command goes on; cancellation is
// rethrown first (scripts/check-conventions.py). The commands, the store and the rules of a folder live together, as
// TeamCode's FolderCommands.kt.
@file:Suppress("TooGenericExceptionCaught", "TooManyFunctions")

package run.ostrom.cli.daemon

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okio.FileMetadata
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.ExitCode
import run.ostrom.cli.commands.GroupCommand
import run.ostrom.cli.commands.directory
import run.ostrom.cli.commands.jsonFlag
import run.ostrom.cli.commands.stopWith
import run.ostrom.cli.commands.windows
import run.ostrom.cli.launcher.join
import run.ostrom.cli.platform.FileStore
import run.ostrom.cli.remote.refuseDirectory
import run.ostrom.core.api.models.DaemonLimits
import run.ostrom.core.api.models.DeviceFolder
import kotlin.random.Random

// `ostrom folders add|list|remove`, as TeamCode's `teamcode folders`: the folders of this device where its owner's
// other devices and apps may start a remote session, hosted by this device's daemon (`ostrom service`).

/** `ostrom folders`. */
internal fun foldersCommand(dependencies: CliDependencies): CliktCommand =
    FoldersCommand().subcommands(FoldersAdd(dependencies), FoldersList(dependencies), FoldersRemove(dependencies))

private class FoldersCommand : GroupCommand(
    "folders",
    "Choose the folders of this device where your other devices and apps may start remote sessions, hosted by " +
        "this device's daemon",
    comingSoon = false,
)

/** `ostrom folders add PATH`. */
private class FoldersAdd(private val dependencies: CliDependencies) : CliktCommand(name = "add") {
    override fun help(context: Context) = "Allow remote sessions in PATH, started from any of your devices. Your " +
        "home directory as a whole, a directory that contains it and system directories are refused; a symbolic " +
        "link is resolved first"

    private val path by argument("PATH")

    override fun run() {
        val store = dependencies.folderStore()
        val (folder, added) = store.add(dependencies.folderPath(path), dependencies.environment["PATH"])
        val said = if (added) {
            "Added ${folder.path} (${folder.id})."
        } else {
            "${folder.path} is already one of this device's folders (${folder.id})."
        }
        dependencies.console.out("$said\n")
        dependencies.publishFromCommand(store)
    }
}

/** `ostrom folders list [--json]`. */
private class FoldersList(private val dependencies: CliDependencies) : CliktCommand(name = "list") {
    override fun help(context: Context) = "List this device's folders: id and path (missing when it no longer exists)"

    private val json by jsonFlag()

    override fun run() {
        val folders = dependencies.folderStore().read().folders
        val console = dependencies.console
        if (json) {
            console.data(FOLDERS_JSON.encodeToString(ListSerializer(LocalFolder.serializer()), folders) + "\n")
            return
        }
        if (folders.isEmpty()) console.err("No folder yet; add one with ostrom folders add PATH.\n")
        for (folder in folders) {
            val missing = !dependencies.fileSystem.isDirectoryAt(folder.path.toPath())
            console.out(listOfNotNull(folder.id, folder.path, "missing".takeIf { missing }).joinToString("\t") + "\n")
        }
    }
}

/** `ostrom folders remove FOLDER`. */
private class FoldersRemove(private val dependencies: CliDependencies) : CliktCommand(name = "remove") {
    override fun help(context: Context) =
        "Stop offering a folder (by id or path). Sessions already running there are not stopped"

    private val folder by argument("FOLDER", help = "The folder's id or path")

    override fun run() {
        val store = dependencies.folderStore()
        val removed = store.remove(folder) ?: throw stopWith(
            ExitCode.NOT_FOUND,
            "ostrom: $folder is not one of this device's folders (ostrom folders list).",
        )
        dependencies.console.out("Removed ${removed.path} (${removed.id}).\n")
        dependencies.publishFromCommand(store)
    }
}

/** `folders add` and `remove` publish at once when they can; the daemon publishes any change anyway. */
private fun CliDependencies.publishFromCommand(store: FolderStore) {
    val report = { text: String -> console.err("$text\n") }
    val server = try {
        daemonServerOf(this)
    } catch (_: PrintMessage) {
        null
    }
    if (server == null) {
        report("Not signed in: this device's daemon publishes its folders once you sign in with ostrom login.")
        return
    }
    runBlocking {
        try {
            if (DAEMON_CAPABILITY !in server.capabilities()) {
                report("This server does not start sessions on devices yet ($DAEMON_CAPABILITY).")
            } else {
                server.publishFolders(published(store.read(), canonicalHome(this@publishFromCommand)))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: DaemonCallException) {
            report("The folders could not be published now (${error.describe()}); this device's daemon publishes " +
                "them when it runs.")
        }
    }
}

/** The capability of a server whose devices' daemons start sessions (`ServerFeatures.RUNNERS_CLOUD_REMOTE_DAEMON`). */
internal const val DAEMON_CAPABILITY = "runners.cloud.remote.daemon.v1"

/** The folders as the server knows them: their ids, names and labels, never their paths. */
internal fun published(file: FoldersFile, home: Path?): List<DeviceFolder> =
    file.folders.map { DeviceFolder(it.id, it.path.toPath().name, folderLabel(it.path, home)) }

/** How the owner recognizes a folder in the apps: `~/git/ostrom` under the home directory, the path otherwise. */
internal fun folderLabel(path: String, home: Path?): String {
    val root = (home ?: return path).toString().trimEnd('/', '\\')
    val inside = path.startsWith("$root/") || path.startsWith("$root\\")
    return if (inside) "~" + path.substring(root.length) else path
}

/** A folder this device offers: [id] is the key the server and the apps know it by, [path] stays on this device. */
@Serializable
internal data class LocalFolder(val id: String, val path: String)

/**
 * `folders.json` in the CLI folder. [searchPath] is the `PATH` of the terminal that last added a folder: the daemon
 * runs under the OS service manager, whose `PATH` rarely holds the agents' directories (`~/.local/bin`, nvm,
 * Homebrew).
 */
@Serializable
internal data class FoldersFile(val folders: List<LocalFolder> = emptyList(), val searchPath: String? = null)

/**
 * The folders file, written privately and under its lock like the remote sessions' records (TeamCode's
 * `FolderStore`).
 *
 * @param file `folders.json`.
 * @param files Writes it owner-only and holds its lock.
 * @param fileSystem Reads it, and resolves a path to remove.
 * @param windows Whether both separators name one folder.
 */
internal class FolderStore(
    val file: String,
    private val files: FileStore,
    private val fileSystem: FileSystem,
    private val windows: Boolean,
) {
    /** Windows accepts both separators: `C:/git/app` and `C:\git\app` are one folder, with one id. */
    private fun key(path: String) = if (windows) path.replace('/', '\\').trimEnd('\\') else path

    /** The file, or none when it does not exist; a link, a large or a damaged file stops the command. */
    fun read(): FoldersFile {
        val metadata = fileSystem.metadataOrNull(file.toPath()) ?: return FoldersFile()
        if (!smallRegularFile(metadata, MAX_BYTES)) {
            throw stopWith(ExitCode.FAILED, "ostrom: $file must be a small regular file, not a symbolic link.")
        }
        return try {
            FOLDERS_JSON.decodeFromString(FoldersFile.serializer(), files.read(file).orEmpty())
        } catch (_: IllegalArgumentException) {
            throw stopWith(ExitCode.FAILED, "ostrom: cannot read $file; fix or remove it, then add your folders again.")
        }
    }

    /** Adds [path] (already checked by [folderPath]) with a new id, or finds it; true when it was added. */
    fun add(path: Path, searchPath: String?): Pair<LocalFolder, Boolean> = files.exclusive(file) {
        val current = read()
        val existing = current.folders.firstOrNull { key(it.path) == key(path.toString()) }
        if (existing == null && current.folders.size >= MAX_FOLDERS) {
            throw stopWith(ExitCode.USAGE, "ostrom: a device offers at most $MAX_FOLDERS folders; remove one first.")
        }
        val folder = existing ?: LocalFolder(newFolderId(), path.toString())
        val folders = if (existing == null) current.folders + folder else current.folders
        write(FoldersFile(folders, searchPath?.takeIf { it.isNotBlank() } ?: current.searchPath))
        folder to (existing == null)
    }

    /** Removes the folder whose id or path is [value] (a path is resolved like `add` does, when it still exists). */
    fun remove(value: String): LocalFolder? = files.exclusive(file) {
        val current = read()
        val canonical = try {
            fileSystem.canonicalize(value.toPath()).toString()
        } catch (_: IOException) {
            value
        }
        val folder = current.folders.firstOrNull {
            it.id == value || key(it.path) == key(value) || key(it.path) == key(canonical)
        }
        folder?.also { write(current.copy(folders = current.folders - it)) }
    }

    private fun write(content: FoldersFile) =
        files.write(file, FOLDERS_JSON.encodeToString(FoldersFile.serializer(), content))

    companion object {
        /** The most folders a device publishes ([DaemonLimits.MAX_FOLDERS]). */
        const val MAX_FOLDERS = DaemonLimits.MAX_FOLDERS
        private const val MAX_BYTES = 262_144L
        private const val ID_BYTES = 8
        private const val HEX = 16

        /** A new folder's id: 16 lowercase hexadecimal digits (`[A-Za-z0-9_-]{1,64}` for the server). */
        fun newFolderId(): String =
            Random.nextBytes(ID_BYTES).joinToString("") { (it.toInt() and BYTE).toString(HEX).padStart(2, '0') }

        private const val BYTE = 0xff
    }
}

private val FOLDERS_JSON = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** This device's folders file, in the CLI folder. */
internal fun CliDependencies.folderStore() = FolderStore(
    join(directory(), "folders.json"),
    files,
    fileSystem,
    windows,
)

/** The home directory, resolved (macOS's `/var` is `/private/var`), or null when the environment does not say. */
internal fun canonicalHome(dependencies: CliDependencies): Path? = dependencies.environment.homeDirectory?.let {
    try {
        dependencies.fileSystem.canonicalize(it.toPath())
    } catch (_: IOException) {
        it.toPath()
    }
}

/** [value], resolved against the working directory, checked by [folderPath]. */
internal fun CliDependencies.folderPath(value: String): Path = folderPath(
    fileSystem,
    (workingDirectory().toPath() / value).toString(),
    canonicalHome(this),
    windows,
)

/**
 * The folder [value] names, resolved (`..` and symbolic links): it must be a directory, and neither the file system's
 * root, nor the home directory, nor a directory that contains the home directory, nor a system directory.
 */
internal fun folderPath(fileSystem: FileSystem, value: String, home: Path?, windows: Boolean): Path {
    val path = try {
        fileSystem.canonicalize(value.toPath())
    } catch (_: IOException) {
        throw stopWith(ExitCode.NOT_FOUND, "ostrom: $value does not exist.")
    }
    if (!fileSystem.isDirectoryAt(path)) {
        throw stopWith(ExitCode.USAGE, "ostrom: $path is not a directory.")
    }
    refuseFolder(path, home, windows)
    return path
}

/** [refuseDirectory], and a directory that contains the home directory. */
internal fun refuseFolder(path: Path, home: Path?, windows: Boolean) {
    refuseDirectory(path, home, windows)
    if (home != null && contains(path, home, windows)) {
        throw stopWith(ExitCode.USAGE, "ostrom: choose a project directory: $path contains your home directory.")
    }
}

/** Whether [inner] lies strictly inside [outer]. */
private fun contains(outer: Path, inner: Path, windows: Boolean): Boolean {
    val root = outer.toString().trimEnd('/', '\\')
    val text = inner.toString()
    fun starts(prefix: String) =
        if (windows) text.lowercase().startsWith(prefix.lowercase()) else text.startsWith(prefix)
    return starts("$root/") || starts("$root\\")
}

/** Why the daemon refuses an order, sent to the server as the refusal's reason code. */
internal object RefusalReasons {
    /** The folder id is not in `folders.json` (removed meanwhile, or published by another configuration). */
    const val FOLDER_NOT_CONFIGURED = "folder_not_configured"

    /** The folder no longer exists or is no longer a directory. */
    const val FOLDER_MISSING = "folder_missing"

    /** The folder's path now resolves elsewhere: it became, or goes through, a symbolic link. */
    const val FOLDER_MOVED = "folder_moved"

    /** The folder is the home directory, contains it, or is a system directory (a hand-edited `folders.json`). */
    const val FOLDER_REFUSED = "folder_refused"

    /** The order's directory name is not the folder's. */
    const val FOLDER_MISMATCH = "folder_mismatch"

    /** An agent this version cannot run. */
    const val AGENT_UNSUPPORTED = "agent_unsupported"

    /** The host kept stopping before it was ready. */
    const val HOST_FAILED = "host_failed"

    /** The conversation to continue is not one of the folder's Claude Code conversations on this device (any more). */
    const val RESUME_NOT_FOUND = "resume_not_found"
}

/** What the daemon's check of an order found. */
internal sealed interface OrderCheck {
    /** Host it in [directory]. */
    data class Accepted(val directory: String) : OrderCheck

    /** Refuse it with [reason], one of [RefusalReasons]. */
    data class Refused(val reason: String) : OrderCheck
}

/**
 * The daemon's own check of an order, whatever the server already checked: the folder must still be in [file],
 * resolve to the very path it was added with (a folder replaced by a symbolic link pointing elsewhere is refused),
 * still be a directory the rules of [folderPath] accept, and carry the order's directory name.
 */
@Suppress("LongParameterList") // the order's three parts, the folders, and the machine (TeamCode's checkOrder)
internal fun checkOrder(
    fileSystem: FileSystem,
    folderId: String,
    directoryName: String,
    agent: String,
    file: FoldersFile,
    home: Path?,
    windows: Boolean,
): OrderCheck {
    val folder = file.folders.firstOrNull { it.id == folderId }
    val stored = folder?.let { it.path.toPath() }
    val resolved = stored?.let { resolvedDirectory(fileSystem, it) }
    val reason = when {
        agent !in SUPPORTED_AGENTS -> RefusalReasons.AGENT_UNSUPPORTED
        stored == null -> RefusalReasons.FOLDER_NOT_CONFIGURED
        resolved == null -> RefusalReasons.FOLDER_MISSING
        resolved.toString() != stored.toString() -> RefusalReasons.FOLDER_MOVED
        !allowed(resolved, home, windows) -> RefusalReasons.FOLDER_REFUSED
        resolved.name != directoryName -> RefusalReasons.FOLDER_MISMATCH
        else -> null
    }
    return if (reason == null) OrderCheck.Accepted(resolved.toString()) else OrderCheck.Refused(reason)
}

/** [path] resolved, when it is still a directory. */
private fun resolvedDirectory(fileSystem: FileSystem, path: Path): Path? = try {
    fileSystem.canonicalize(path).takeIf { fileSystem.isDirectoryAt(it) }
} catch (_: IOException) {
    null
}

private fun allowed(path: Path, home: Path?, windows: Boolean): Boolean = try {
    refuseFolder(path, home, windows)
    true
} catch (_: PrintMessage) {
    false
}

private val SUPPORTED_AGENTS = setOf("claude", "codex")

// How the daemon reads this device's files: a link is never followed, and what the file system does not tell (a size,
// a time) counts as none.

/** Whether [path] is a directory: false when it is missing, or any other kind of file. */
internal fun FileSystem.isDirectoryAt(path: Path): Boolean = metadataOrNull(path)?.isDirectory == true

/** Whether [metadata] is a regular file, not a symbolic link; false for a missing file. */
internal fun regularFile(metadata: FileMetadata?): Boolean =
    metadata != null && metadata.symlinkTarget == null && metadata.isRegularFile

/** [regularFile], of at most [maxBytes]. */
internal fun smallRegularFile(metadata: FileMetadata?, maxBytes: Long): Boolean =
    metadata != null && metadata.symlinkTarget == null && metadata.isRegularFile && (metadata.size ?: 0L) <= maxBytes

/** When [metadata]'s file was last written, in milliseconds; 0 when unknown. */
internal fun lastModified(metadata: FileMetadata?): Long = metadata?.lastModifiedAtMillis ?: 0L
