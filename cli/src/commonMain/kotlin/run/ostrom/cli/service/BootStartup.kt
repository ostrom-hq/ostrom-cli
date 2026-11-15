// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode's service as it is (copy first, polish later): its long lines and dense functions stay.
@file:Suppress(
    "MaxLineLength",
    "MagicNumber",
    "TooManyFunctions",
)

package run.ostrom.cli.service

import com.github.ajalt.clikt.core.CliktError
import okio.FileSystem
import okio.Path.Companion.toPath
import run.ostrom.cli.platform.EnvironmentImpl
import run.ostrom.cli.platform.ExcludeFromCoverage
import run.ostrom.cli.platform.OperatingSystem
import run.ostrom.cli.platform.ServiceAccount
import run.ostrom.cli.platform.currentExecutable
import run.ostrom.cli.platform.currentServiceAccount

/** Only fixed OS tools receive elevation; the service continues running as its configuration owner. */
internal data class ServiceManagerInvocation(val command: String, val arguments: List<String>)

/** The OS account and system this process runs as, which a saved boot registration must name. */
internal data class ServicePlatform(
    val os: String,
    val executable: String,
    val userId: String,
    val userName: String? = null,
    val userHome: String? = null,
)

/** A saved boot registration cannot redirect privileged setup to another OS account. */
internal fun requireBootServiceIdentity(settings: ServiceSettings, identity: ServicePlatform) {
    require(settings.os == identity.os && settings.userId == identity.userId)
    if (settings.os == "macos") {
        require(settings.userName == identity.userName && settings.userHome == identity.userHome)
    }
}

/** This process's system, binary and account. */
internal fun servicePlatform(
    os: String = hostOs(),
    executable: String? = currentExecutable(),
    account: ServiceAccount = currentServiceAccount(),
): ServicePlatform = ServicePlatform(
    os,
    FileSystem.SYSTEM.canonicalize(
        (executable ?: throw CliktError("Cannot locate the native Ostrom executable.")).toPath(),
    ).toString(),
    account.userId,
    account.userName,
    account.userHome,
)

/** `macos`, `linux` or `windows`. */
internal fun hostOs(): String = osNameOf(EnvironmentImpl().operatingSystem)

internal fun osNameOf(system: OperatingSystem): String = when (system) {
    OperatingSystem.MACOS -> "macos"
    OperatingSystem.WINDOWS -> "windows"
    OperatingSystem.LINUX -> "linux"
}

/** Writes the registration: the user's own file, or for a macOS boot daemon a root-owned copy through `sudo`. */
// coverage: its macOS boot branch needs the root-owned /Library/LaunchDaemons; the macOS boot smoke run covers it.
@ExcludeFromCoverage
internal suspend fun installServiceRegistration(settings: ServiceSettings, runner: ServiceRunner) {
    if (settings.atBoot && settings.os == "macos") installBootDaemon(settings, runner) else writeRegistration(settings)
}

/** The LaunchDaemon is root-owned: copied into place by `sudo install`, from a private staging file. */
// coverage: needs the root-owned /Library/LaunchDaemons and a real sudo; the macOS boot smoke run covers it.
@ExcludeFromCoverage
private suspend fun installBootDaemon(settings: ServiceSettings, runner: ServiceRunner) {
    if (ownsRegistration(settings)) return
    val directory = FileSystem.SYSTEM.metadataOrNull("/Library/LaunchDaemons".toPath())
    require(directory?.isDirectory == true && directory.symlinkTarget == null)
    val staging = settings.config.toPath().parent!!.resolve("boot-registration-${randomHex(8)}.plist")
    writePrivateFile(staging.toString(), serviceDefinition(settings))
    try {
        privilegedServiceOperation(
            ServiceManagerInvocation(
                "/usr/bin/sudo",
                listOf("/usr/bin/install", "-o", "root", "-g", "wheel", "-m", "0644", staging.toString(), settings.registration),
            ),
            runner,
        )
        if (!ownsRegistration(settings)) throw CliktError("The installed boot registration differs from this configuration.")
    } finally {
        FileSystem.SYSTEM.delete(staging, mustExist = false)
    }
}

internal suspend fun removeServiceRegistration(settings: ServiceSettings, runner: ServiceRunner) {
    require(ownsRegistration(settings))
    removeRegistrationFile(settings, runner)
}

/** A root-owned LaunchDaemon goes with `sudo rm`; any other registration is the user's own file. */
// coverage: its macOS boot branch needs the root-owned /Library/LaunchDaemons; the macOS boot smoke run covers it.
@ExcludeFromCoverage
private suspend fun removeRegistrationFile(settings: ServiceSettings, runner: ServiceRunner) {
    if (settings.atBoot && settings.os == "macos") {
        privilegedServiceOperation(ServiceManagerInvocation("/usr/bin/sudo", listOf("/bin/rm", "--", settings.registration)), runner)
    } else {
        FileSystem.SYSTEM.delete(settings.registration.toPath())
    }
}

internal suspend fun enableLinuxBootStartup(settings: ServiceSettings, runner: ServiceRunner) {
    require(settings.os == "linux" && settings.atBoot && settings.userId != "0")
    // Linger is shared user-manager policy. Never disable it during Ostrom uninstall.
    privilegedServiceOperation(
        ServiceManagerInvocation("/usr/bin/sudo", listOf("/usr/bin/loginctl", "enable-linger", settings.userId)),
        runner,
    )
}

private suspend fun privilegedServiceOperation(invocation: ServiceManagerInvocation, runner: ServiceRunner) {
    val result = runner.run(invocation.command, invocation.arguments, emptyMap(), quiet = false, timeoutMillis = AUTHORIZATION_MILLIS)
    if (result != 0) throw CliktError("OS authorization failed. Retry service install after checking administrator access.")
}

private const val AUTHORIZATION_MILLIS = 180_000L

/** Task Scheduler protects the password; it never enters Ostrom files, environment or arguments. */
internal fun windowsBootRegistrationScript(): String = """
    ${'$'}ErrorActionPreference = 'Stop'
    ${'$'}passwordPointer = [IntPtr]::Zero
    try {
        ${'$'}identity = [Security.Principal.WindowsIdentity]::GetCurrent()
        ${'$'}principal = New-Object Security.Principal.WindowsPrincipal(${'$'}identity)
        if (-not ${'$'}principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Elevation required' }
        if (${'$'}identity.User.Value -ne ${'$'}env:OSTROM_SERVICE_USER) { throw 'Different identity' }
        ${'$'}scheduler = New-Object -ComObject 'Schedule.Service'
        ${'$'}scheduler.Connect()
        ${'$'}xml = Get-Content -LiteralPath ${'$'}env:OSTROM_SERVICE_REGISTRATION -Raw
        ${'$'}secret = Read-Host 'Windows account password for startup before login (not your PIN or Ostrom password)' -AsSecureString
        ${'$'}passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR(${'$'}secret)
        ${'$'}password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR(${'$'}passwordPointer)
        ${'$'}null = ${'$'}scheduler.GetFolder('\').RegisterTask(${'$'}env:OSTROM_SERVICE_TASK, ${'$'}xml, 2, ${'$'}identity.Name, ${'$'}password, 1, ${'$'}null)
        exit 0
    } catch {
        [Console]::Error.WriteLine('Boot task registration failed. Check elevation, Windows credentials and local/domain task policy.')
        exit 2
    } finally {
        ${'$'}password = ${'$'}null
        if (${'$'}passwordPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR(${'$'}passwordPointer) }
        if (${'$'}secret) { ${'$'}secret.Dispose() }
    }
""".trimIndent()

/** Refuse control of a boot task whose effective OS identity, command or trigger changed. */
internal fun windowsBootTaskCheck(): String = """
    ${'$'}definition = ${'$'}task.Definition
    ${'$'}owner = ${'$'}definition.Principal.UserId
    if (-not ${'$'}owner.StartsWith('S-1-')) {
        ${'$'}owner = (New-Object Security.Principal.NTAccount(${'$'}owner)).Translate([Security.Principal.SecurityIdentifier]).Value
    }
    if (${'$'}owner -ne ${'$'}env:OSTROM_SERVICE_USER -or ${'$'}definition.Principal.LogonType -ne 1 -or ${'$'}definition.Principal.RunLevel -ne 0) { exit 5 }
    if (${'$'}definition.Triggers.Count -ne 1 -or ${'$'}definition.Triggers.Item(1).Type -ne 8) { exit 5 }
    if (${'$'}definition.Actions.Count -ne 1 -or ${'$'}definition.Actions.Item(1).Type -ne 0) { exit 5 }
    if (${'$'}definition.Actions.Item(1).Path -ne ${'$'}env:OSTROM_SERVICE_EXECUTABLE -or ${'$'}definition.Actions.Item(1).Arguments -cne ${'$'}env:OSTROM_SERVICE_ARGUMENTS) { exit 5 }
""".trimIndent()
