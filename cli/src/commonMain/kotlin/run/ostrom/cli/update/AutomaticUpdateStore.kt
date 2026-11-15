// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.update

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import okio.ByteString.Companion.toByteString
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import run.ostrom.cli.launcher.objectOf
import run.ostrom.cli.launcher.parseObject
import run.ostrom.cli.launcher.text
import run.ostrom.cli.platform.Environment
import run.ostrom.cli.platform.FileStoreImpl
import run.ostrom.cli.platform.FileWriteException
import run.ostrom.cli.platform.OperatingSystem
import run.ostrom.cli.platform.secureRandomBytes

/**
 * The system and the user an automatic update belongs to (TeamCode's `ServicePlatform`): [os] is `macos`, `linux` or
 * `windows`; [userId] the numeric user id on macOS and Linux, `DOMAIN\user` on Windows, or null when unknown.
 */
internal data class AutomaticUpdatePlatform(val os: String, val userId: String?)

/**
 * The explicit local opt-in of `ostrom update --automatic on` (`automatic-update.json`): paths and identity only, never
 * a token or a login. [configuration] is the CLI folder the attempts read the server from, [executable] the stable
 * launcher the job runs, [registration] the job's definition (a LaunchAgent, a systemd timer, a task's XML).
 */
@Suppress("LongParameterList") // The recorded fields, as TeamCode's AutomaticUpdateSettings.
internal data class AutomaticUpdateSettings(
    val id: String,
    val configuration: String,
    val executable: String,
    val registration: String,
    val os: String,
    val userId: String,
    val enabled: Boolean = false,
) {
    /** The job's name: the LaunchAgent's label, the systemd units' name, the scheduled task's name. */
    val label: String get() = "$LABEL_PREFIX$id"

    init {
        require(ID.matches(id) && os in SYSTEMS)
        require(if (os == WINDOWS) WINDOWS_USER.matches(userId) else POSIX_USER.matches(userId) && userId != ROOT_USER)
        listOf(configuration, executable, registration).forEach {
            require(it.toPath().isAbsolute && it.none { c -> c.code < CONTROL_END || c.code == DELETE })
        }
        require(registration.toPath().name == label + EXTENSIONS.getValue(os))
    }

    /** As `automatic-update.json` writes it. */
    fun json(): JsonObject = objectOf(
        "schemaVersion" to SCHEMA,
        "id" to id,
        "configuration" to configuration,
        "executable" to executable,
        "registration" to registration,
        "os" to os,
        "userId" to userId,
        "enabled" to enabled,
    )

    companion object {
        /** The settings written as [json] wrote them; refused ([IllegalArgumentException]) when they do not read so. */
        fun of(json: JsonObject?): AutomaticUpdateSettings {
            requireNotNull(json)
            require(json.schema() == SCHEMA)
            return AutomaticUpdateSettings(
                requireNotNull(json.text("id")),
                requireNotNull(json.text("configuration")),
                requireNotNull(json.text("executable")),
                requireNotNull(json.text("registration")),
                requireNotNull(json.text("os")),
                requireNotNull(json.text("userId")),
                requireNotNull((json["enabled"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull),
            )
        }
    }
}

/**
 * One automatic attempt (`automatic-update-result.json`): STARTED, NO_UPDATE, UPDATED, DEFERRED or FAILED, with the
 * version installed and a diagnostic code, never a remote body. STARTED is an observation, not proof that an attempt
 * still runs.
 */
internal data class AutomaticUpdateResult(
    val attemptedAtMillis: Long,
    val outcome: String,
    val version: String? = null,
    val diagnostic: String? = null,
) {
    init {
        require(attemptedAtMillis >= 0 && outcome in OUTCOMES)
        require(version == null || SemVer.of(version) != null)
        require(diagnostic == null || DIAGNOSTIC.matches(diagnostic))
    }

    /** As `automatic-update-result.json` writes it. */
    fun json(): JsonObject = objectOf(
        "schemaVersion" to SCHEMA,
        "attemptedAtMillis" to attemptedAtMillis,
        "outcome" to outcome,
        "version" to version,
        "diagnostic" to diagnostic,
    )

    companion object {
        const val STARTED = "STARTED"
        const val NO_UPDATE = "NO_UPDATE"
        const val UPDATED = "UPDATED"
        const val DEFERRED = "DEFERRED"
        const val FAILED = "FAILED"

        /** The result written as [json] wrote it; refused ([IllegalArgumentException]) when it does not read so. */
        fun of(json: JsonObject?): AutomaticUpdateResult {
            requireNotNull(json)
            require(json.schema() == SCHEMA)
            val attempted = (json["attemptedAtMillis"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            return AutomaticUpdateResult(
                requireNotNull(attempted),
                requireNotNull(json.text("outcome")),
                json.text("version"),
                json.text("diagnostic"),
            )
        }
    }
}

private val OUTCOMES = setOf(
    AutomaticUpdateResult.STARTED,
    AutomaticUpdateResult.NO_UPDATE,
    AutomaticUpdateResult.UPDATED,
    AutomaticUpdateResult.DEFERRED,
    AutomaticUpdateResult.FAILED,
)

/**
 * The private policy and the last result of the automatic updates, kept in the root of the managed installation
 * [installation] (canonical), with their two locks: the settings' (one change at a time) and the worker's (one attempt
 * at a time).
 */
internal class AutomaticUpdateStore(installation: InstallationStore) {
    private val fileSystem = installation.fileSystem
    private val files = installation.files
    private val texts = FileStoreImpl(fileSystem)
    val paths = InstallationPaths(fileSystem.canonicalize(installation.paths.root), installation.paths.target)
    val policy = paths.root / "automatic-update.json"
    private val result = paths.root / "automatic-update-result.json"
    val workerLock = paths.root / "automatic-update-worker.lock"
    val managementLock = paths.root / "automatic-update-settings.lock"

    /** The exclusive lock of [path], or null when another process holds it. */
    fun tryLock(path: Path): AutoCloseable? = try {
        files.tryLock(path.toString(), exclusive = true)
    } catch (_: IOException) {
        refuseInstallation("release_lock_failed")
    }

    /** The opt-in, or null when there is none; refused when it is not this installation's. */
    fun settings(): AutomaticUpdateSettings? = read(policy, AutomaticUpdateSettings::of)?.also {
        if (it.executable != paths.launcher.toString()) refuseInstallation(SETTINGS_INVALID)
    }

    /** The last attempt, or null before the first. */
    fun result(): AutomaticUpdateResult? = read(result, AutomaticUpdateResult::of)

    /** Records [settings], owner-only. */
    fun save(settings: AutomaticUpdateSettings) {
        require(settings.executable == paths.launcher.toString())
        write(policy, settings.json().toString())
    }

    /** Records the attempt [value], owner-only. */
    fun record(value: AutomaticUpdateResult) = write(result, value.json().toString())

    /** Deletes the opt-in. */
    fun remove() = fileSystem.delete(policy, mustExist = false)

    private fun write(path: Path, text: String) {
        if (!files.isPrivate(paths.root.toString(), directory = true)) refuseInstallation(InstallationStore.INVALID)
        try {
            texts.write(path.toString(), text)
        } catch (_: FileWriteException) {
            refuseInstallation("release_write_failed")
        }
    }

    private fun <T> read(path: Path, decode: (JsonObject?) -> T): T? {
        val metadata = fileSystem.metadataOrNull(path) ?: return null
        // okio's metadata never follows a link: a link is not a regular file
        if (!metadata.isRegularFile || !files.isPrivate(path.toString(), directory = false)) {
            refuseInstallation(SETTINGS_INVALID)
        }
        val text = fileSystem.source(path).buffer().use { source ->
            if (source.request(MAX_BYTES + 1)) refuseInstallation(SETTINGS_INVALID)
            source.readUtf8()
        }
        return try {
            decode(parseObject(text))
        } catch (_: IllegalArgumentException) {
            refuseInstallation(SETTINGS_INVALID)
        }
    }

    companion object {
        const val SETTINGS_INVALID = "automatic_settings_invalid"
        private const val MAX_BYTES = 8192L
    }
}

/**
 * New settings (disabled) for the canonical installation [paths] of [platform]'s user: a fresh id, the CLI folder of
 * [environment], and where the job's definition goes: `~/Library/LaunchAgents` on macOS, the systemd user folder of
 * `$XDG_CONFIG_HOME` (`~/.config`) on Linux, the installation's root on Windows (the task itself is in Task Scheduler).
 */
internal fun automaticUpdateSettings(
    paths: InstallationPaths,
    platform: AutomaticUpdatePlatform,
    environment: Environment,
    id: String = secureRandomBytes(ID_BYTES).toByteString().hex(),
): AutomaticUpdateSettings {
    val configuration = environment.configDirectory ?: refuseInstallation(HOME_UNAVAILABLE)
    val home = environment.homeDirectory ?: refuseInstallation(HOME_UNAVAILABLE)
    val user = platform.userId ?: refuseInstallation(USER_UNAVAILABLE)
    val label = "$LABEL_PREFIX$id"
    val registration = when (platform.os) {
        MACOS -> "$home/Library/LaunchAgents/$label.plist"
        WINDOWS -> (paths.root / "$label.xml").toString()
        else -> "${environment["XDG_CONFIG_HOME"] ?: "$home/.config"}/systemd/user/$label.timer"
    }
    return AutomaticUpdateSettings(
        id = id,
        configuration = configuration,
        executable = paths.launcher.toString(),
        registration = registration,
        os = platform.os,
        userId = user,
    )
}

/**
 * The [AutomaticUpdatePlatform] of [environment]: the numeric [userId] on macOS and Linux, `%USERDOMAIN%\%USERNAME%`
 * on Windows (the task runs as the user who registers it).
 */
internal fun automaticUpdatePlatform(environment: Environment, userId: () -> String?): AutomaticUpdatePlatform =
    when (environment.operatingSystem) {
        OperatingSystem.MACOS -> AutomaticUpdatePlatform(MACOS, userId())
        OperatingSystem.LINUX -> AutomaticUpdatePlatform(LINUX, userId())
        OperatingSystem.WINDOWS -> {
            val name = environment["USERNAME"]
            val domain = environment["USERDOMAIN"]
            AutomaticUpdatePlatform(WINDOWS, if (name == null || domain == null) name else "$domain\\$name")
        }
    }

private fun JsonObject.schema(): Long? = (this["schemaVersion"] as? JsonPrimitive)?.longOrNull

internal const val MACOS = "macos"
internal const val LINUX = "linux"
internal const val WINDOWS = "windows"
internal const val ROOT_USER = "0"
internal const val USER_UNAVAILABLE = "release_user_unavailable"
private const val LABEL_PREFIX = "run.ostrom.updates."
private val SYSTEMS = setOf(MACOS, LINUX, WINDOWS)
private val EXTENSIONS = mapOf(MACOS to ".plist", LINUX to ".timer", WINDOWS to ".xml")
private val ID = Regex("[a-f0-9]{32}")
private val POSIX_USER = Regex("[0-9]{1,10}")
private val WINDOWS_USER = Regex("[^\\x00-\\x1f\\x7f]{1,256}")
private val DIAGNOSTIC = Regex("[a-zA-Z0-9_]{1,120}")
private const val ID_BYTES = 16
private const val SCHEMA = 1L
private const val CONTROL_END = 32
private const val DELETE = 127
