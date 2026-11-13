// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.todo

import run.ostrom.cli.platform.CaptureResult
import run.ostrom.cli.platform.OperatingSystem
import run.ostrom.cli.platform.ProcessCapture
import run.ostrom.core.api.models.Todo
import run.ostrom.core.api.models.TodoCheck
import run.ostrom.core.api.models.TodoLinkKind

/**
 * The `command` check: the stored command in the agent's shell (`sh -c`, `cmd /c` on Windows), its placeholders
 * replaced from the items' links, at most 120 seconds, its exit code compared and the end of its output kept as the
 * proof (300 characters, masked). A value not linked yet, a timeout or a shell that does not start is unknown.
 *
 * @param system The system of this machine (`cmd.exe` on Windows, `sh` elsewhere).
 */
class CommandCheck(private val capture: ProcessCapture, private val system: OperatingSystem) {
    /** Runs [check] of an item of [list]. */
    fun run(list: Todo, check: TodoCheck.Command): CheckOutcome {
        val command = substitute(list, check.run)
        val seconds = check.timeoutSeconds.coerceIn(1, MAX_SECONDS)
        return when (val result = capture.capture(shell(command, check.cwd), seconds * MILLIS_PER_SECOND)) {
            is CaptureResult.Exited -> {
                val tail = Secrets.mask(result.output.trim().takeLast(MAX_OUTPUT))
                val shown = if (tail.isEmpty()) "" else ": $tail"
                if (result.code == check.expectExit) {
                    CheckOutcome(true, "command exited ${result.code}$shown")
                } else {
                    CheckOutcome(false, "command exited ${result.code}, expected ${check.expectExit}$shown")
                }
            }
            CaptureResult.TimedOut -> CheckOutcome(null, "unknown: the command did not finish in $seconds s")
            CaptureResult.NotFound, CaptureResult.Failed -> CheckOutcome(null, NO_SHELL)
        }
    }

    /**
     * The shell line of [command], in [cwd] when given. On POSIX the folder and the command are the shell's own
     * arguments, never pasted into its script, so a folder name cannot add a command.
     */
    private fun shell(command: String, cwd: String?): List<String> = when {
        system == OperatingSystem.WINDOWS ->
            listOf("cmd.exe", "/c", if (cwd == null) command else "cd /d \"$cwd\" && $command")
        cwd == null -> listOf("sh", "-c", command)
        else -> listOf("sh", "-c", "cd -- \"\$1\" && eval \"\$2\"", "sh", cwd, command)
    }

    /**
     * `${pr:<item>.number}`, `${pr:<item>.mergeSha}` and `${release:<item>.tag}`, from what the items are linked to;
     * a value not linked yet makes the check unknown, and nothing runs. Only the characters a number, a SHA or a tag
     * have go into the command.
     */
    private fun substitute(list: Todo, command: String): String = PLACEHOLDER.replace(command) { match ->
        val (kind, id, field) = match.destructured
        val pr = kind == "pr"
        val wanted = if (pr) TodoLinkKind.PR else TodoLinkKind.RELEASE
        val link = list.items.firstOrNull { it.id == id }?.let { item -> item.links.firstOrNull { it.kind == wanted } }
            ?: throw UnknownCheck("waiting for the ${if (pr) "pull request" else "release"} of item $id")
        val value = when (field) {
            "number" -> link.number?.toString()
            "mergeSha" -> link.sha
            else -> link.tag
        } ?: throw UnknownCheck("item $id has no $field yet")
        value.filter { it.isLetterOrDigit() || it in SAFE }
    }

    private companion object {
        const val MAX_SECONDS = 120
        const val MILLIS_PER_SECOND = 1_000
        const val MAX_OUTPUT = 300
        const val SAFE = "._-/"
        const val NO_SHELL = "unknown: the shell could not be started"
        val PLACEHOLDER = Regex("""\$\{(pr|release):([^.}]+)\.(number|mergeSha|tag)}""")
    }
}

/** What looks like a credential, masked before a command's output or an attested text is kept as proof. */
object Secrets {
    private val SHAPES = Regex(
        """(gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|Bearer\s+\S+|ostrom_[A-Za-z0-9_-]{20,}|""" +
            """\b[0-9a-f]{64}\b)""",
    )

    /** [text] with every credential-shaped word replaced by `[REDACTED]`. */
    fun mask(text: String): String = SHAPES.replace(text, "[REDACTED]")
}
