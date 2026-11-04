// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.adapters

import dev.ostrom.cli.launcher.LaunchFiles
import dev.ostrom.cli.launcher.join

/**
 * The variables that choose Gemini's account, project, credentials and model (Gemini CLI 0.66, security audit of the
 * CLI n°2, H3, inventory annex A § 4.4), pinned to the developer's. Gemini loads a single `.env`, the first found from
 * the working folder up, and the one of the home only when no folder has one: a repository's `.env` would otherwise
 * send the session's prompts to the repository's account. The developer's value is the one in their environment, else
 * the one of the `.env` Gemini reads in their home (`.gemini/.env`, else `.env`), else empty, which Gemini reads as
 * unset.
 */
internal object GeminiAuth {
    /** The pinned variables, by name. */
    fun of(layout: SessionLayout, files: LaunchFiles): Map<String, String> {
        val home = layout.variable(HOME) ?: layout.home
        val text = listOf(join(home, ".gemini", ".env"), join(home, ".env")).firstNotNullOfOrNull { files.read(it) }
        val theirs = DotEnv.parse(text.orEmpty())
        return NAMES.associateWith { layout.variable(it) ?: theirs[it].orEmpty() }
    }

    // Gemini's home, the developer's (Gemini reads its home's .env there)
    private const val HOME = "GEMINI_CLI_HOME"

    /** The variables pinned. */
    val NAMES = listOf(
        "GEMINI_API_KEY",
        "GOOGLE_API_KEY",
        "GOOGLE_CLOUD_PROJECT",
        "GOOGLE_CLOUD_PROJECT_ID",
        "GOOGLE_CLOUD_LOCATION",
        "GOOGLE_GENAI_USE_VERTEXAI",
        "GOOGLE_GENAI_USE_GCA",
        "GOOGLE_CLOUD_ACCESS_TOKEN",
        "GOOGLE_APPLICATION_CREDENTIALS",
        "GEMINI_API_KEY_AUTH_MECHANISM",
        "GEMINI_CLI_USE_COMPUTE_ADC",
        "GEMINI_DEFAULT_AUTH_TYPE",
        "CLOUD_SHELL",
        "GEMINI_MODEL",
    )
}
