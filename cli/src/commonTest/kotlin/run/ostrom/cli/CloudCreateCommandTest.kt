// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import run.ostrom.cli.commands.CloudRepositoryOption
import run.ostrom.cli.commands.parseRepository
import com.github.ajalt.clikt.core.UsageError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `ostrom cloud create`, as TeamCode's `teamcode cloud create`, with its preparation recipe. */
class CloudCreateCommandTest : SetupKit() {
    private val create = "POST /api/v1/cloud/sessions"
    private val created = """{"id":"cs1","agent":"claude","status":"creating","createdAt":"1970-01-10T23:55:00Z"}"""
    private val recipe = mapOf(capabilities("runners.cloud.v1", "runners.cloud.environment.v1"))

    private fun routes(served: Map<String, Pair<Int, String>> = recipe) = served + (create to (201 to created))

    @Test
    fun theRecipeOfTheRepositoriesBranchAndWorkingDirectoryIsSent() {
        val options = arrayOf(
            "--agent", "claude", "--name", "Fix CI", "--model", "opus", "--message", "Fix the build",
            "--repo", "https://github.com/acme/api.git#main", "--repo", "libs/ui=https://github.com/acme/ui",
            "--branch", "fix-ci", "--workdir", "api",
        )
        assertEquals(0, run(routes(), "cloud", "create", *options))
        assertEquals("cs1  claude  creating  -  5m\n", console.out.toString())
        assertEquals(
            """{"agent":"claude","name":"Fix CI","model":"opus","prompt":"Fix the build","environment":{""" +
                """"repositories":[{"url":"https://github.com/acme/api.git","path":"api","ref":"main",""" +
                """"branch":"fix-ci"},{"url":"https://github.com/acme/ui","path":"libs/ui","branch":"fix-ci"}],""" +
                """"workingDirectory":"api"}}""",
            bodyOf(create),
        )
    }

    @Test
    fun withoutARecipeNoneIsSentAndTheJsonIsPrinted() {
        val cloudOnly = routes(mapOf(capabilities("runners.cloud.v1")))
        assertEquals(0, run(cloudOnly, "cloud", "create", "--agent", "shell", "--json"))
        assertEquals("""{"agent":"shell"}""", bodyOf(create))
        assertEquals("$created\n", console.out.toString())
        console.out.clear()
        assertEquals(0, run(routes(), "cloud", "create", "--agent", "codex", "--workdir", "src"))
        assertEquals("""{"agent":"codex","environment":{"workingDirectory":"src"}}""", bodyOf(create))
    }

    @Test
    fun theMessageMayComeFromStandardInputButNeverEmpty() {
        typed += listOf("Fix", "the build")
        assertEquals(0, run(routes(), "cloud", "create", "--agent", "claude", "--message", "-"))
        assertEquals("""{"agent":"claude","prompt":"Fix\nthe build"}""", bodyOf(create))
        assertEquals(64, run(routes(), "cloud", "create", "--agent", "claude", "--message", " "))
        assertErr("the --message text is empty.")
    }

    @Test
    fun anOlderServerNeverDropsTheRecipeSilently() {
        val cloudOnly = routes(mapOf(capabilities("runners.cloud.v1")))
        val options = arrayOf("--agent", "claude", "--repo", "https://github.com/acme/api")
        assertEquals(69, run(cloudOnly, "cloud", "create", *options))
        assertErr("takes no session recipe")
        assertEquals(69, run(routes(mapOf(capabilities())), "cloud", "create", "--agent", "claude"))
        assertErr("runs no cloud session")
        assertTrue(sent().none { it == create }, "nothing created")
    }

    @Test
    fun aBranchNeedsARepositoryAndARepositoryAnHttpsUrl() {
        assertEquals(64, run(routes(), "cloud", "create", "--agent", "claude", "--branch", "b"))
        assertErr("Use --branch with --repo.")
        assertEquals(64, run(routes(), "cloud", "create", "--agent", "claude", "--repo", "acme/api"))
        assertErr("Use --repo [PATH=]https://host/owner/name[#REF].")
        assertTrue(sent().none { it == create })
    }

    @Test
    fun aRepositoryIsReadAsTeamCodesIs() {
        val plain = parseRepository("https://github.com/a/b/")
        assertEquals(CloudRepositoryOption("https://github.com/a/b/", "b", null), plain)
        val placed = parseRepository("x/y=https://github.com/a/b.git#v1")
        assertEquals(CloudRepositoryOption("https://github.com/a/b.git", "x/y", "v1"), placed)
        val unnamed = assertFailsWith<UsageError> { parseRepository("https://github.com/.git") }
        assertEquals("Name the directory: --repo PATH=https://github.com/.git.", unnamed.message)
    }
}
