# The cloud runner (`ostrom cloud …`, hidden)

The processes of a cloud session are hidden commands of the `ostrom` binary, as in TeamCode (`teamcode cloud runner`,
`teamcode cloud agent-supervisor`). The team's image stays a toolchain: the session's init container copies the
`ostrom` Linux binary into a volume at `/opt/ostrom`, and each container runs `/opt/ostrom/ostrom cloud <command>`.
The agent gets `/opt/ostrom` first on its `PATH`, so its hooks and MCP servers run the same binary, and git's
credential helper is `/opt/ostrom/ostrom cloud credential` (the runner reads its own path from `/proc/self/exe`).
These commands run on Linux; elsewhere they say so and exit with 2.

The server's `runners` module starts a session as three containers of the team's image; each pair shares at most one
socket directory:

- **The agent host** (`ostrom cloud agent-supervisor`, UID 1001, the agent's container). It owns the workspace and runs
  each terminal the runner asks for on a PTY: `/bin/sh -c` cloning the repository and starting the agent, then a
  login shell once the agent exits. It never has the runner token.
- **The runner** (`ostrom cloud runner`, UID 1000). It holds the runner token, relays the terminal to the server's
  WebSocket `/api/v1/cloud/runner/terminal`, and heartbeats the execution on `/api/v1/cloud/runner/heartbeat`. It
  reaches the agent host through the Unix socket `/run/ostrom/agent.sock` (`OSTROM_AGENT_SOCKET`), opened to the
  group the containers share (1000). For every agent but the plain shell, it opens the agent's launch on
  `/api/v1/cloud/runner/launch` before starting the terminal, and gives the agent `OSTROM_LAUNCH_ID`,
  `OSTROM_AGENT_TOKEN`, `OSTROM_URL` and `OSTROM_RUNNER_CHECKS`. When the server refuses the launch (the
  organization's policy, a limit) or never answers it, the agent does not start: the terminal says why and leaves a
  shell (`OSTROM_LAUNCH_REFUSED`).
- **The check host** (`ostrom cloud check-host`, UID 1001, a container of its own). It mounts the workspace and runs
  the hooks' checks the runner sends it, without a terminal (`/bin/sh -c`, stdin from `/dev/null`, stdout and stderr
  on one pipe), and reports each exit code. The runner listens on `/run/ostrom-check/check.sock`
  (`OSTROM_CHECK_HOST_SOCKET`), on a volume only the runner's container also mounts, and the check host links to it
  as it starts: the runner accepts that one link, then for each check a connection that opens with the nonce it sent
  over the link. The check host is the pid 1 of its container and not dumpable; after each check it kills every other
  process of its uid and removes the check's home (`HOME` and `TMPDIR`, new for each check), and a script inherits none
  of its descriptors. Checks run one at a time. Before each, the runner binds its socket again if a script replaced it.
  It never has the runner token.

On Kubernetes the agent host is a native sidecar of the session pod (an init container with `restartPolicy: Always`,
**Kubernetes 1.29 or later**), so the pod ends when the runner ends. The token and the first message reach the
runner's container only, as variables the kubelet reads from the execution's Secret (`secretKeyRef`): they are in
no file, in no volume, and not in the pod's spec. The check host is a second native sidecar. The pod does not share
its process namespace. On Docker, the driver runs the same three containers.

## A registered runner on a machine (`ostrom cloud machine`)

An owner or an admin registers a machine of the organization with `POST /api/v1/runners`; the answer shows the
runner's key once. On that machine, `ostrom cloud machine` signs of life with it on
`POST /api/v1/runners/{id}/heartbeat`, at once and then every 20 s, so `GET /api/v1/runners` shows it `online`
(offline 90 s after its last heartbeat):

```sh
docker run -d --name ostrom-machine \
  -e OSTROM_SERVER_URL=https://ostrom.example.com \
  -e OSTROM_RUNNER_ID=<id> -e OSTROM_RUNNER_KEY_FILE=/run/ostrom/key -v /etc/ostrom/key:/run/ostrom/key:ro \
  docker.io/ostromhq/ostrom-runner cloud machine
```

| Variable | |
|---|---|
| `OSTROM_SERVER_URL` | the server, `http(s)://` |
| `OSTROM_RUNNER_ID` | the registered runner |
| `OSTROM_RUNNER_KEY` or `OSTROM_RUNNER_KEY_FILE` | its key; the file is better, the key is never logged |
| `OSTROM_RUNNER_INTERVAL_SECONDS` | between heartbeats, 1 to 30 (20 by default): a lost heartbeat stays inside the 90 s |

A heartbeat that fails (server unreachable, 5xx) is logged with its cause and retried at the next interval. When the
server refuses the key three times in a row (401: the runner was removed, or its key replaced; a single 401 may come
from a proxy), the process ends with code 3. A bad configuration, an unreadable key file included, ends it with code 2,
a stop (SIGTERM) with 0.

### Cloud sessions on a registered runner

With a Docker Engine (`OSTROM_DOCKER_API_URL`) and a server whose sessions run on registered runners
(`OSTROM_RUNNERS_DRIVER=registered`), the machine runs the organization's cloud sessions. Each heartbeat reports what
it holds and gets back what it must hold:

- an execution handed over comes with its plan, once: the volumes and the three containers (agent host, check host,
  runner) that the server's Docker driver would create, with the execution's runner token. The machine checks the plan
  before creating anything: the session's own named volumes only, never a host path, a privilege, a capability, a host
  network or another image than `OSTROM_RUNNER_SESSION_IMAGE` when set, and as a non-root user. The `ostrom` binary
  arrives as in the pod's init container: the execution's delivery container (`<execution>-bin`, from the runner image,
  `OSTROM_RUNNER_DELIVERY_IMAGE` when set) mounts only the execution's binary volume over the image's binary
  directory, so the engine copies the binary into it, and the other containers mount that volume read-only. A refused
  plan is logged and the execution reported missing, so the server moves the session on;
- an execution being created (an image pull may take minutes) is reported `pending`, without holding up the
  heartbeats; its runner's container then makes it `running`, and its end `ended`;
- every object the machine creates carries the label `ostrom.run/runner=<id>`. The machine stops and deletes its own
  containers and volumes the server no longer lists (a stopped or moved execution, a deleted workspace) and never
  touches anything else on the engine.

A heartbeat with a report also asks the server to hold it up to 20 s for work (`?wait=20`): an execution queued for
the organization is handed over within seconds, not at the next interval. The machine asks again at once after an
answer held for the whole wait or one that hands an execution over, and after the interval otherwise, so a server that
does not wait, or a machine without room, is heartbeated as before. A waiting heartbeat is allowed 30 s, its wait and
the usual 10 s, so a lost one still stays inside the 90 s.

When a machine stops heartbeating, the server gives its running sessions to another online runner of the
organization after 90 s, as a new execution with a fresh workspace (the old one stays on the lost machine). A paused
session resumes on the machine that holds its workspace while that machine is online. A machine whose engine cannot
be read heartbeats without a report, and is given nothing that time.

```sh
docker run -d --name ostrom-machine --network host \
  -e OSTROM_SERVER_URL=https://ostrom.example.com \
  -e OSTROM_RUNNER_ID=<id> -e OSTROM_RUNNER_KEY_FILE=/run/ostrom/key -v /etc/ostrom/key:/run/ostrom/key:ro \
  -e OSTROM_DOCKER_API_URL=http://127.0.0.1:2375 \
  docker.io/ostromhq/ostrom-runner cloud machine
```

| Variable | |
|---|---|
| `OSTROM_DOCKER_API_URL` | the Docker Engine API, `http(s)://`; for the local socket, a socket proxy on the loopback that allows containers, volumes and images |
| `OSTROM_DOCKER_NETWORK` | the network the sessions join (the engine's default when unset); their containers reach the server through `host.docker.internal` |
| `OSTROM_RUNNER_SESSION_IMAGE` | the only image a session may run; the server's choice when unset |
| `OSTROM_RUNNER_DELIVERY_IMAGE` | the only image a session's `ostrom` binary may come from (the runner image); the server's choice when unset |
| `OSTROM_RUNNER_MAX_SESSIONS` | the sessions the machine runs at once at most, 0 to 256 (0 drains it); the server never hands it more, and a session with no room anywhere waits. When unset, what the machine holds, read from the engine's `/info`: 2 processors and 4.5 GiB a session, one at least. A socket proxy with `INFO=0` hides it: then no limit is declared, so set this variable |

## Checks run by the runner (`isolated_runner`)

In a cloud session, the agent's hook does not run a package check itself: it asks the runner on
`/run/ostrom/checks.sock` (`OSTROM_RUNNER_CHECKS`), one JSON line per connection:

```json
{"sessionId": "…", "digest": "sha256:…", "nodeId": "…", "scriptId": "lint", "script": "npm run lint\n",
 "directory": "/workspace/api", "timeoutMs": 600000, "head": "c0ffee…"}
```

The runner refuses a script over 64 KiB, a directory outside the workspace and a time limit outside 1 ms to 1 h. It
computes the SHA-256 of the script text itself, runs the script on the check host with a clean environment (`PATH`,
`LANG`, `HOME=/tmp`, `CI=1`, `OSTROM_CHECK`), stops it at its time limit, and posts the run to
`/api/v1/cloud/runner/checks` with its runner token. The server records it with the attestation `isolated_runner`,
and as tampered when the SHA-256 is not the package's. The hook gets one JSON line back:

```json
{"started": true, "exit": 0, "outcome": "passed", "scriptSha": "…", "outputTail": "…", "reported": true}
```

This is the exchange the hook of `ostrom-cli` speaks (cli#68): it reads `started`, `exit` (an integer, or `null` when
the script ran past its limit) and `outputTail`, and ignores the rest. `started: false` means the check was not run
(`outputTail` says why): the hook refuses the action and never runs the check itself. The hook waits for the script's
time limit and 5 s more, so the runner gives up on a report the server has not taken within 3 s (`reported: false`).
Two checks run at a time at most.

**What this proves, and what it does not: Ostrom launched the script and read its exit code, not that the agent left
the workspace untouched.** The check runs on the agent's workspace, which the agent could have prepared.

The check host runs under the agent's UID so that what a check writes in the workspace stays the agent's to change;
what keeps the agent out of the check is the container: the agent's container mounts neither the check host's
socket nor its temporary directory, and sees none of its processes.

## GitHub in the session: git and `gh` with the owner's link

The agent's environment holds no token, only paths. `GIT_CONFIG_*` resets the image's credential helpers and sets
`ostrom cloud credential` as the helper for `https://github.com` only; `GIT_TERMINAL_PROMPT=0` keeps git from asking.
The helper sends the remote git describes to the runner's `/run/ostrom/credential.sock` (`OSTROM_CREDENTIAL_SOCKET`)
and prints the one credential it answers: `x-access-token` and the GitHub user token of the member the session runs
for, which the runner asks of the server (`POST /api/v1/cloud/runner/github`) when first needed and again five minutes
before it expires, and keeps in memory. Any other host, no link, or a session whose GitHub access is `none` gets
nothing, and public repositories clone anonymously. `store` and `erase` do nothing.

`gh` reads `GH_CONFIG_DIR=/run/ostrom/gh`, a directory only the runner writes, with the token in `hosts.yml` (written
before the agent starts, rewritten when the token is renewed, removed when the runner stops). Without a usable link it
holds only `OSTROM-GITHUB-NOT-LINKED.txt`, a notice without any token, and the server is asked again every five
minutes. The volume of `/run/ostrom` is in memory on Kubernetes.

## The session's preparation (`runners.cloud.environment.v1`)

Before the agent starts, the runner prepares the workspace from the session's recipe, as TeamCode's
`EnvironmentPreparation`: it reads `POST /api/v1/cloud/runner/environment` (the recipe, the owner's Git identity and
variables), then

1. writes the owner's Git identity (`git config --global`, on every execution);
2. clones each repository on the first execution into `/workspace/.ostrom/clone-<n>`, checks out its `ref`, creates
   its `branch`, then moves it in place (an existing empty directory is replaced, nothing else); on a later execution
   it only runs `git fetch --prune origin`: work on the workspace is never reset or cloned again;
3. checks the working directory;
4. runs the `create` commands once per workspace (a marker per command, named by its SHA-256, under
   `/workspace/.ostrom/setup`), then the `start` commands on every execution, with `/bin/sh -c` in the working
   directory.

Each phase is reported on `POST /api/v1/cloud/runner/preparation` (a report that fails never stops the preparation; a
refusal, `execution_fenced`, `not_preparing` or `preparation_failed`, does). A failure is reported as
`{step, index, exitCode}` only, retried with backoff while the server cannot be reached; the server writes the
session's message and stops the execution, and the runner keeps heartbeating unready until it does. The runner
heartbeats unready during the whole preparation and reports ready once the agent's terminal is started.

Every command runs on the agent host, in the agent's container and under its uid, never beside the runner token:
without a terminal (stdin from `/dev/null`), its arguments in its environment, never in a script's text. Its
output ends with its shell: a server it starts in the background keeps running and holds nothing up. Every `git`
runs with hooks and fsmonitor off, HTTPS only, no submodule, and no credential helper but the runner's own
(`credential.helper=` resets them first). The owner's variables reach the commands through their environment, without
the names that would steer them: an invalid name, `PATH`, `HOME`, `SHELL`, `IFS`, `ENV`, `BASH_ENV`,
`XDG_CONFIG_HOME`, and the prefixes `LD_`, `DYLD_`, `GIT_`, `GH_`, `OSTROM_`, `TEAMCODE_`, `SSH_`. The agent host
masks the owner's values and the GitHub token (4 characters or more) as `[REDACTED]` in each line of output, appends it
to `/workspace/.ostrom/logs/prepare-e<epoch>.log` (its last 4 MiB are kept) as the agent's uid, so the runner never
opens a file the agent could have replaced by a link, and sends the masked lines back to the session's terminal.

A server without the route (404) leaves the clone to the first terminal's script, as before: it clones
`OSTROM_REPOSITORY_URL` with hooks, fsmonitor and submodules off and HTTPS only (`OSTROM_GIT_HARDENING`), so a
repository's content runs nothing before the agent does.

## The owner's environment variables (`runners.cloud.variables.v1`)

The runner asks the server for the owner's variables (`POST /api/v1/cloud/runner/variables`) and keeps them in memory
only, as TeamCode's `RunnerVariables`. Each agent start (the first terminal and every shell after it) asks again and
gets them in its process environment, under the runner's own variables, which win. Claude Code also reads
`CLAUDE_ENV_FILE=/run/ostrom/agent-env.sh` before each Bash command: the script holds no value and runs
`ostrom cloud environment --socket /run/ostrom/environment.sock`, which asks the runner and prints `export` and `unset`
lines; the runner asks the server again when its last answer is older than 30 seconds, so a rotated, added or removed
variable reaches the next command without a restart. A failed read keeps the last values (retried after 15 seconds), a
server without the route (404) is not asked again, and a fenced or refused execution gives none. Names that steer the
shell, the loader, git, `gh` or the agent (TeamCode's list, with `OSTROM_`) never reach the agent, and no value is
logged or put on a command line.

## The owner's skills and team hooks (`runners.cloud.skills.v1`, `runners.cloud.hooks.v1`)

At each start of Claude Code or Codex, once the launch is open, the runner reads what the session's owner receives
(`POST /api/v1/cloud/runner/skills` and `/hooks`, then `…/artifact` for each version, with its runner token) and checks
every file and digest itself (`sha256:` over `<path>\0<mode>\0<size>\0<sha256>\n`, sorted by path, after the hook's
definition line for a hook), as TeamCode's `teamcode cloud install-skills|install-hooks`. It then starts the hidden
`ostrom cloud install-skills` (and, for Claude Code only, `install-hooks`) on a terminal of the agent host, so the files
are written in the agent's container, under its uid. The installer makes its terminal raw, says `hello`, and the two
speak JSON lines: the manifest, what it needs (a missing or changed item; hooks are always written again, since the
agent can change their folder), each item's files in chunks, then one result per item.

- Skills go to `~/.claude/skills/<name>` (Codex: `$CODEX_HOME/skills/<name>`) with a `.ostrom-skill` marker; a folder
  of the same name without a marker is the member's own and stays (a conflict). Team hooks go to
  `~/.ostrom/hooks/<name>` with a `.ostrom-hook` marker. A marked folder no longer delivered is removed.
- The installed team hooks join Claude Code's `--settings` (`OSTROM_AGENT_SETTINGS`), before the runner's own `Stop`
  and `SessionEnd` hooks (`ostrom cloud agent-state`), which stay in every case. Codex runs no team hook.
- A server without these routes (404), a failure, a refused artifact or an installer that does not answer is a line in
  the pod's log, with counts only, and the agent starts with what is installed. Each kind takes 60 s at most; the
  runner heartbeats (unready) meanwhile.

## Ostrom's MCP servers (`ostrom`, `ostrom-todo`, `ostrom-memory`, `ostrom-workflow`, `ostrom-artifacts`)

Once the launch is open, the runner asks the server, with the launch's agent token and 20 s at most, which of Ostrom's
MCP servers the agent gets, as TeamCode's runner asks with `tcex_`: `ostrom-artifacts` when the publication policy
answers `canPublish` (`GET /api/v1/artifacts/policy`), `ostrom-memory` when the memory answers the token
(`GET /api/v1/memory/index?limit=1`), and from the features the server announces (`GET /api/v1/capabilities`), as
`ostrom run` reads them: the waits (`ostrom`) whenever it answers, `ostrom-todo` with `todos.v1`, `ostrom-workflow`
with `workflows.deliver.v1`. It heartbeats (unready) meanwhile. Claude Code and Codex get each one as
`ostrom cloud mcp --server <name>`, a stdio server of the binary in the agent's container that reaches
`$OSTROM_URL` with `$OSTROM_AGENT_TOKEN` (already in the agent's environment) directly, so it works under the
Kubernetes and the Docker drivers alike: `ostrom-artifacts`, `ostrom` and `ostrom-workflow` are forwarded to
`/api/v1/artifacts/mcp`, `/api/v1/waits/mcp` and `/api/v1/workflows/mcp`; `ostrom-memory` and `ostrom-todo` are the
memory's and the todo's tools of `ostrom run`, their calls made to the server instead of a local relay (the todo's
checks run in the agent's container). No session id is sent: the server binds a cloud launch's agent token to its
cloud session (ostrom-server#539), as TeamCode binds its `tcex_`.

The runner sets `OSTROM_AGENT_ARGUMENTS`, one argument per line, which the session script appends after `--settings`
(names and paths only, never the token; set empty when nothing is connected, so an owner's variable of that name
connects nothing):

- Claude Code: `--disallowedTools=Artifact,ArtifactComments,ArtifactData,ArtifactCheck` while `ostrom-artifacts` is
  connected (its own artifact tools publish to claude.ai), one `--mcp-config=<json>`, and `--append-system-prompt=` with
  the instruction of the servers that have one (TeamCode's for artifacts and memory, `ostrom run`'s for the
  workflow delivery), in their `=` forms so a variadic option never swallows the first message.
- Codex: `-c mcp_servers.<name>={command=…,args=["cloud","mcp","--server","<name>"],env_vars=["OSTROM_URL",
  "OSTROM_AGENT_TOKEN"]}` per server (Codex passes a stdio server only the variables it lists) and
  `-c developer_instructions=…`.

### The todo's and the workflow's hooks (Claude Code)

As TeamCode's runner adds `teamcode cloud todo-stop`, `todo-context` and `workflow-stop` to Claude Code's `--settings`,
the runner adds, to `OSTROM_AGENT_SETTINGS`, only for the servers actually connected (Codex gets none): with
`ostrom-todo`, a `Stop` hook `ostrom cloud todo-stop` (150 s) and a `SessionStart` hook `ostrom cloud todo-context`
(matcher `compact|resume`, 25 s); with `ostrom-workflow`, a `Stop` hook `ostrom cloud workflow-stop` (5 s). They join
each event's entries, after the team hooks and next to the runner's own `Stop` and `SessionEnd` (`agent-state`). They
are the hooks of `ostrom hook` (the same decisions, [TODOS.md](../docs/TODOS.md), [WORKFLOW-DELIVERY.md](../docs/WORKFLOW-DELIVERY.md)),
reaching the server with `$OSTROM_URL` and `$OSTROM_AGENT_TOKEN`, which binds the token to its cloud session: no relay,
no session id. They fail open as the local hooks do, and the todo's Stop is never held for a wait's answer (no
`--wake`): in a pod the answer arrives as the next execution's first message (below).

### The answers to the session's waits (first message)

TeamCode queues an answer to a wait (or a time, an event, a timeout) as a message on its session, which resumes a
paused one, and the resumed pod reads it first. Ostrom's server resumes the paused cloud session the same way and keeps
the answer on its wait; once the launch of the new execution is open and the agent has the waits' server (so the
server binds the token to its cloud session), the runner asks `POST /api/v1/waits/wake` with the agent token and
`waitMillis` 0 (never held, no session named; 20 s at most, heartbeating unready meanwhile, a stop ends the runner
before the agent). The messages it takes (`[Ostrom wait answered] …`, each handed over once) become the agent's first
message for this execution only (`OSTROM_AGENT_PROMPT`, after the session's own first message if any; a shell started
again never gets them). The pod's log says how many, never what. A refusal, an error or no answer in time gives none:
the answers stay on their waits. The agent continues its conversation on the kept workspace (below), the answers as
its next message, with its todo given back by `todo-context` when `ostrom-todo` is connected.

### The agent's conversation across executions

As TeamCode's pod resumes the conversation id it keeps in `$HOME/.teamcode/cloud-agent.json`, the session script of a
new execution (a manual resume, an answer to a wait, after an idle pause) continues the agent's conversation instead of
starting a new one. `HOME` is `/workspace/.home`, on the workspace volume, so the agent's own transcripts outlive the
pod:

- Claude Code starts with `--session-id <id>`, an id the runner gives the first terminal of each execution
  (`OSTROM_AGENT_NEW_CONVERSATION`, which the script takes out of the agent's environment), and the script records it
  in `$HOME/.ostrom/cloud-agent.json` (`{"kind":…,"conversationId":…}`, TeamCode's `AgentState`). The next execution
  starts `claude --resume <id>` when the recorded kind is the session's agent and the transcript is still there
  (`~/.claude/projects/<directory>/<id>.jsonl`, not empty); otherwise a new conversation starts, recorded in its place.
- Codex starts `codex resume <id>` with its newest rollout (`$CODEX_HOME/sessions/**/rollout-*-<id>.jsonl`), whichever
  started it (Codex cannot be given an id), recorded in the same file; a first start, without any, starts a new
  conversation.

The model, the MCP servers and the first message follow as for a new conversation: the first message (the answers to
the waits) is the resumed conversation's next message. For Claude Code, a conversation started by hand in the shell
left after the agent exits is the member's; the next execution resumes the recorded one. OpenCode and Gemini CLI
start a new conversation, as before. The agent's version is not compared, unlike TeamCode's headless host: a terminal
agent reads an older transcript of its own.

A running session's Stop hook does not hold the agent for an answer (as TeamCode's pod): Claude Code's terminal redraws
its spinner every second while a hook runs, and the runner counts the terminal's output as activity, so a held hook
would keep the session from its idle pause. An answer given while the session runs stays on its wait; when the idle
guard comes and an answer resolved since this execution started was never taken, the server follows the pause with a
resume (ostrom-server#554), and the next execution hands it over as above.

A refusal, an error or no answer in time leaves the server out, and the agent keeps its own tools. The token is
revoked when the launch ends. The server takes the owner (and the origin) of what the agent publishes or writes from
the token, never from the call.

## The agent's models through the runner's relay (`runners.cloud.inference.v1`)

TeamCode's inference from the pod. In a Kubernetes pod, once the launch is open and the agent is Claude Code, the
runner asks `POST /api/v1/cloud/runner/inference` for the execution's credential: a one-hour agent token of that
launch, limited to inference, kept in the runner's memory only (`RunnerInference`). It is renewed after a third of its
lifetime, measured on the runner's clock; while a renewal is in flight the current one is served, and one is never
offered in its last five minutes. The runner starts the relay of a local launch (`relay`, inference only) on the pod's
loopback, which the agent's container shares, and starts Claude Code as `ostrom run` does: `ANTHROPIC_BASE_URL` on the
relay, `ANTHROPIC_AUTH_TOKEN` its random local token, and `ANTHROPIC_API_KEY`, `CLAUDE_CODE_OAUTH_TOKEN` and the
`CLAUDE_CODE_USE_*` provider switches emptied, over the owner's variables. The relay sends each model call under the
credential of the moment, and answers 503 with `Retry-After: 15` while it has none.

The agent keeps its own provider configuration, and the pod log says so, on a server without the route (404), when
the organization serves no model through Ostrom (`GET /api/v1/inference/v1/models` empty, the local launch's test),
for another agent than Claude Code, and on a Docker delivery, whose containers do not share a loopback.
`OSTROM_AGENT_TOKEN` is still given to the agent for Ostrom's MCP servers and hooks (above).

## A conversation session (`OSTROM_SESSION_MODE=conversation`)

A session in `conversation` mode has no terminal: its agent runs headless, as TeamCode's cloud agent host runs it, and
the runner relays it to the server's runner channel (`POST /api/v1/cloud/runner/agent|events|inputs|inputs/ack`, with
the runner token). The runner reads the mode from `OSTROM_SESSION_MODE`; absent or `terminal`, it relays a terminal as
before, and so it does when the server answers `409 terminal_session`.

- **Exec mode of the agent host.** The runner asks the agent's container for a process without a PTY
  (`AgentFrame.Start` with `exec`, its arguments as `sh -c` positional parameters): stdin and stdout on pipes, stderr
  in the host's log. The agent's lines cross as INPUT and OUTPUT frames.
- **The agents.** Claude Code runs in stream-json mode (`-p --input-format stream-json --output-format stream-json`,
  `--permission-prompt-tool stdio` when the server relays permissions, `--include-partial-messages` for live text),
  with the pod's `--settings` and MCP servers; Codex runs `codex app-server` (JSON-RPC). OpenCode and Gemini CLI have
  no headless mode: their messages are refused (`agent_unavailable`) and the transcript says why.
- **The loop** (`conversation/ConversationHost.kt`, TeamCode's `AgentHost`): the execution's first message (the
  session's prompt and the answers to its waits) starts the agent and is recorded as the member's; the events go up in
  batches with consecutive sequences (at most 200 events, 1 MB; resent from `acceptedThrough + 1`; an event refused
  for its content is replaced by a marker); the inputs come from a long poll that carries the desired status (a pause
  reaches the heartbeat loop at once) and what the host takes (task stops, model and effort changes; no files yet).
  Messages, interrupts, permission answers (with their deadline), task stops, model and effort changes are
  acknowledged one by one.
- **A pause drains** the agent within the server's 2 minutes: open requests are denied, the running turn and the
  background tasks get the time left, then the turn is interrupted, the agent stopped and the transcript flushed;
  the stopped heartbeat follows. A resume while draining cancels it.
- **Resume.** The conversation id is kept in `$HOME/.ostrom/cloud-agent.json` (`{"kind","version","conversationId"}`,
  written by short processes of the agent host, as the agent's uid). The next execution resumes it (`claude --resume`,
  Codex `thread/resume`) only when the server says `resumable` and the agent's kind and version (`<agent> --version`)
  are unchanged; otherwise it starts a new conversation and says `status{conversation: transcript_only}`.

## The agent cannot read the runner token (S2-49)

The agent and the runner never share a UID, a container, a process namespace or the token. They do share a group:
the UIDs and the group are TeamCode's image contract (runner 1000, agent and check host 1001, every container in
group 1000, the pod's fsGroup), so a team image built for TeamCode runs on Ostrom. The group gives the runner nothing
of the agent's to read that it does not already reach, and the agent nothing of the runner's: the runner mounts no
workspace and no temporary directory of the agent's, its root file system is read-only, the token and the first message
are only in its process environment (which another container's processes cannot read), and the only volume it shares
with the agent is the socket directory `/run/ostrom`, which holds the sockets (the agent's at mode 0660) and the `gh`
configuration written for the agent. CI proves it on the real
image (`ci/runner-isolation.sh`, step "Session image, the agent cannot read the runner token" of the `ci` job). It
runs one session as the Docker driver does, then checks:

- the runner started the agent's terminal through the host;
- the agent's container sees no process of another UID;
- nothing the agent can read holds the token: process environments, command lines, writable directories;
- the same search in the runner's container does find the token;
- a check asked from the agent's container runs on the check host, the runner returns its exit code and the SHA-256
  of the script, the check's environment and the check host's container hold no token, and the agent's container
  sees neither the check host's socket nor the running check.
