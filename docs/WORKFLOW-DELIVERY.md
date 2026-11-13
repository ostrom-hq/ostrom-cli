# A workflow step's delivery on the agent's machine

An agent node of a workflow ends when its agent delivers the result. Ostrom serves the tool and the check
(ostrom-server `docs/decisions/workflows-one-system.md`, "Delivery"; feature `workflows.deliver.v1`); this binary connects
them to the agent, only when the server announces the feature. It is a port of TeamCode's agent delivery
(teamcode `docs/workflows.md`, "Agent delivery" and "Stop hook").

## The `ostrom-workflow` MCP server

One tool, `deliver {output}`: call it once, when the step's task is done, with exactly what the step asked for, then end
the turn. The server is Ostrom's (`POST /api/v1/workflows/mcp`, `workflowsMcp`; coming soon: the server does not serve it yet, and announces `workflows.deliver.v1` only when it does); the launch's local relay forwards it,
as it does the waits server, under `/mcp/ostrom-workflow`, for the session the relay knows: the `session` and any other
query the agent sends are dropped. The relay opens no other workflow route to the agent (no read, start or cancel of a
run). Claude Code and Codex reach it over HTTP through the relay; an agent that only starts stdio servers gets
`ostrom mcp --session <file> --server ostrom-workflow`, a bridge to the same relay route.

The agent is also told, with the rules the launch passes to Codex, Gemini, Pi and Grok, to call `deliver` only on a
workflow step, never for an ordinary session. Claude Code is given no appended system prompt by `ostrom run`: it is told
by the server's own instructions of the tool, at the MCP handshake.

## Not announced, not connected

A server that does not announce `workflows.deliver.v1` (or cannot answer `GET /api/v1/capabilities`) gets neither the
server nor the line: the session starts as before.

## The Stop hook: `ostrom hook workflow-stop`

An agent can end its turn without calling `deliver` and without asking a person. Where the hook is installed (Claude Code,
one more `Stop` entry beside the gate's, the wait's hold and the todo's, none replacing another; only when the delivery
tool is connected), the first such stop is sent back to work once; the second ends the turn as before (the run then fails
`ended_without_delivery`). Codex has no such hook here: the server's own reminder covers it.

- It reads nothing but `hook_event_name` and `stop_hook_active` of its input.
- It prints nothing and exits 0 when `stop_hook_active` is true, on any error (unreadable input, no relay or token, a
  server without the route, a refusal) and when it took more than 2 seconds in all: a problem of Ostrom never keeps the
  agent from stopping.
- Otherwise it asks `GET /api/v1/workflows/session/pending` (`pendingWorkflowSession`; coming soon: the server does not serve it yet) through the launch's relay, which
  opens that read to the hooks' capability only and names the session it knows. When `needsAction`, it prints
  `{"decision":"block","reason":…}`: the reason names the step (its title, one cleaned line) and the form of the
  deliverable (never its schema or instruction), and tells the agent to call `deliver` of `ostrom-workflow` with the
  result or, if it needs a person's answer, `ask_human` of `ostrom`, then end its turn.
- Nothing of the agent's messages is read, kept, logged or sent.

Not exercised with a real Claude Code session or a server that serves the route yet; the logic, the relay's route and the
installed command are tested.
