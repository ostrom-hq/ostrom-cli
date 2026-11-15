// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode's service as it is (copy first, polish later): its long lines and dense functions stay.
@file:Suppress(
    "MaxLineLength",
    "MagicNumber",
    "TooManyFunctions",
    "LongParameterList",
)

package run.ostrom.cli.service

import com.github.ajalt.clikt.core.CliktError
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toPath
import run.ostrom.cli.platform.quoteWindowsArgument
import run.ostrom.cli.platform.secureRandomBytes

/** The JSON of `service.json`: written with its defaults, read leniently (a field of a later version is ignored). */
internal val serviceJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/**
 * `service.json`: what the OS registration of one configuration is made of. Private to the user, next to the
 * configuration file; it holds no credential.
 *
 * @property id Random, in the registration's label, so isolated configurations never control each other's service.
 * @property executable The absolute path the service manager starts, `service serve --config <config>`.
 * @property config The absolute path of the configuration file (`config.toml`) the service belongs to.
 * @property registration Where the OS registration is written: a plist, a systemd unit or a Task Scheduler XML.
 * @property os `macos`, `linux` or `windows`.
 * @property userId The numeric user id, or the Windows SID.
 * @property atBoot Whether it starts before login (a LaunchDaemon, linger, a boot task) instead of at login.
 */
@Serializable
internal class ServiceSettings(
    val id: String,
    val executable: String,
    val config: String,
    val registration: String,
    val os: String,
    val userId: String,
    val atBoot: Boolean = false,
    val userName: String? = null,
    val userHome: String? = null,
) {
    init {
        require(Regex("[a-f0-9]{32}").matches(id))
        listOf(executable, config, registration).forEach { path ->
            require(path.toPath().isAbsolute && path.none { it.code < 32 || it.code == 127 })
        }
        if (atBoot && os == "macos") {
            require(registration == "/Library/LaunchDaemons/$SERVICE_LABEL_PREFIX$id.plist")
            require(userId != "0" && userName?.let { Regex("[a-zA-Z_][a-zA-Z0-9_.-]{0,63}").matches(it) } == true)
            require(userHome != null && userHome.toPath().isAbsolute && userHome.none { it.code < 32 || it.code == 127 })
        }
        require(os in setOf("macos", "linux", "windows"))
        require(
            if (os == "windows") {
                Regex("S-1-[0-9-]{1,180}").matches(userId)
            } else {
                userId.isNotEmpty() && userId.all { it.isDigit() }
            },
        )
    }

    /** The launchd label, the systemd unit name and the Task Scheduler task name. */
    val label: String get() = "$SERVICE_LABEL_PREFIX$id"

    override fun toString() = "ServiceSettings(id=$id)"
}

/** The start of every label: `run.ostrom.<id>`. */
internal const val SERVICE_LABEL_PREFIX = "run.ostrom."

/** The environment variable that points the CLI to its folder (`Environment.configDirectory`). */
internal const val HOME_VARIABLE = "OSTROM_HOME"

internal fun servicePath(config: String) = config.toPath().parent!!.resolve("service.json").toString()
internal fun serviceLogPath(config: String) = config.toPath().parent!!.resolve("service.log").toString()
internal fun serviceStopPath(config: String) = config.toPath().parent!!.resolve("service.stop").toString()

/** One lock per configuration, held by the running service ([run.ostrom.cli.platform.holdExclusiveLock]). */
internal fun daemonLockPath(config: String) = config.toPath().parent!!.resolve("daemon.lock").toString()

internal fun randomHex(bytes: Int) = secureRandomBytes(bytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

internal fun serviceXml(value: String) =
    value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

/** systemd's executable token has stricter escaping rules than subsequent arguments. */
internal fun systemdCommand(arguments: List<String>): String {
    fun quote(value: String) =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("%", "%%").replace("$", "$$") + "\""
    val unsafe = arguments.first().any { it in setOf('\'', '"', '\\', '$') }
    val values = if (unsafe) listOf("/usr/bin/env", "--") + arguments else arguments
    return values.joinToString(" ", transform = ::quote)
}

/**
 * Reads `service.json` of [config], or null when there is none. A boot registration must name the account that runs
 * this process ([identity]).
 */
internal fun loadService(config: String, identity: () -> ServicePlatform = ::servicePlatform): ServiceSettings? {
    val path = servicePath(config).toPath()
    val meta = FileSystem.SYSTEM.metadataOrNull(path) ?: return null
    require(isPlainFile(meta, MAX_FILE_BYTES))
    return serviceJson.decodeFromString<ServiceSettings>(FileSystem.SYSTEM.read(path) { readUtf8() }).also {
        if (it.config != config) throw CliktError("The service belongs to a different configuration.")
        if (it.atBoot) requireBootServiceIdentity(it, identity())
    }
}

private const val MAX_FILE_BYTES = 65_536L

/** The arguments the service manager starts the executable with. */
internal fun serviceArguments(config: String) = listOf("service", "serve", "--config", config)

internal fun serviceDefinition(settings: ServiceSettings): String {
    fun xml(value: String) = serviceXml(value)
    val arguments = listOf(settings.executable) + serviceArguments(settings.config)
    // systemd rejects quotes/backslashes in the executable token even after unquoting.
    // env execs the absolute path as an argument, preserving PID and literal $ expansion.
    return when (settings.os) {
        "windows" -> windowsTask(settings, arguments, ::xml)
        "macos" -> launchdPlist(settings, arguments, ::xml)
        else -> """
            [Unit]
            Description=Ostrom local service
            StartLimitIntervalSec=0

            [Service]
            Type=exec
            ExecStart=${systemdCommand(arguments)}
            Restart=on-failure
            RestartSec=30
            TimeoutStopSec=660
            UMask=0077
            NoNewPrivileges=yes
            StandardOutput=null
            StandardError=null

            [Install]
            WantedBy=default.target
        """.trimIndent() + "\n"
    }
}

private fun windowsTask(settings: ServiceSettings, arguments: List<String>, xml: (String) -> String): String {
    val trigger = if (settings.atBoot) {
        "<BootTrigger><Enabled>true</Enabled></BootTrigger>"
    } else {
        "<LogonTrigger><Enabled>true</Enabled><UserId>${xml(settings.userId)}</UserId></LogonTrigger>"
    }
    val logon = if (settings.atBoot) "Password" else "InteractiveToken"
    return """
        <?xml version="1.0" encoding="UTF-16"?>
        <Task version="1.4" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
        <RegistrationInfo><Description>Ostrom local service</Description></RegistrationInfo>
        <Triggers>$trigger</Triggers>
        <Principals><Principal id="OstromUser"><UserId>${xml(settings.userId)}</UserId><LogonType>$logon</LogonType><RunLevel>LeastPrivilege</RunLevel></Principal></Principals>
        <Settings>
        <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
        <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
        <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
        <AllowHardTerminate>false</AllowHardTerminate>
        <StartWhenAvailable>true</StartWhenAvailable>
        <RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable>
        <AllowStartOnDemand>true</AllowStartOnDemand><Enabled>true</Enabled><Hidden>false</Hidden>
        <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
        <RestartOnFailure><Interval>PT1M</Interval><Count>255</Count></RestartOnFailure>
        </Settings>
        <Actions Context="OstromUser"><Exec><Command>${xml(settings.executable)}</Command>
        <Arguments>${xml(arguments.drop(1).joinToString(" ", transform = ::quoteWindowsArgument))}</Arguments></Exec></Actions>
        </Task>
    """.trimIndent() + "\n"
}

private fun launchdPlist(settings: ServiceSettings, arguments: List<String>, xml: (String) -> String): String {
    // userName and userHome are never null at boot: ServiceSettings requires them.
    val boot = if (settings.atBoot) {
        "<key>UserName</key><string>${xml(settings.userName.toString())}</string>" +
            "<key>EnvironmentVariables</key><dict><key>HOME</key><string>${xml(settings.userHome.toString())}</string>" +
            "<key>$HOME_VARIABLE</key><string>${xml(settings.config.toPath().parent!!.toString())}</string></dict>"
    } else {
        ""
    }
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0"><dict>
        <key>Label</key><string>${settings.label}</string>
        <key>ProgramArguments</key><array>${arguments.joinToString("") { "<string>${xml(it)}</string>" }}</array>
        ${boot}<key>RunAtLoad</key><true/>
        <key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>
        <key>ThrottleInterval</key><integer>30</integer>
        <key>ExitTimeOut</key><integer>660</integer>
        <key>ProcessType</key><string>Standard</string>
        <key>StandardOutPath</key><string>/dev/null</string>
        <key>StandardErrorPath</key><string>/dev/null</string>
        </dict></plist>
    """.trimIndent() + "\n"
}

/** Only a byte-for-byte matching registration may be managed or removed. */
internal fun ownsRegistration(settings: ServiceSettings): Boolean {
    val path = settings.registration.toPath()
    val meta = FileSystem.SYSTEM.metadataOrNull(path) ?: return false
    require(isPlainFile(meta, MAX_FILE_BYTES))
    if (!FileSystem.SYSTEM.read(path) { readByteArray() }.contentEquals(registrationBytes(settings))) {
        throw CliktError("The service registration was modified externally; refusing to replace or remove it.")
    }
    return true
}

internal fun writeRegistration(settings: ServiceSettings) {
    val path = settings.registration.toPath()
    val fs = FileSystem.SYSTEM
    require(fs.metadataOrNull(path.parent!!)?.symlinkTarget == null)
    fs.createDirectories(path.parent!!)
    if (ownsRegistration(settings)) return
    // Do not chmod a shared LaunchAgents/systemd directory. The manifest contains no credentials.
    fs.write(path, mustCreate = true) { write(registrationBytes(settings)) }
}

/** Task Scheduler consumes a Unicode XML document; match its declaration and BOM. */
internal fun registrationBytes(settings: ServiceSettings): ByteArray {
    val xml = serviceDefinition(settings)
    if (settings.os != "windows") return xml.encodeToByteArray()
    return Buffer().apply {
        writeShortLe(0xfeff)
        xml.forEach { writeShortLe(it.code) }
    }.readByteArray()
}
