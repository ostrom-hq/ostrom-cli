// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.help

import com.github.ajalt.mordant.rendering.AnsiLevel
import run.ostrom.cli.platform.Environment

/**
 * The colours of help, decided before the line is parsed, so that a help printed before any command runs follows
 * them too (a usage error, on stderr, never has any). In order: `--no-color` among the options of `ostrom` itself,
 * `NO_COLOR` set and not empty (no-color.org), `ostrom config set color never|always`, `TERM=dumb`, and last whether
 * stdout is a terminal: never a colour in a pipe, a file, a CI log or an agent.
 *
 * @param args The command line, the options of `ostrom` first.
 * @param setting The `color` setting of this device (`auto`, `always`, `never`), null when unset.
 * @param interactive Whether stdout is a terminal.
 *
 * @return [AnsiLevel.NONE] for no colour, [AnsiLevel.ANSI16] when forced, null to let the terminal say how many.
 */
internal fun colourLevel(args: List<String>, environment: Environment, setting: String?, interactive: Boolean) =
    when {
        NO_COLOR_OPTION in args.takeWhile { it.startsWith("-") && it != "--" } -> AnsiLevel.NONE
        !environment["NO_COLOR"].isNullOrEmpty() -> AnsiLevel.NONE
        setting == "never" -> AnsiLevel.NONE
        setting == "always" -> AnsiLevel.ANSI16
        environment["TERM"] == "dumb" || !interactive -> AnsiLevel.NONE
        else -> null
    }

/** The option of `ostrom` that turns colours off, like `NO_COLOR`. */
internal const val NO_COLOR_OPTION = "--no-color"
