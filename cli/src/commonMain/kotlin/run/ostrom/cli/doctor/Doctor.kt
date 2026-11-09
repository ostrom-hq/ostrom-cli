// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.doctor

import run.ostrom.cli.api.OstromApi
import run.ostrom.cli.api.ServerUnreachableException
import run.ostrom.cli.capabilities.CapabilitiesRead
import run.ostrom.cli.capabilities.ServerCapabilities
import run.ostrom.cli.capabilities.incompatibility
import run.ostrom.cli.capabilities.shown
import run.ostrom.cli.launcher.Agent
import run.ostrom.cli.launcher.Credentials
import run.ostrom.cli.platform.ConfigKey
import run.ostrom.cli.platform.ConfigStore
import run.ostrom.cli.platform.Environment
import run.ostrom.cli.platform.ExecutableFinder
import run.ostrom.cli.platform.FileStore
import run.ostrom.cli.platform.FileWriteException
import run.ostrom.cli.platform.SecretStore
import run.ostrom.cli.platform.SecretStoreException
import okio.Path.Companion.toPath

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
 * @param contractVersion `info.version` of the API contract this build speaks, blank when unknown.
 * @param secrets Opens the secret store, where the device token is kept.
 * @param credentials The credentials this device holds for a server URL (no trailing slash), or null.
 * @param capabilities What a server speaks, read with those credentials once the server is reachable.
 */
@Suppress("LongParameterList") // Each check reads one dependency; grouping them would only hide which.
class RunDoctorUseCaseImpl(
    private val version: String,
    private val environment: Environment,
    private val files: FileStore,
    private val finder: ExecutableFinder,
    private val configDirectory: String?,
    private val config: ConfigStore?,
    private val api: (String) -> OstromApi,
    private val contractVersion: String,
    private val secrets: () -> SecretStore,
    private val credentials: (String) -> Credentials?,
    private val capabilities: ServerCapabilities,
) : RunDoctorUseCase {
    override suspend fun invoke(): List<DoctorCheck> {
        val url = environment["OSTROM_URL"] ?: config?.get(ConfigKey.SERVER)
        val signedIn = url?.let { signedIn(it.trimEnd('/')) }
        val server = server(url)
        // the API the server speaks, once it answers as an Ostrom server and this device can sign the read
        val spoken = if (url != null && signedIn != null && server.status != CheckStatus.PROBLEM) {
            apiCheck(url, signedIn.token)
        } else {
            null
        }
        return listOfNotNull(
            DoctorCheck(
                CheckStatus.OK,
                "ostrom $version · ${environment.operatingSystem.displayName} ${environment.architecture}",
            ),
            configFolder(),
            secretStore(),
            signedIn?.let { DoctorCheck(CheckStatus.OK, "Signed in as @${it.login}") }
                ?: DoctorCheck(CheckStatus.WARNING, "Not signed in. Run ostrom login."),
            server,
            spoken,
            git(),
            agents(),
        )
    }

    /** The credentials of [url]; a secret store that does not answer is said by its own line. */
    private fun signedIn(url: String): Credentials? = try {
        credentials(url)
    } catch (_: SecretStoreException) {
        null
    }

    /** Whether this ostrom speaks the server's API: a server it does not speak is a problem, not a warning. */
    private suspend fun apiCheck(url: String, token: String): DoctorCheck {
        val host = hostOf(url)
        return when (val read = capabilities.read(url.trimEnd('/'), token)) {
            is CapabilitiesRead.Read -> {
                val spoken = read.capabilities
                incompatibility(host, spoken, version)?.let { DoctorCheck(CheckStatus.PROBLEM, it) }
                    ?: DoctorCheck(
                        CheckStatus.OK,
                        "$host speaks the Ostrom API v${spoken.apiVersion}, as this ostrom does " +
                            "(Ostrom ${shown(spoken.serverVersion)})",
                    )
            }
            CapabilitiesRead.Unknown -> DoctorCheck(
                CheckStatus.WARNING,
                "$host does not say which Ostrom API it speaks: it is older than this ostrom. " +
                    "Ask your admin to update it.",
            )
            is CapabilitiesRead.Failed -> {
                val why = read.status?.let { "HTTP $it" } ?: "unreachable"
                DoctorCheck(
                    CheckStatus.WARNING,
                    if (read.status == UNAUTHORIZED) {
                        "$host refused this device's token: run ostrom login."
                    } else {
                        "$host did not say which Ostrom API it speaks ($why)."
                    },
                )
            }
        }
    }

    private fun configFolder(): DoctorCheck {
        val directory = configDirectory ?: return DoctorCheck(CheckStatus.PROBLEM, NO_HOME)
        val probe = (directory.toPath() / ".doctor").toString()
        return try {
            files.write(probe, "ok")
            files.delete(probe)
            DoctorCheck(CheckStatus.OK, "Config folder $directory is writable")
        } catch (_: FileWriteException) {
            DoctorCheck(CheckStatus.PROBLEM, "Cannot write $directory: check its owner and permissions.")
        }
    }

    private fun secretStore(): DoctorCheck = try {
        val backend = secrets().backend
        val warning = backend.warning
        if (warning == null) {
            DoctorCheck(CheckStatus.OK, "Secrets kept in ${backend.displayName}")
        } else {
            DoctorCheck(CheckStatus.WARNING, warning)
        }
    } catch (e: SecretStoreException) {
        DoctorCheck(CheckStatus.PROBLEM, e.message)
    }

    private suspend fun server(url: String?): DoctorCheck {
        if (url == null) return DoctorCheck(CheckStatus.WARNING, "No server yet: ostrom login sets it.")
        val host = hostOf(url)
        return try {
            healthCheck(host, url, api(url).use { it.get(HEALTH_ROUTE) }, contractVersion)
        } catch (_: ServerUnreachableException) {
            DoctorCheck(CheckStatus.PROBLEM, "$host unreachable: check your network, or the server URL ($url).")
        }
    }

    private fun git(): DoctorCheck {
        val path = finder.find("git")
            ?: return DoctorCheck(CheckStatus.PROBLEM, "git not found: install git, agents need it.")
        return DoctorCheck(CheckStatus.OK, "git found ($path)")
    }

    private fun agents(): DoctorCheck {
        val found = Agent.entries.filter { finder.find(it.executable) != null }
        if (found.isEmpty()) {
            val agents = Agent.entries.joinToString(", ") { it.command }
            return DoctorCheck(
                CheckStatus.WARNING,
                "No agent installed ($agents). Install one, e.g. Claude Code: ${Agent.CLAUDE.installUrl}",
            )
        }
        return DoctorCheck(CheckStatus.OK, "Agents found: ${found.joinToString(", ") { it.command }}")
    }
}

/** The host of a server URL, as the doctor names it. */
private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/')

private const val UNAUTHORIZED = 401

/** The liveness route of the API contract (ostrom-core `api/openapi.yaml`), which the doctor probes. */
internal const val HEALTH_ROUTE = "/api/v1/health"

/** What to do when no home folder is known, the same for doctor and config. */
internal const val NO_HOME = "No home folder is known: set HOME (USERPROFILE on Windows)."
