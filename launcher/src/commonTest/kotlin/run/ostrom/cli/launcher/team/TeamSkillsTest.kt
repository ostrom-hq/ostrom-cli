// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.team

import run.ostrom.cli.launcher.Agent
import run.ostrom.cli.launcher.FakeServer
import run.ostrom.cli.launcher.Routes
import run.ostrom.cli.launcher.ServerJson
import run.ostrom.cli.launcher.join
import run.ostrom.cli.launcher.parseObject
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A file system that fails where a test says, with or without a message. */
internal class FailingFileSystem(delegate: FileSystem) : ForwardingFileSystem(delegate) {
    var failSink: (Path) -> Boolean = { false }
    var failDelete: (Path) -> Boolean = { false }
    var message: String? = "disk full"

    override fun sink(file: Path, mustCreate: Boolean): Sink {
        if (failSink(file)) throw IOException(message)
        return super.sink(file, mustCreate)
    }

    override fun delete(path: Path, mustExist: Boolean) {
        if (failDelete(path)) throw IOException(message)
        super.delete(path, mustExist)
    }
}

class TeamSkillsTest {
    private val server = FakeServer()
    private val fake = FakeFileSystem()
    private val fileSystem = FailingFileSystem(fake)
    private val restricted = mutableListOf<String>()
    private val said = mutableListOf<String>()
    private val root = "/home/dev/.claude/skills"
    private val review = listOf(
        Artifacts.Source("SKILL.md", "# Review\n"),
        Artifacts.Source("scripts/run.sh", "#!/bin/sh\n", Delivered.EXECUTABLE),
    )
    private val deploy = listOf(Artifacts.Source("SKILL.md", "# Deploy\n"))

    private fun skills(vararg skills: Triple<String, String, List<Artifacts.Source>>) {
        val entries = skills.joinToString(",") { (name, version, files) ->
            val digest = Artifacts.digest(files)
            """{"name":"$name","version":"$version","digest":"$digest","sizeBytes":1,"fileCount":1}"""
        }
        server["GET ${Routes.SKILL_DELIVERY}"] = 200 to """{"memberId":"m1","skills":[$entries]}"""
        skills.forEach { (name, version, files) -> serve(name, version, files) }
        server["PUT ${Routes.SKILL_INSTALLATIONS}"] = 204 to ""
    }

    private fun serve(name: String, version: String, files: List<Artifacts.Source>, digest: String? = null) {
        val artifact = JsonObject(
            mapOf(
                "name" to JsonPrimitive(name),
                "version" to JsonPrimitive(version),
                "digest" to JsonPrimitive(digest ?: Artifacts.digest(files)),
                "files" to JsonArray(files.map(Artifacts::entry)),
            ),
        )
        server["GET ${Routes.skill(name, version)}"] = 200 to artifact.toString()
    }

    private suspend fun sync() =
        TeamSkills(ServerJson(server), fileSystem) { path, executable -> restricted += "$path $executable" }
            .sync(root, "dev-token") { said += it }

    private fun text(path: String) = fake.read(path.toPath()) { readUtf8() }

    private fun reported(): List<Map<String, String>> {
        val body = parseObject(server.sent("PUT ${Routes.SKILL_INSTALLATIONS}").last().body)
        return (body?.get("installations") as JsonArray).map { item ->
            (item as JsonObject).mapValues { (it.value as JsonPrimitive).content }
        }
    }

    private fun installed(name: String, version: String, files: List<Artifacts.Source>) =
        mapOf("name" to name, "status" to "installed", "version" to version, "digest" to Artifacts.digest(files))

    @Test
    fun deliveredSkillsAreVerifiedInstalledMarkedAndReported() = runTest {
        skills(Triple("review", "1.0.0", review), Triple("deploy", "2", deploy))
        sync()
        assertEquals("# Review\n", text("$root/review/SKILL.md"))
        assertEquals("#!/bin/sh\n", text("$root/review/scripts/run.sh"))
        assertEquals("${Artifacts.digest(review)}\n1.0.0\n", text("$root/review/${TeamSkills.MARKER}"))
        assertEquals(listOf("$root/review/scripts/run.sh true"), restricted.map { it.replace(".ostrom-staging-", "") })
        assertEquals(listOf("ostrom: team skills installed: review 1.0.0, deploy 2"), said)
        assertEquals(
            listOf(
                installed("review", "1.0.0", review),
                installed("deploy", "2", deploy),
            ),
            reported(),
        )
        assertEquals("dev-token", server.requests.map { it.token }.distinct().single())
        assertFalse(fake.list(root.toPath()).any { it.name.startsWith(".ostrom-staging") }, "nothing left behind")
    }

    @Test
    fun aSkillAlreadyThereIsNotDownloadedAgain() = runTest {
        skills(Triple("review", "1.0.0", review))
        sync()
        said.clear()
        sync()
        assertEquals(1, server.sent("GET ${Routes.skill("review", "1.0.0")}").size)
        assertEquals(emptyList(), said, "nothing changed: nothing said")
        assertEquals("installed", reported().single()["status"])
        assertEquals("1.0.0", reported().single()["version"])
    }

    @Test
    fun aNewVersionReplacesTheOneOstromInstalled() = runTest {
        skills(Triple("review", "1.0.0", review))
        sync()
        val next = listOf(Artifacts.Source("SKILL.md", "# Review v2\n"))
        skills(Triple("review", "1.1.0", next))
        sync()
        assertEquals("# Review v2\n", text("$root/review/SKILL.md"))
        assertFalse(fake.exists("$root/review/scripts".toPath()), "the old files are gone")
        assertEquals("ostrom: team skills installed: review 1.1.0", said.last())
    }

    @Test
    fun aPersonalSkillOfTheSameNameIsNeverTouchedAndReportedAsAConflict() = runTest {
        fake.createDirectories("$root/review".toPath())
        fake.write("$root/review/SKILL.md".toPath()) { writeUtf8("mine") }
        skills(Triple("review", "1.0.0", review))
        sync()
        assertEquals("mine", text("$root/review/SKILL.md"))
        assertTrue(server.sent("GET ${Routes.skill("review", "1.0.0")}").isEmpty())
        assertEquals(listOf(mapOf("name" to "review", "status" to "conflict")), reported())
        assertEquals(
            listOf("ostrom: team skill review not installed: a skill of yours has that name in $root (kept)"),
            said,
        )
    }

    @Test
    fun aSkillNoLongerDeliveredIsRemovedOnlyWhenOstromInstalledIt() = runTest {
        skills(Triple("review", "1.0.0", review))
        sync()
        fake.createDirectories("$root/mine".toPath())
        fake.createDirectories("$root/.hidden".toPath())
        fake.write("$root/.hidden/${TeamSkills.MARKER}".toPath()) { writeUtf8("x") }
        skills()
        sync()
        assertFalse(fake.exists("$root/review".toPath()))
        assertTrue(fake.exists("$root/mine".toPath()))
        assertTrue(fake.exists("$root/.hidden".toPath()))
        assertEquals(listOf(mapOf("name" to "review", "status" to "removed")), reported())
        assertEquals("ostrom: team skills removed, no longer delivered: review", said.last())
    }

    @Test
    fun nothingDeliveredNorInstalledReportsNothing() = runTest {
        skills()
        sync()
        assertTrue(server.sent("PUT ${Routes.SKILL_INSTALLATIONS}").isEmpty())
        assertEquals(emptyList(), said)
    }

    @Test
    fun aManifestThatCannotBeReadLeavesEverySkillAsItIs() = runTest {
        skills(Triple("review", "1.0.0", review))
        sync()
        said.clear()
        val manifest = "GET ${Routes.SKILL_DELIVERY}"
        server.unreachable = setOf(manifest)
        sync()
        server.unreachable = emptySet()
        val unreadable = listOf(
            """{"memberId":"m1"}""",
            """{"skills":["x"]}""",
            """{"skills":[{"version":"1","digest":"d"}]}""",
            """{"skills":[{"name":"Review","version":"1","digest":"d"}]}""",
            """{"skills":[{"name":"${"a".repeat(65)}","version":"1","digest":"d"}]}""",
            """{"skills":[{"name":"a","version":"-1","digest":"d"}]}""",
            """{"skills":[{"name":"a","digest":"d"}]}""",
            """{"skills":[{"name":"a","version":"1"}]}""",
            """{"skills":[{"name":"a","version":"1","digest":"d"},{"name":"a","version":"2","digest":"d"}]}""",
            """{"skills":[${(0..100).joinToString(",") { """{"name":"s$it","version":"1","digest":"d"}""" }}]}""",
        )
        unreadable.forEach {
            server[manifest] = 200 to it
            sync()
        }
        assertTrue(fake.exists("$root/review/SKILL.md".toPath()), "kept")
        assertEquals(1, server.sent("PUT ${Routes.SKILL_INSTALLATIONS}").size, "never reported")
        assertEquals(
            "ostrom: team skills not synced (connection refused); your skills are left as they are",
            said.first(),
        )
        assertEquals(
            List(unreadable.size) {
                "ostrom: team skills not synced (its list cannot be read); your skills are left as they are"
            },
            said.drop(1),
        )
    }

    @Test
    fun aVersionThatCannotBeDownloadedOrVerifiedIsNotInstalledAndTheOneBeforeIsKept() = runTest {
        skills(Triple("review", "1.0.0", review))
        sync()
        said.clear()
        val next = listOf(Artifacts.Source("SKILL.md", "# Review v2\n"))
        skills(Triple("review", "1.1.0", next), Triple("deploy", "2", deploy))
        server.unreachable = setOf("GET ${Routes.skill("review", "1.1.0")}")
        serve("deploy", "2", deploy, digest = "sha256:other")
        sync()
        assertEquals("# Review\n", text("$root/review/SKILL.md"), "kept")
        assertFalse(fake.exists("$root/deploy".toPath()))
        assertEquals(
            listOf(
                "ostrom: team skill review 1.1.0 not installed (connection refused); the version installed before " +
                    "is kept",
                "ostrom: team skill deploy 2 not installed (the server sent another version)",
            ),
            said,
        )
        assertEquals(listOf("failed", "failed"), reported().map { it["status"] })
        assertNull(reported().first()["version"])
    }

    @Test
    fun anArtifactOfAnotherSkillOrVersionOrWithFilesThatDoNotMatchIsRefused() = runTest {
        skills(Triple("deploy", "2", deploy))
        val artifact = "GET ${Routes.skill("deploy", "2")}"
        val good = Artifacts.digest(deploy)
        val entry = Artifacts.entry(deploy[0]).toString()
        val wrong = listOf(
            """{"name":"other","version":"2","digest":"$good","files":[$entry]}""",
            """{"name":"deploy","version":"3","digest":"$good","files":[$entry]}""",
            """{"name":"deploy","version":"2","digest":"$good","files":[]}""",
        )
        wrong.forEach {
            server[artifact] = 200 to it
            sync()
        }
        assertFalse(fake.exists("$root/deploy".toPath()))
        assertEquals("ostrom: team skill deploy 2 not installed (refused: it has no file)", said.last())
    }

    @Test
    fun aSkillThatCannotBeWrittenIsReportedFailedWithNothingLeftBehind() = runTest {
        skills(Triple("review", "1.0.0", review))
        fileSystem.failSink = { it.name == "run.sh" }
        sync()
        fileSystem.message = null
        sync()
        assertFalse(fake.exists("$root/review".toPath()))
        assertTrue(fake.list(root.toPath()).isEmpty(), "the staging folder is removed")
        assertEquals(
            listOf(
                "ostrom: team skill review 1.0.0 not installed (disk full)",
                "ostrom: team skill review 1.0.0 not installed (it cannot be written)",
            ),
            said,
        )
    }

    @Test
    fun aFolderThatCannotBeChangedLeavesTheSkillsAsTheyAre() = runTest {
        skills(Triple("review", "1.0.0", review))
        sync()
        said.clear()
        skills()
        fileSystem.failDelete = { it.name == "review" }
        sync()
        assertEquals(
            listOf("ostrom: team skills not synced in $root (disk full); your skills are left as they are"),
            said,
        )
        assertEquals(1, server.sent("PUT ${Routes.SKILL_INSTALLATIONS}").size, "only the first sync reported")
    }

    @Test
    fun aReportThatFailsIsSaidAndStopsNothing() = runTest {
        skills(Triple("review", "1.0.0", review))
        server["PUT ${Routes.SKILL_INSTALLATIONS}"] = 409 to
            """{"error":{"type":"device_required","message":"sign in with ostrom login"}}"""
        sync()
        assertTrue(fake.exists("$root/review/SKILL.md".toPath()))
        assertEquals(
            "ostrom: the team skills of this workstation were not reported to Ostrom (sign in with ostrom login)",
            said.last(),
        )
    }

    @Test
    fun claudeAndCodexHaveASkillsFolderOfTheirOwnAndNoOtherAgent() {
        val none = { _: String -> null }
        assertEquals(join("/home/dev", ".claude", "skills"), skillsFolder(Agent.CLAUDE, "/home/dev", none))
        assertEquals(join("/home/dev", ".codex", "skills"), skillsFolder(Agent.CODEX, "/home/dev", none))
        val set = mapOf("CLAUDE_CONFIG_DIR" to "/c", "CODEX_HOME" to "")
        assertEquals(join("/c", "skills"), skillsFolder(Agent.CLAUDE, "/home/dev", set::get))
        assertEquals(join("/home/dev", ".codex", "skills"), skillsFolder(Agent.CODEX, "/home/dev", set::get))
        assertNull(skillsFolder(Agent.GEMINI, "/home/dev", none))
    }
}
