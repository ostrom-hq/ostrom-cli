// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.adapters

/**
 * A `.env` read as Gemini CLI reads it (dotenv 16 `parse`): `NAME=value` or `NAME: value`, `export ` allowed before the
 * name, a value in single, double or back quotes kept as written (`\n` and `\r` expanded in double quotes only), an
 * unquoted value trimmed and cut at `#`, and the last of two lines naming the same variable wins.
 */
internal object DotEnv {
    /** The variables [text] sets, by name. */
    fun parse(text: String): Map<String, String> =
        LINE.findAll(text.replace(NEWLINE, "\n")).associate { line ->
            val raw = line.groupValues[2].trim()
            val unquoted = QUOTED.matchEntire(raw)?.let { it.groupValues[2] } ?: raw
            val value = if (raw.startsWith('"')) unquoted.replace("\\n", "\n").replace("\\r", "\r") else unquoted
            line.groupValues[1] to value
        }

    private val NEWLINE = Regex("\r\n?")

    // dotenv 16's LINE, `(?:^|^)` written `^`
    private val LINE = Regex(
        """^\s*(?:export\s+)?([\w.-]+)(?:\s*=\s*?|:\s+?)(\s*'(?:\\'|[^'])*'|\s*"(?:\\"|[^"])*"|\s*`(?:\\`|[^`])*`|""" +
            """[^#\r\n]+)?\s*(?:#.*)?$""",
        RegexOption.MULTILINE,
    )
    private val QUOTED = Regex("""^(['"`])([\s\S]*)\1$""")
}
