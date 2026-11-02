// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.commands

import com.github.ajalt.clikt.parameters.arguments.argument

// The last command of the spec (CLI.md § 2.2) declared but not built yet. It keeps the spec's argument so that
// `--help` already documents it; its behaviour lands with the agent that owns it.

internal class CheckCommand : PendingCommand("check", "Run a workflow check (used by agents)", hidden = true) {
    val node by argument("node")
}
