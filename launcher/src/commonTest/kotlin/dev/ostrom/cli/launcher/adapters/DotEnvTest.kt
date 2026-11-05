// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.adapters

import kotlin.test.Test
import kotlin.test.assertEquals

/** A `.env` read as Gemini CLI reads it (dotenv 16). */
class DotEnvTest {
    @Test
    fun valuesAreReadAsDotenvReadsThem() {
        val text = listOf(
            "# a comment",
            "PLAIN = some value  # cut here",
            "export EXPORTED=yes",
            "COLON: given",
            "SINGLE='a \\n #kept'",
            "DOUBLE=\"line\\nnext\\r\"",
            "BACK=`back #kept`",
            "EMPTY=",
            "TWICE=first",
            "TWICE=second",
            "not a variable",
        ).joinToString("\r\n")
        val expected = mapOf(
            "PLAIN" to "some value",
            "EXPORTED" to "yes",
            "COLON" to "given",
            "SINGLE" to "a \\n #kept",
            "DOUBLE" to "line\nnext\r",
            "BACK" to "back #kept",
            "EMPTY" to "",
            "TWICE" to "second",
        )
        assertEquals(expected, DotEnv.parse(text))
    }

    @Test
    fun whiteSpaceIsJavaScriptsWhiteSpace() {
        // REVUES-CLI-5: dotenv's `\s` is JavaScript's, which takes in the byte order mark and the no-break spaces;
        // read with ASCII's only, a `.env` starting with a BOM or a U+00A0 set GIT_SSH_COMMAND for Gemini (replayed on
        // Gemini CLI 0.66's loader) while Ostrom saw no variable
        val texts = listOf(
            "\uFEFFGIT_SSH_COMMAND=./steal.sh",
            "\u00A0GIT_SSH_COMMAND=./steal.sh",
            "export\u00A0GIT_SSH_COMMAND=./steal.sh",
            "GIT_SSH_COMMAND\u00A0=./steal.sh",
            "\u2003GIT_SSH_COMMAND\u3000=\u202F./steal.sh",
        )
        for (text in texts) assertEquals(mapOf("GIT_SSH_COMMAND" to "./steal.sh"), DotEnv.parse(text), text)
        // and a value is trimmed as JavaScript's `trim` does (the same strings, checked with dotenv's own parse)
        val values = DotEnv.parse("A=﻿ x ﻿\nB=' q' # c")
        assertEquals(mapOf("A" to "x", "B" to " q"), values)
    }

    @Test
    fun nothingIsReadFromAnEmptyFile() {
        assertEquals(emptyMap(), DotEnv.parse(""))
    }
}
