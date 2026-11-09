// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.imports

/** One row of a table: its columns by name, null for SQL `NULL`. */
typealias Row = Map<String, String?>

/**
 * Reads what `mysql --batch` prints for a script whose every table follows a `SELECT '<name>' AS ostrom_table`
 * marker: a header line, then one line per row, tab-separated, with `\n`, `\t`, `\\` and `\0` escaped and `NULL` for
 * SQL null. A table without rows prints nothing, not even its header.
 */
object MysqlBatch {
    /** The column name of the markers. */
    const val MARKER = "ostrom_table"

    /** The tables of [output], by name, in order. */
    fun parse(output: String): Map<String, List<Row>> {
        val tables = linkedMapOf<String, MutableList<Row>>()
        var rows: MutableList<Row>? = null
        var header: List<String>? = null
        var naming = false
        for (line in output.lines().filter { it.isNotEmpty() }) {
            val columns = header
            when {
                line == MARKER -> naming = true
                naming -> {
                    rows = tables.getOrPut(line) { mutableListOf() }
                    header = null
                    naming = false
                }
                columns == null -> header = line.split('\t')
                else -> rows?.add(columns.zip(line.split('\t').map(::valueOf)).toMap())
            }
        }
        return tables
    }

    /** A field as `mysql --batch` printed it. */
    fun valueOf(field: String): String? = if (field == "NULL") null else unescape(field)

    private fun unescape(field: String): String = buildString {
        var i = 0
        while (i < field.length) {
            val c = field[i]
            val next = field.getOrNull(i + 1)
            if (c == '\\' && next != null) {
                append(ESCAPES[next] ?: next)
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }

    private val ESCAPES = mapOf('n' to '\n', 't' to '\t', '0' to '\u0000', '\\' to '\\')
}
