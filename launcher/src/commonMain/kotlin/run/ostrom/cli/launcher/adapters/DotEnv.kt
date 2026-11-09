// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.adapters

/**
 * A `.env` read as Gemini CLI reads it (dotenv 16 `parse`): `NAME=value` or `NAME: value`, `export ` allowed before the
 * name, a value in single, double or back quotes kept as written (`\n` and `\r` expanded in double quotes only), an
 * unquoted value trimmed and cut at `#`, and the last of two lines naming the same variable wins.
 *
 * White space is JavaScript's (review REVUES-CLI-5): dotenv's `\s` and `trim` take in the byte order mark and the
 * no-break spaces, which Kotlin's `\s` does not, and a `.env` starting with one set a variable for Gemini that this
 * reading did not see. [GeminiEnvironment] does not rely on this parser alone to refuse a file.
 */
internal object DotEnv {
    /** The variables [text] sets, by name. */
    fun parse(text: String): Map<String, String> =
        LINE.findAll(text.replace(NEWLINE, "\n")).associate { line ->
            val raw = line.groupValues[2].trim { it in SPACES }
            val unquoted = QUOTED.replace(raw, "\$2")
            val value = if (raw.startsWith('"')) unquoted.replace("\\n", "\n").replace("\\r", "\r") else unquoted
            line.groupValues[1] to value
        }

    // JavaScript's white space (ECMA-262 WhiteSpace and LineTerminator), its `\s` and what `trim` removes
    private const val SPACES = "\t\n\u000B\u000C\r           " +
        "       　﻿"
    private const val S = "[$SPACES]"

    private val NEWLINE = Regex("\r\n?")

    // dotenv 16's LINE, `(?:^|^)` written `^`, `\s` written [S]
    private val LINE = Regex(
        """^$S*(?:export$S+)?([\w.-]+)(?:$S*=$S*?|:$S+?)($S*'(?:\\'|[^'])*'|$S*"(?:\\"|[^"])*"|$S*`(?:\\`|[^`])*`|""" +
            """[^#\r\n]+)?$S*(?:#.*)?$""",
        RegexOption.MULTILINE,
    )
    private val QUOTED = Regex("""^(['"`])([\s\S]*)\1$""")
}
