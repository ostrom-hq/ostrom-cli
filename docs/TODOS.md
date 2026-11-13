# The session todo on the agent's machine

A session's todo is a plan the agent writes once (a reference to the full plan and its items, each with the checks that
say when it is done) and that Ostrom holds it to. Ostrom keeps the list (ostrom-server module `todos`, the routes under
`/api/v1/todos/session`, feature `todos.v1`); the agent's machine does what only it can: run the checks with the
agent's own `gh` and shell, and serve the tools the agent calls. Coming soon on the server: ostrom-server main does not serve
the todo routes yet. This is a port of TeamCode's session todo
(teamcode `docs/todos.md`, `cli/src/commonMain/kotlin/me/nathanfallet/teamcode/cli/todo/`).

## The `ostrom-todo` MCP server

`ostrom mcp --session <session-env.json> --server ostrom-todo` does not bridge to a relayed server: it serves the todo
in this process, on stdio (module `todo`, `TodoTools`). It reaches Ostrom only through the launch's local relay, with
the agent's relay capability; the relay names the session itself.

| Tool | What it does |
| --- | --- |
| `todo_help` | The item format, the checks and examples. |
| `todo_create` | Writes the list once: `reference` (one paragraph pointing to the plan), optional `refs`, `items`. |
| `todo_show` | The list: the reference, the next item in full, one line per item. |
| `todo_start` | Marks an item in progress; for an item ending on `pr_merged` or `pr_closed`, gives the marker to put in the PR body (`<!-- ostrom-todo: <list8>/<item> -->`). |
| `todo_complete` | Runs the item's stored checks here, links what they found (the PR), and sends the reports; Ostrom decides. A refusal says what is missing. |
| `todo_link` | Links an existing PR, release, artifact or wait (the kind is deduced from a GitHub address). |
| `todo_add` | Adds an item found on the way, with the reason. |
| `todo_note` | Notes progress on an item. |
| `todo_drop` | Asks the session's owner to drop an item (a wait is opened). |

The server's instructions are TeamCode's, with Ostrom's names (`ask_human` and `wait_for` of the `ostrom` server,
`publish` of `ostrom-artifacts`) and TeamCode #257's guidance: a check must stay true once the work moves on, never a
passing state such as an open PR; an item that delivers a PR ends on `pr_merged`, even when a person merges it.

## Checks run on this machine

| Check | How it is observed |
| --- | --- |
| `pr_merged {repo}`, `pr_closed {repo}` | The PR linked to the item (in the check's repository), or else the one found by its marker: `gh pr list --repo R --state all --search "<list8> <item> in:body"`, its body must carry the marker and its author must be the `gh` token's login (`gh api user`; unknown when it cannot be read); the highest number wins. The PR found is linked to the item. |
| `release_contains {repo, of}` | The merge commit of item `of`'s PR, then the published releases (`gh release list --exclude-drafts`), newest first: the earliest release whose tag contains the merge (`gh api repos/R/compare/<sha>...<tag>`, `ahead` or `identical`). |
| `ci_green {repo, of or ref, workflow?}` | `gh run list --commit <sha>` (the merge of item `of`'s PR, or `ref`): every run (of the workflow, when named) completed with success; none, or one still running, is not done. |
| `command {run, cwd?, expectExit, timeoutSeconds}` | `sh -c` (`cmd.exe /c` on Windows), 1 to 120 s, the exit code compared, the last 300 characters of the output kept as proof, credentials masked. `${pr:<item>.number}`, `${pr:<item>.mergeSha}` and `${release:<item>.tag}` are replaced from the links; only the characters of a number, a SHA or a tag go in. |
| `artifact_published`, `wait_answered` | Verified by the server, never from this machine's report. |
| `attested {what}` | The agent's proof text and its `https` addresses, marked as not verified. |

A check that cannot be read (`gh` missing or failing, a timeout, a value not linked yet, a check this version does not
know) is unknown, and an unknown result never counts as done.

## The Stop hook (`ostrom hook todo-stop`)

The agent cannot end its turn while items remain, unless a wait is open or a background task runs (TeamCode's
`TodoStop`). Ostrom decides; the hook runs what only this machine can:

1. `begin`: Ostrom answers `allow` (no list, the hook off for this member, a wait open, a background task or a
   scheduled wake-up in the event's `background_tasks` or `session_crons`, the list finished) or asks for a phase.
2. `verify`: the checks of the item in progress and of the waiting items whose checks are all cheap (`pr_merged`,
   `release_contains`, `artifact_published`, `wait_answered`), each item in parallel, a PR found by its marker linked
   to its item; with them the pull requests the session opened that no item names (the addresses a successful
   `gh pr create` printed in the Claude Code transcript, at most 5) and the end of the last message (600 characters).
3. `settle`: every check of the done items. Ostrom never reopens an item because of an `attested` check.

`block` gives the agent Ostrom's reason (`{"decision":"block","reason":…}`). The hook never relaxes on
`stop_hook_active`: the bound is Ostrom's count of stops without progress, after which it asks the session's owner.
It fails open: the whole decision has 140 s (the hook's timeout is 150 s), one call to Ostrom 20 s; past them, or
when Ostrom is unreachable or refuses, the agent stops and the failure is recorded on the list (`hook-error`). The
hooks reach Ostrom through the launch's relay with the hooks' capability (`OSTROM_RELAY_TOKEN_FILE`), which alone
opens the `stop`, `hook-error` and `context` routes.

When the session also holds its stop for a wait's answer (`waits.wake.v1`, Claude Code), the two are one Stop hook,
`ostrom hook todo-stop` with its wake argument, as TeamCode's: Claude Code runs an event's hooks side by side, so the
todo decides first, and only a stop it lets pass goes on to `ostrom hook wake`'s hold, on the same event (timeout
86400 s).

`ostrom hook todo-context` is the SessionStart hook for `compact` and `resume`: it gives the agent the reference, the
list and the next item again (`additionalContext`), nothing at any other start or on any failure.

## What a launch installs

A launch reads the server's capabilities; only when it announces `todos.v1` does the session get the todo:

| Agent | MCP server `ostrom-todo` | Stop hook | SessionStart (compact, resume) |
| --- | --- | --- | --- |
| Claude Code | stdio entry in the session's `--mcp-config` | `Stop`, 150 s (the todo then the wait's hold, 86400 s, with `waits.wake.v1`) | second `SessionStart` group, `compact\|resume`, 25 s |
| Codex | `-c mcp_servers.ostrom-todo={command,args}` | `-c hooks.Stop`, 150 s, trusted by its hash | no: its transcript is not read either |
| Gemini CLI, Grok, Hermes, OpenCode, Pi | through `ostrom mcp --session … --server ostrom-todo` | no | no |

Without `todos.v1` (an older server, or one that cannot say) nothing of the todo is offered and no hook is installed.

## Tests

The module is tested with fakes of the relay, of `gh` and of the transcript; end-to-end journeys against the real
server (ostrom-e2e) come once ostrom-server serves the routes.
