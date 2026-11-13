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
