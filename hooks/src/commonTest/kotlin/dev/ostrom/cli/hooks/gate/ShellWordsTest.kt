// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.gate

import kotlin.test.Test
import kotlin.test.assertEquals

class ShellWordsTest {
    @Test
    fun cutsAtSeparatorsButNotInQuotesOrRedirections() {
        assertEquals(
            listOf(listOf("git", "push", "origin", "main", "2>&1"), listOf("tail", "-15")),
            ShellWords.segments("git push origin main 2>&1 | tail -15"),
        )
        assertEquals(
            listOf(listOf("git", "commit", "-m", "a && b"), listOf("git", "push")),
            ShellWords.segments("git commit -m 'a && b' && git push"),
        )
        val segments = ShellWords.segments("cd /x && git push # current branch: main")
        assertEquals(listOf(listOf("cd", "/x"), listOf("git", "push")), segments)
    }

    @Test
    fun readsEscapesDoubleQuotesAndEveryKindOfSeparator() {
        assertEquals(
            listOf(listOf("echo", "a \"b\" c"), listOf("ls"), listOf("pwd"), listOf("id"), listOf("x"), listOf("y")),
            ShellWords.segments("echo \"a \\\"b\\\" c\"; ls || pwd\nid & x |y"),
        )
        assertEquals(listOf(listOf("a b", "c'd")), ShellWords.segments("a\\ b c\\'d"))
        assertEquals(listOf(listOf("cmd", "&>log")), ShellWords.segments("cmd &>log"))
        assertEquals(listOf(listOf("", "x")), ShellWords.segments("'' x"), "an empty quoted word is a word")
    }

    @Test
    fun aCommentRunsToTheEndOfItsLineOnly() {
        assertEquals(listOf(listOf("a"), listOf("b")), ShellWords.segments("a # note\nb"))
        assertEquals(listOf(listOf("a#b")), ShellWords.segments("a#b"), "a # inside a word is not a comment")
        assertEquals(emptyList(), ShellWords.segments("# only a comment"))
        assertEquals(emptyList(), ShellWords.segments(" ;; "))
    }

    @Test
    fun aTrailingBackslashAndAnUnclosedQuoteAreKept() {
        assertEquals(listOf(listOf("a\\")), ShellWords.segments("a\\"))
        assertEquals(listOf(listOf("a", "b c\\")), ShellWords.segments("a \"b c\\"))
        assertEquals(listOf(listOf("x\\y")), ShellWords.segments("'x\\y'"))
        assertEquals(listOf(listOf("a", "b")), ShellWords.segments("a\tb"), "a tab separates words")
        assertEquals(listOf(listOf("sleep", "1")), ShellWords.segments("sleep 1 &"), "a last & runs in the background")
    }
}
