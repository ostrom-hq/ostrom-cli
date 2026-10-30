package run.ostrom.cli.doctor

import run.ostrom.cli.api.OstromApi
import run.ostrom.cli.api.ServerUnreachableException
import run.ostrom.cli.launcher.Agent
import run.ostrom.cli.platform.ConfigKey
import run.ostrom.cli.platform.ConfigStore
import run.ostrom.cli.platform.Environment
import run.ostrom.cli.platform.ExecutableFinder
import run.ostrom.cli.platform.FileStore
import run.ostrom.cli.platform.FileWriteException

/** How one doctor line reads: ✓ fine, ! to do, ✗ broken. */
enum class CheckStatus(val mark: String) {
    OK("✓"),
    WARNING("!"),
    PROBLEM("✗"),
}

/** One line of `ostrom doctor`. */
data class DoctorCheck(val status: CheckStatus, val message: String)

/** Checks this device and says what to do, one line per point (spec CLI.md § 2.2, `ostrom doctor`). */
interface RunDoctorUseCase {
    /** Every check, in display order. */
    suspend operator fun invoke(): List<DoctorCheck>
}

/**
 * [RunDoctorUseCase] of the socle: the binary, the CLI folder, the sign-in, the server, git and the agents. The
 * signature, clock and pinned-key checks of the spec come with update, receipts and login.
 *
 * @param version The CLI version.
 * @param configDirectory The CLI folder, or null when no home folder is known.
 * @param config The device configuration, or null with no CLI folder.
 * @param api Builds the client of a server URL.
 */
class RunDoctorUseCaseImpl(
    private val version: String,
    private val environment: Environment,
    private val files: FileStore,
    private val finder: ExecutableFinder,
    private val configDirectory: String?,
    private val config: ConfigStore?,
    private val api: (String) -> OstromApi,
) : RunDoctorUseCase {
    override suspend fun invoke(): List<DoctorCheck> = listOf(
        DoctorCheck(CheckStatus.OK, "ostrom $version · ${environment.operatingSystem.displayName} ${environment.architecture}"),
        configFolder(),
        DoctorCheck(CheckStatus.WARNING, "Not signed in. Run ostrom login."),
        server(),
        git(),
        agents(),
    )

    private fun configFolder(): DoctorCheck {
        val directory = configDirectory ?: return DoctorCheck(CheckStatus.PROBLEM, "No home folder is known: set HOME (USERPROFILE on Windows).")
        val probe = "$directory${if (directory.contains('\\')) "\\" else "/"}.doctor"
        return try {
            files.write(probe, "ok")
            files.delete(probe)
            DoctorCheck(CheckStatus.OK, "Config folder $directory is writable")
        } catch (e: FileWriteException) {
            DoctorCheck(CheckStatus.PROBLEM, "Cannot write $directory: check its owner and permissions.")
        }
    }

    private suspend fun server(): DoctorCheck {
        val url = environment["OSTROM_URL"] ?: config?.get(ConfigKey.SERVER)
            ?: return DoctorCheck(CheckStatus.WARNING, "No server yet: ostrom login sets it.")
        val host = url.substringAfter("://").substringBefore('/')
        return try {
            val response = api(url).get("/api/health")
            if (response.status < 500) {
                DoctorCheck(CheckStatus.OK, "$host reachable (${response.millis} ms)")
            } else {
                DoctorCheck(CheckStatus.PROBLEM, "$host answers ${response.status}: the server is in trouble, try again later.")
            }
        } catch (e: ServerUnreachableException) {
            DoctorCheck(CheckStatus.PROBLEM, "$host unreachable: check your network, or the server URL ($url).")
        }
    }

    private fun git(): DoctorCheck = finder.find("git")
        ?.let { DoctorCheck(CheckStatus.OK, "git found ($it)") }
        ?: DoctorCheck(CheckStatus.PROBLEM, "git not found: install git, agents need it.")

    private fun agents(): DoctorCheck {
        val found = Agent.entries.filter { finder.find(it.executable) != null }
        if (found.isEmpty()) {
            return DoctorCheck(CheckStatus.WARNING, "No agent installed (${Agent.entries.joinToString(", ") { it.command }}). Install one, e.g. Claude Code: ${Agent.CLAUDE.installUrl}")
        }
        return DoctorCheck(CheckStatus.OK, "Agents found: ${found.joinToString(", ") { it.command }}")
    }
}
