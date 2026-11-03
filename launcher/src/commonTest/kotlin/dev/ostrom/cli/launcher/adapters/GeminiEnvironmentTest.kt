// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.adapters

import dev.ostrom.cli.launcher.LaunchVariables
import dev.ostrom.cli.launcher.MemoryFiles
import dev.ostrom.cli.launcher.join
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

// what changes the shell that runs Ostrom's hooks (`bash -c`, PowerShell on Windows), the loader under it, or Node
private val SHELL = listOf(
    "PATH",
    "BASH_ENV",
    "ENV",
    "SHELLOPTS",
    "BASHOPTS",
    "CDPATH",
    "GLOBIGNORE",
    "IFS",
    "PS4",
    "LD_PRELOAD",
    "LD_AUDIT",
    "LD_LIBRARY_PATH",
    "DYLD_INSERT_LIBRARIES",
    "DYLD_LIBRARY_PATH",
    "DYLD_FRAMEWORK_PATH",
    "NODE_OPTIONS",
    "NODE_PATH",
    "GEMINI_SANDBOX",
    "ComSpec",
)

// with one of these set, Ostrom cannot vouch for its gate
private val REFUSED = listOf(
    "BASH_ENV",
    "ENV",
    "SHELLOPTS",
    "BASHOPTS",
    "LD_PRELOAD",
    "LD_AUDIT",
    "DYLD_INSERT_LIBRARIES",
)

/**
 * Gemini CLI 0.66 loads the folder's `.env` (`.gemini/.env` included) without replacing a variable already set, then
 * runs each hook by `bash -c` with that environment: with `BASH_ENV` or `SHELLOPTS=noexec` from a `.env`, Ostrom's gate
 * never runs (security audit of the CLI n°2, H3).
 */
class GeminiEnvironmentTest {
    private val files = MemoryFiles()

    private fun prepare(layout: SessionLayout): AgentLaunch {
        files.deleteRecursively(join("/tmp/run", "gemini-home"))
        return GeminiAdapter().prepare(layout, files)
    }

    private fun refusal(layout: SessionLayout): String =
        assertFailsWith<AdapterException> { prepare(layout) }.reason

    @Test
    fun aRepositorysEnvCannotSetTheShellOfOstromsHooks() {
        files.texts[join("/work/repo", ".env")] = "BASH_ENV=./evil.sh\nSHELLOPTS=braceexpand:noexec\n"
        files.texts[join("/work/repo", ".gemini", ".env")] = "BASH_ENV=./evil.sh\n"
        val environment = prepare(layout(workspace = listOf("/work/repo"))).environment
        // set, and empty: Gemini leaves them as they are, and bash reads no file and turns no option on
        assertEquals("", environment["BASH_ENV"])
        assertEquals("", environment["SHELLOPTS"])
        val expected = SHELL.associateWith { if (it == "PATH") "/usr/bin" else "" }
        assertEquals(expected, environment.filterKeys { it in SHELL })
    }

    @Test
    fun theDevelopersOwnValuesAreKept() {
        val developers = mapOf(
            "LD_LIBRARY_PATH" to "/opt/lib",
            "NODE_OPTIONS" to "--max-old-space-size=4096",
            "CDPATH" to "/work",
            "ComSpec" to "C:\\Windows\\system32\\cmd.exe",
        )
        val environment = prepare(layout(variables = developers)).environment
        assertEquals(developers, environment.filterKeys { it in developers })
        // the launcher's PATH, this binary's folder first, wins over the developer's
        val launched = prepare(layout(environment = mapOf("PATH" to "/opt:/usr/bin"))).environment
        assertEquals("/opt:/usr/bin", launched["PATH"])
    }

    @Test
    fun geminiIsNotStartedWithAVariableThatRunsBeforeTheGate() {
        for (name in REFUSED) {
            assertEquals(
                "gemini: $name is set in your environment, and it changes what runs before Ostrom's hooks: Ostrom " +
                    "cannot vouch for its gate, so Gemini is not started. Unset it to work with Ostrom",
                refusal(layout(variables = mapOf(name to "x"))),
            )
        }
        // empty is unset
        prepare(layout(variables = mapOf("BASH_ENV" to "")))
    }

    @Test
    fun geminiIsNotStartedWithoutAPath() {
        assertEquals(
            "gemini: PATH is not set, and Gemini looks bash up in it to run Ostrom's hooks, so a repository's .env " +
                "could name another: Gemini is not started. Set PATH to work with Ostrom",
            refusal(layout(variables = mapOf("PATH" to ""))),
        )
    }

    @Test
    fun ostromsHooksAreRunByTheFullPathOfThisBinary() {
        assertEquals(
            "gemini: Ostrom does not know the full path of its own binary (ostrom), and Gemini would look it up in " +
                "PATH to run Ostrom's hooks, so it is not started. Run Ostrom by its full path to work with Ostrom",
            refusal(layout(executable = "ostrom")),
        )
        prepare(layout(executable = "/opt/ostrom"))
    }

    @Test
    fun everyOstromVariableIsSetTheLaunchersOrTheDevelopersOrEmpty() {
        val launcher = mapOf(
            LaunchVariables.RELAY_URL to "http://127.0.0.1:4100",
            LaunchVariables.DEVICE_TOKEN to null,
            LaunchVariables.PACKAGE to null,
        )
        val developers = mapOf("OSTROM_HOME" to "/cfg", "OSTROM_TOKEN" to "device-token")
        val environment = prepare(layout(variables = developers, environment = launcher)).environment
        assertEquals("http://127.0.0.1:4100", environment["OSTROM_RELAY_URL"])
        assertEquals("/cfg", environment["OSTROM_HOME"])
        // removed by the launcher: empty, never the developer's
        assertEquals("", environment["OSTROM_TOKEN"])
        for (name in listOf("OSTROM_PACKAGE", "OSTROM_PACKAGE_SEAL", "OSTROM_PACKAGE_MANIFEST", "OSTROM_GIT")) {
            assertEquals("", environment[name], name)
        }
        assertEquals("", environment["OSTROM_MODEL"])
        assertEquals("gemini", environment["OSTROM_HOOK_DIALECT"], "the adapter's own win")
        assertTrue(LaunchVariables.ALL.all { it in environment }, "every OSTROM_ variable the CLI reads")
    }

    @Test
    fun geminiIsNotStartedInAFolderTheDeveloperDoesNotTrust() {
        val trusted = join("/home/dev", ".gemini", "trustedFolders.json")
        files.texts[trusted] = """{"/work": "DO_NOT_TRUST"}"""
        assertEquals(
            "gemini: $trusted does not trust /work/repo (DO_NOT_TRUST), and Gemini then runs none of the settings' " +
                "hooks, Ostrom's included, so it is not started. Remove that entry to work with Ostrom",
            refusal(layout(workspace = listOf("/work/repo"))),
        )
        prepare(layout(workspace = listOf("/other")))
        files.texts[trusted] = """{"C:\\Work": "DO_NOT_TRUST"}"""
        assertTrue("(DO_NOT_TRUST)" in refusal(layout(workspace = listOf("c:\\work\\repo"))))
        files.texts[trusted] = """{"/work": "DO_NOT_TRUST"}"""
        // as Gemini matches: the longest rule wins, a parent's TRUST_PARENT names the parent, and case is not told
        files.texts[trusted] = """{"/work": "DO_NOT_TRUST", "/work/repo/x": "TRUST_PARENT"}"""
        prepare(layout(workspace = listOf("/work/repo")))
        files.texts[trusted] = """{"/Work/": "DO_NOT_TRUST", "/workshop": "TRUST_FOLDER"}"""
        assertTrue("does not trust /work/repo" in refusal(layout(workspace = listOf("/work/repo"))))
        prepare(layout(workspace = listOf("/workshop")))
        // the developer's own file, named by their variable, with comments as Gemini reads it
        files.texts["/dev/trusted.json"] = "{// mine\n\"/\": \"DO_NOT_TRUST\"}"
        val named = layout(variables = mapOf("GEMINI_CLI_TRUSTED_FOLDERS_PATH" to "/dev/trusted.json"))
        assertTrue(refusal(named.copy(workspace = listOf("/elsewhere"))).startsWith("gemini: /dev/trusted.json"))
        files.texts[trusted] = "[]"
        assertEquals(
            "gemini: $trusted cannot be read as JSON: Ostrom cannot tell whether it takes the trust away from this " +
                "folder, so Gemini is not started",
            refusal(layout(workspace = listOf("/work/repo"))),
        )
        files.texts[trusted] = "  "
        prepare(layout(workspace = listOf("/work/repo")))
    }

    @Test
    fun linksAreFollowedAsGeminiFollowsThem() {
        val trusted = join("/home/dev", ".gemini", "trustedFolders.json")
        // a link to a folder the developer does not trust: Gemini follows it, and runs none of the hooks
        files.links["/lien"] = "/vrai/projet"
        files.texts[trusted] = """{"/vrai/projet": "DO_NOT_TRUST"}"""
        assertTrue("does not trust /lien (DO_NOT_TRUST)" in refusal(layout(workspace = listOf("/lien"))))
        // a rule written through a link
        files.links["/raccourci"] = "/vrai"
        files.texts[trusted] = """{"/raccourci/projet": "DO_NOT_TRUST"}"""
        assertTrue("does not trust /vrai/projet" in refusal(layout(workspace = listOf("/vrai/projet"))))
        // TRUST_PARENT through a link names the folder, longer than the rule that distrusts its parent
        files.texts[trusted] = """{"/vrai": "DO_NOT_TRUST", "/raccourci/projet/x": "TRUST_PARENT"}"""
        assertNull(started(layout(workspace = listOf("/vrai/projet"))))
    }

    private fun started(layout: SessionLayout): String? = try {
        prepare(layout)
        null
    } catch (refused: AdapterException) {
        refused.reason
    }

    private fun layout(
        variables: Map<String, String> = emptyMap(),
        environment: Map<String, String?> = emptyMap(),
        workspace: List<String> = emptyList(),
        executable: String = "/opt/ostrom",
    ): SessionLayout {
        val all = mapOf("PATH" to "/usr/bin") + variables
        return SessionLayout(
            temporary = "/tmp/run",
            plugin = join("/tmp/run", "plugin"),
            executable = executable,
            rules = "",
            arguments = emptyList(),
            pack = null,
            home = "/home/dev",
            variable = { all[it]?.ifEmpty { null } },
            relay = RelayView("http://127.0.0.1:4100", "relay-token", listOf("ostrom"), false),
            workspace = workspace,
            environment = environment,
        )
    }
}
