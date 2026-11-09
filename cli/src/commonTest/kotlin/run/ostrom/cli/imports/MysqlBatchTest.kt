// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.imports

import kotlin.test.Test
import kotlin.test.assertEquals

class MysqlBatchTest {
    @Test
    fun eachTableFollowsItsMarkerAndAnEmptyOneHasNoRows() {
        val output = "orphan\theader\nx\ty\n" +
            batch("a", listOf("id", "note"), listOf("1", null), listOf("2", "two")) +
            batch("empty", listOf("id")) + batch("b", listOf("id"), listOf("3")) + batch("a", listOf("id"), listOf("4"))
        val tables = MysqlBatch.parse(output)
        assertEquals(listOf("a", "empty", "b"), tables.keys.toList(), "rows before any marker belong to no table")
        val a = listOf(mapOf("id" to "1", "note" to null), mapOf("id" to "2", "note" to "two"), mapOf("id" to "4"))
        assertEquals(a, tables["a"], "a table named twice gathers its rows")
        assertEquals(emptyList(), tables["empty"])
        assertEquals(listOf(mapOf("id" to "3")), tables["b"])
    }

    @Test
    fun escapesAreReadBack() {
        assertEquals("a\nb\tc\\d\u0000e", MysqlBatch.valueOf("a\\nb\\tc\\\\d\\0e"))
        assertEquals("\\x", MysqlBatch.valueOf("\\\\x"))
        assertEquals("q", MysqlBatch.valueOf("\\q"), "an unknown escape keeps its character")
        assertEquals("end\\", MysqlBatch.valueOf("end\\"), "a last backslash stays")
        assertEquals(null, MysqlBatch.valueOf("NULL"))
    }
}
