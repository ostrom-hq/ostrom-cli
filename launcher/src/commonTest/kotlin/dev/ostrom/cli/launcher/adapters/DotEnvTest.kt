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
    fun nothingIsReadFromAnEmptyFile() {
        assertEquals(emptyMap(), DotEnv.parse(""))
    }
}
