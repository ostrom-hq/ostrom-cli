// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import run.ostrom.cli.BuildInfo
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.ExitCode
import run.ostrom.cli.platform.Environment
import run.ostrom.cli.platform.OperatingSystem
import run.ostrom.cli.platform.CaptureResult
import run.ostrom.cli.update.AutomaticUpdateCommand
import run.ostrom.cli.update.AutomaticUpdateScheduleImpl
import run.ostrom.cli.update.ServerApiVersion
import run.ostrom.cli.update.automaticUpdatePlatform
import run.ostrom.cli.update.UpdateRequest
import kotlinx.coroutines.runBlocking

/** `ostrom update [--check] [--rollback] [--version V]`: the signed self-update (CLI.md `ostrom update`). */
internal class UpdateCommand(private val dependencies: CliDependencies) : CliktCommand(name = "update") {
    override fun help(context: Context) = "Update ostrom (signed release)"

    val check by option("--check", help = "Only say whether an update exists").flag()
    val rollback by option("--rollback", help = "Go back to the previous version").flag()
    val version by option("--version", help = "Install this version")
    val json by option("--json", help = "With --check: one JSON line (current and latest version, update available)")
        .flag()
    val notifications by option(
        "--notifications",
        help = "Turn the daily update notice on or off for this device, or show it",
    ).choice("on", "off", "status")

    val noService by option(
        "--no-service",
        help = "Do not check the local service (this device's daemon); OSTROM_NO_SERVICE does the same",
    ).flag()

    val automatic by option(
        "--automatic",
        help = "Turn the hourly automatic update of this managed installation on or off, show it, or remove it",
    ).choice("on", "off", "status", "uninstall")
    val root by option("--root", help = "With --automatic: the managed installation's folder, instead of this one's")

    // what the hourly job of --automatic runs: one attempt, when the automatic updates are on
    val automaticRun by option("--automatic-run", hidden = true).flag()

    // plumbing of the update notice, started detached once a day at most: records the latest release, says nothing
    val recordLatest by option("--record-latest", hidden = true).flag()

    override fun run() {
        if (automaticOptions()) return automaticUpdates()
        if (notifications != null) {
            if (listOf(rollback, check, json, recordLatest, version != null).any { it }) {
                throw UsageError("--notifications takes no other option")
            }
            return notificationsSetting()
        }
        if (json && !check) throw UsageError("--json only applies to --check")
        if (recordLatest && listOf(rollback, check, version != null).any { it }) {
            throw UsageError("--record-latest is the update notice's own refresh: it takes no other option")
        }
        if (rollback && (check || version != null)) {
            throw UsageError("--rollback goes back to the version kept: it takes neither --check nor --version")
        }
        val updates = dependencies.selfUpdate
        val environment = dependencies.environment
        val request = UpdateRequest(
            current = BuildInfo.VERSION,
            target = releaseTarget(environment),
            executable = dependencies.executable,
            releases = releasesUrl(environment),
            version = version,
            checkOnly = check,
            json = json,
        )
        if (recordLatest) {
            // without a CLI folder there is nothing to record: the release is not even asked for
            val notice = dependencies.updateNotice ?: throw ProgramResult(ExitCode.OK.code)
            notice.record(runBlocking { updates.latest(request) })
            throw ProgramResult(ExitCode.OK.code)
        }
        val code = runBlocking {
            val api = apiVersionOf(dependencies)
            if (rollback) updates.rollback(request, api) else updates.update(request, api)
        }
        throw ProgramResult(withServiceStep(code))
    }

    /** [code], once the service step has said what it did: the update is done and kept, whatever the step says. */
    private fun withServiceStep(code: Int): Int {
        if (code == ExitCode.OK.code && !check) dependencies.reportServiceStep(noService)
        return code
    }

    /** True when automatic updates are asked for, alone; refuses them mixed with another operation. */
    private fun automaticOptions(): Boolean {
        val asked = automatic != null || automaticRun
        val mixed = when {
            listOf(notifications != null, automatic != null, automaticRun).count { it } > 1 ->
                "Choose one of --notifications, --automatic and --automatic-run"
            asked && listOf(rollback, check, json, recordLatest, version != null).any { it } ->
                "Automatic updates take no other option than --root"
            !asked && root != null -> "--root only applies to --automatic"
            else -> null
        }
        if (mixed != null) throw UsageError(mixed)
        return asked
    }

    /** `--automatic on|off|status|uninstall`, and the scheduled attempt of `--automatic-run`. */
    private fun automaticUpdates(): Nothing {
        val updates = dependencies.selfUpdate
        val environment = dependencies.environment
        val request = UpdateRequest(
            current = BuildInfo.VERSION,
            target = releaseTarget(environment),
            executable = dependencies.executable,
            releases = releasesUrl(environment),
        )
        val command = AutomaticUpdateCommand(
            installations = updates.installations,
            root = root,
            executable = dependencies.executable,
            platform = automaticUpdatePlatform(environment, dependencies.userId),
            environment = environment,
            schedule = { store ->
                val systemRoot = environment["SystemRoot"] ?: "C:\\Windows"
                AutomaticUpdateScheduleImpl(store.fileSystem, systemRoot) { name, arguments ->
                    when (val ran = dependencies.capture.capture(listOf(name) + arguments, MANAGER_MILLIS)) {
                        is CaptureResult.Exited -> ran.code
                        else -> -1
                    }
                }
            },
            fetch = { updates.releaseCheck(request) },
            install = { store, candidate, settings ->
                val api = apiVersionOf(dependencies, settings.configuration)
                updates.automaticInstall(store, request, candidate, api)
            },
            say = { dependencies.console.out("$it\n") },
        )
        val operation = automatic
        val code = runBlocking { if (operation == null) command.attempt() else command.configure(operation) }
        throw ProgramResult(code)
    }

    /** `--notifications on|off|status`: the daily notice (it also stops for any OSTROM_DISABLE_AUTOUPDATE). */
    private fun notificationsSetting() {
        val notice = dependencies.updateNotice
        if (notice == null) {
            dependencies.console.err("ostrom: there is no CLI folder here to keep the setting in.\n")
            throw ProgramResult(ExitCode.CANNOT_WRITE.code)
        }
        if (notifications != STATUS) notice.setNotifications(notifications == ON)
        val console = dependencies.console
        console.out("Update notifications: ${if (notice.notificationsEnabled()) "on" else "off"}.\n")
        if (!dependencies.environment["OSTROM_DISABLE_AUTOUPDATE"].isNullOrEmpty()) {
            console.out("Environment override: OSTROM_DISABLE_AUTOUPDATE turns the notice off in this process.\n")
        }
    }
}

/** Asks [serverApiVersion] of [dependencies], once a binary is to be put in place. */
internal fun apiVersionOf(
    dependencies: CliDependencies,
    directory: String? = dependencies.environment.configDirectory,
): suspend () -> Int = { serverApiVersion(dependencies, directory) }

/**
 * The API version of the server the CLI folder [directory] is signed in to, which the binary to put in place must
 * speak.
 */
internal suspend fun serverApiVersion(dependencies: CliDependencies, directory: String?): Int {
    val signedIn = directory?.let { folder ->
        val serverUrl = dependencies.serverOf(null, folder)
        // the device's token never travels in clear, not even for this read
        serverUrl to if (inClear(serverUrl)) null else dependencies.credentials(folder).load(serverUrl)?.token
    }
    val read = ServerApiVersion(dependencies.capabilities) { dependencies.console.out("$it\n") }
    return read(signedIn?.first, signedIn?.second)
}

/** The release target of this binary, as the release names its archives (`linux-x64`, `macos-arm64`…). */
internal fun releaseTarget(environment: Environment): String {
    val system = when (environment.operatingSystem) {
        OperatingSystem.MACOS -> "macos"
        OperatingSystem.LINUX -> "linux"
        OperatingSystem.WINDOWS -> "windows"
    }
    return "$system-${environment.architecture}"
}

/**
 * Where releases are published: `OSTROM_RELEASES_URL` when set (a mirror, the end-to-end tests), else the GitHub
 * releases of ostrom-cli on `OSTROM_GITHUB_WEB_URL` (GitHub Enterprise), else github.com.
 */
internal fun releasesUrl(environment: Environment): String = environment["OSTROM_RELEASES_URL"]
    ?: "${(environment["OSTROM_GITHUB_WEB_URL"] ?: "https://github.com").trimEnd('/')}/ostrom-hq/ostrom-cli/releases"

private const val ON = "on"

/** How long the system's scheduler may take to answer. */
private const val MANAGER_MILLIS = 15_000
private const val STATUS = "status"
