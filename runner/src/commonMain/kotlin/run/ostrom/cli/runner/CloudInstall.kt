// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import okio.Buffer
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath

/**
 * One line of the exchange between the runner and the installer in the agent's container (`ostrom cloud
 * install-skills|install-hooks`), TeamCode's `SkillMessage`: the installer says [HELLO] once its terminal is raw, the
 * runner sends the [MANIFEST], the installer answers what it [NEED]s then [READY], the runner streams each needed item
 * ([BEGIN], its [FILE] chunks, [COMMIT]), then [END]; the installer answers a [RESULT] per item, then [DONE].
 *
 * @property t The kind of line.
 * @property name The item.
 * @property version Its version.
 * @property digest Its digest.
 * @property path A file's path in the item.
 * @property mode A file's mode, on its first chunk.
 * @property data A chunk of a file, in base64.
 * @property status The last chunk of a file ([LAST]), or an item's result.
 * @property items The manifest.
 */
@Serializable
data class InstallMessage(
    val t: String,
    val name: String? = null,
    val version: String? = null,
    val digest: String? = null,
    val path: String? = null,
    val mode: String? = null,
    val data: String? = null,
    val status: String? = null,
    val items: List<Item> = emptyList(),
) {
    /**
     * An item of the manifest.
     *
     * @property name Its name.
     * @property version Its version.
     * @property digest The digest of its files (`sha256:` over its sorted file lines).
     */
    @Serializable
    data class Item(val name: String, val version: String, val digest: String)

    /** The line. */
    fun encode(): String = CODEC.encodeToString(serializer(), this)

    /** The kinds of lines and results. */
    companion object {
        const val HELLO = "hello"
        const val MANIFEST = "manifest"
        const val NEED = "need"
        const val READY = "ready"
        const val BEGIN = "begin"
        const val FILE = "file"
        const val COMMIT = "commit"
        const val END = "end"
        const val RESULT = "result"
        const val DONE = "done"
        const val INSTALLED = "installed"
        const val UNCHANGED = "unchanged"
        const val CONFLICT = "conflict"
        const val FAILED = "failed"
        const val REMOVED = "removed"

        /** The status of a file's last chunk. */
        const val LAST = "last"

        /** The raw bytes of one chunk: a base64 JSON line well under a frame. */
        const val CHUNK_BYTES = 192 * 1024

        private val CODEC = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        /**
         * Reads a line.
         *
         * @param text The line.
         *
         * @return The message, or null when it is not one.
         */
        fun decode(text: String): InstallMessage? = try {
            CODEC.decodeFromString(serializer(), text)
        } catch (_: IllegalArgumentException) {
            // SerializationException included: it extends IllegalArgumentException.
            null
        }
    }
}

/**
 * Installs the delivered items into one folder, under the agent's own uid (the installer runs in its container): the
 * owner's skills in the agent's personal skills folder, or the team hooks in `~/.ostrom/hooks`. TeamCode's
 * `SkillInstaller` (cli/…/cloud/agent/Skills.kt).
 *
 * - An installed item carries a marker file ([marker]: its digest, then its version). A folder of the same name without
 *   one is the member's own: it is left alone and reported as a conflict.
 * - A marked folder the manifest no longer lists (unassigned, unpinned, retired) is removed, so a transfer or a
 *   revoked decision leaves no copy behind.
 * - Each item is checked against its digest, written in a staging folder beside [root], then moved in.
 *
 * @param root The folder.
 * @param fileSystem The file system.
 * @param makeExecutable Makes the files of mode `executable` executable.
 * @param entryFile A file every item must hold (`SKILL.md`), or null.
 * @param marker The marker's name.
 * @param alwaysReplace Installs every listed item again instead of trusting a matching marker: hooks sit in a folder
 *   the agent can write, so each start puts back the verified version.
 */
// The folder and how this kind of item is installed, named at each call site; one function per line of the exchange.
@Suppress("LongParameterList", "TooManyFunctions")
class DeliveryInstaller(
    private val root: Path,
    private val fileSystem: FileSystem,
    private val makeExecutable: (List<Path>) -> Unit,
    private val entryFile: String? = SKILL_FILE,
    private val marker: String = SKILL_MARKER,
    private val alwaysReplace: Boolean = false,
) {
    private class Staged {
        val files = LinkedHashMap<String, ByteArray>()
        val modes = HashMap<String, String>()
        var current: Pair<String, Buffer>? = null
    }

    private val staging = root.resolve("..", normalize = true) / "$STAGING_PREFIX${root.name}"
    private val staged = LinkedHashMap<String, Staged>()
    private val failed = LinkedHashSet<String>()
    private val committed = LinkedHashSet<String>()
    private val results = mutableListOf<InstallMessage>()
    private var manifest: Map<String, InstallMessage.Item> = emptyMap()
    private var begun: String? = null
    private var totalBytes = 0L

    /**
     * Handles one line of the runner.
     *
     * @param message The line.
     *
     * @return The lines to answer.
     */
    fun handle(message: InstallMessage): List<InstallMessage> = when (message.t) {
        InstallMessage.MANIFEST -> manifest(message.items)
        InstallMessage.BEGIN -> begin(message.name)
        InstallMessage.FILE -> file(message)
        InstallMessage.COMMIT -> commit(message)
        InstallMessage.END -> finish()
        else -> emptyList()
    }

    private fun markerOf(folder: Path) = folder / marker

    private fun digestOf(folder: Path): String? = try {
        fileSystem.read(markerOf(folder)) { readUtf8() }.lineSequence().first().trim()
    } catch (_: IOException) {
        null
    }

    private fun manifest(items: List<InstallMessage.Item>): List<InstallMessage> {
        fileSystem.deleteRecursively(staging, mustExist = false)
        manifest = items.filter { NAME.matches(it.name) }.associateBy { it.name }
        val needs = mutableListOf<InstallMessage>()
        for (item in manifest.values) {
            val folder = root / item.name
            when {
                !fileSystem.exists(folder) -> needs += InstallMessage(InstallMessage.NEED, name = item.name)
                !fileSystem.exists(markerOf(folder)) -> results += result(item.name, InstallMessage.CONFLICT)
                !alwaysReplace && digestOf(folder) == item.digest ->
                    results += result(item.name, InstallMessage.UNCHANGED)
                else -> needs += InstallMessage(InstallMessage.NEED, name = item.name)
            }
        }
        return needs + InstallMessage(InstallMessage.READY)
    }

    private fun begin(name: String?): List<InstallMessage> {
        begun = name?.takeIf { it in manifest && it !in staged && it !in failed }
        begun?.let { staged[it] = Staged() }
        return emptyList()
    }

    @Suppress("ReturnCount") // Not begun, a line that breaks the item, or a chunk taken.
    private fun file(message: InstallMessage): List<InstallMessage> {
        val name = begun ?: return emptyList()
        val item = staged.getValue(name)
        val path = message.path?.takeIf(DeliveredItems::validPath)
        val chunk = message.data?.decodeBase64()?.toByteArray()
        if (path == null || chunk == null) return fail(name)
        totalBytes += chunk.size
        if (totalBytes > MAX_TOTAL_BYTES || item.files.size >= MAX_FILES) return fail(name)
        val current = item.current?.takeIf { it.first == path }
            ?: (path to Buffer()).also {
                item.current = it
                item.modes[path] = message.mode ?: DeliveredItems.FILE
            }
        current.second.write(chunk)
        if (message.status == InstallMessage.LAST) {
            item.files[path] = current.second.readByteArray()
            item.current = null
        }
        return emptyList()
    }

    private fun fail(name: String): List<InstallMessage> {
        staged.remove(name)
        failed += name
        begun = null
        return emptyList()
    }

    @Suppress("ReturnCount") // Not begun, refused, or staged.
    private fun commit(message: InstallMessage): List<InstallMessage> {
        val name = begun ?: return emptyList()
        val item = staged.getValue(name)
        val expected = manifest.getValue(name)
        val lines = item.files.entries.sortedBy { it.key }.joinToString("") { (path, bytes) ->
            val sha256 = bytes.toByteString().sha256().hex()
            DeliveredItems.line(path, item.modes.getValue(path), bytes.size.toLong(), sha256)
        }
        val sound = item.files.isNotEmpty() && DeliveredItems.digestOf(lines) == expected.digest &&
            message.digest == expected.digest && (entryFile == null || entryFile in item.files)
        if (!sound || !stage(name, item, expected)) return fail(name)
        committed += name
        begun = null
        return emptyList()
    }

    /** Writes [item] in its staging folder; false when it could not. */
    private fun stage(name: String, item: Staged, expected: InstallMessage.Item): Boolean = try {
        val folder = staging / name
        fileSystem.createDirectories(folder)
        for ((path, bytes) in item.files) {
            val target = folder / path
            fileSystem.createDirectories(target.resolve("..", normalize = true))
            fileSystem.write(target) { write(bytes) }
        }
        fileSystem.write(markerOf(folder)) { writeUtf8("${expected.digest}\n${expected.version}\n") }
        val executables = item.modes.filterValues { it == DeliveredItems.EXECUTABLE }.keys.sorted().map { folder / it }
        if (executables.isNotEmpty()) makeExecutable(executables)
        item.files.clear()
        true
    } catch (_: IOException) {
        false
    }

    private fun finish(): List<InstallMessage> {
        if (fileSystem.metadataOrNull(root)?.isDirectory == true) {
            for (child in fileSystem.list(root)) {
                val ours = fileSystem.metadata(child).isDirectory && fileSystem.exists(markerOf(child))
                if (child.name !in manifest && ours) {
                    fileSystem.deleteRecursively(child, mustExist = false)
                    results += result(child.name, InstallMessage.REMOVED)
                }
            }
        }
        // An item begun but never committed leaves its installed version as it is.
        for (name in staged.keys) results += result(name, if (name in committed) swap(name) else InstallMessage.FAILED)
        for (name in failed) results += result(name, InstallMessage.FAILED)
        fileSystem.deleteRecursively(staging, mustExist = false)
        return results + InstallMessage(InstallMessage.DONE)
    }

    /** Moves a staged item in place of its former version. */
    private fun swap(name: String): String {
        val folder = root / name
        return try {
            fileSystem.createDirectories(root)
            fileSystem.deleteRecursively(folder, mustExist = false)
            fileSystem.atomicMove(staging / name, folder)
            InstallMessage.INSTALLED
        } catch (_: IOException) {
            InstallMessage.FAILED
        }
    }

    private fun result(name: String, status: String) =
        InstallMessage(InstallMessage.RESULT, name = name, status = status)

    /** The kinds of items, and the bounds of one installation. */
    companion object {
        /** The file every skill holds. */
        const val SKILL_FILE = "SKILL.md"

        /** The marker of an installed skill, the same as `ostrom run`'s (`TeamSkills` of the launcher). */
        const val SKILL_MARKER = ".ostrom-skill"

        /** The marker of an installed team hook. */
        const val HOOK_MARKER = ".ostrom-hook"

        /** The bytes one installation takes at most. */
        const val MAX_TOTAL_BYTES = 128L * 1024 * 1024

        /** The files one item holds at most. */
        const val MAX_FILES = DeliveredItems.MAX_FILES

        private const val STAGING_PREFIX = ".ostrom-staging-"
        private val NAME = Regex("[a-z0-9]+(-[a-z0-9]+){0,31}")
    }
}

/** The hidden `ostrom cloud install-skills|install-hooks`: where it installs, and its exchange with the runner. */
object CloudInstall {
    /** Where cloud sessions install the team hooks, under the agent's home. */
    const val HOOKS_FOLDER = ".ostrom/hooks"

    /**
     * The installer of [command] for [agent].
     *
     * @param command [CloudProcess.INSTALL_SKILLS] or [CloudProcess.INSTALL_HOOKS].
     * @param agent The agent's command (`claude`, `codex`).
     * @param environment The installer's environment (`HOME`, `CLAUDE_CONFIG_DIR`, `CODEX_HOME`).
     * @param fileSystem The file system.
     * @param makeExecutable Makes files executable.
     *
     * @return The installer, or null for an agent that gets none, or without a home.
     */
    fun installer(
        command: String,
        agent: String?,
        environment: Map<String, String>,
        fileSystem: FileSystem,
        makeExecutable: (List<Path>) -> Unit,
    ): DeliveryInstaller? {
        val home = environment["HOME"].orEmpty().trim().trimEnd('/')
        if (home.isEmpty()) return null
        val codex = environment["CODEX_HOME"].orEmpty().trim().trimEnd('/').ifEmpty { "$home/.codex" }
        val claude = environment["CLAUDE_CONFIG_DIR"].orEmpty().trim().trimEnd('/').ifEmpty { "$home/.claude" }
        return when {
            command == CloudProcess.INSTALL_HOOKS && agent == CLAUDE -> DeliveryInstaller(
                "$home/$HOOKS_FOLDER".toPath(),
                fileSystem,
                makeExecutable,
                entryFile = null,
                marker = DeliveryInstaller.HOOK_MARKER,
                alwaysReplace = true,
            )
            command != CloudProcess.INSTALL_SKILLS -> null
            agent == CLAUDE -> DeliveryInstaller("$claude/skills".toPath(), fileSystem, makeExecutable)
            agent == CODEX -> DeliveryInstaller("$codex/skills".toPath(), fileSystem, makeExecutable)
            else -> null
        }
    }

    /**
     * Speaks with the runner until its [InstallMessage.END]: says hello, then answers each line.
     *
     * @param installer The installer.
     * @param readLine Reads a line of the runner, null at the end.
     * @param writeLine Writes a line to the runner.
     *
     * @return Whether the runner ended the exchange; when it left before, nothing was moved in.
     */
    fun serve(installer: DeliveryInstaller, readLine: () -> String?, writeLine: (String) -> Unit): Boolean {
        writeLine(InstallMessage(InstallMessage.HELLO).encode())
        while (true) {
            val line = readLine() ?: return false
            val message = InstallMessage.decode(line.trimEnd('\r')) ?: continue
            for (reply in installer.handle(message)) writeLine(reply.encode())
            if (message.t == InstallMessage.END) return true
        }
    }

    /** The agents that get skills, and the one that gets team hooks. */
    const val CLAUDE = "claude"

    /** Codex gets skills, never team hooks: it runs only hooks a person trusted. */
    const val CODEX = "codex"
}
