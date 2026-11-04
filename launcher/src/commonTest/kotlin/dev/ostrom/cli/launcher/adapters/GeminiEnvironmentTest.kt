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
    // the rest of Gemini CLI 0.66's own list of what changes a program run (BLOCKED_EXECUTION_ENVS)
    "NODE_CLI_FLAGS",
    "_FORCE_NODE_OPTIONS",
    "ELECTRON_RUN_AS_NODE",
    "PYTHONPATH",
    "PYTHONSTARTUP",
    "RUBYOPT",
    "RUBYLIB",
    "PERL5OPT",
    "PERL5LIB",
    "PERL5DB",
    "LD_DEBUG",
    "LD_PROFILE",
    "DYLD_FORCE_FLAT_NAMESPACE",
    "DYLD_FALLBACK_LIBRARY_PATH",
    "DYLD_FALLBACK_FRAMEWORK_PATH",
    "JAVA_TOOL_OPTIONS",
    "_JAVA_OPTIONS",
    "CLASSPATH",
    "DOTNET_STARTUP_HOOKS",
    "CORECLR_PROFILER_PATH",
    "CORECLR_ENABLE_PROFILING",
    // and what Gemini's list lacks: Python's home, and the proxies the hooks' HTTP client would send the relay's
    // token through
    "PYTHONHOME",
    "HTTP_PROXY",
    "HTTPS_PROXY",
    "ALL_PROXY",
    "NO_PROXY",
    "http_proxy",
    "https_proxy",
    "all_proxy",
    "no_proxy",
    // what a repository's .env could still set, from the inventory of Gemini CLI 0.66 (AUDIT-SECU-CLI-H3-INVENTAIRE,
    // annex A § 4): git's configuration, pagers, TLS, endpoints, telemetry, system prompt, sandbox, IDE
    "GIT_CONFIG_COUNT",
    "GIT_CONFIG_PARAMETERS",
    "GIT_CONFIG_NOSYSTEM",
    "GIT_EXTERNAL_DIFF",
    "GIT_EXEC_PATH",
    "GIT_ASKPASS",
    "SSH_ASKPASS",
    "GIT_PROXY_COMMAND",
    "GIT_ALTERNATE_OBJECT_DIRECTORIES",
    "XDG_CONFIG_HOME",
    "PAGER",
    "GIT_PAGER",
    "BROWSER",
    "NODE_TLS_REJECT_UNAUTHORIZED",
    "NODE_EXTRA_CA_CERTS",
    "CODE_ASSIST_ENDPOINT",
    "CODE_ASSIST_API_VERSION",
    "GOOGLE_GEMINI_BASE_URL",
    "GOOGLE_VERTEX_BASE_URL",
    "GEMINI_CLI_CUSTOM_HEADERS",
    "GEMINI_TELEMETRY_ENABLED",
    "GEMINI_TELEMETRY_TARGET",
    "GEMINI_TELEMETRY_OTLP_ENDPOINT",
    "GEMINI_TELEMETRY_OTLP_PROTOCOL",
    "GEMINI_TELEMETRY_OTLP_HEADERS",
    "GEMINI_TELEMETRY_LOG_PROMPTS",
    "GEMINI_TELEMETRY_OUTFILE",
    "GEMINI_TELEMETRY_USE_COLLECTOR",
    "GEMINI_TELEMETRY_USE_CLI_AUTH",
    "GEMINI_TELEMETRY_TRACES_ENABLED",
    "OTEL_EXPORTER_OTLP_ENDPOINT",
    "OTEL_EXPORTER_OTLP_HEADERS",
    "GEMINI_CLI_ACTIVITY_LOG_TARGET",
    "GEMINI_SYSTEM_MD",
    "GEMINI_WRITE_SYSTEM_MD",
    "GEMINI_CONTEXT_TRACE_DIR",
    "GEMINI_EXP",
    "GEMINI_SANDBOX_IMAGE",
    "GEMINI_SANDBOX_PROXY_COMMAND",
    "SANDBOX",
    "SANDBOX_FLAGS",
    "SANDBOX_MOUNTS",
    "SANDBOX_ENV",
    "SANDBOX_PORTS",
    "SANDBOX_SET_UID_GID",
    "BUILD_SANDBOX",
    "SEATBELT_PROFILE",
    "DEBUG",
    "DEBUG_MODE",
    "DEBUG_PORT",
    "GEMINI_CLI_IDE_SERVER_STDIO_COMMAND",
    "GEMINI_CLI_IDE_SERVER_STDIO_ARGS",
    "GEMINI_CLI_IDE_SERVER_PORT",
    "GEMINI_CLI_IDE_AUTH_TOKEN",
    "GEMINI_CLI_IDE_PID",
    "GEMINI_CLI_EXTENSION_REGISTRY_URI",
    "GEMINI_CLI_EXP_AGENT",
    "GEMINI_CLI_INTEGRATION_TEST",
    "VITEST",
    "OAUTH_CALLBACK_HOST",
    "OAUTH_CALLBACK_PORT",
    "npm_config_registry",
    "NPM_CONFIG_REGISTRY",
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
    private val files = MemoryFiles().apply { texts[join("/usr/bin", "bash")] = "" }

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
    fun gitsPlacesTheEditorsAndTheShellAreTheDevelopersOrLeftAsTheyAre() {
        // REVUES-CLI-4: an empty GIT_DIR stops git, an empty GIT_SSH_COMMAND runs nothing (git 2.47): these are never
        // set empty, only to the developer's own value
        val developers = mapOf("GIT_SSH_COMMAND" to "ssh -i /home/dev/.ssh/work", "SHELL" to "/bin/zsh")
        val environment = prepare(layout(variables = developers, workspace = listOf("/work/repo"))).environment
        assertEquals(developers, environment.filterKeys { it in ProgramVariables.KEPT })
    }

    @Test
    fun geminiIsNotStartedWhereTheRepositorysEnvNamesAVariableOstromCannotSetEmpty() {
        val workspace = listOf("/w/app/src", "/w/app")
        files.texts[join("/w/app", ".env")] = "GIT_SSH_COMMAND=./steal.sh\n"
        assertEquals(
            "gemini: /w/app/.env sets GIT_SSH_COMMAND, which changes the programs Gemini and Ostrom's hooks start, " +
                "and Ostrom cannot set it for this session without breaking them, so Gemini is not started. Remove " +
                "it from that file, or set it in your environment, to work with Ostrom",
            refusal(layout(workspace = workspace)),
        )
        // the developer's own value: Gemini does not replace it
        prepare(layout(variables = mapOf("GIT_SSH_COMMAND" to "ssh"), workspace = workspace))
        // Gemini loads only the first .env found from the working folder up
        files.texts[join("/w/app/src", ".gemini", ".env")] = "DEBUG=1\n"
        prepare(layout(workspace = workspace))
        files.texts[join("/w/app/src", ".gemini", ".env")] = "export EDITOR=./ed\n"
        assertTrue("/w/app/src/.gemini/.env sets EDITOR" in refusal(layout(workspace = workspace)))
        // above the repository's top, the .env is the developer's
        prepare(layout(workspace = listOf("/w/app/src/x", "/w/app/src/x")))
        prepare(layout(workspace = listOf("/elsewhere", "/w/app/src")))
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
    fun geminiIsNotStartedWithoutABashOnItsPath() {
        // Gemini runs its hooks with the bash of its PATH: without one, the spawn fails, and the tool runs ungated
        assertEquals(
            "gemini: there is no bash on the PATH Ostrom gives it (/opt/bin:/usr/local/bin), and Gemini runs " +
                "Ostrom's hooks with it: without one they fail open, so Gemini is not started. Install bash, or " +
                "put its folder on your PATH, to work with Ostrom",
            refusal(layout(environment = mapOf("PATH" to "/opt/bin:/usr/local/bin"))),
        )
        // nothing on it at all
        assertTrue("on the PATH Ostrom gives it ()" in refusal(layout(environment = mapOf("PATH" to null))))
        // Windows runs the hooks with PowerShell, not bash
        files.texts[join("C:\\Windows", "powershell.exe")] = ""
        prepare(layout(environment = mapOf("PATH" to "C:\\Windows")).copy(pathSeparator = ';'))
        // a bash of the repository's is the repository's program, even ahead of the system's
        files.texts[join("/work/repo/bin", "bash")] = ""
        assertEquals(
            "gemini: the bash Gemini would run Ostrom's hooks with (/work/repo/bin/bash) is inside /work/repo, so " +
                "the repository chooses it: Gemini is not started. Put one from outside the repository first on your " +
                "PATH to work with Ostrom",
            refusal(layout(environment = mapOf("PATH" to "/work/repo/bin:/usr/bin"), workspace = listOf("/work/repo"))),
        )
    }

    @Test
    fun onWindowsGeminiRunsItsHooksWithAPowerShellFromOutsideTheRepository() {
        val system = "C:\\Windows\\System32\\WindowsPowerShell\\v1.0"
        val seven = "C:\\Program Files\\PowerShell\\7"
        val path = "C:\\work\\Repo\\bin;$system;$seven"
        fun windows(comSpec: String = "C:\\Windows\\system32\\cmd.exe") = layout(
            variables = mapOf("ComSpec" to comSpec),
            environment = mapOf("PATH" to path),
            workspace = listOf("C:\\work\\repo"),
        ).copy(pathSeparator = ';', foldsCase = true)
        assertEquals(
            "gemini: there is no PowerShell where Gemini looks for it (ComSpec, then the PATH Ostrom gives it: " +
                "$path), and Gemini runs Ostrom's hooks with it: without one they fail open, so Gemini is not " +
                "started. Install PowerShell, or put its folder on your PATH, to work with Ostrom",
            refusal(windows()),
        )
        // Windows PowerShell, when there is no pwsh; Windows looks a bare name up in the current folder first unless
        // told not to, and Gemini runs `powershell.exe` by its bare name
        files.texts[join(system, "powershell.exe")] = ""
        assertEquals("1", prepare(windows()).environment["NoDefaultCurrentDirectoryInExePath"])
        assertNull(prepare(layout()).environment["NoDefaultCurrentDirectoryInExePath"])
        // the first one on the PATH is the repository's, whatever its case
        files.texts[join("C:\\work\\Repo\\bin", "powershell.exe")] = ""
        assertEquals(
            "gemini: the PowerShell Gemini would run Ostrom's hooks with (C:\\work\\Repo\\bin\\powershell.exe) is " +
                "inside C:\\work\\repo, so the repository chooses it: Gemini is not started. Put one from outside " +
                "the repository first on your PATH to work with Ostrom",
            refusal(windows()),
        )
        // Gemini takes pwsh first, wherever it is on the PATH, with the extensions Windows tries
        files.texts[join(seven, "pwsh.exe.cmd")] = ""
        prepare(windows())
        files.texts[join("C:\\work\\Repo\\bin", "pwsh.exe")] = ""
        assertTrue("(C:\\work\\Repo\\bin\\pwsh.exe) is inside" in refusal(windows()))
        // and ComSpec before both, when it names PowerShell
        files.texts["C:\\Tools\\PowerShell.exe"] = ""
        prepare(windows(comSpec = "C:\\Tools\\PowerShell.exe"))
        val committed = "C:\\work\\repo\\tools\\pwsh.exe"
        files.texts[committed] = ""
        assertTrue("($committed) is inside" in refusal(windows(comSpec = committed)))
        assertTrue("there is no PowerShell" in refusal(windows(comSpec = "D:\\pwsh.exe")))
        assertEquals(
            "gemini: ComSpec names PowerShell without its full path (pwsh.exe), and Gemini would look it up from " +
                "the repository to run Ostrom's hooks, so it is not started. Set ComSpec to a full path to work " +
                "with Ostrom",
            refusal(windows(comSpec = "pwsh.exe")),
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
        val windows = layout(workspace = listOf("c:\\work\\repo")).copy(pathSeparator = ';', foldsCase = true)
        assertTrue("(DO_NOT_TRUST)" in refusal(windows))
        files.texts[trusted] = """{"/work": "DO_NOT_TRUST"}"""
        // as Gemini matches: the longest rule wins, a parent's TRUST_PARENT names the parent, and case is not told
        files.texts[trusted] = """{"/work": "DO_NOT_TRUST", "/work/repo/x": "TRUST_PARENT"}"""
        prepare(layout(workspace = listOf("/work/repo")))
        // case is told apart as Gemini tells it: not on macOS and Windows, but on Linux
        val linux = layout(workspace = listOf("/work/repo"))
        val macos = linux.copy(foldsCase = true)
        files.texts[trusted] = """{"/Work/": "DO_NOT_TRUST", "/workshop": "TRUST_FOLDER"}"""
        assertTrue("does not trust /work/repo" in refusal(macos))
        assertNull(started(linux))
        files.texts[trusted] = """{"/work": "DO_NOT_TRUST", "/work/Repo": "TRUST_FOLDER"}"""
        assertTrue("does not trust /work/repo" in refusal(linux))
        assertNull(started(macos))
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
        // `..` is taken as written, then links followed, as Node's realpathSync: /a/lien/../projet is /a/projet, even
        // where /a/lien leads to /b/x
        files.links["/a/lien"] = "/b/x"
        files.texts[trusted] = """{"/a/projet": "DO_NOT_TRUST"}"""
        assertTrue("does not trust /a/lien/../projet" in refusal(layout(workspace = listOf("/a/lien/../projet"))))
        files.texts[trusted] = """{"/a/lien/../projet": "DO_NOT_TRUST"}"""
        assertTrue("does not trust /a/projet" in refusal(layout(workspace = listOf("/a/projet"))))
        files.texts[trusted] = """{"/b/projet": "DO_NOT_TRUST"}"""
        assertNull(started(layout(workspace = listOf("/a/lien/../projet"))))
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
