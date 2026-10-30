// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.gate

/** A git that answers from a table: `"<dir>|<args joined by spaces>"` to its output; anything else fails (""). */
class FakeGit(private val answers: Map<String, String> = emptyMap()) : GitRunner {
    val calls = mutableListOf<String>()

    override fun run(directory: String, arguments: List<String>): String {
        val key = "$directory|${arguments.joinToString(" ")}"
        calls += key
        return answers[key].orEmpty()
    }
}
