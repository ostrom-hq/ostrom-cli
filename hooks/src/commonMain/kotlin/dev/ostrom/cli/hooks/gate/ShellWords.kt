package dev.ostrom.cli.hooks.gate

/**
 * A shell command cut into its simple commands, each a list of words, the way the gate reads it (port of `segments`
 * in the prototype's `launcher/src/push.rs` and `bridge/plugin/bin/push.mjs`).
 *
 * The cut happens at `&&`, `||`, `;`, `|`, `&` and new lines; quotes are honoured (a separator inside a quoted commit
 * message does not cut), a backslash escapes the next character, `2>&1` and `&>file` stay redirections, and a `#`
 * that starts a word comments out the rest of the line (the note the hook appends, `# ostrom: …`, is left out).
 */
object ShellWords {
    /**
     * @param text The command as the agent wrote it.
     * @return The simple commands, in order, without the empty ones.
     */
    fun segments(text: String): List<List<String>> = Splitter(text).split()

    private class Splitter(private val text: String) {
        private val out = mutableListOf(mutableListOf<String>())
        private val token = StringBuilder()
        private var hasToken = false
        private var quote: Char? = null
        private var i = 0

        fun split(): List<List<String>> {
            while (i < text.length) {
                val q = quote
                if (q != null) quoted(q) else plain(text[i])
                i++
            }
            flush()
            return out.filter { it.isNotEmpty() }
        }

        private fun quoted(q: Char) {
            val c = text[i]
            when {
                c == q -> quote = null
                c == '\\' && q == '"' && i + 1 < text.length -> token.append(text[++i])
                else -> token.append(c)
            }
        }

        private fun plain(c: Char) {
            when {
                c == '\'' || c == '"' -> {
                    quote = c
                    hasToken = true
                }
                c == '\\' && i + 1 < text.length -> append(text[++i])
                c == '#' && !hasToken -> skipComment()
                c == ' ' || c == '\t' -> flush()
                c == '&' && (token.endsWith('>') || text.getOrNull(i + 1) == '>') -> append(c)
                c in SEPARATORS -> separate()
                else -> append(c)
            }
        }

        private fun append(c: Char) {
            token.append(c)
            hasToken = true
        }

        private fun skipComment() {
            while (i + 1 < text.length && text[i + 1] != '\n') i++
        }

        private fun separate() {
            flush()
            if (out.last().isNotEmpty()) out.add(mutableListOf())
            while (i + 1 < text.length && (text[i + 1] == '&' || text[i + 1] == '|')) i++
        }

        private fun flush() {
            if (hasToken) {
                out.last().add(token.toString())
                token.clear()
                hasToken = false
            }
        }
    }

    private val SEPARATORS = charArrayOf('\n', ';', '&', '|')
}
