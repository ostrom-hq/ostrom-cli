// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.help

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.output.HelpFormatter
import com.github.ajalt.clikt.output.MordantHelpFormatter
import com.github.ajalt.mordant.rendering.Whitespace
import com.github.ajalt.mordant.widgets.Text
import dev.ostrom.cli.platform.plainText

/**
 * The help of every command, as gh and stripe write theirs: Clikt's help, ended by the command's [Guide] (examples
 * and its page of the docs); a usage error ends with the command that shows how to use it.
 */
internal class OstromHelpFormatter(context: Context) : MordantHelpFormatter(context) {
    override fun formatHelp(
        error: UsageError?,
        prolog: String,
        epilog: String,
        parameters: List<HelpFormatter.ParameterHelp>,
        programName: String,
    ): String {
        val command = context.commandNameWithParents()
        if (error != null) {
            // on stderr, which may not be the terminal stdout is: never a colour there
            val help = plainText(super.formatHelp(error, prolog, epilog, parameters, programName)).trimEnd()
            return "$help\n\nRun '${command.joinToString(" ")} --help' for usage.\n"
        }
        // the guides are those of the ostrom tree: another command (a test's) has none
        val guide = if (command.first() == ROOT) guideOf(command.drop(1).joinToString(" "))?.render() else null
        val end = listOf(epilog, guide.orEmpty()).filter { it.isNotBlank() }.joinToString("\n\n")
        return super.formatHelp(null, prolog, end, parameters, programName)
    }

    /** The end keeps its lines: one example per line, aligned. */
    override fun renderEpilog(epilog: String) = Text(epilog, whitespace = Whitespace.PRE_WRAP)
}

/** The name of the root of the tree the guides are written for. */
private const val ROOT = "ostrom"
