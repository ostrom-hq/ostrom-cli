// SPDX-License-Identifier: Apache-2.0
// A failure of the server, a download or the installer leaves what is installed and never keeps the agent from
// starting; cancellation is rethrown first (scripts/check-conventions.py).
@file:Suppress("TooGenericExceptionCaught")

package run.ostrom.cli.runner

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okio.Buffer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What the runner prepares in the agent's home before the agent's first terminal. */
fun interface AgentDeliveries {
    /**
     * Installs what the session's owner receives.
     *
     * @param environment The agent's environment.
     *
     * @return The variables to change in it (Claude Code's `--settings` with the team hooks), never throwing but on
     *   cancellation.
     */
    suspend fun install(environment: Map<String, String>): Map<String, String>
}

/** The routes that deliver the owner's skills and team hooks to the runner (`runners.cloud.{skills,hooks}.v1`). */
interface DeliveryApi {
    /**
     * A manifest: `SkillManifest` or `HookManifest`.
     *
     * @param kind [SKILLS] or [HOOKS].
     *
     * @return The manifest, or null when this server does not serve the route.
     */
    suspend fun manifest(kind: String): JsonObject?

    /**
     * The files of one version: `SkillArtifact` or `HookArtifact`.
     *
     * @param kind [SKILLS] or [HOOKS].
     * @param name The item.
     * @param version Its version.
     *
     * @return The artifact.
     */
    suspend fun artifact(kind: String, name: String, version: String): JsonObject

    /** The two kinds. */
    companion object {
        /** The owner's skills. */
        const val SKILLS = "skills"

        /** The owner's team hooks. */
        const val HOOKS = "hooks"
    }
}

/**
 * [DeliveryApi] over HTTP, with the runner token: `POST /api/v1/cloud/runner/{skills,hooks}[/artifact]`.
 *
 * @param client An HTTP client.
 * @param config The runner's configuration.
 */
class HttpDeliveryApi(private val client: HttpClient, private val config: RunnerConfig) : DeliveryApi {
    override suspend fun manifest(kind: String): JsonObject? {
        val response = client.post("${config.serverUrl}/api/v1/cloud/runner/$kind") {
            bearerAuth(config.token)
            contentType(ContentType.Application.Json)
            setBody(execution().toString())
        }
        return when (response.status) {
            HttpStatusCode.OK -> Json.parseToJsonElement(response.bodyAsText()).jsonObject
            HttpStatusCode.NotFound -> null
            else -> error("The $kind manifest answered ${response.status.value}.")
        }
    }

    override suspend fun artifact(kind: String, name: String, version: String): JsonObject {
        val response = client.post("${config.serverUrl}/api/v1/cloud/runner/$kind/artifact") {
            bearerAuth(config.token)
            contentType(ContentType.Application.Json)
            val item = mapOf("name" to JsonPrimitive(name), "version" to JsonPrimitive(version))
            setBody(JsonObject(execution() + item).toString())
        }
        check(response.status == HttpStatusCode.OK) { "The artifact answered ${response.status.value}." }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private fun execution(): JsonObject = buildJsonObject {
        put("sessionId", config.sessionId.toString())
        put("epoch", config.epoch)
    }
}

/**
 * The lines of the installer, over its terminal: its output split at each newline (a carriage return dropped), a line
 * over [maxLine] bytes skipped.
 *
 * @param terminal The installer's terminal.
 * @param maxLine The longest line read.
 */
class InstallerLink(private val terminal: Terminal, private val maxLine: Int = MAX_LINE) {
    private val pending = ArrayDeque<String>()
    private val line = Buffer()
    private var skipping = false

    /**
     * The next line.
     *
     * @return It, or null once the installer ended.
     */
    suspend fun read(): String? {
        while (pending.isEmpty()) {
            val bytes = terminal.read() ?: return null
            for (byte in bytes) take(byte)
        }
        return pending.removeFirst()
    }

    /** Takes one byte: lines are split on bytes, so a character split across two reads stays whole. */
    private fun take(byte: Byte) {
        when {
            byte == NEWLINE -> {
                val text = line.readUtf8()
                if (!skipping) pending += text.trimEnd('\r')
                skipping = false
            }
            skipping -> Unit
            line.size >= maxLine -> {
                line.clear()
                skipping = true
            }
            else -> line.writeByte(byte.toInt())
        }
    }

    /**
     * The next message, skipping lines that are not one.
     *
     * @return It, or null once the installer ended.
     */
    suspend fun message(): InstallMessage? {
        while (true) {
            val text = read() ?: return null
            InstallMessage.decode(text)?.let { return it }
        }
    }

    /**
     * Writes a message.
     *
     * @param message The message.
     */
    suspend fun write(message: InstallMessage) = terminal.write((message.encode() + "\n").encodeToByteArray())

    /** Constants of the enclosing type. */
    companion object {
        /** The longest line of the installer: its results are short. */
        const val MAX_LINE = 64 * 1024

        private const val NEWLINE = '\n'.code.toByte()
    }
}

/**
 * A team hook the runner installed: its name and definition.
 *
 * @property name The hook.
 * @property definition Its definition.
 */
class InstalledHook(val name: String, val definition: TeamHookDefinition)

/**
 * The owner's skills and team hooks in a cloud session, at each start of Claude Code or Codex (TeamCode's `SkillSync`
 * and `CloudHookSync`, cli/…/cloud/agent/Skills.kt): the runner reads the manifest and the artifacts with its token,
 * verifies every digest, and streams what the installer asks for to `ostrom cloud install-skills|install-hooks`, which
 * it starts in the agent's container, under the agent's uid. Codex gets skills and no team hook: it runs only hooks a
 * person trusted. A route this server does not serve, a failure or a refused artifact is a notice in the pod's log
 * (counts only, never a name or a content) and never keeps the agent from starting.
 *
 * @param api The routes.
 * @param terminals Starts the installer in the agent's container.
 * @param config The runner's configuration.
 * @param log Writes a line to the pod's log.
 * @param timeout The longest each kind may take.
 * @param helloTimeout The longest the installer may take to start.
 */
// Four collaborators and two tunings with defaults, all named at the call site; one function per step of the exchange.
@Suppress("LongParameterList", "TooManyFunctions")
class PodDeliveries(
    private val api: DeliveryApi,
    private val terminals: TerminalStarter,
    private val config: RunnerConfig,
    private val log: (String) -> Unit,
    private val timeout: Duration = 60.seconds,
    private val helloTimeout: Duration = 20.seconds,
) : AgentDeliveries {
    override suspend fun install(environment: Map<String, String>): Map<String, String> {
        val agent = config.agentCommand
        val skilled = agent == CloudInstall.CLAUDE || agent == CloudInstall.CODEX
        if (skilled) bounded(DeliveryApi.SKILLS) { skills(environment) }
        val hooks = if (agent == CloudInstall.CLAUDE) bounded(DeliveryApi.HOOKS) { hooks(environment) } else null
        return if (hooks.isNullOrEmpty()) {
            emptyMap()
        } else {
            mapOf(RunnerConfig.SETTINGS_VARIABLE to TeamHookSettings.claude(config.binary, hooks))
        }
    }

    /** [block] within [timeout]; a failure or the timeout is a notice and null. */
    private suspend fun <T : Any> bounded(kind: String, block: suspend () -> T): T? = try {
        withTimeoutOrNull(timeout) { block() }
            ?: null.also { log("$kind: took too long; the agent starts with what is installed") }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log("$kind: not installed (${error::class.simpleName}); the agent starts with what is installed")
        null
    }

    private suspend fun skills(environment: Map<String, String>) {
        val manifest = api.manifest(DeliveryApi.SKILLS) ?: return notServed(DeliveryApi.SKILLS)
        val entries = entries(manifest, DeliveryApi.SKILLS)
        var budget = DeliveredItems.MAX_BYTES
        val chosen = entries.mapNotNull { entry ->
            val identity = DeliveredItems.identity(entry)
            val size = (entry["sizeBytes"] as? JsonPrimitive)?.longOrNull ?: Long.MAX_VALUE
            val count = (entry["fileCount"] as? JsonPrimitive)?.longOrNull ?: Long.MAX_VALUE
            identity?.takeIf { count <= DeliveredItems.MAX_FILES && size <= budget }?.also { budget -= size }
        }.distinctBy { it.first }.sortedBy { it.first }.take(MAX_SKILLS)
        if (chosen.size < entries.size) log("skills: ${entries.size - chosen.size} not installed (limits)")
        sync(CloudProcess.INSTALL_SKILLS, DeliveryApi.SKILLS, chosen.map { it.item() }, environment) { item ->
            val artifact = api.artifact(DeliveryApi.SKILLS, item.name, item.version)
            DeliveredItems.verify(artifact, Triple(item.name, item.version, item.digest))
                ?.let { DeliveredItem(item.name, item.version, item.digest, it) }
        }
    }

    private suspend fun hooks(environment: Map<String, String>): List<InstalledHook> {
        val manifest = api.manifest(DeliveryApi.HOOKS) ?: return emptyList<InstalledHook>().also {
            notServed(DeliveryApi.HOOKS)
        }
        val entries = entries(manifest, DeliveryApi.HOOKS)
        val runnable = entries.mapNotNull { entry ->
            val identity = DeliveredItems.identity(entry)
            val definition = DeliveredItems.definition(entry["definition"] as? JsonObject)
            if (identity == null || definition == null) null else identity to definition
        }.distinctBy { it.first.first }.sortedBy { it.first.first }.take(MAX_HOOKS)
        val prepared = runnable.mapNotNull { (identity, definition) ->
            hook(identity, definition)?.let { InstalledHook(identity.first, definition) to it }
        }
        if (prepared.size < entries.size) log("hooks: ${entries.size - prepared.size} refused or not downloaded")
        // An empty manifest still runs the installer, so the hooks no longer delivered are removed.
        val items = prepared.map { it.second }
        val statuses = sync(CloudProcess.INSTALL_HOOKS, DeliveryApi.HOOKS, items.map { it.item() }, environment) {
            item -> items.first { it.name == item.name }
        }
        return prepared.filter { statuses[it.first.name] in KEPT }.map { it.first }
    }

    /** A hook's verified files, identified by the digest of its file tree; null when refused or not downloaded. */
    private suspend fun hook(identity: Triple<String, String, String>, definition: TeamHookDefinition): DeliveredItem? {
        val artifact = try {
            api.artifact(DeliveryApi.HOOKS, identity.first, identity.second)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return null
        }
        val given = DeliveredItems.definition(artifact["definition"] as? JsonObject)?.digestLine
        val defined = given == definition.digestLine
        val files = DeliveredItems.verify(
            artifact,
            identity,
            definition.digestLine,
            DeliveredItems.MAX_HOOK_BYTES,
            DeliveredItems.MAX_HOOK_FILES,
            executable = definition.command,
        )
        return if (files == null || !defined) {
            null
        } else {
            DeliveredItem(identity.first, identity.second, DeliveredItems.digestOf(files), files)
        }
    }

    private fun entries(manifest: JsonObject, kind: String): List<JsonObject> =
        (manifest[kind] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun notServed(kind: String) = log("$kind: this server delivers none to cloud sessions")

    /**
     * Runs the installer [command] and streams to it the [items] it needs, each fetched by [fetch] (null: refused).
     *
     * @return Each item's result (`installed`, `unchanged`, `conflict`, `failed`, `removed`).
     */
    @Suppress("ReturnCount") // The installer did not start, left early, or ended the exchange.
    private suspend fun sync(
        command: String,
        kind: String,
        items: List<InstallMessage.Item>,
        environment: Map<String, String>,
        fetch: suspend (InstallMessage.Item) -> DeliveredItem?,
    ): Map<String, String> {
        // Only what the installer reads: never the agent's token nor its first message.
        val variables = environment.filterKeys { it in INSTALLER_VARIABLES } +
            mapOf(KIND_VARIABLE to command, BINARY_VARIABLE to config.binary)
        val terminal = terminals.start(SCRIPT, variables, config.workspace)
        try {
            val link = InstallerLink(terminal)
            val hello = withTimeoutOrNull(helloTimeout) { link.message() }
            if (hello?.t != InstallMessage.HELLO) {
                log("$kind: the installer did not start")
                return emptyMap()
            }
            link.write(InstallMessage(InstallMessage.MANIFEST, items = items))
            val needed = mutableListOf<String>()
            while (true) {
                val reply = link.message() ?: return emptyMap()
                if (reply.t == InstallMessage.READY) break
                if (reply.t == InstallMessage.NEED) reply.name?.let(needed::add)
            }
            var refused = 0
            for (item in items.filter { it.name in needed }) {
                val delivered = fetchOrNull(item, fetch)
                if (delivered == null) refused++ else stream(link, delivered)
            }
            if (refused > 0) log("$kind: $refused refused or not downloaded")
            link.write(InstallMessage(InstallMessage.END))
            return results(link, kind)
        } finally {
            withContext(NonCancellable) {
                if (terminal.exitCode == null) terminal.terminate()
                terminal.close()
            }
        }
    }

    private suspend fun fetchOrNull(
        item: InstallMessage.Item,
        fetch: suspend (InstallMessage.Item) -> DeliveredItem?,
    ): DeliveredItem? = try {
        fetch(item)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private suspend fun stream(link: InstallerLink, item: DeliveredItem) {
        link.write(InstallMessage(InstallMessage.BEGIN, name = item.name))
        for (file in item.files) {
            val bytes = file.content
            var offset = 0
            do {
                val end = minOf(bytes.size, offset + InstallMessage.CHUNK_BYTES)
                val last = end >= bytes.size
                val chunk = bytes.substring(offset, end).base64()
                val status = if (last) InstallMessage.LAST else null
                val message = InstallMessage(InstallMessage.FILE, path = file.path, mode = file.mode, data = chunk)
                link.write(message.copy(status = status))
                offset = end
            } while (!last)
        }
        link.write(InstallMessage(InstallMessage.COMMIT, name = item.name, digest = item.digest))
    }

    /** The installer's results until its [InstallMessage.DONE], logged as counts; then its end. */
    private suspend fun results(link: InstallerLink, kind: String): Map<String, String> {
        val statuses = LinkedHashMap<String, String>()
        while (true) {
            val reply = link.message()
            if (reply == null || reply.t == InstallMessage.DONE) break
            val name = reply.name
            val status = reply.status
            if (reply.t == InstallMessage.RESULT && name != null && status != null) statuses[name] = status
        }
        val counts = statuses.values.groupingBy { it }.eachCount().entries.sortedBy { it.key }
        log("$kind: " + counts.joinToString(", ") { "${it.value} ${it.key}" }.ifEmpty { "none delivered" })
        while (link.read() != null) Unit
        return statuses
    }

    private fun Triple<String, String, String>.item() = InstallMessage.Item(first, second, third)

    private fun DeliveredItem.item() = InstallMessage.Item(name, version, digest)

    /** Constants of the enclosing type. */
    companion object {
        /** The skills installed at most. */
        const val MAX_SKILLS = 100

        /** The team hooks installed at most. */
        const val MAX_HOOKS = 50

        /** The installer's command, given through the environment, never through the script's text. */
        const val KIND_VARIABLE = "OSTROM_INSTALL_COMMAND"

        /** The `ostrom` binary the installer runs. */
        const val BINARY_VARIABLE = "OSTROM_INSTALL_BINARY"

        /** Starts the installer for the agent, its values from the environment. */
        const val SCRIPT = "exec \"${'$'}OSTROM_INSTALL_BINARY\" cloud \"${'$'}OSTROM_INSTALL_COMMAND\" " +
            "--agent \"${'$'}OSTROM_AGENT_COMMAND\""

        private val KEPT = setOf(InstallMessage.INSTALLED, InstallMessage.UNCHANGED)

        /** The agent's variables the installer gets. */
        private val INSTALLER_VARIABLES = setOf(
            "HOME", "PATH", "LANG", "TERM", "OSTROM_AGENT_COMMAND", "CLAUDE_CONFIG_DIR", "CODEX_HOME",
        )
    }
}

/**
 * Claude Code's `--settings` in a cloud session with the team hooks the runner installed: each runs its executable
 * from the agent's home through the shell (`"$HOME"/'.ostrom/hooks/<name>/<command>'`), before the runner's own
 * `Stop` and `SessionEnd` hooks ([AgentState.claudeSettings]), which stay in every case.
 */
object TeamHookSettings {
    /**
     * The settings.
     *
     * @param binary The `ostrom` binary.
     * @param hooks The installed team hooks.
     *
     * @return The settings, as JSON.
     */
    fun claude(binary: String, hooks: List<InstalledHook>): String {
        val own = Json.parseToJsonElement(AgentState.claudeSettings(binary)).jsonObject.getValue("hooks").jsonObject
        val events = (own.keys + hooks.flatMap { it.definition.events }).sorted()
        return buildJsonObject {
            putJsonObject("hooks") {
                for (event in events) {
                    putJsonArray(event) {
                        for (hook in hooks.filter { event in it.definition.events }) addJsonObject { entry(hook) }
                        (own[event] as? JsonArray).orEmpty().forEach { add(it) }
                    }
                }
            }
        }.toString()
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.entry(hook: InstalledHook) {
        hook.definition.matcher?.let { put("matcher", it) }
        putJsonArray("hooks") {
            addJsonObject {
                put("type", "command")
                val path = "${CloudInstall.HOOKS_FOLDER}/${hook.name}/${hook.definition.command}"
                put("command", "\"\$HOME\"/'" + path.replace("'", "'\\''") + "'")
                put("timeout", hook.definition.timeoutSeconds)
            }
        }
    }
}
