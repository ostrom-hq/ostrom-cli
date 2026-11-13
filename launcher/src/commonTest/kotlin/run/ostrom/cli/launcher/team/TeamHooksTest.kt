// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.team

import run.ostrom.cli.launcher.FakeServer
import run.ostrom.cli.launcher.Routes
import run.ostrom.cli.launcher.ServerJson
import run.ostrom.cli.launcher.parseObject
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** A team hook as the server delivers it, its digest computed as the catalogue does. */
internal object Hooks {
    const val DEFINITION =
        """{"events":["PreToolUse","Stop"],"matcher":"Bash","command":"check.sh","timeoutSeconds":30}"""
    val files = listOf(
        Artifacts.Source("check.sh", "#!/bin/sh\nexit 0\n", Delivered.EXECUTABLE),
        Artifacts.Source("README.md", "Checks.\n"),
    )
    val definition = checkNotNull(HookDefinition.of(parseObject(DEFINITION)))
    val digest = Artifacts.digest(files, definition.digestLine)

    fun entry(name: String = "guard", version: String = "1.0.0", digest: String = Hooks.digest) =
        """{"name":"$name","version":"$version","digest":"$digest","definition":$DEFINITION,"sizeBytes":1,""" +
            """"fileCount":2}"""

    fun artifact(
        name: String = "guard",
        version: String = "1.0.0",
        digest: String = Hooks.digest,
        definition: String = DEFINITION,
        files: List<Artifacts.Source> = Hooks.files,
    ) = JsonObject(
        mapOf(
            "name" to JsonPrimitive(name),
            "version" to JsonPrimitive(version),
            "digest" to JsonPrimitive(digest),
            "definition" to checkNotNull(parseObject(definition)),
            "files" to JsonArray(files.map(Artifacts::entry)),
        ),
    ).toString()
}

class TeamHooksTest {
    private val server = FakeServer()
    private val hooks = TeamHooks(ServerJson(server), "dev-token")

    private fun definition(json: String) = HookDefinition.of(parseObject(json))

    @Test
    fun aDefinitionIsReadWithItsDefaultsAndItsDigestLine() {
        val read = assertNotNull(definition("""{"events":["Stop","PreToolUse"],"command":"bin/run"}"""))
        assertEquals(60, read.timeoutSeconds)
        assertNull(read.matcher)
        assertEquals("hook\u0000PreToolUse,Stop\u0000\u0000bin/run\u000060\n", read.digestLine)
        assertNull(assertNotNull(definition("""{"events":["Stop"],"matcher":null,"command":"a"}""")).matcher)
        assertEquals("hook\u0000PreToolUse,Stop\u0000Bash\u0000check.sh\u000030\n", Hooks.definition.digestLine)
    }

    @Test
    fun aDefinitionThisCliCannotInstallIsNone() {
        val refused = listOf(
            """{"events":[],"command":"a"}""",
            """{"command":"a"}""",
            """{"events":[1],"command":"a"}""",
            """{"events":[{}],"command":"a"}""",
            """{"events":["Stop"],"command":"a","timeoutSeconds":1.5}""",
            """{"events":["Stop","Stop"],"command":"a"}""",
            """{"events":["OnBoot"],"command":"a"}""",
            """{"events":["Stop"]}""",
            """{"events":["Stop"],"command":"../a"}""",
            """{"events":["Stop"],"command":"a","matcher":""}""",
            """{"events":["Stop"],"command":"a","matcher":"${"x".repeat(257)}"}""",
            """{"events":["Stop"],"command":"a","matcher":"a\nb"}""",
            """{"events":["Stop"],"command":"a","matcher":3}""",
            """{"events":["Stop"],"command":"a","matcher":[]}""",
            """{"events":["Stop"],"command":"a","timeoutSeconds":0}""",
            """{"events":["Stop"],"command":"a","timeoutSeconds":601}""",
            """{"events":["Stop"],"command":"a","timeoutSeconds":"5"}""",
            """{"events":["Stop"],"command":"a","timeoutSeconds":{}}""",
        )
        refused.forEach { assertNull(definition(it), it) }
        assertNull(HookDefinition.of(null))
        assertNotNull(definition("""{"events":["Stop"],"command":"a","matcher":"${"x".repeat(256)}"}"""))
        assertNotNull(definition("""{"events":["Stop"],"command":"a","timeoutSeconds":600}"""))
    }

    @Test
    fun theManifestKeepsTheHooksThisCliCanInstallSortedAndCountsTheOthers() = runTest {
        val bad = listOf(
            "\"x\"",
            Hooks.entry(name = "Bad"),
            Hooks.entry(name = "a".repeat(65)),
            Hooks.entry(version = "-1"),
            Hooks.entry(digest = "md5:x"),
            Hooks.entry().replace("\"name\":\"guard\",", ""),
            Hooks.entry().replace("\"version\":\"1.0.0\",", ""),
            Hooks.entry().replace("\"digest\":\"${Hooks.digest}\",", ""),
            Hooks.entry().replace(Hooks.DEFINITION, "{}"),
            Hooks.entry(name = "audit"),
        )
        val entries = (listOf(Hooks.entry(name = "zeta"), Hooks.entry()) + bad).joinToString(",")
        server["GET ${Routes.HOOK_DELIVERY}"] = 200 to """{"memberId":"m1","hooks":[$entries]}"""
        val read = assertIs<HookManifest.Read>(hooks.manifest())
        assertEquals(listOf("audit", "guard", "zeta"), read.hooks.map { it.name })
        assertEquals(9, read.skipped)
        assertEquals("dev-token", server.requests.single().token)
        val twice = "${Hooks.entry()},${Hooks.entry()}"
        server["GET ${Routes.HOOK_DELIVERY}"] = 200 to """{"memberId":"m1","hooks":[$twice]}"""
        assertEquals(1, assertIs<HookManifest.Read>(hooks.manifest()).hooks.size)
        server["GET ${Routes.HOOK_DELIVERY}"] = 200 to """{"memberId":"m1"}"""
        assertIs<HookManifest.Unreadable>(hooks.manifest())
        server["GET ${Routes.HOOK_DELIVERY}"] = 403 to """{"error":{"type":"forbidden","message":"no"}}"""
        assertEquals(403, assertIs<HookManifest.Failed>(hooks.manifest()).failure.status)
    }

    private suspend fun filesOf(artifact: String): HookFiles {
        server["GET ${Routes.hook("guard", "1.0.0")}"] = 200 to artifact
        return hooks.files(guard())
    }

    private suspend fun guard(): EffectiveHook {
        server["GET ${Routes.HOOK_DELIVERY}"] = 200 to """{"hooks":[${Hooks.entry()}]}"""
        return assertIs<HookManifest.Read>(hooks.manifest()).hooks.single()
    }

    private suspend fun refusal(artifact: String) = assertIs<HookFiles.Refused>(filesOf(artifact)).reason

    @Test
    fun theFilesOfAVersionAreVerifiedAgainstItsDefinitionAndDigest() = runTest {
        val files = assertIs<HookFiles.Verified>(filesOf(Hooks.artifact())).files
        assertEquals(listOf("README.md", "check.sh"), files.map { it.path })
        val another = "the server sent another version"
        assertEquals(another, refusal(Hooks.artifact(name = "other")))
        assertEquals(another, refusal(Hooks.artifact(version = "2")))
        assertEquals(another, refusal(Hooks.artifact(digest = "sha256:" + "0".repeat(64))))
        assertEquals(another, refusal(Hooks.artifact(definition = Hooks.DEFINITION.replace("30", "31"))))
        assertEquals(another, refusal(Hooks.artifact(definition = "{}")))
        val unsigned = Hooks.files.map { Artifacts.Source(it.path, it.text + " ", it.mode) }
        assertEquals("digest", refusal(Hooks.artifact(files = unsigned)))
        val many = (0..50).map { Artifacts.Source("f$it", "x") }
        assertEquals("it has more than 50 files", refusal(Hooks.artifact(files = many)))
        val big = listOf(Artifacts.Source("check.sh", "x".repeat(1024 * 1024 + 1), Delivered.EXECUTABLE))
        assertEquals("it is bigger than 1 MiB", refusal(Hooks.artifact(files = big)))
    }

    @Test
    fun aVersionWhoseCommandIsNotOneOfItsExecutablesIsRefused() = runTest {
        val plain = listOf(Artifacts.Source("check.sh", "#!/bin/sh\n"))
        val missing = listOf(Artifacts.Source("other.sh", "#!/bin/sh\n", Delivered.EXECUTABLE))
        val reason = "its command check.sh is not an executable file of it"
        for (files in listOf(plain, missing)) {
            val digest = Artifacts.digest(files, Hooks.definition.digestLine)
            server["GET ${Routes.HOOK_DELIVERY}"] = 200 to """{"hooks":[${Hooks.entry(digest = digest)}]}"""
            server["GET ${Routes.hook("guard", "1.0.0")}"] = 200 to Hooks.artifact(digest = digest, files = files)
            val hook = assertIs<HookManifest.Read>(hooks.manifest()).hooks.single()
            assertEquals(reason, assertIs<HookFiles.Refused>(hooks.files(hook)).reason)
        }
    }

    @Test
    fun aDownloadTheServerRefusesIsItsFailure() = runTest {
        val hook = guard()
        server.unreachable = setOf("GET ${Routes.hook("guard", "1.0.0")}")
        assertNull(assertIs<HookFiles.ServerFailure>(hooks.files(hook)).failure.status)
    }
}
