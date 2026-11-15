// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.waits

/**
 * The values of the secret fields, one each, read without echo from the terminal ([hidden]) or, with [fromStdin], from
 * standard input: [line] per field, or everything for a single field (a multi-line key). Nothing is echoed or logged;
 * [prompt] names the field only.
 *
 * @param fields The secret fields to read, in order.
 * @param fromStdin Whether to read standard input instead of the terminal.
 * @param prompt Writes the question for one field (to stderr).
 * @param hidden Reads one line typed without echo, or null at the end of the input.
 * @param line Reads one line of standard input, or null at its end.
 *
 * @return The value of each field.
 */
internal fun readSecrets(
    fields: List<String>,
    fromStdin: Boolean,
    prompt: (String) -> Unit,
    hidden: () -> String?,
    line: () -> String?,
): Map<String, String> {
    return if (fromStdin && fields.size == 1) {
        mapOf(fields.single() to generateSequence(line).joinToString("\n").trimEnd('\n', '\r'))
    } else {
        fields.associateWith { field ->
            if (!fromStdin) prompt("Secret for $field (hidden): ")
            (if (fromStdin) line() else hidden()).orEmpty().trimEnd('\n', '\r')
        }
    }
}
