# The session todo on the agent's machine

A session's todo is a plan the agent writes once (a reference to the full plan and its items, each with the checks that
say when it is done) and that Ostrom holds it to. Ostrom keeps the list (ostrom-server module `todos`, the routes under
`/api/v1/todos/session`, feature `todos.v1`); the agent's machine does what only it can: run the checks with the
agent's own `gh` and shell, and serve the tools the agent calls. This is a port of TeamCode's session todo
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
| `pr_merged {repo}`, `pr_closed {repo}` | The PR linked to the item, or else the one found by its marker: `gh pr list --repo R --state all --search "<list8> <item> in:body"`, its body must carry the marker and its author must be the `gh` token's login (`gh api user`); the highest number wins. The PR found is linked to the item. |
| `release_contains {repo, of}` | The merge commit of item `of`'s PR, then the published releases (`gh release list --exclude-drafts`), newest first: the earliest release whose tag contains the merge (`gh api repos/R/compare/<sha>...<tag>`, `ahead` or `identical`). |
| `ci_green {repo, of or ref, workflow?}` | `gh run list --commit <sha>` (the merge of item `of`'s PR, or `ref`): every run (of the workflow, when named) completed with success; none, or one still running, is not done. |
| `command {run, cwd?, expectExit, timeoutSeconds}` | `sh -c` (`cmd.exe /c` on Windows), 1 to 120 s, the exit code compared, the last 300 characters of the output kept as proof, credentials masked. `${pr:<item>.number}`, `${pr:<item>.mergeSha}` and `${release:<item>.tag}` are replaced from the links; only the characters of a number, a SHA or a tag go in. |
| `artifact_published`, `wait_answered` | Verified by the server, never from this machine's report. |
| `attested {what}` | The agent's proof text and its `https` addresses, marked as not verified. |

A check that cannot be read (`gh` missing or failing, a timeout, a value not linked yet, a check this version does not
know) is unknown, and an unknown result never counts as done.

## Gating and tests

Ostrom announces `todos.v1` only when it serves the routes; without it nothing of the todo is offered to the agent. The
module is tested with fakes of the relay and of `gh`; end-to-end journeys against the real server (ostrom-e2e) come
once ostrom-server serves the routes.
