// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.team

import run.ostrom.cli.launcher.Routes
import run.ostrom.cli.launcher.ServerAnswer
import run.ostrom.cli.launcher.ServerJson
import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.list
import run.ostrom.cli.launcher.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * The feature key of the team hooks (`GET /api/v1/hook-delivery`), `ServerFeatures.HOOKS` of ostrom-core: the launcher
 * depends on no other module, so the CLI's tests check that the two are the same.
 */
const val TEAM_HOOKS = "hooks.v1"

/**
 * When and what a team hook runs (`HookDefinition` of the contract): [command] is a file of its version, run with no
 * argument and the event's JSON on standard input.
 */
class HookDefinition(val events: List<String>, val matcher: String?, val command: String, val timeoutSeconds: Int) {
    /** The line its version's digest begins with, before the files' lines. */
    val digestLine: String
        get() = "hook\u0000${events.sorted().joinToString(",")}\u0000${matcher.orEmpty()}\u0000$command\u0000" +
            "$timeoutSeconds\n"

    /** The definitions of the contract, and the bounds this CLI installs. */
    companion object {
        /** The agent events a team hook may run on. */
        val EVENTS = listOf(
            "PreToolUse", "PostToolUse", "UserPromptSubmit", "Stop", "SubagentStop", "SessionStart", "SessionEnd",
            "PreCompact", "Notification",
        )
        private const val DEFAULT_SECONDS = 60
        private const val MAX_SECONDS = 600
        private const val MAX_MATCHER = 256

        /**
         * [json] as a definition, or null when it is not one this CLI can install: known events, each once; a
         * matcher of 1 to 256 characters without control characters; a command that is a path a version may hold;
         * 1 to 600 seconds.
         */
        fun of(json: JsonObject?): HookDefinition? = json?.let(::read)

        private fun read(json: JsonObject): HookDefinition? {
            val events = json.list("events").map { event -> (event as? JsonPrimitive)?.takeIf { it.isString }?.content }
            val given = json["matcher"]
            val matcher = (given as? JsonPrimitive)?.takeIf { it.isString }?.content
            val matches = if (matcher == null) given == null || given is JsonNull else matcherAllowed(matcher)
            val command = json.text("command")?.takeIf(Delivered::validPath)
            val timeout = json["timeoutSeconds"]
            val seconds = if (timeout == null) DEFAULT_SECONDS else (timeout as? JsonPrimitive)?.secondsOf()
            val known = events.isNotEmpty() && events.all { it in EVENTS } && events.distinct().size == events.size
            val valid = known && matches
            return if (valid && command != null && seconds != null) {
                HookDefinition(events.filterNotNull(), matcher, command, seconds)
            } else {
                null
            }
        }

        private fun matcherAllowed(matcher: String) =
            matcher.length in 1..MAX_MATCHER && matcher.none(Char::isISOControl)

        private fun JsonPrimitive.secondsOf(): Int? =
            takeIf { !it.isString }?.intOrNull?.takeIf { it in 1..MAX_SECONDS }
    }
}

/** A pinned hook version the member receives (`EffectiveHook` of the contract). */
class EffectiveHook(val name: String, val version: String, val digest: String, val definition: HookDefinition) {
    /** The contract's hooks, read from the manifest and the artifacts. */
    companion object {
        private val NAME = Regex("[a-z0-9]+(-[a-z0-9]+)*")
        private val VERSION = Regex("[0-9A-Za-z][0-9A-Za-z.+_-]{0,63}")
        private val DIGEST = Regex("sha256:[0-9a-f]{64}")
        private const val MAX_NAME = 64

        /** [json] as a hook, or null when its name, version, digest or definition is not one this CLI installs. */
        fun of(json: JsonObject): EffectiveHook? {
            val name = json.text("name")?.takeIf { it.length <= MAX_NAME && NAME.matches(it) }
            val version = json.text("version")?.takeIf(VERSION::matches)
            val digest = json.text("digest")?.takeIf(DIGEST::matches)
            val definition = HookDefinition.of(json.child("definition"))
            return if (name == null || version == null || digest == null) {
                null
            } else {
                definition?.let { EffectiveHook(name, version, digest, it) }
            }
        }
    }
}

/** What the hook manifest gave. */
sealed interface HookManifest {
    /** The hooks delivered, sorted by name; [skipped] those this CLI cannot install, by count. */
    class Read(val hooks: List<EffectiveHook>, val skipped: Int) : HookManifest

    /** The server refused or could not be reached. */
    class Failed(val failure: ServerAnswer.Failed) : HookManifest

    /** The answer is not a manifest. */
    data object Unreadable : HookManifest
}

/** The team hooks routes, with one person's credential: the manifest, and the files of a delivered version. */
class TeamHooks(private val server: ServerJson, private val token: String) {
    /** `GET /api/v1/hook-delivery`. */
    suspend fun manifest(): HookManifest =
        when (val answer = server.get(Routes.HOOK_DELIVERY, token, MANIFEST_MILLIS)) {
            is ServerAnswer.Failed -> HookManifest.Failed(answer)
            is ServerAnswer.Ok -> manifestOf(answer.body["hooks"] as? JsonArray)
        }

    private fun manifestOf(entries: JsonArray?): HookManifest {
        if (entries == null) return HookManifest.Unreadable
        val hooks = entries.mapNotNull { (it as? JsonObject)?.let(EffectiveHook::of) }
            .distinctBy { it.name }.sortedBy { it.name }
        return HookManifest.Read(hooks, entries.size - hooks.size)
    }

    /**
     * The files of [hook]'s version, verified: the same name, version, digest and definition as the manifest's, every
     * file matching, the digest recomputed after the definition's line, and its command an executable file of it.
     * The server's refusal is [ServerFailure].
     */
    suspend fun files(hook: EffectiveHook): HookFiles =
        when (val answer = server.get(Routes.hook(hook.name, hook.version), token, ARTIFACT_MILLIS)) {
            is ServerAnswer.Failed -> HookFiles.ServerFailure(answer)
            is ServerAnswer.Ok -> checked(answer.body, hook)
        }

    private fun checked(artifact: JsonObject, hook: EffectiveHook): HookFiles {
        val named = artifact.text("name") == hook.name && artifact.text("version") == hook.version
        val defined = HookDefinition.of(artifact.child("definition"))?.digestLine == hook.definition.digestLine
        val same = named && artifact.text("digest") == hook.digest && defined
        val command = hook.definition.command
        return when (val verified = if (same) verify(artifact, hook) else null) {
            null -> HookFiles.Refused("the server sent another version")
            is Verified.Refused -> HookFiles.Refused(verified.reason)
            is Verified.Files -> if (verified.files.any { it.path == command && it.executable }) {
                HookFiles.Verified(verified.files)
            } else {
                HookFiles.Refused("its command $command is not an executable file of it")
            }
        }
    }

    private fun verify(artifact: JsonObject, hook: EffectiveHook) =
        Delivered.verify(artifact, hook.digest, hook.definition.digestLine, MAX_BYTES, MAX_FILES)

    private companion object {
        const val MANIFEST_MILLIS = 10_000L
        const val ARTIFACT_MILLIS = 30_000L
        const val MAX_FILES = 50
        const val MAX_BYTES = 1024L * 1024
    }
}

/** The files of a hook version, or why there are none. */
sealed interface HookFiles {
    /** Verified against the digest the member reads and approves. */
    class Verified(val files: List<DeliveredFile>) : HookFiles

    /** Refused by this CLI, for the member. */
    class Refused(val reason: String) : HookFiles

    /** The server refused or could not be reached. */
    class ServerFailure(val failure: ServerAnswer.Failed) : HookFiles
}
