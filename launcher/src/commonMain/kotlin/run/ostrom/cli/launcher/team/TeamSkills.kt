// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.team

import run.ostrom.cli.launcher.Agent
import run.ostrom.cli.launcher.Routes
import run.ostrom.cli.launcher.ServerAnswer
import run.ostrom.cli.launcher.ServerJson
import run.ostrom.cli.launcher.join
import run.ostrom.cli.launcher.list
import run.ostrom.cli.launcher.objectOf
import run.ostrom.cli.launcher.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath

/**
 * The feature key of the team skills (`GET /api/v1/skill-delivery`), `ServerFeatures.SKILLS` of ostrom-core: the
 * launcher depends on no other module, so the CLI's tests check that the two are the same.
 */
const val TEAM_SKILLS = "skills.v1"

/**
 * The folder [agent] reads its member's own skills from, which team skills are installed into: Claude Code's
 * `${CLAUDE_CONFIG_DIR:-~/.claude}/skills`, Codex's `${CODEX_HOME:-~/.codex}/skills` (the session's Codex home links
 * them). Null for an agent Ostrom delivers no skill to.
 */
fun skillsFolder(agent: Agent, home: String, variable: (String) -> String?): String? {
    val (name, folder) = when (agent) {
        Agent.CLAUDE -> "CLAUDE_CONFIG_DIR" to ".claude"
        Agent.CODEX -> "CODEX_HOME" to ".codex"
        else -> return null
    }
    return join(variable(name)?.takeIf { it.isNotEmpty() } ?: join(home, folder), "skills")
}

/**
 * The skills the organisation delivers to its member, synced into the agent's skills folder before a launch (TeamCode
 * TC-SKL-8): the manifest read with the device token, each effective version downloaded and verified (every file's
 * size and sha256, then the digest), installed through a staging folder beside it, a marker ([MARKER]) saying Ostrom
 * manages it. A folder of the same name without the marker is the member's own skill: never touched, reported
 * `conflict`. A managed skill no longer delivered is removed. What happened is reported to Ostrom for this workstation.
 *
 * Never stops a launch: a manifest that cannot be read leaves every skill as it is, a version that cannot be
 * downloaded or verified leaves the one installed before, each said through `say`.
 *
 * @param restrict Sets the owner-only mode of a path (`executable`: 0700): a skill's executable files.
 */
class TeamSkills(
    private val server: ServerJson,
    private val fileSystem: FileSystem,
    private val restrict: (String, Boolean) -> Unit,
) {
    /** One skill of the manifest. */
    private class Effective(val name: String, val version: String, val digest: String)

    /**
     * What became of one skill, as reported (`status`: installed, failed, conflict, removed).
     *
     * @property installed The version now installed, or null.
     * @property changed Whether this sync installed it.
     */
    private class Outcome(
        val name: String,
        val status: String,
        val installed: Effective? = null,
        val changed: Boolean = false,
    )

    /** Syncs the skills [token]'s member receives into [folder]; [say] tells what changed and what could not be. */
    suspend fun sync(folder: String, token: String, say: (String) -> Unit) {
        val effective = when (val answer = server.get(Routes.SKILL_DELIVERY, token, MANIFEST_MILLIS)) {
            is ServerAnswer.Failed -> null.also { say(untouched(answer.message)) }
            is ServerAnswer.Ok -> manifestOf(answer.body) ?: null.also { say(untouched("its list cannot be read")) }
        }
        val root = folder.toPath()
        val outcomes = effective?.let {
            try {
                it.map { item -> skill(root, item, token, say) } + removed(root, it.map(Effective::name).toSet())
            } catch (e: IOException) {
                null.also { say("ostrom: team skills not synced in $folder (${e.message}); $LEFT") }
            }
        }
        if (outcomes != null) {
            announce(outcomes, say)
            report(outcomes, token, say)
        }
    }

    /** What this sync installed and removed, said. */
    private fun announce(outcomes: List<Outcome>, say: (String) -> Unit) {
        val installed = outcomes.mapNotNull { outcome -> outcome.installed?.takeIf { outcome.changed } }
        if (installed.isNotEmpty()) {
            say("ostrom: team skills installed: " + installed.joinToString { "${it.name} ${it.version}" })
        }
        val gone = outcomes.filter { it.status == REMOVED }
        if (gone.isNotEmpty()) say("ostrom: team skills removed, no longer delivered: " + gone.joinToString { it.name })
    }

    /** The manifest's skills, or null when it is not one: a skill without its name, version or digest, or too many. */
    private fun manifestOf(body: JsonObject): List<Effective>? {
        val entries = body["skills"] as? JsonArray ?: return null
        val read = entries.map { (it as? JsonObject)?.let(::effectiveOf) }
        val skills = read.filterNotNull()
        val whole = skills.size == read.size && skills.size <= MAX_SKILLS
        return skills.takeIf { whole && it.distinctBy(Effective::name).size == it.size }
    }


    /** One skill: kept when its version is there, refused over a skill of the member's, else downloaded. */
    private suspend fun skill(root: Path, skill: Effective, token: String, say: (String) -> Unit): Outcome {
        val target = root / skill.name
        val managed = markerOf(target)
        return when {
            fileSystem.metadataOrNull(target) != null && managed == null -> Outcome(skill.name, CONFLICT).also {
                say("ostrom: team skill ${skill.name} not installed: a skill of yours has that name in $root (kept)")
            }
            managed == skill.digest -> Outcome(skill.name, INSTALLED, skill)
            else -> when (val reason = install(root, skill, token)) {
                null -> Outcome(skill.name, INSTALLED, skill, changed = true)
                else -> Outcome(skill.name, FAILED).also {
                    val kept = if (managed == null) "" else "; the version installed before is kept"
                    say("ostrom: team skill ${skill.name} ${skill.version} not installed ($reason)$kept")
                }
            }
        }
    }

    /** Downloads, verifies and installs [skill]; null once installed, else why not. */
    private suspend fun install(root: Path, skill: Effective, token: String): String? =
        when (val verified = download(skill, token)) {
            is Verified.Refused -> verified.reason
            is Verified.Files -> write(root, skill, verified.files)
        }

    /** The files of [skill]'s version, verified; refused with the server's words, or why they are not its. */
    private suspend fun download(skill: Effective, token: String): Verified {
        val artifact = when (val answer = server.get(Routes.skill(skill.name, skill.version), token, ARTIFACT_MILLIS)) {
            is ServerAnswer.Failed -> return Verified.Refused(answer.message)
            is ServerAnswer.Ok -> answer.body
        }
        val same = artifact.text("name") == skill.name && artifact.text("version") == skill.version &&
            artifact.text("digest") == skill.digest
        val verified = if (same) Delivered.verify(artifact, skill.digest) else null
        return when (verified) {
            null -> Verified.Refused("the server sent another version")
            is Verified.Refused -> Verified.Refused("refused: ${verified.reason}")
            is Verified.Files -> verified
        }
    }

    /** Writes [files] beside [root]'s skill, then moves them in; null once done, else why not. */
    private fun write(root: Path, skill: Effective, files: List<DeliveredFile>): String? {
        val staging = root / "$STAGING${skill.name}"
        return try {
            fileSystem.deleteRecursively(staging, mustExist = false)
            fileSystem.createDirectories(staging)
            for (file in files) {
                val names = file.path.split('/')
                val parent = names.dropLast(1).fold(staging) { folder, name -> folder / name }
                fileSystem.createDirectories(parent)
                val path = parent / names.last()
                fileSystem.write(path) { write(file.content) }
                if (file.executable) restrict(path.toString(), true)
            }
            fileSystem.write(staging / MARKER) { writeUtf8("${skill.digest}\n${skill.version}\n") }
            val target = root / skill.name
            fileSystem.deleteRecursively(target, mustExist = false)
            fileSystem.atomicMove(staging, target)
            null
        } catch (e: IOException) {
            fileSystem.deleteRecursively(staging, mustExist = false)
            e.message ?: "it cannot be written"
        }
    }

    /** The digest the marker of [folder] names: Ostrom installed it; null for a folder Ostrom does not manage. */
    private fun markerOf(folder: Path): String? = try {
        fileSystem.read(folder / MARKER) { readUtf8() }.lineSequence().first().trim()
    } catch (_: IOException) {
        null
    }

    /** The skills Ostrom installed in [root] that are no longer delivered, removed. */
    private fun removed(root: Path, delivered: Set<String>): List<Outcome> =
        fileSystem.listOrNull(root).orEmpty()
            .filter { it.name !in delivered && !it.name.startsWith(".") && markerOf(it) != null }
            .map { folder -> Outcome(folder.name, REMOVED).also { fileSystem.deleteRecursively(folder) } }

    /** `PUT /api/v1/skill-delivery/installations`: what this workstation holds now. */
    private suspend fun report(outcomes: List<Outcome>, token: String, say: (String) -> Unit) {
        if (outcomes.isEmpty()) return
        val installations = outcomes.map {
            objectOf(
                "name" to it.name,
                "status" to it.status,
                "version" to it.installed?.version,
                "digest" to it.installed?.digest,
            )
        }
        val body = objectOf("installations" to installations)
        val answer = server.call("PUT", Routes.SKILL_INSTALLATIONS, body, token, REPORT_MILLIS)
        if (answer is ServerAnswer.Failed) {
            say("ostrom: the team skills of this workstation were not reported to Ostrom (${answer.message})")
        }
    }

    /** The marker, statuses and limits of the team skills. */
    companion object {
        /** The file in a skill's folder that says Ostrom installed it: its digest, then its version. */
        const val MARKER = ".ostrom-skill"
        private const val STAGING = ".ostrom-staging-"
        private const val LEFT = "your skills are left as they are"
        private const val INSTALLED = "installed"
        private const val FAILED = "failed"
        private const val CONFLICT = "conflict"
        private const val REMOVED = "removed"
        private const val MAX_SKILLS = 100
        private const val MANIFEST_MILLIS = 5_000L
        private const val ARTIFACT_MILLIS = 30_000L
        private const val REPORT_MILLIS = 5_000L
        private const val MAX_NAME = 64
        private val NAME = Regex("[a-z0-9]+(-[a-z0-9]+)*")
        private val VERSION = Regex("[0-9A-Za-z][0-9A-Za-z.+_-]{0,63}")

        private fun untouched(reason: String) = "ostrom: team skills not synced ($reason); $LEFT"

        private fun effectiveOf(skill: JsonObject): Effective? {
            val name = skill.text("name")?.takeIf { it.length <= MAX_NAME && NAME.matches(it) }
            val version = skill.text("version")?.takeIf(VERSION::matches)
            val digest = skill.text("digest")
            return if (name == null || version == null || digest == null) null else Effective(name, version, digest)
        }
    }
}
