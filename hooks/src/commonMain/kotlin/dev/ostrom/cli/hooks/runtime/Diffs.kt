// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import dev.ostrom.cli.hooks.gate.GitRunner
import dev.ostrom.cli.hooks.gate.Push

/**
 * What a push would send, shown to the approver before the yes (prototype `outgoing_diff`, `pushed_diff`). It is for
 * reading only: the server computes the diff it signs from GitHub, never from a local ref the agent can move (audit
 * A7b).
 */
class Diffs(private val git: GitRunner) {
    /** The commits a push of the current branch would send (upstream..HEAD, else the last commit) and their diff. */
    fun outgoing(directory: String): String {
        val upstream = git.run(directory, listOf("rev-parse", "--abbrev-ref", "@{u}"))
        return diffOf(directory, if (upstream.isEmpty()) "HEAD~1..HEAD" else "@{u}..HEAD")
    }

    /** The commits [push] really sends, ref by ref (what the remote-tracking ref lacks, else the last commit). */
    fun pushed(directory: String, push: Push): String = cut(
        buildString {
            for (ref in push.refs.filter { it.sha.isNotEmpty() }) {
                val tracking = "refs/remotes/${push.remote}/${ref.destination}"
                val known = git.run(directory, listOf("rev-parse", "--verify", "--quiet", tracking)).isNotEmpty()
                val diff = diffOf(directory, if (known) "$tracking..${ref.sha}" else "${ref.sha}~1..${ref.sha}")
                if (diff.isNotEmpty()) append("${ref.destination} ← ${ref.sha.take(SHORT_SHA)}\n$diff\n")
            }
        },
    )

    private fun diffOf(directory: String, range: String): String {
        val log = git.run(directory, listOf("log", "--no-color", "--format=%h %s (%an)", range))
        if (log.isEmpty()) return ""
        val diff = git.run(directory, listOf("diff", "--no-color", "--stat", "--patch", range))
        return cut("$log\n\n$diff")
    }

    /** Text cut at [MAX_BYTES] UTF-8 bytes, never inside a character. */
    companion object {
        /** The most a diff may weigh in a gate request. */
        const val MAX_BYTES = 8_000
        private const val SHORT_SHA = 12
        private const val CONTINUATION_MASK = 0xC0
        private const val CONTINUATION = 0x80

        /** [text] cut to [MAX_BYTES] bytes with a note saying so, or unchanged when it fits. */
        fun cut(text: String): String {
            val bytes = text.encodeToByteArray()
            if (bytes.size <= MAX_BYTES) return text
            var end = MAX_BYTES
            while ((bytes[end].toInt() and CONTINUATION_MASK) == CONTINUATION) end--
            return bytes.decodeToString(0, end) + "\n… (diff cut at 8 000 bytes)"
        }
    }
}
