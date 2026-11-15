// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.update

import okio.Buffer
import okio.Path
import okio.Path.Companion.toPath
import run.ostrom.cli.platform.windowsCommandLine

/**
 * The definitions of the hourly user job of [settings], by path (TeamCode's `automaticUpdateDefinitions`): a systemd
 * user timer and its service on Linux, a LaunchAgent on macOS, a Task Scheduler task (UTF-16 LE with its BOM) on
 * Windows. Each runs the stable launcher with the automatic attempt of its installation, and holds no token or login.
 */
internal fun automaticUpdateDefinitions(settings: AutomaticUpdateSettings): Map<Path, ByteArray> {
    val root = (settings.executable.toPath() / ".." / "..").normalized().toString()
    val arguments = listOf(settings.executable, "update", "--automatic-run", "--root", root)
    val primary = settings.registration.toPath()
    return when (settings.os) {
        LINUX -> mapOf(
            primary to timer(settings).encodeToByteArray(),
            (primary / ".." / "${settings.label}.service").normalized() to service(arguments).encodeToByteArray(),
        )
        MACOS -> mapOf(primary to launchAgent(settings, arguments).encodeToByteArray())
        else -> mapOf(primary to utf16(task(settings, arguments)))
    }
}

private fun timer(settings: AutomaticUpdateSettings) = """
    [Unit]
    Description=Ostrom automatic update schedule
    [Timer]
    OnCalendar=hourly
    Persistent=true
    RandomizedDelaySec=60
    Unit=${settings.label}.service
    [Install]
    WantedBy=timers.target
""".trimIndent() + "\n"

private fun service(arguments: List<String>) = """
    [Unit]
    Description=Ostrom signed automatic update
    [Service]
    Type=oneshot
    ExecStart=${systemdCommand(arguments)}
    UMask=0077
    NoNewPrivileges=yes
    StandardOutput=null
    StandardError=null
""".trimIndent() + "\n"

private fun launchAgent(settings: AutomaticUpdateSettings, arguments: List<String>) = """
    <?xml version="1.0" encoding="UTF-8"?>
    <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
    <plist version="1.0"><dict>
    <key>Label</key><string>${settings.label}</string>
    <key>ProgramArguments</key><array>${arguments.joinToString("") { "<string>${xml(it)}</string>" }}</array>
    <key>RunAtLoad</key><true/>
    <key>StartInterval</key><integer>3600</integer>
    <key>ProcessType</key><string>Background</string>
    <key>StandardOutPath</key><string>/dev/null</string>
    <key>StandardErrorPath</key><string>/dev/null</string>
    </dict></plist>
""".trimIndent() + "\n"

/**
 * The task runs as the user who registers it: its principal names no `UserId` (TeamCode writes the user's SID there),
 * and an interactive token, so no password is ever stored.
 */
private fun task(settings: AutomaticUpdateSettings, arguments: List<String>) = """
    <?xml version="1.0" encoding="UTF-16"?>
    <Task version="1.4" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
    <RegistrationInfo><Description>Ostrom signed automatic update</Description></RegistrationInfo>
    <Triggers><TimeTrigger><Repetition><Interval>PT1H</Interval><StopAtDurationEnd>false</StopAtDurationEnd></Repetition><StartBoundary>2020-01-01T00:00:00</StartBoundary></TimeTrigger></Triggers>
    <Principals><Principal id="OstromUser"><LogonType>InteractiveToken</LogonType><RunLevel>LeastPrivilege</RunLevel></Principal></Principals>
    <Settings><MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy><DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries><StopIfGoingOnBatteries>false</StopIfGoingOnBatteries><AllowHardTerminate>false</AllowHardTerminate><StartWhenAvailable>true</StartWhenAvailable><RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable><AllowStartOnDemand>true</AllowStartOnDemand><Enabled>true</Enabled><ExecutionTimeLimit>PT0S</ExecutionTimeLimit></Settings>
    <Actions Context="OstromUser"><Exec><Command>${xml(settings.executable)}</Command><Arguments>${xml(windowsCommandLine(arguments.drop(1)))}</Arguments></Exec></Actions>
    </Task>
""".trimIndent() + "\n"

/** [text] in UTF-16 little-endian after its byte order mark, as Task Scheduler reads a task's XML. */
private fun utf16(text: String): ByteArray = Buffer().apply {
    writeShortLe(BOM)
    text.forEach { writeShortLe(it.code) }
}.readByteArray()

/** [value] escaped for an XML text node or attribute. */
internal fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .replace("\"", "&quot;").replace("'", "&apos;")

/**
 * [arguments] as systemd's `ExecStart=`: each quoted, `%` and `$` doubled. systemd refuses quotes, backslashes and `$`
 * in the executable token even once unquoted: `env` then runs the absolute path given as its argument.
 */
internal fun systemdCommand(arguments: List<String>): String {
    fun quote(value: String) =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("%", "%%").replace("$", "$$") + "\""
    val unsafe = arguments.first().any { it in "'\"\\$" }
    val values = if (unsafe) listOf("/usr/bin/env", "--") + arguments else arguments
    return values.joinToString(" ", transform = ::quote)
}

private const val BOM = 0xfeff
