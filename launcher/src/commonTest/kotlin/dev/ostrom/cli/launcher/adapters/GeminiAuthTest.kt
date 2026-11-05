// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.adapters

import dev.ostrom.cli.launcher.MemoryFiles
import dev.ostrom.cli.launcher.join
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Gemini CLI 0.66 loads one `.env`, the first found from the working folder up, the home's only when no folder has
 * one: a repository's `.env` naming `GEMINI_API_KEY` or `GOOGLE_CLOUD_PROJECT` would send the session's prompts to the
 * repository's account. Ostrom pins them to the developer's (security audit of the CLI n°2, H3, inventory annex A
 * § 4.4).
 */
class GeminiAuthTest {
    private val files = MemoryFiles().apply {
        texts[join("/usr/bin", "bash")] = ""
        texts[join("/work/repo", ".env")] = "GEMINI_API_KEY=repo-key\nGOOGLE_CLOUD_PROJECT=repo-project\n"
        texts[join("/work/repo", ".gemini", ".env")] = "GOOGLE_GENAI_USE_VERTEXAI=true\nGEMINI_MODEL=repo-model\n"
    }

    private fun environment(variables: Map<String, String> = emptyMap()): Map<String, String?> {
        files.deleteRecursively(join("/tmp/run", "gemini-home"))
        val all = mapOf("PATH" to "/usr/bin") + variables
        val layout = SessionLayout(
            temporary = "/tmp/run",
            plugin = join("/tmp/run", "plugin"),
            executable = "/opt/ostrom",
            rules = "",
            arguments = emptyList(),
            pack = null,
            home = "/home/dev",
            variable = { all[it]?.ifEmpty { null } },
            relay = RelayView("http://127.0.0.1:4100", "relay-token", listOf("ostrom"), false),
            workspace = listOf("/work/repo"),
            // the repository's .env is loaded only when the developer trusts it (GeminiRepositoryEnvTest)
            trustProject = true,
        )
        return GeminiAdapter().prepare(layout, files).environment.filterKeys { it in GeminiAuth.NAMES }
    }

    @Test
    fun aRepositorysEnvCannotChooseGeminisAccount() {
        // set, and empty: Gemini leaves them as they are, and reads empty as unset
        assertEquals(GeminiAuth.NAMES.associateWith { "" }, environment())
    }

    @Test
    fun theDevelopersEnvironmentComesFirst() {
        files.texts[join("/home/dev", ".gemini", ".env")] = "GEMINI_API_KEY=home-key\n"
        val pinned = environment(mapOf("GEMINI_API_KEY" to "shell-key", "GOOGLE_CLOUD_LOCATION" to "europe-west1"))
        assertEquals("shell-key", pinned["GEMINI_API_KEY"])
        assertEquals("europe-west1", pinned["GOOGLE_CLOUD_LOCATION"])
        assertEquals("", pinned["GOOGLE_CLOUD_PROJECT"])
    }

    @Test
    fun theHomesEnvIsReadAsGeminiReadsIt() {
        // .gemini/.env first, and only it when it is there; else .env
        files.texts[join("/home/dev", ".env")] = "GEMINI_API_KEY=dotenv-key\nGOOGLE_CLOUD_PROJECT=dotenv-project\n"
        assertEquals("dotenv-key", environment()["GEMINI_API_KEY"])
        files.texts[join("/home/dev", ".gemini", ".env")] = "export GEMINI_API_KEY=\"gemini-key\" # mine\n"
        val pinned = environment()
        assertEquals("gemini-key", pinned["GEMINI_API_KEY"])
        assertEquals("", pinned["GOOGLE_CLOUD_PROJECT"], "the home's .env is not read after its .gemini/.env")
    }

    @Test
    fun geminisHomeIsTheDevelopersWhenTheyNameOne() {
        files.texts[join("/home/dev", ".gemini", ".env")] = "GEMINI_API_KEY=home-key\n"
        files.texts[join("/srv/gemini", ".gemini", ".env")] = "GEMINI_API_KEY=named-key\n"
        assertEquals("named-key", environment(mapOf("GEMINI_CLI_HOME" to "/srv/gemini"))["GEMINI_API_KEY"])
    }
}
