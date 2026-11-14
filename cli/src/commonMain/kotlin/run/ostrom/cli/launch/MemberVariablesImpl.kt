// SPDX-License-Identifier: Apache-2.0
// A read that fails in any way starts the agent without the variables; cancellation is rethrown first
// (scripts/check-conventions.py).
@file:Suppress("TooGenericExceptionCaught")

package run.ostrom.cli.launch

import run.ostrom.cli.launcher.Agent
import run.ostrom.cli.launcher.FileAccess
import run.ostrom.cli.launcher.LaunchFiles
import run.ostrom.cli.launcher.LaunchSite
import run.ostrom.cli.launcher.MemberVariables
import run.ostrom.cli.launcher.ServerAnswer
import run.ostrom.cli.launcher.ServerJson
import run.ostrom.cli.launcher.VariablesDelivery
import run.ostrom.cli.launcher.VariablesRead
import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.join
import run.ostrom.cli.launcher.list
import run.ostrom.cli.runner.AgentEnvironment
import run.ostrom.core.api.models.ServerFeatures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.IOException

/**
 * The member's environment variables for a local launch, as TeamCode's `teamcode run`: `GET /api/v1/environment`
 * before the agent starts (`environment.v1`), never blocking it. A credential that may not read them (a service key:
 * 403) starts the agent without them; any other failure starts it without them too, and Claude Code still gets them
 * at the next refresh. Only names are ever said.
 *
 * Every agent gets them in its process environment. Claude Code also gets `CLAUDE_ENV_FILE`
 * ([LocalEnvironmentFile]), refreshed every minute, so a rotated, added or removed value reaches its next Bash
 * command; the other agents keep what they start with.
 *
 * @param server The server, with the launch's organisation.
 * @param files Where the private files are written.
 * @param sleep Waits between two refreshes (replaced in tests).
 */
internal class MemberVariablesImpl(
    private val server: ServerJson,
    private val files: LaunchFiles,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : MemberVariables {
    @Suppress("ReturnCount") // One per way the agent gets none.
    override suspend fun read(site: LaunchSite, features: Set<String>): VariablesRead {
        if (ServerFeatures.ENVIRONMENT !in features) return VariablesRead(null)
        val notes = mutableListOf<String>()
        val read = try {
            server.get(ROUTE, site.credentials.token, READ_MILLIS)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // never blocks the launch, as TeamCode's: the agent starts without them
            ServerAnswer.Failed(null, null, "unreadable")
        }
        val values = when (val answer = read) {
            is ServerAnswer.Failed -> return if (answer.status == FORBIDDEN) {
                VariablesRead(null, listOf(INTERACTIVE_ONLY))
            } else {
                VariablesRead(emptyMap(), listOf(UNREADABLE))
            }
            is ServerAnswer.Ok -> answer.body
        }
        val variables = variablesOf(values)
        val unavailable = values.list("unavailable").mapNotNull { (it as? JsonPrimitive)?.content }.sorted()
        if (unavailable.isNotEmpty()) {
            notes += "ostrom: environment variables unavailable right now: ${unavailable.joinToString(", ")}"
        }
        val refused = LocalEnvironment.refused(variables)
        if (refused.isNotEmpty()) {
            notes += "ostrom: environment variables not given to the agent (reserved names): " +
                refused.joinToString(", ")
        }
        val accepted = AgentEnvironment.accepted(variables)
        if (accepted.isNotEmpty()) notes += "ostrom: environment variables: ${accepted.keys.joinToString(", ")}"
        return VariablesRead(accepted, notes)
    }

    override fun deliver(
        agent: Agent,
        variables: Map<String, String>,
        site: LaunchSite,
        directory: String,
        say: (String) -> Unit,
    ): VariablesDelivery? {
        if (agent != Agent.CLAUDE) return null
        val path = join(directory, "environment.sh")
        val values = join(directory, "environment-values.sh")
        val chained = site.variable(AgentEnvironment.CLAUDE_ENV_FILE)?.takeIf { it.isNotBlank() }
        val fetch: suspend () -> Map<String, String> = {
            val answer = server.get(ROUTE, site.credentials.token, REFRESH_MILLIS)
            if (answer !is ServerAnswer.Ok) throw IOException("the variables were not read")
            variablesOf(answer.body)
        }
        val write = { text: String -> files.write(values, text, FileAccess.PRIVATE) }
        val refresh = EnvironmentRefresh(fetch, write, variables, sleep = sleep, log = say)
        val file = LocalEnvironmentFile(path, values, refresh, files)
        return try {
            refresh.start()
            files.write(path, LocalEnvironment.script(values, chained), FileAccess.PRIVATE)
            file
        } catch (_: IOException) {
            file.close()
            say("ostrom: the environment file could not be written; the agent keeps the variables it starts with")
            null
        }
    }

    /** `EnvironmentValues.variables`: the string values, by name. */
    private fun variablesOf(body: JsonObject): Map<String, String> = body.child("variables").orEmpty()
        .mapNotNull { (name, value) -> (value as? JsonPrimitive)?.takeIf { it.isString }?.let { name to it.content } }
        .toMap()

    private companion object {
        const val ROUTE = "/api/v1/environment"
        const val FORBIDDEN = 403
        const val READ_MILLIS = 5_000L
        const val REFRESH_MILLIS = 10_000L
        const val INTERACTIVE_ONLY = "ostrom: environment variables are given to interactive logins only; this " +
            "credential starts the agent without them"
        const val UNREADABLE = "ostrom: environment variables could not be read; starting the agent without them"
    }
}

/**
 * The launch's `CLAUDE_ENV_FILE` ([path]) and the values file it sources before each Bash command ([values]), both
 * private (`0600`, in the launch's `0700` folder), as TeamCode's `LocalEnvironmentFile`. [refresh] rewrites the values
 * file when the variables change; both are removed when the launch ends, even when its folder is kept.
 */
internal class LocalEnvironmentFile(
    private val path: String,
    private val values: String,
    private val refresh: EnvironmentRefresh,
    private val files: LaunchFiles,
) : VariablesDelivery {
    override val environment: Map<String, String?> = mapOf(AgentEnvironment.CLAUDE_ENV_FILE to path)

    override suspend fun keepCurrent() = refresh.run()

    override fun close() {
        for (file in listOf(values, path)) {
            try {
                files.deleteRecursively(file)
            } catch (_: IOException) {
                // the launch's folder is removed with it
            }
        }
    }
}
