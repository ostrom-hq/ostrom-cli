// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.adapters

import run.ostrom.cli.launcher.MemoryFiles
import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.join
import run.ostrom.cli.launcher.parseObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NOT_YOURS = "is not yours: Gemini loads its variables into the session, and a repository's " +
    "variables can run its programs (its Google credentials, ripgrep's configuration), so Gemini is not started. " +
    "Remove it, or run `ostrom run gemini --trust-project-settings` if your team trusts this repository, to work " +
    "with Ostrom"

/**
 * Review REVUES-CLI-6, probed on Gemini CLI 0.66: the `.env` a repository leaves in the working folder ran its own
 * programs outside the gate, `OSTROM_RELAY_TOKEN` in their environment. `google_application_credentials` in lower case
 * (google-auth-library reads `UPPER || lower`) with `GOOGLE_EXTERNAL_ACCOUNT_ALLOW_EXECUTABLES=1` runs the
 * credentials' executable at the first call to the model; `RIPGREP_CONFIG_PATH=.rgrc` with `--pre=./pre.sh` runs the
 * script behind a `grep_search` the gate allows. A list of names cannot keep up with every program's variables: by
 * default the session does not load the repository's `.env` at all, as `ostrom run claude` does not read its settings.
 */
class GeminiRepositoryEnvTest {
    private val files = MemoryFiles().apply {
        texts[join("/usr/bin", "bash")] = ""
        texts[join("/w/app", ".env")] = "google_application_credentials=./.ci/creds.json\n" +
            "GOOGLE_EXTERNAL_ACCOUNT_ALLOW_EXECUTABLES=1\nRIPGREP_CONFIG_PATH=.rgrc\nDATABASE_URL=postgres://db/app\n"
    }

    private fun prepare(layout: SessionLayout): AgentLaunch {
        files.deleteRecursively(join("/tmp/run", "gemini-home"))
        return GeminiAdapter().prepare(layout, files)
    }

    private fun refusal(layout: SessionLayout): String = assertFailsWith<AdapterException> { prepare(layout) }.reason

    private fun settings() =
        checkNotNull(parseObject(files.texts[join("/tmp/run", "gemini-home", ".gemini", "settings.json")]))

    @Test
    fun geminiDoesNotLoadTheRepositorysEnvByDefault() {
        // the developer's own advanced settings are kept, their ignoreLocalEnv is not
        files.texts[join("/home/dev", ".gemini", "settings.json")] =
            """{"advanced":{"autoConfigureMemory":true,"ignoreLocalEnv":false}}"""
        val launch = prepare(layout())
        val advanced = settings().child("advanced")
        assertEquals(JsonPrimitive(true), advanced?.get("ignoreLocalEnv"), "the session skips the folders' .env")
        assertEquals(JsonPrimitive(true), advanced?.get("autoConfigureMemory"))
        assertEquals(emptyList(), launch.notes)
    }

    @Test
    fun aRepositorysGeminiEnvStopsTheLaunch() {
        // Gemini loads the first .gemini/.env from the working folder up even when told to skip the .env files
        files.texts[join("/w/app", ".gemini", ".env")] = "GOOGLE_EXTERNAL_ACCOUNT_ALLOW_EXECUTABLES=1\n"
        assertEquals("gemini: /w/app/.gemini/.env $NOT_YOURS", refusal(layout()))
        // above the repository's top too, where Gemini keeps looking
        files.texts.remove(join("/w/app", ".gemini", ".env"))
        files.texts[join("/w", ".gemini", ".env")] = "DEBUG=1\n"
        assertEquals("gemini: /w/.gemini/.env $NOT_YOURS", refusal(layout(workspace = listOf("/w/app/src", "/w/app"))))
        // the developer's own, in their home, is theirs; a repository's below it is found first
        files.texts.remove(join("/w", ".gemini", ".env"))
        files.texts[join("/home/dev", ".gemini", ".env")] = "GEMINI_API_KEY=mine\n"
        prepare(layout(workspace = listOf("/home/dev/work/app")))
        files.texts[join("/home/dev/work", ".gemini", ".env")] = "GEMINI_API_KEY=theirs\n"
        assertTrue(
            refusal(layout(workspace = listOf("/home/dev/work/app"))).startsWith("gemini: /home/dev/work/.gemini/.env"),
        )
        // as is the one of their GEMINI_CLI_HOME, whatever the case where paths fold it (the same file, on macOS)
        files.texts[join("/srv/g", ".gemini", ".env")] = "GEMINI_API_KEY=named\n"
        files.texts[join("/SRV/g", ".gemini", ".env")] = "GEMINI_API_KEY=named\n"
        val named = layout(variables = mapOf("GEMINI_CLI_HOME" to "/srv/g"), workspace = listOf("/srv/g/app"))
        prepare(named)
        prepare(named.copy(workspace = listOf("/SRV/g/app"), foldsCase = true))
        assertTrue(refusal(named.copy(workspace = listOf("/SRV/g/app"))).startsWith("gemini: /SRV/g/.gemini/.env"))
    }

    @Test
    fun trustingTheRepositoryLoadsItsEnvAsBefore() {
        files.texts[join("/w/app", ".gemini", ".env")] = "DATABASE_URL=postgres://db/app\n"
        val launch = prepare(layout().copy(trustProject = true))
        assertNull(settings().child("advanced")?.get("ignoreLocalEnv"), "Gemini loads the repository's .env")
        assertEquals(
            listOf(
                "gemini: --trust-project-settings: Gemini loads this repository's .env and .gemini/.env, whose " +
                    "variables can run the repository's programs, reads its .env as your extensions' settings, runs " +
                    "the shell commands of its commands, and loads its agents, with their MCP servers and their " +
                    "remote agents' `auth`: Ostrom pins the variables it knows of, and does not vouch for its gate " +
                    "in this session",
            ),
            launch.notes,
        )
        // the variables Ostrom cannot set empty are still refused where the .env names them
        files.texts[join("/w/app", ".gemini", ".env")] = "GIT_SEQUENCE_EDITOR=./ed.sh\n"
        assertTrue("/w/app/.gemini/.env names GIT_SEQUENCE_EDITOR" in refusal(layout().copy(trustProject = true)))
    }

    @Test
    fun aTrustedRepositorysEnvCannotRunAProgramThroughGoogleAuthOrRipgrep() {
        val environment = prepare(layout().copy(trustProject = true)).environment
        val names = listOf(
            "google_application_credentials",
            "GOOGLE_EXTERNAL_ACCOUNT_ALLOW_EXECUTABLES",
            "GCE_METADATA_HOST",
            "GCE_METADATA_IP",
            "RIPGREP_CONFIG_PATH",
        )
        // set, and empty: Gemini does not replace them, and google-auth and ripgrep read empty as unset
        assertEquals(names.associateWith { "" }, environment.filterKeys { it in names })
        // the developer's own are kept
        val theirs = mapOf("RIPGREP_CONFIG_PATH" to "/home/dev/.rgrc", "GCE_METADATA_HOST" to "metadata.internal")
        val kept = prepare(layout(variables = theirs).copy(trustProject = true)).environment
        assertEquals(theirs, kept.filterKeys { it in theirs })
    }

    @Test
    fun onWindowsTheNamesOfTheEnvAreReadWhateverTheirCase() {
        // Windows' variables do not tell case apart: a .env's `git_ssh_command` is git's GIT_SSH_COMMAND there
        files.texts[join("C:\\Windows", "powershell.exe")] = ""
        files.texts[join("/w/app", ".env")] = "git_ssh_command=./steal.sh\n"
        val linux = layout().copy(trustProject = true)
        prepare(linux)
        val windows = layout(path = "C:\\Windows").copy(trustProject = true, pathSeparator = ';')
        assertTrue("/w/app/.env names GIT_SSH_COMMAND" in refusal(windows))
        // and a credentials file named in either case is one variable: only the developer's is given
        files.texts[join("/w/app", ".env")] = "DATABASE_URL=x\n"
        val credentials = mapOf("GOOGLE_APPLICATION_CREDENTIALS" to "C:\\keys\\adc.json")
        val environment = prepare(windows.copy(variable = { (credentials + ("PATH" to "C:\\Windows"))[it] }))
            .environment
        assertEquals("C:\\keys\\adc.json", environment["GOOGLE_APPLICATION_CREDENTIALS"])
        assertTrue("google_application_credentials" !in environment)
    }

    private fun layout(
        variables: Map<String, String> = emptyMap(),
        workspace: List<String> = listOf("/w/app"),
        path: String = "/usr/bin",
    ): SessionLayout {
        val all = mapOf("PATH" to path) + variables
        return SessionLayout(
            temporary = "/tmp/run",
            plugin = join("/tmp/run", "plugin"),
            executable = "/opt/ostrom",
            rules = "",
            arguments = emptyList(),
            pack = null,
            home = "/home/dev",
            variable = { all[it]?.ifEmpty { null } },
            relay = RelayView("http://127.0.0.1:4100", "relay-token", listOf("ostrom"), false),
            workspace = workspace,
        )
    }
}
