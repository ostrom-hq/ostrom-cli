// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.help

import dev.ostrom.cli.launcher.Agent

/** The documentation of the CLI, on the site. */
internal const val DOCS = "https://ostrom.run/docs"

/**
 * The guide of every command, by its path below `ostrom` (`""` for `ostrom` itself, `"receipts show"`): every
 * command of the tree has one (HelpTest), so a new command adds its line here.
 */
internal fun guideOf(path: String): Guide? = GUIDES[path]

/** The paths that have a guide, to check that each names a command of the tree. */
internal val guidedPaths: Set<String> get() = GUIDES.keys

private fun guide(page: String, vararg examples: Pair<String, String>) = Guide(examples.toList(), page)

private val AGENT_GUIDES = Agent.entries.associate { agent ->
    agent.command to guide(
        "agents",
        "ostrom ${agent.command}" to "start ${agent.command} under your team's harness",
        "ostrom ${agent.command} --dry-run" to "show the harness that would apply, then exit",
        "ostrom ${agent.command} --cloud" to "run it in Ostrom's cloud, attached here (Ctrl-] detaches)",
        "ostrom ${agent.command} -- --help" to "everything after -- goes to ${agent.command} itself",
    )
}

private val GUIDES: Map<String, Guide> = AGENT_GUIDES + mapOf(
    "" to guide(
        "quickstart",
        "ostrom login" to "sign in to your team's server",
        "ostrom claude" to "start Claude Code under your team's harness",
        "ostrom doctor" to "check everything and say what to do",
    ),
    "status" to guide(
        "quickstart",
        "ostrom status" to "you, this repository and this device",
        "ostrom status --json" to "the same, for a script",
    ),
    "inbox" to guide(
        "approvals",
        "ostrom inbox" to "the requests that wait for your yes",
        "ostrom inbox --json" to "the same, as the server's JSON",
    ),
    "approve" to guide("approvals", "ostrom approve apr_3f9c" to "say yes to a request"),
    "deny" to guide("approvals", "ostrom deny apr_3f9c" to "say no to a request"),
    "receipts" to guide(
        "receipts",
        "ostrom receipts list" to "the receipts of your sessions",
        "ostrom receipts show rcp_8a1d" to "one receipt",
        "ostrom receipts pull rcp_8a1d" to "keep its bundle to verify it offline",
    ),
    "receipts list" to guide(
        "receipts",
        "ostrom receipts list" to "the receipts of your sessions, from the server",
        "ostrom receipts list --local" to "the bundles kept on this device",
        "ostrom receipts list --json" to "for a script",
    ),
    "receipts show" to guide("receipts", "ostrom receipts show rcp_8a1d" to "one receipt, from the server"),
    "receipts pull" to guide("receipts", "ostrom receipts pull rcp_8a1d" to "keep its bundle to verify it offline"),
    "verify" to guide(
        "receipts/format",
        "ostrom verify rcp_8a1d" to "verify a bundle you pulled, offline",
        "ostrom verify receipt.json --key server.pub" to "a file, with a key you trust",
        "ostrom verify rcp_8a1d --json" to "the report, for a script",
    ),
    "attach" to guide(
        "agents",
        "ostrom attach ses_41b2" to "attach again to a cloud session that kept running",
    ),
    "logs" to guide(
        "agents",
        "ostrom logs" to "the thread of your last session",
        "ostrom logs ses_41b2 --follow" to "follow a session live",
    ),
    "harness" to guide(
        "harness",
        "ostrom harness list" to "the harnesses of this repository",
        "ostrom harness add harness.json --enforce" to "add one, mandatory",
    ),
    "harness list" to guide(
        "harness",
        "ostrom harness list" to "the harnesses of this repository",
        "ostrom harness list --all" to "every harness of your organisation",
    ),
    "harness show" to guide("harness", "ostrom harness show hrn_2c4e" to "what a harness holds"),
    "harness add" to guide("harness", "ostrom harness add harness.json" to "add a harness from its file"),
    "harness enforce" to guide("harness", "ostrom harness enforce hrn_2c4e" to "make it mandatory"),
    "harness update" to guide("harness", "ostrom harness update hrn_2c4e harness.json" to "replace it with a new file"),
    "harness remove" to guide("harness", "ostrom harness remove hrn_2c4e" to "stop applying it"),
    "login" to guide(
        "quickstart",
        "ostrom login" to "sign in from the browser",
        "ostrom login --server https://ostrom.acme.dev" to "to a self-hosted server",
    ),
    "logout" to guide("quickstart", "ostrom logout" to "sign out and forget this device's token"),
    "init" to guide(
        "quickstart",
        "ostrom init" to "set up this repository (admin: the organisation)",
        "ostrom init --repo acme/api" to "for another repository than this folder's",
    ),
    "config" to guide(
        "reference/cli",
        "ostrom config" to "every setting of this device",
        "ostrom config set color never" to "change one",
        "ostrom config unset color" to "back to its default",
    ),
    "config list" to guide("reference/cli", "ostrom config list" to "every setting of this device"),
    "config get" to guide("reference/cli", "ostrom config get server" to "one setting"),
    "config set" to guide("reference/cli", "ostrom config set color never" to "change one setting"),
    "config unset" to guide("reference/cli", "ostrom config unset color" to "back to its default"),
    "doctor" to guide(
        "troubleshooting",
        "ostrom doctor" to "check everything and say what to do",
        "ostrom doctor --json" to "the checks, for a script",
    ),
    "telemetry" to guide(
        "security",
        "ostrom telemetry" to "whether usage statistics are on",
        "ostrom telemetry disable" to "turn them off",
    ),
    "telemetry status" to guide("security", "ostrom telemetry status" to "whether usage statistics are on"),
    "telemetry enable" to guide("security", "ostrom telemetry enable" to "send anonymous usage statistics"),
    "telemetry disable" to guide("security", "ostrom telemetry disable" to "stop sending them"),
    "update" to guide(
        "install",
        "ostrom update" to "install the latest signed version",
        "ostrom update --check" to "only say whether one is out",
        "ostrom update --rollback" to "back to the version before",
    ),
    "version" to guide("install", "ostrom version" to "this version, its platform and build"),
    "invite" to guide(
        "organizations",
        "ostrom invite lena@acme.dev" to "invite a developer by e-mail",
        "ostrom invite lena@acme.dev --role admin" to "as an admin",
    ),
    "members" to guide("organizations", "ostrom members list" to "the members of your organisation"),
    "members list" to guide("organizations", "ostrom members list" to "the members of your organisation"),
    "members role" to guide("organizations", "ostrom members role lena admin" to "change someone's role"),
    "members remove" to guide("organizations", "ostrom members remove lena" to "remove someone"),
    "policy" to guide(
        "organizations",
        "ostrom policy show" to "the policy of your organisation",
        "ostrom policy rules list" to "its rules",
    ),
    "policy show" to guide("organizations", "ostrom policy show" to "the policy of your organisation"),
    "policy set" to guide("organizations", "ostrom policy set default-model opus" to "change one of its values"),
    "policy check" to guide(
        "organizations",
        "ostrom policy check claude --model opus" to "whether a launch is allowed",
    ),
    "policy rules" to guide(
        "harness",
        "ostrom policy rules list --status proposed" to "the rules waiting for a decision",
        "ostrom policy rules accept --proposed" to "accept every one of them",
    ),
    "policy rules list" to guide(
        "harness",
        "ostrom policy rules list" to "the rules of your organisation",
        "ostrom policy rules list --repo acme/api" to "those of one repository",
    ),
    "policy rules show" to guide("harness", "ostrom policy rules show rul_7d1a" to "one rule"),
    "policy rules accept" to guide("harness", "ostrom policy rules accept rul_7d1a" to "accept a proposed rule"),
    "policy rules reject" to guide("harness", "ostrom policy rules reject rul_7d1a" to "reject it"),
    "policy rules edit" to guide(
        "harness",
        "ostrom policy rules edit rul_7d1a --strength required" to "change a rule",
    ),
    "import" to guide(
        "migrate-from-teamcode",
        "ostrom import teamcode --db mysql://teamcode@db.acme.dev/teamcode --key-file teamcode.key" to
            "bring your TeamCode in",
    ),
    "import teamcode" to guide(
        "migrate-from-teamcode",
        "ostrom import teamcode --db mysql://teamcode@db.acme.dev/teamcode --key-file teamcode.key" to
            "bring your TeamCode in (password: MYSQL_PWD)",
        "ostrom import teamcode --db mysql://teamcode@db.acme.dev/teamcode --key-file teamcode.key --yes" to
            "without the confirmation",
    ),
    "check" to guide("workflows", "ostrom check tests" to "run a workflow check (agents call it)"),
    "wait" to guide("approvals", "ostrom wait apr_3f9c --timeout 600" to "wait for a yes (agents call it)"),
    "hook" to guide("agents", "ostrom hook gate < event.json" to "answer an agent hook (agents call it)"),
    "mcp" to guide("mcp-and-secrets", "ostrom mcp" to "the local MCP server, on stdio (agents call it)"),
    "help" to guide(
        "reference/cli",
        "ostrom help" to "every command",
        "ostrom help exit-codes" to "what each exit code means",
        "ostrom help plumbing" to "the commands agents call",
    ),
    "completion" to guide(
        "install",
        "ostrom completion zsh" to "print the script; to load it in each new shell:",
        "echo 'source <(ostrom completion zsh)' >> ~/.zshrc" to "zsh",
        "echo 'source <(ostrom completion bash)' >> ~/.bashrc" to "bash",
        "echo 'ostrom completion fish | source' >> ~/.config/fish/config.fish" to "fish",
        "Add-Content \$PROFILE 'ostrom completion powershell | Out-String | Invoke-Expression'" to "PowerShell",
    ),
)
