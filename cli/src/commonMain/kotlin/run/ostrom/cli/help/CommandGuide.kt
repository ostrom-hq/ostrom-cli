// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.help

import run.ostrom.cli.launcher.Agent

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

private fun agentGuide(agent: Agent) = guide(
    "agents",
    "ostrom run ${agent.command}" to "start ${agent.command} under your team's harness",
    "ostrom run ${agent.command} -- --help" to "everything after -- goes to ${agent.command} itself",
    "ostrom run ${agent.command} --dry-run" to "show the harness that would apply, then exit",
    "ostrom run ${agent.command} --cloud" to "run it in Ostrom's cloud, attached here (Ctrl-] detaches)",
)

/** `run <agent>`, and the former `<agent>` kept hidden for one release, which points to it. */
private val AGENT_GUIDES = Agent.entries.flatMap { agent ->
    listOf("run ${agent.command}" to agentGuide(agent), agent.command to agentGuide(agent))
}.toMap() + mapOf(
    "run" to guide(
        "agents",
        "ostrom run claude" to "start Claude Code under your team's harness",
        "ostrom run codex -- exec \"fix the tests\"" to "its own arguments go after --",
    ),
)

/** `sessions show`, and the former `logs` kept hidden for one release. */
private val sessionsShow = guide(
    "agents",
    "ostrom sessions show" to "the thread of your last session",
    "ostrom sessions show ses_41b2 --follow" to "follow a session live",
)

private val GUIDES: Map<String, Guide> = AGENT_GUIDES + mapOf(
    "" to guide(
        "quickstart",
        "ostrom login" to "sign in to your team's server",
        "ostrom run claude" to "start Claude Code under your team's harness",
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
    "sessions" to guide(
        "agents",
        "ostrom sessions list" to "your sessions, newest first",
        "ostrom sessions show" to "the thread of your last session",
    ),
    "sessions list" to guide(
        "agents",
        "ostrom sessions list" to "your sessions, newest first",
        "ostrom sessions list --all --open" to "everyone's sessions still running",
    ),
    "sessions show" to sessionsShow,
    "logs" to sessionsShow,
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
    "whoami" to guide(
        "quickstart",
        "ostrom whoami" to "who this device is signed in as, and your role in each organisation",
        "ostrom whoami --json" to "the same, for a script",
    ),
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
    "waits" to guide(
        "waits",
        "ostrom waits" to "what waits for you",
        "ostrom waits ask --title \"Which region?\" --assignee lena" to "ask a member for something",
    ),
    "waits list" to guide(
        "waits",
        "ostrom waits list" to "the open waits asked of you",
        "ostrom waits list --all --session ses_41b2" to "every wait of one session",
    ),
    "waits show" to guide("waits", "ostrom waits show 7f3a" to "a wait: its text, fields, actions and answer"),
    "waits answer" to guide(
        "waits",
        "ostrom waits answer 7f3a --action approve --set note=\"ship it\"" to "answer with an action and a value",
        "ostrom waits answer 7f3a --set-json tags='[\"a\",\"b\"]'" to "a JSON value",
    ),
    "waits cancel" to guide("waits", "ostrom waits cancel 7f3a" to "withdraw a wait"),
    "waits ask" to guide(
        "waits",
        "ostrom waits ask --title \"Deploy?\" --action yes:Yes --action no:No" to "ask for a decision",
        "ostrom waits ask --title \"Region\" --field r:choice:Region:required --option r=eu --option r=us" to
            "ask with a typed field",
    ),
    "hooks" to guide(
        "harness",
        "ostrom hooks" to "the team hooks delivered to you, and where each stands",
        "ostrom hooks show guard" to "read a hook before you decide",
    ),
    "hooks list" to guide("harness", "ostrom hooks list" to "the team hooks delivered to you, and where each stands"),
    "hooks show" to guide("harness", "ostrom hooks show guard" to "its definition and files, checked by digest"),
    "hooks approve" to guide(
        "harness",
        "ostrom hooks approve guard" to "run the version you read in your sessions",
        "ostrom hooks approve guard --digest sha256:9f2c…" to "approve only that exact version",
    ),
    "hooks refuse" to guide("harness", "ostrom hooks refuse guard" to "never run this version"),
    "workflows" to guide(
        "workflows",
        "ostrom workflows list" to "the organisation's workflows",
        "ostrom workflows run deploy --input env=staging --wait" to "start a run and follow it to its end",
        "ostrom workflows runs --status failed" to "the runs that failed",
    ),
    "workflows list" to guide(
        "workflows",
        "ostrom workflows list" to "id, name, version, status, size and age of each workflow",
        "ostrom workflows list --json" to "for a script",
    ),
    "workflows show" to guide(
        "workflows",
        "ostrom workflows show deploy" to "a workflow: inputs, nodes and where each outcome leads",
        "ostrom workflows show deploy --json > w.json" to "the definition, which update takes back",
    ),
    "workflows create" to guide(
        "workflows",
        "ostrom workflows create --file deploy.json" to "save a new workflow",
        "cat deploy.json | ostrom workflows create --file -" to "the definition from standard input",
    ),
    "workflows update" to guide(
        "workflows",
        "ostrom workflows update deploy --file w.json --expected-version 3" to
            "save a new version, refused if someone saved in between",
    ),
    "workflows delete" to guide("workflows", "ostrom workflows delete deploy" to "delete a workflow"),
    "workflows run" to guide(
        "workflows",
        "ostrom workflows run deploy --input env=staging" to "start a run",
        "ostrom workflows run deploy --wait --timeout 30" to "follow it up to 30 minutes",
    ),
    "workflows runs" to guide(
        "workflows",
        "ostrom workflows runs" to "your runs, newest first",
        "ostrom workflows runs --workflow deploy --status done" to "the runs of a workflow that succeeded",
    ),
    "workflows run-show" to guide(
        "workflows",
        "ostrom workflows run-show 3f9c" to "a run: its step, what it waits for, its visits",
    ),
    "workflows cancel" to guide("workflows", "ostrom workflows cancel 3f9c" to "cancel a run"),
    "members" to guide("organizations", "ostrom members list" to "the members of your organisation"),
    "members list" to guide("organizations", "ostrom members list" to "the members of your organisation"),
    "members role" to guide("organizations", "ostrom members role lena admin" to "change someone's role"),
    "members remove" to guide("organizations", "ostrom members remove lena" to "remove someone"),
    "github" to guide("cloud-sessions", "ostrom github status" to "the GitHub account your cloud sessions use"),
    "github link" to guide("cloud-sessions", "ostrom github link" to "link your GitHub account with a device code"),
    "github status" to guide("cloud-sessions", "ostrom github status" to "your link, and whether it works"),
    "github links" to guide("cloud-sessions", "ostrom github links" to "the GitHub account of every member (admin)"),
    "github unlink" to guide(
        "cloud-sessions",
        "ostrom github unlink" to "remove your link",
        "ostrom github unlink --member mem_7d1a" to "remove a member's link (admin)",
    ),
    "cloud" to guide(
        "cloud-sessions",
        "ostrom cloud list" to "your cloud sessions",
        "ostrom cloud github ses_41b2 --access shared" to "share your GitHub account",
    ),
    "cloud list" to guide(
        "cloud-sessions",
        "ostrom cloud list" to "your cloud sessions, newest first",
        "ostrom cloud list --status paused" to "only the paused ones",
    ),
    "cloud show" to guide("cloud-sessions", "ostrom cloud show ses_41b2" to "one cloud session"),
    "cloud pause" to guide("cloud-sessions", "ostrom cloud pause ses_41b2" to "pause it: the agent finishes its turn"),
    "cloud resume" to guide("cloud-sessions", "ostrom cloud resume ses_41b2" to "resume it"),
    "cloud delete" to guide("cloud-sessions", "ostrom cloud delete ses_41b2 --yes" to "delete it and its workspace"),
    "cloud attach" to guide(
        "cloud-sessions",
        "ostrom cloud attach ses_41b2" to "attach again to a cloud session that kept running",
    ),
    "cloud github" to guide(
        "cloud-sessions",
        "ostrom cloud github ses_41b2 --access shared" to "let anyone who may use the session instruct the agent",
        "ostrom cloud github ses_41b2 --access none" to "no GitHub token (pause the session first)",
    ),
    "accounts" to guide(
        "migrate-from-teamcode",
        "ostrom accounts handover \"Team Max\" --confirm" to "let Ostrom renew an imported account's tokens",
    ),
    "accounts handover" to guide(
        "migrate-from-teamcode",
        "ostrom accounts handover \"Team Max\" --confirm" to "once TeamCode no longer renews its tokens",
    ),
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
        "ostrom import teamcode --db mysql://teamcode@db.acme.dev/teamcode --key-file teamcode.key --yes " +
            "--access-only" to "a trial: the accounts serve their access tokens, never renewed",
    ),
    "check" to guide("workflows", "ostrom check tests" to "run a workflow check (agents call it)"),
    "wait" to guide("approvals", "ostrom wait apr_3f9c --timeout 600" to "wait for a yes (agents call it)"),
    "hook" to guide("agents", "ostrom hook gate < event.json" to "answer an agent hook (agents call it)"),
    "mcp" to guide(
        "mcp-and-secrets",
        "ostrom mcp" to "the local MCP server, on stdio (agents call it)",
        "ostrom mcp list" to "the servers you reach, and the state of the hosted commands",
    ),
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
