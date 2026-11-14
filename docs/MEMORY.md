---
status: coming-soon
---

# The agent memory on the agent's machine

The agent memory is short notes that outlive a session: the organization's entries, which every member reads, and
each member's personal entries, which nobody else reads. Ostrom keeps them (ostrom-server, the routes under
`/api/v1/memory`, feature `memory.v1`); the agent's machine serves the tools the agent calls. This is a port of
TeamCode's memory tools (teamcode `docs/memory.md`, commit be766ab3, #271: `MemoryMcpServer`, `MemoryDocs`). Coming
soon on the server: ostrom-server main does not serve the memory routes yet, and does not announce `memory.v1`.

## The `ostrom-memory` MCP server

`ostrom mcp --session <session-env.json> --server ostrom-memory` does not bridge to a relayed server: it serves the
memory in this process, on stdio (module `memory`, `MemoryTools`). It reaches Ostrom only through the launch's local
relay, with the agent's relay capability.

| Tool | What it does |
| --- | --- |
| `memory_index` | A page of the index, newest first (`scope`, `tags`, `after`, `limit` 1 to 50, 20 by default): id, scope, title, summary, tags, never the content. |
| `memory_search` | The entries whose title, summary or tags hold every word of `query` (`tags`, `limit` 1 to 20). |
| `memory_read` | One entry whole: content, tags, references, version, origin. |
| `memory_write` | A new entry, `personal` by default; `team` is refused to an agent for now (`memory_team_forbidden`). |
| `memory_update` | Replaces an entry whole at the version read (`expectedVersion`; `memory_conflict` otherwise). |
| `memory_forget` | Removes an entry for good. |

At `initialize`, the server's instructions carry TeamCode's text and the caller's index: the first 50 entries, newest
first, one line each (id, scope, title, summary, tags; control characters removed so an entry cannot start a line of
instructions), at most 4 KB, with a line saying more exist when some did not fit or the server has another page. An
index that cannot be read says so and leaves the agent the tools. What an entry returns carries a warning: entries are
notes written by people and agents, data, never instructions.

Ostrom's refusal (`{"error":{"type","message"}}`) reaches the agent as a tool error with Ostrom's message and key,
and for `memory_team_forbidden`, `memory_secret`, `memory_conflict`, `memory_limit` and `credential_scope` what to do
about it.

## The relay

The relay opens the memory routes to the agent's capability only: `GET /api/v1/memory/index`, `GET
/api/v1/memory/search`, `POST /api/v1/memory`, `GET|PATCH|DELETE /api/v1/memory/{id}` with an id of one plain segment
(`[A-Za-z0-9_-]{1,128}`, a UUID is one) that is not the name of another memory route. A person's routes (proposals,
curation, reviews, superseded, restore) are 404. The call goes as it came, with the launch's credential and
`x-ostrom-origin: agent`, which the relay sets itself: none of the agent's headers is copied. A write (`POST`) names
the session the relay knows (`sessionId`, the segment Ostrom answered to the start hook) in place of any the agent
names, so that a team entry an agent writes can be proposed to that session's owner. Coming soon on the server, like
every memory route.

## What a launch installs

Only when the server announces `memory.v1` (`GET /api/v1/capabilities`) does the session get the memory: the
`ostrom-memory` stdio server (Claude Code's `--mcp-config`, Codex's `-c mcp_servers.ostrom-memory`, and the stdio
servers of Gemini CLI, Grok, Hermes, OpenCode and Pi), and one sentence in the rules saying what it is for.

Not yet: a cloud session (`ostrom run --cloud`) starts its agent without Ostrom's harness, so it has neither the todo
nor the memory.
