// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.commands

import com.github.ajalt.clikt.completion.CompletionCandidates
import com.github.ajalt.clikt.core.BaseCliktCommand

/*
 * The tab completion of the four shells, from one table: each visible command path ("" for ostrom itself,
 * "receipts list") maps to the words that may follow it, its visible subcommands, the fixed values of its arguments,
 * then its visible options. Each script walks the words before the cursor down that table, skips the value after an
 * option that takes one, and offers the words of the deepest path that start with what is typed. Hidden commands
 * (the plumbing agents call) and hidden options are never offered, in any shell.
 */

private const val DOLLAR = "$"

/** The script of [shell] (bash, zsh, fish or powershell) for the tree under [root], ending with a newline. */
internal fun completionScript(root: BaseCliktCommand<*>, shell: String): String {
    val paths = completionPaths(root)
    val valued = valuedOptions(root)
    val script = when (shell) {
        "bash" -> "# ostrom completion for bash. Load it from ~/.bashrc: source <(ostrom completion bash)\n" +
            bash(paths, valued)
        "zsh" -> zsh(paths, valued)
        "fish" -> fish(paths, valued)
        else -> powerShell(paths, valued)
    }
    return script.trimEnd() + "\n"
}

/** Every visible command below [command] with the words that may follow it, the command itself first. */
internal fun completionPaths(command: BaseCliktCommand<*>, path: String = ""): List<Pair<String, List<String>>> {
    val children = command.registeredSubcommands().filterNot { it.hiddenFromHelp }
    val values = command.registeredArguments().flatMap {
        (it.completionCandidates as? CompletionCandidates.Fixed)?.candidates.orEmpty()
    }
    val options = command.registeredOptions().filterNot { it.hidden }.flatMap { it.names + it.secondaryNames }
    val here = path to children.map { it.commandName } + values + options.sorted()
    return listOf(here) + children.flatMap { completionPaths(it, "$path ${it.commandName}".trimStart()) }
}

/** The names of the options of the tree that take a value (`--server <url>`): the next word is never a command. */
internal fun valuedOptions(command: BaseCliktCommand<*>): List<String> {
    val here = command.registeredOptions().filter { it.nvalues.last > 0 }.flatMap { it.names }
    return (here + command.registeredSubcommands().flatMap { valuedOptions(it) }).distinct().sorted()
}

private fun bash(paths: List<Pair<String, List<String>>>, valued: List<String>): String {
    val skip = if (valued.isEmpty()) "" else """
        |        case ${DOLLAR}word in
        |            ${valued.joinToString("|") { shQuoted(it) }}) ((i++)); continue ;;
        |        esac""".trimMargin() + "\n"
    val known = paths.drop(1).joinToString("|") { shQuoted(it.first) }
    val descend = if (known.isEmpty()) "" else """
        |        case ${DOLLAR}next in
        |            $known) at=${DOLLAR}next ;;
        |        esac""".trimMargin() + "\n"
    val cases = paths.joinToString("\n") { (path, words) ->
        "        ${shQuoted(path)}) candidates=${shQuoted(words.joinToString(" "))} ;;"
    }
    return """
        |_ostrom() {
        |    local at='' word next candidates='' i
        |    for ((i = 1; i < COMP_CWORD; i++)); do
        |        word=${DOLLAR}{COMP_WORDS[i]}
        |$skip        next=${DOLLAR}{at:+${DOLLAR}at }${DOLLAR}word
        |$descend    done
        |    case ${DOLLAR}at in
        |$cases
        |    esac
        |    COMPREPLY=(${DOLLAR}(compgen -W "${DOLLAR}candidates" -- "${DOLLAR}{COMP_WORDS[COMP_CWORD]}"))
        |}
        |complete -F _ostrom ostrom
        |""".trimMargin()
}

private fun zsh(paths: List<Pair<String, List<String>>>, valued: List<String>) = """
    |# ostrom completion for zsh. Load it from ~/.zshrc: source <(ostrom completion zsh)
    |(( ${DOLLAR}+functions[compdef] )) || { autoload -U +X compinit && compinit }
    |autoload -U +X bashcompinit && bashcompinit""".trimMargin() + "\n" + bash(paths, valued)

private fun fish(paths: List<Pair<String, List<String>>>, valued: List<String>): String {
    val skip = if (valued.isEmpty()) "" else """
        |        switch ${DOLLAR}word
        |            case ${valued.joinToString(" ") { fishQuoted(it) }}
        |                set skip 1
        |                continue
        |        end""".trimMargin() + "\n"
    val known = paths.drop(1).joinToString(" ") { fishQuoted(it.first) }
    val descend = if (known.isEmpty()) "" else """
        |        switch ${DOLLAR}next
        |            case $known
        |                set at ${DOLLAR}next
        |        end""".trimMargin() + "\n"
    val cases = paths.filter { it.second.isNotEmpty() }.joinToString("\n") { (path, words) ->
        "        case ${fishQuoted(path)}\n            printf '%s\\n' ${words.joinToString(" ") { fishQuoted(it) }}"
    }
    return """
        |# ostrom completion for fish. Load it from ~/.config/fish/config.fish: ostrom completion fish | source
        |function __ostrom_words
        |    set -l at ''
        |    set -l skip 0
        |    for word in (commandline -opc)[2..-1]
        |        if test ${DOLLAR}skip = 1
        |            set skip 0
        |            continue
        |        end
        |$skip        set -l next (string trim -- "${DOLLAR}at ${DOLLAR}word")
        |$descend    end
        |    switch ${DOLLAR}at
        |$cases
        |    end
        |end
        |complete -c ostrom -f -a '(__ostrom_words)'
        |""".trimMargin()
}

private fun powerShell(paths: List<Pair<String, List<String>>>, valued: List<String>): String {
    val table = paths.joinToString("\n") { (path, words) ->
        "        ${psQuoted(path)} = @(${words.joinToString(", ") { psQuoted(it) }})"
    }
    val d = DOLLAR
    return """
        |# ostrom completion for PowerShell. Load it from your profile:
        |#   Add-Content ${d}PROFILE 'ostrom completion powershell | Out-String | Invoke-Expression'
        |Register-ArgumentCompleter -Native -CommandName ostrom -ScriptBlock {
        |    param(${d}wordToComplete, ${d}commandAst, ${d}cursorPosition)
        |    ${d}words = @{
        |$table
        |    }
        |    ${d}valued = @(${valued.joinToString(", ") { psQuoted(it) }})
        |    ${d}path = ''
        |    ${d}skip = ${d}false
        |    foreach (${d}element in ${d}commandAst.CommandElements | Select-Object -Skip 1) {
        |        if (${d}element.Extent.EndOffset -ge ${d}cursorPosition) { break }
        |        ${d}word = if (${d}element -is [System.Management.Automation.Language.StringConstantExpressionAst]) {
        |            ${d}element.Value
        |        } else {
        |            ${d}element.Extent.Text
        |        }
        |        if (${d}skip) { ${d}skip = ${d}false; continue }
        |        if (${d}valued -contains ${d}word) { ${d}skip = ${d}true; continue }
        |        ${d}next = (@(${d}path, ${d}word) | Where-Object { ${d}_ }) -join ' '
        |        if (${d}words.ContainsKey(${d}next)) { ${d}path = ${d}next }
        |    }
        |    ${d}words[${d}path] | Where-Object {
        |        ${d}_.StartsWith(${d}wordToComplete, [System.StringComparison]::OrdinalIgnoreCase)
        |    } | ForEach-Object {
        |        [System.Management.Automation.CompletionResult]::new(${d}_, ${d}_, 'ParameterValue', ${d}_)
        |    }
        |}
        |""".trimMargin()
}

/** A POSIX shell single-quoted word. */
private fun shQuoted(text: String) = "'" + text.replace("'", "'\\''") + "'"

/** A fish single-quoted word. */
private fun fishQuoted(text: String) = "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'"

/** A PowerShell single-quoted string. */
private fun psQuoted(text: String) = "'" + text.replace("'", "''") + "'"
