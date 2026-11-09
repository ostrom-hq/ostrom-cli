// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.telemetry

import run.ostrom.cli.platform.ConfigKey
import run.ostrom.cli.platform.ConfigStore
import run.ostrom.cli.platform.Environment
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.uuid.Uuid

/** Whether usage statistics leave this machine, and why. */
enum class UsageState(val summary: String) {
    ON("on"),
    OFF("off (the default; ostrom telemetry enable turns it on)"),
    OFF_BY_ENVIRONMENT("off (OSTROM_TELEMETRY=0 or DO_NOT_TRACK is set, whatever the setting)"),
    NOT_IN_THIS_BUILD("off (this build has no statistics destination)"),
}

/**
 * Anonymous usage statistics of the CLI, **opt-in only**: nothing is sent unless the user ran `ostrom telemetry
 * enable`, and `OSTROM_TELEMETRY=0` or `DO_NOT_TRACK=1` turn it off whatever the setting. One event per command,
 * `cli_command_run` (ostrom-core `docs/telemetry.md`): the command name among the known ones, the class of its exit
 * code, a duration bucket, the OS, the architecture and the version. Never an argument, a path, a repository, a token
 * or the account; the id is a random install id that is not linked to the account and is deleted by `disable`.
 *
 * @param environment Reads `OSTROM_TELEMETRY` and `DO_NOT_TRACK`.
 * @param config Where the choice and the install id are kept; null when the device has no home folder.
 * @param projectKey The PostHog project key compiled into this build; blank means no destination.
 * @param version The CLI version.
 * @param send Posts a body to a URL; failures are the sender's to swallow.
 * @param clock Stamps the event.
 * @param newId Makes the install id.
 */
@Suppress("LongParameterList") // Every collaborator is injected so tests control the environment, time and network.
class UsageStatistics(
    private val environment: Environment,
    private val config: ConfigStore?,
    private val projectKey: String,
    private val version: String,
    private val send: suspend (url: String, body: String) -> Unit,
    private val clock: Clock = Clock.System,
    private val newId: () -> String = { Uuid.random().toString() },
) {
    /** The current state; the environment wins over the setting. */
    fun state(): UsageState = when {
        environment["OSTROM_TELEMETRY"]?.lowercase() in DISABLING_VALUES -> UsageState.OFF_BY_ENVIRONMENT
        environment["DO_NOT_TRACK"].let { it != null && it != "0" } -> UsageState.OFF_BY_ENVIRONMENT
        projectKey.isBlank() -> UsageState.NOT_IN_THIS_BUILD
        installId() != null -> UsageState.ON
        else -> UsageState.OFF
    }

    /**
     * Turns statistics on: the install id first, then the setting, so that a failed write never leaves the setting
     * on without an id. @throws FileWriteException
     */
    fun enable(store: ConfigStore) {
        if (store.get(ConfigKey.TELEMETRY_ID) == null) store.set(ConfigKey.TELEMETRY_ID, newId())
        store.set(ConfigKey.TELEMETRY, ON)
    }

    /**
     * Turns statistics off: the install id is deleted first, so that once it is gone nothing can be sent, even if
     * writing the setting then fails. @throws FileWriteException
     */
    fun disable(store: ConfigStore) {
        store.unset(ConfigKey.TELEMETRY_ID)
        store.set(ConfigKey.TELEMETRY, OFF)
    }

    /** The install id when the setting is on: on means both, and one without the other sends nothing. */
    private fun installId(): String? = config?.takeIf { it.get(ConfigKey.TELEMETRY) == ON }?.get(ConfigKey.TELEMETRY_ID)

    /**
     * Sends one `cli_command_run` when statistics are on. Plumbing commands (called by agents, on their hot path) are
     * never measured, and neither is `ostrom telemetry`: turning statistics on sends nothing in the same breath.
     *
     * @param command The first word of the command line, kept only if it is one of [knownCommands].
     * @param knownCommands The top-level command names of the CLI.
     * @param exitCode The process exit code.
     * @param duration How long the command took.
     */
    suspend fun commandRun(command: String?, knownCommands: Set<String>, exitCode: Int, duration: Duration) {
        val installId = measuredInstallId(command) ?: return
        val name = when (command) {
            null -> "home"
            in knownCommands -> command
            else -> "unknown"
        }
        val body = buildJsonObject {
            put("api_key", projectKey)
            put(
                "batch",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("event", "cli_command_run")
                            put("distinct_id", installId)
                            put("timestamp", clock.now().toString())
                            put("properties", properties(name, exitCode, duration))
                        },
                    )
                },
            )
        }
        send(BATCH_URL, body.toString())
    }

    /**
     * The install id to send under, or null when nothing is sent: a command that is never measured, or statistics
     * off. The common case (off) costs one read of the settings and no network: checked before anything else.
     */
    private fun measuredInstallId(command: String?): String? =
        if (command !in NOT_MEASURED && state() == UsageState.ON) installId() else null

    private fun properties(command: String, exitCode: Int, duration: Duration) = buildJsonObject {
        put("command", command)
        put("exit_code_class", exitClass(exitCode))
        put("duration_ms_bucket", durationBucket(duration.inWholeMilliseconds))
        put("os", environment.operatingSystem.displayName)
        put("arch", environment.architecture)
        put("version", version)
        put("\$lib", "ostrom-cli")
        put("\$lib_version", version)
        put("\$process_person_profile", JsonPrimitive(false))
        // Anonymous means no network either: no address kept, no city or country derived from it.
        put("\$ip", JsonNull)
        put("\$geoip_disable", JsonPrimitive(true))
    }

    /** The values and endpoint. */
    companion object {
        /** PostHog Cloud EU. */
        const val BATCH_URL: String = "https://eu.i.posthog.com/batch/"

        private const val ON = "on"
        private const val OFF = "off"
        private val DISABLING_VALUES = setOf("0", "false", "off", "no")
        /** The plumbing, and `telemetry` itself: never measured. */
        internal val NOT_MEASURED = setOf("hook", "mcp", "wait", "check", "telemetry")
        private const val USAGE = 64
        private val BUCKETS = listOf(10L, 30L, 100L, 300L, 1_000L, 3_000L, 10_000L)

        /** `ok`, `usage` (64) or `error`: the class, never the code of a specific failure. */
        internal fun exitClass(code: Int): String = when (code) {
            0 -> "ok"
            USAGE -> "usage"
            else -> "error"
        }

        /** The bucket of a duration: `<10`, `10-30`, … `>=10000` milliseconds. */
        internal fun durationBucket(millis: Long): String {
            val upper = BUCKETS.indexOfFirst { millis < it }
            return when (upper) {
                -1 -> ">=${BUCKETS.last()}"
                0 -> "<${BUCKETS.first()}"
                else -> "${BUCKETS[upper - 1]}-${BUCKETS[upper]}"
            }
        }
    }
}
