# Modules of the CLI, and how to add to them

The CLI is one Kotlin/Native binary, `ostrom`, built for macosArm64, linuxX64, linuxArm64 and mingwX64. Every module
also has a `jvm()` target that only runs the common tests and measures coverage (Kover, 100 % of lines and branches);
it is never shipped. Every dependency must be published for the four native targets and the JVM. The conventions of
all Ostrom repositories are in `ostrom-core/docs/CONVENTIONS.md`; this page only adds what is particular to the CLI.

## The modules

| Module | Package | Holds | Depends on |
| --- | --- | --- | --- |
| `platform` | `dev.ostrom.cli.platform` | everything that touches the OS: processes sharing the terminal, stdin with a deadline, unbuffered stdout/stderr, environment, files, `config.toml`, PATH lookup | okio |
| `hooks` | `dev.ostrom.cli.hooks` | `ostrom hook gate\|session`: event parsing (all agents' dialects), the gate, the reply; `ostrom wait` (`GateWait`) | `platform` |
| `api-client` | `dev.ostrom.cli.api` | the server over HTTPS (Ktor, platform engine) | ostrom-core `api` |
| `launcher` | `dev.ostrom.cli.launcher` | the agents (`Agent`) and starting one in this terminal | `platform` |
| `mcp` | `dev.ostrom.cli.mcp` | the local MCP server on stdio (`ostrom mcp`) | `platform` |
| `relay` | `dev.ostrom.cli.relay` | the launch's local relay on `127.0.0.1` (Ktor CIO): hooks, MCP and inference reach Ostrom through it | `platform` |
| `cli` | `dev.ostrom.cli` | the command tree (Clikt), `main`, the wiring (`CliDependencies`), the executable | all of the above |

Rules that keep the binary fast and the modules apart:

- Only `platform` uses cinterop. Its C shims are in `platform/src/nativeInterop/cinterop/` (`posix.def` for macOS and
  Linux, `windows.def` for Windows). Shared POSIX Kotlin lives in `src/posixMain`, added to `macosMain` and
  `linuxMain`; Windows code in `src/mingwMain`.
- `hooks` stays light: no Clikt, no Ktor, no coroutines. `main` sends `ostrom hook …` straight to `HookRunner` before
  building anything else (`cli/.../Main.kt`). A hook reaches Ostrom only through the launcher's local relay, which
  answers the gate catalogue from its own memory; the hook trusts no cache on disk the agent could edit (S2-20).
- Interfaces and their implementation: `XxxRepository` / `XxxRepositoryImpl`, `XxxUseCase` with
  `operator fun invoke`, KDoc on everything public, no `!!`, a `CancellationException` is always rethrown
  (`ostrom-core/docs/CONVENTIONS.md`).
- An `expect` declared in `platform` has a native `actual` (tested in `posixTest` / `mingwTest` on each target) and a
  JVM `actual` in `jvmMain`, only there so the common tests run on the JVM. Those JVM actuals carry
  `@ExcludeFromCoverage` with a `// coverage: <why>` line above; nothing else may use the annotation without the
  same line, read in review.
- Wiring is plain constructors in `CliDependencies` (no container: the start-up of `ostrom hook` matters more than
  the convenience). Tests build `CliDependencies` with fakes.
- Output goes through `Console` (unbuffered, UTF-8 on Windows consoles). Commands never call `println`.

## The organisation of a launch

`ostrom <agent>` (and `--cloud`, and `ostrom attach`) acts in one organisation: `--org`, else `OSTROM_ORG`, else the
one `ostrom init` kept. It is looked up among the caller's (`GET /api/v1/orgs`, by id or short name) and its id is sent
as `Ostrom-Org` on every call (`ApiServerTransport`); one the person is not a member of stops with 66. With none named,
nothing is looked up, the header is left out and the server takes the person's only organisation; a member of several
gets 409 `org_required` from the policy check, and the launch stops with 64 and the way to name one.

## The local relay

While `ostrom <agent>` runs, the launcher starts a `LocalRelay`
(`LocalRelayStarterImpl(report).start(RelayConfig(...))`) on `127.0.0.1` and a port chosen by the system, and closes
it when the agent exits. The agent's hooks, its MCP client
and, when the launch relays it, its inference reach Ostrom through it with one of two random capabilities (64
hexadecimal characters from `secureRandomBytes` each); the relay adds the launch's agent token, which never enters the
agent's environment (audit A12/A13). The hooks' capability (`LocalRelay.token`) is written to a private file
(`OSTROM_RELAY_TOKEN_FILE`) and opens the hook routes; the agent's own (`LocalRelay.clientToken`, `OSTROM_RELAY_TOKEN`)
opens its MCP servers, its inference and the read of a gate (`GET /api/v1/gates/{id}`, for `ostrom wait`), no other
hook route: 403 otherwise (S2-25).

`ostrom wait GATE_ID`, which a gate's refusal tells the agent to run as its own command, reaches Ostrom the same way
(`GET /api/v1/gates/{id}?wait=30`: Ostrom holds a read 30 s at most, a held read is asked again at once, under the
relay's 70 s bound): the agent's shell inherits the relay's URL and capability, never a token of Ostrom.

Security decision (30/10/2026 09:45): the agent's capability is exported a second time as `OSTROM_RELAY_CLIENT`,
because Codex's default shell policy drops every variable named like a token, key or secret, and `ostrom wait` runs in
that shell. Every command of the agent can therefore read it. This is accepted: it is the agent's own capability, for
this session only, on the loopback; it opens the agent's MCP servers, its inference and the read of a gate, never a
hook route (the hooks' capability stays in its private file, S2-25), and it never reaches Ostrom's server. Refused
alternative: a second private file for `ostrom wait`, which a command of the agent could read just the same.

- Every request needs a capability (`Authorization: Bearer`, `x-api-key` or `x-ostrom-local-token`, compared in
  constant time): 401 otherwise. An `Origin` header or a `Host` that is not the loopback: 403.
- Only an allow-list is forwarded, everything else is 404 (never an open proxy): the hook routes
  (`POST /api/v1/sessions`, `/sessions/{id}/reopen|seen`, `/api/v1/gates`, `/api/v1/notifications`,
  `/api/v1/checks`, `GET /api/v1/gates/{id}` and `/gates/catalog`, as they are, 2 MiB, 15 s, 70 s for a gate wait;
  ending a session, its sign of life and the use of a yes are the launcher's and Ostrom's, never forwarded, S2-25 and
  S2-29; reading check runs is the console's, never forwarded). A check run's report is the hooks', which run the
  package's checks, and takes their capability, not the agent's. It is the developer's machine's word: the hooks'
  capability file is readable by the agent's own user (S2-20), so a report proves no more than the hooks that sent
  it. The catalogue of the verified package is answered from memory when there is one. Also forwarded: the
  organisation's gate policy signed by the instance (`GET /api/v1/gates/catalog/signed`, kept 30 s in memory and read again every 12 s in the background while a hook
  asked within 2 minutes, so that no hook of an active session waits on Ostrom for it; a failure never kept, a refusal
  (401, 403, 404) read in the background drops it; the
  hooks check its signature against `OSTROM_INSTANCE_KEYS` and its expiry at every call, and hold every action without
  one, S2-20 and S2-38), `POST /mcp/{server}` for the launch's servers (to `/api/v1/mcp/{server}`, streamed, 401/403 from
  Ostrom become 502; Ostrom's own server, `ostrom`, goes to the waits server `/api/v1/waits/mcp`, with
  `?session=` the session segment Ostrom answered to the start hook, `<id>~r<n>` once resumed, none before), and with inference `/v1/messages`, `/v1/chat/completions`, `/v1/models` (to
  `/api/v1/inference/…`, 4 MiB, the response streamed, only the provider headers forwarded). Coming soon on the
  server: ostrom-server `main` does not serve `/api/v1/notifications` nor `/api/v1/checks` yet (ostrom-core's
  `api/contract/served-routes.txt`); the sessions and the signed policy are served since ostrom-server d9301e4.
- Ostrom unreachable or too slow: 502 with `{"error":{"type":"unreachable","message":"Ostrom is unreachable (…)"}}`,
  and one line for the user per outage through the starter's `report`; Ktor's own logger is silenced.
- `hooks` never depends on `relay`: it speaks plain HTTP to the loopback URL and capability the launcher hands it
  (how they reach the hook is the launch flow's choice, not this module's).

## Exit codes

`cli/.../ExitCode.kt`, from the spec (`CLI.md` § 2.3), printed by `ostrom help exit-codes`. The table below is the
same, line for line (`ExitCodesTest` fails otherwise). `ostrom <agent>` returns the agent's own code once it has
started.

| Code | Name | Meaning |
|---:|---|---|
| 0 | `OK` | Done. |
| 1 | `FAILED` | The check asked for failed: verify found the receipt invalid, doctor found a problem. |
| 2 | `INCOMPLETE` | Incomplete, cancelled, or a no. |
| 64 | `USAGE` | Bad usage: an unknown command, option or agent, an option not in this version, a question with no terminal. |
| 65 | `SIGN_IN_REQUIRED` | Sign-in required: run ostrom login. |
| 66 | `NOT_FOUND` | Not found: a receipt, a gate, a harness, or not a git repository. |
| 69 | `UNREACHABLE` | The server is unreachable, or the secret store did not answer. |
| 70 | `NOT_IMPLEMENTED` | The command is not implemented in this version yet. |
| 73 | `CANNOT_WRITE` | A local file could not be written. |
| 75 | `STILL_WAITING` | Still waiting: wait reached its deadline, or nobody confirmed a governance change in time. |
| 77 | `REFUSED` | Refused: the policy, your role, a self-approval, another organisation. |
| 78 | `SIGNATURE_REFUSED` | A signature or a package was refused. |
| 127 | `AGENT_MISSING` | The agent is not installed. |

A command declared but not built exits with 70 (`NOT_IMPLEMENTED`) and says so on stderr; it disappears once every
command is built. An option of a built command that this version does not have yet is a usage error instead: 64
(`USAGE`); none is in that state since `--cloud` was built. `ostrom <agent> --cloud` and `ostrom attach` end with the
session: 0 when it ends or the developer detaches, 64 for agent arguments (before any sign-in is read) or a `--repo`
not on GitHub, 65 to sign in again, 66 for no such session, 69 when Ostrom or the connection is lost (the session
keeps running: `ostrom attach <id>`), 77 for a session of someone else. `ostrom logs` exits 0 once the thread is printed
(`-f`: once the session ends), 64 for `--json` with `-f`, 65 to sign in again, 66 for no such session, 69 when Ostrom
cannot be reached, does not serve the sessions' reads (`GET /api/v1/sessions` not found: an older server) or answers
without the session, 77 for a session the caller may not read (a gate it may not read is shown by its id).

## What every command does the same way

- **Help**: `ostrom <command> --help` ends with examples and the page of the docs (`cli/.../help/CommandGuide.kt`,
  one entry per command: `HelpTest` fails for a command without one). A usage error ends with the
  `--help` to run.
- **Colours**: only on a terminal. `--no-color`, `NO_COLOR` (set and not empty, as no-color.org says) or
  `ostrom config set color never` turn them off, `TERM=dumb` too; `color always` forces them
  (`cli/.../help/Colours.kt`). Whether stdout is a terminal decides for help; a usage error, on stderr, never has colours.
- **`--json`**: one JSON document on stdout, then a newline, never a colour nor a line for humans; keys are only
  added, never renamed or removed, like the API contract. `ostrom doctor --json` is
  `{"checks":[{"status":"ok|warning|problem","message":"…"}],"problems":0,"warnings":0}`; `ostrom verify --json` is
  `{"checks":[{"check":"…","detail":"…","result":"…"}],"result":"verified|invalid|incomplete","title":"…"}`;
  `ostrom inbox --json` and `ostrom receipts list --json` are an array of the server's items as they are (the
  `items` of their route in the API contract). `JsonOutputTest` and `ReceiptCommandsTest` hold these shapes.
- **Errors**: say what happened and the command that gets you out (`ostrom login`, `ostrom doctor`, `--server`).
- **Governance writes** (`ostrom policy set`, the rule decisions and edits of `ostrom policy rules`, `ostrom harness
  enforce`, `update`, `remove`, `add --enforce`) never go with the device's token nor a console token (S2-52): they take the token of `ostrom login --admin`, which asks for
  `policy:write`, `harness:write` and `gates:policy` (`AdminTokens.SCOPES`), shown and approved in the browser by an
  owner or admin. The read a governance write makes first (the model policy of `ostrom policy set`, `inference:read`,
  which the device's token lacks) goes with the same token (`OrgAccess.readToGovern`). It is kept apart from the device's token (`<server>#admin` in the secret store, its expiry in
  `admin.json`), lasts until the server's expiry or 8 hours, the sooner, and is never renewed. Without a valid one, a
  403 is exit 77 saying to run `ostrom login --admin`; a 401 to it forgets it (exit 65). `OSTROM_TOKEN`, set on purpose,
  is never swapped for it. `ostrom logout` revokes and forgets it too, with or without the device's token. A token
  without all three scopes (a server that does not serve `--admin` yet), or already expired, is revoked and not kept
  (exit 69); one approved by another person than the device's is kept, and said.
  Made with a CLI token (the admin token or the device's), a governance write is held by the server until an owner or
  an admin confirms it on the console, from a session signed in less than 5 minutes ago (audit H1, decision of
  02/11/2026): the write answers 202
  `GovernanceConfirmationPending` and nothing is written. Any write answered so is waited on (`ConfirmationWait`): the
  CLI says the link (`confirmUrl`, else `<console>/confirm/<id>`) on stderr and polls
  `GET /api/v1/confirmations/{id}?wait=30` with the token that asked; a pending answer that came at once is asked again
  after 5 seconds, an outage (no answer, a 5xx) is tried 3 times in a row. The server says when it has expired; the CLI
  stops waiting a minute past the 15 minutes by its own clock. Confirmed, the command goes on with `result`, what the
  write returned (exit 0); refused, or confirmed but not written (`failed`, the server's reason said), exit 77;
  expired, or still pending when the CLI stops waiting, exit 75; a status it does not know, exit 1. Every stop while
  the request may still stand says where it can be confirmed; a 401 to the admin token forgets it.

## Add a command

1. Find its declaration: a command of the spec not built yet extends `PendingCommand`, with its options and
   arguments (`cli/.../commands/CheckCommand.kt` is the last one); `pendingCommands()` in `PendingCommands.kt` places
   it in the help.
2. Move it to its own file in `cli/.../commands/`, extend `CliktCommand` instead of `PendingCommand`, take
   `CliDependencies` in the constructor, and write `run()`. Put the logic in a use case (`XxxUseCase` /
   `XxxUseCaseImpl`) in the module it belongs to, or in a `cli/.../<topic>/` package when it only assembles others
   (`doctor/Doctor.kt` is the example).
3. In `pendingCommands(...)` replace the declaration with `named("<name>")` and add the instance to the
   `implemented` list in `OstromCli.tree()`. A new command adds its examples and docs page in
   `cli/.../help/CommandGuide.kt`.
4. Exit codes: throw `ProgramResult(ExitCode.X.code)`; messages to the user through `dependencies.console`, in
   English, with the wording of the spec (`CLI.md` § 2.2).
5. Tests first, in `cli/src/commonTest` with `OstromCliTest`'s fakes (console, environment, `FakeFileSystem`, API).
   A behaviour ported from the prototype takes the prototype's test cases.

A subcommand of a group (`harness add`) works the same way, inside its group's `.subcommands(...)`.

## Add an agent adapter

An adapter is what makes `ostrom <agent>` work for one agent: where its settings go (hooks, MCP servers) in a 0700
temporary folder, which environment it gets, the hook dialect (`OSTROM_HOOK_DIALECT`), and how its hook events read.

1. `launcher/.../Agent.kt`: add the agent (command, display name, executable, install URL once checked). The command
   tree and `doctor` pick it up.
2. Hook dialect: if its events or refusals differ from Claude Code's, add the event names to the table of
   `HookEvent.parse` and the refusal shape to `HookProcessorImpl.deny`, with test cases from the prototype
   (`mvp/launcher/src/adapters.rs`, `bridge/plugin/bin/lib.mjs`).
3. The adapter itself (settings files, environment, temporary folder) is the launcher agent's work and lands in
   `launcher` as `AgentAdapter` / one implementation per agent; it never writes into the repository or into
   `~/.claude` / `~/.codex`, and never passes a long-lived token in the agent's environment
   (`ostrom/mvp/COORD/VERIF-LANCEUR.md`).
4. What a repository can configure in that agent decides whether Ostrom's gate holds in it. Every adapter pins the
   variables of `ProgramVariables` (the shell of the hooks, the loader, git's configuration, the interpreters, the
   proxies) with the launcher's value, else the developer's, else empty; the few that are not unset when empty
   (`GIT_DIR`, `GIT_SSH_COMMAND`, `GIT_CONFIG_GLOBAL`, the editors, `HOME`, `SHELL`…) only to the developer's own
   value, and the agent must close them another way.

### What `ostrom claude` lets a repository configure

Claude Code is started with `--setting-sources user` (review REVUES-CLI-4, 04/11/2026): the repository's
`.claude/settings.json`, `.claude/settings.local.json` and `.mcp.json` are not read. They could otherwise run the
repository's programs at start, outside the gate and with the session's environment (a `SessionStart` hook,
`apiKeyHelper`, `statusLine`, the `.mcp.json` servers that `enableAllProjectMcpServers` approves), or wrap every hook
in `CLAUDE_CODE_SHELL_PREFIX`. The working folder and the repository's top are given back by `--add-dir` with
`CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD=1`: the team's `CLAUDE.md`, `.claude/CLAUDE.md` and `.claude/rules` are
read, as are the files they import from the repository. Probed on Claude Code 2.1.318, what the session reads:

| From the repository | `ostrom claude` | `--trust-project-settings` |
| --- | --- | --- |
| `.claude/settings.json`, `.claude/settings.local.json` (`env`, `hooks`, `apiKeyHelper`, `statusLine`, permissions) | no | yes |
| `.mcp.json` | no | yes, when the settings approve it |
| `.claude/skills`, `.claude/commands`, `.claude/agents`, `.claude/output-styles` | no | yes |
| `CLAUDE.md`, `.claude/CLAUDE.md`, `.claude/rules`, their imports from the repository | yes | yes |
| `CLAUDE.local.md`, a `CLAUDE.md` of a folder above the repository or between it and the working folder | no | yes |
| `.git/config` (a repository given as an archive) | yes, as git reads it | yes |

Ostrom's `--settings` still pin their variables in both cases (`ClaudeEnvironment`: `LaunchVariables.ALL`,
`ProgramVariables`, `CLAUDE_CODE_SHELL_PREFIX`, `CLAUDE_CODE_GIT_BASH_PATH`, `CLAUDE_CODE_SHELL`), and keep the hooks
and the plugin on. A developer's own `--setting-sources` is not used, and said.

What the developer's side cannot do either (review REVUES-CLI-5): Claude Code is not started with `--bare` or
`--safe-mode`, which skip every hook and plugin (the launch stops, exit 70); `CLAUDE_CODE_SIMPLE` and
`CLAUDE_CODE_SAFE_MODE`, the same modes by variable, are removed and written empty, never taken from the developer's
shell. `ostrom` reads no argument file: `ostrom claude -p @TASK.md` passes `@TASK.md` to Claude Code as it is, so a
file of the repository cannot carry `--trust-project-settings` or `--settings`. A developer's own `--settings` is
merged as before: it is theirs, typed on their command line.

`ostrom claude --trust-project-settings` is for a team that trusts its repository: Claude Code reads
`user,project,local` (or the developer's own `--setting-sources`), and the launch says before the agent starts that
the repository's settings can run its programs and turn the gate off. It lowers the guarantee: the pins hold, the
programs the repository's settings start do not go through the gate. The choice is said on the developer's terminal
only; Ostrom's server does not record it (no field in the launch's contract).

### What `ostrom gemini` lets a repository configure

Gemini CLI loads a `.env` file into its own environment, which every tool, hook and program it starts inherits. A
repository's `.env` ran its own programs outside the gate (review REVUES-CLI-6, probed on Gemini CLI 0.66): a
lower-case `google_application_credentials` naming an external account with an executable, and
`GOOGLE_EXTERNAL_ACCOUNT_ALLOW_EXECUTABLES=1`, runs that executable at the first call to the model, with
`OSTROM_RELAY_TOKEN` in its environment; `RIPGREP_CONFIG_PATH=.rgrc` with `--pre=./pre.sh` runs the script behind a
`grep_search` the gate allows. A list of names cannot follow every program's variables, so by default, as for
Claude Code, the repository does not configure the agent:

| From the repository | `ostrom gemini` | `--trust-project-settings` |
| --- | --- | --- |
| `.env` of the working folder or a folder above it | no (`advanced.ignoreLocalEnv` in the session's settings) | yes, Ostrom's pins win |
| `.gemini/.env` of the working folder or a folder above it | Gemini is not started (exit 70): Gemini loads it even with `ignoreLocalEnv` | yes, Ostrom's pins win |
| `.env` of the working folder, read as the developer's extensions' settings | no (`experimental.extensionConfig: false`) | yes |
| `.gemini/commands`: a command whose prompt holds `!{…}`, or injects a file (`@{…}`) and takes arguments (`{{args}}`) | Gemini is not started (exit 70): Gemini runs that shell command outside the gate | yes |
| `.gemini/agents`: any agent (a `.md` not starting with `_`, through links, in any case) | Gemini is not started (exit 70): an agent's `mcp_servers` start outside the gate, and a remote agent's `auth` runs a command or sends a variable when Gemini starts | yes |
| `.gemini/settings.json` | only the keys that change how the terminal looks, else Gemini is not started | same |
| `GEMINI.md` | yes | yes |

The developer's own `<home>/.gemini/.env` (or the one of their `GEMINI_CLI_HOME`) is still loaded. **What it costs**:
the application variables a repository keeps in its `.env` (`DATABASE_URL`, an API's address…) no longer reach the
shell commands Gemini runs; a team sets them in its own shell, or passes `--trust-project-settings`. The developer's
extensions run without the settings they keep in Gemini (`gemini extensions config`, an extension's `settings`):
Gemini 0.66 reads those from the extension's folder and the keychain, but also from the working folder's `.env`, whose
values replaced `${extensionPath}` in an extension's hooks and ran the repository's script (review REVUES-CLI-7), and
one setting turns both off. The launch names those extensions; their hooks and servers still run, `${extensionPath}`
pointing at the extension, and a setting is then read from the developer's own environment only. A repository's
commands that run nothing are loaded as before; its agents are not (review REVUES-CLI-8): a front matter whose second
line is `---` hid `mcp_servers` from a check of keys, and a remote agent's `auth` (`!…` run by `bash -c`, `$…` sent to
the card's address) is resolved when Gemini starts, acknowledged by the card's address and not by the text. Gemini 0.66
has no setting that skips the project's agents alone (`experimental.enableAgents: false` takes the developer's own and
the built-in ones away too), so the launch stops there, as `ostrom claude` reads none of the repository's agents. The
developer's own agents, in their home, are loaded as before.

`ostrom gemini --trust-project-settings` is for a team that trusts its repository: Gemini loads its `.env`, its commands
and its agents as it does outside Ostrom, and the launch says so before the agent starts. Ostrom still pins the variables it knows of (the shell,
the loader, git, the interpreters, ripgrep's configuration, Gemini's account and credentials, `google_application_credentials`
and `GOOGLE_EXTERNAL_ACCOUNT_ALLOW_EXECUTABLES` included), and still refuses a `.env` that names one it cannot set
empty (`GIT_DIR`, `GIT_SSH_COMMAND`, the editors…, in any case on Windows). It lowers the guarantee: a variable no list
knows of can still run the repository's programs. As for Claude, the choice is said on the developer's terminal only.

## Add a platform primitive

Declare an interface and its `Impl` in `platform/src/commonMain`, the platform half as `internal expect fun`, and the
`actual`s in `posixMain`, `mingwMain` and `jvmMain` (the last one for the tests only, see above). Keep the logic in
common code (as `standardInputOf` does for the stdin shim) so the 100 % coverage measures it. When the OS API is awkward from Kotlin, write a small `static inline`
C function in the `.def` file and call it. Test it for real in `posixTest` / `mingwTest`.

## Build and CI

Do not build on a shared Mac directly: CI builds everything. Locally, if unavoidable, go through
`~/git/ostrom-hq/.coord/build-lock.sh ./gradlew …`.

Every pull request marked ready runs one required check on Linux, `ci` (drafts use no runner):

- `scripts/check-conventions.py` (module graph, broad catches that drop a cancellation), detekt (zero issue), `jvmTest`,
  and `koverVerify` at 100 % of lines and branches. On failure, `scripts/uncovered.py` lists what is not covered.
- `linuxX64Test`; the linuxArm64 and mingwX64 test binaries and the release binaries of the three Linux-built targets;
  `scripts/measure.py` measures, on the release binary, `ostrom hook gate` and `ostrom --version` start-up, warm (fails
  above a 15 ms median, and a p95 of 20 ms for the hook and 30 ms for `version`) and with the file cache emptied first
  (300 ms, median of 5 starts), the hook's peak memory (25 MB; on Linux the figure read is never below the Python
  script's own, about 14 MB, so the budget catches a hook above 25 MB, not a growth under it) and the binary sizes
  (15 MB, Windows 17); `ci/build-linux.sh` leaves `dist/ostrom` for the end-to-end bench. linuxArm64 holds the same
  start-up and memory budgets. macOS runners vary by about 3 times from one machine to the next, so macOS holds about
  twice its slowest runner: a 30 ms median, a p95 of 45 ms for the hook and 60 ms for `version`, a 1500 ms median
  with the cache emptied, the same 25 MB. The `git push` budget is held on linuxX64 only, and Windows holds only its 40 ms
  median.

On `main` and on demand (`workflow_dispatch` with `full_validation`), three more jobs run: the
cross-compiled test binaries of every module on `ubuntu-24.04-arm` and on `windows-latest` (no Gradle there; the
Windows start-up budget is 40 ms, process creation costs more), and macosArm64 tests and binary on `macos-15`.
CI does not run on tags: it uploads binaries as artifacts and keeps a Gradle cache, which zizmor refuses in a
workflow that publishes (cache poisoning). The release workflow, built without cache, publishes the binaries.

`OSTROM_NETWORK_TESTS=1` turns on the real HTTPS test of `api-client` (each platform's TLS and certificate store).

What a Windows runner proves and a Linux cross-compilation does not: the child process sharing the console and
receiving Ctrl-C, stdin read through `PeekNamedPipe`, UTF-8 output on the console, WinHttp's TLS. All are in the
`windows` job; none can be checked without it.

## End-to-end bench

`ci/build-linux.sh` builds the linuxX64 release binary into `dist/ostrom`. Besides the variables the launcher passes
to the hooks, the binary reads `OSTROM_URL` (server), `OSTROM_HOME` (the CLI folder, instead of `~/.config/ostrom`),
`OSTROM_TOKEN` (the console token of `ostrom import teamcode`), `OSTROM_TELEMETRY` and `OSTROM_HOOK_DIALECT`. It finds
`claude`, `codex` and the other agents on `PATH`: no variable names their binary.

## Import from TeamCode

`ostrom import teamcode` (package `dev.ostrom.cli.imports` of `cli`) reads one TeamCode team with the `mysql` client
(`ProcessCapture`, one `START TRANSACTION READ ONLY`, the password in `MYSQL_PWD`, never on the command line), opens
TeamCode's credential envelopes with its key file (`aesGcmOpen` of `launcher`: AES-256-GCM, `v1:` then base64url of
nonce, ciphertext and tag, bound to `variant:<team>:<id>[:<member>]` or `<team>:<account>`), maps the members to
Ostrom's by e-mail then login (a server that does not list its members matches no one, and every
assignment is skipped), names an account's provider as Ostrom does (TeamCode's `anthropic` and `openai` are `claude`
and `codex`), and sends the plan to `POST /api/v1/import/teamcode?dryRun=true` (ostrom-server#28).
An MCP server's tool mode is named as Ostrom does too (TeamCode's `ALLOWLIST` and `DENYLIST`
are `allow` and `deny`); a server with any other mode is said and left out. It imports only with `--yes`, and prints
no secret. A normal pass refreshes no token: a shared account TeamCode still
uses is reported `revive_at_cutover`, and the CLI says how many wait. `--cutover` is the owner's final pass once
TeamCode is stopped (ostrom-server#86): the plan with `?dryRun=true&cutover=true` (its account lines say `planned` for
the accounts the cutover writes, revived ones included), then, only once the owner typed
`cutover` (or passed `--confirm cutover` without a terminal; `--cutover --yes` is a usage error), the same export
with `?cutover=true` (10 minutes at most: a token rotation per account at its provider), which rotates those accounts'
tokens and revives them. A plan with no account to write asks nothing (2 when its accounts cannot be imported).
Accounts left as they were are listed with their reason and end the command with 2; a lost answer says the cutover may have run in part, and the same command
shows what still waits.

## Usage statistics (opt-in)

`ostrom telemetry enable | disable | status` (`cli/.../telemetry/UsageStatistics.kt`). **Off unless the user turns it
on**, and `OSTROM_TELEMETRY=0` (or `false`, `off`, `no`) or `DO_NOT_TRACK=1` keep it off whatever the setting. When on,
each top-level command sends one `cli_command_run` event to PostHog EU after its output is written, waiting at most
400 ms (the sender's client gives up by then; a lost event is not retried): the command name (only one of the known
commands, read from the parsed command line, else `unknown`), `ok` / `usage` / `error`, a duration range, the OS, the
architecture and the version, with a random install id (`telemetry_id` in `config.toml`, deleted by `disable`, never
linked to the account). Never an argument, a path, a repository, code or a token; no IP address is kept and no
location derived from it (`$ip` null, `$geoip_disable`). Plumbing commands (`hook`, `mcp`, `wait`, `check`) and
`ostrom telemetry` itself are never measured. Statistics are on only with both `telemetry = "on"` and an install id;
`ostrom config set | unset` refuses these two keys and names `ostrom telemetry`, which keeps them consistent.

The destination is compiled into release builds only: CI passes the repository variable `OSTROM_POSTHOG_KEY` (a public,
write-only project key) as `-PostromPosthogKey` on tags. Any other build has none: `ostrom telemetry enable` then says
so and writes nothing, and nothing is ever sent.

Every call to the server carries a W3C `traceparent` (`api-client/.../TraceContext.kt`): one random trace id for the
whole command, a new parent id per call. The server continues the trace; this is not usage statistics and sends
nothing anywhere else.

## ostrom-core

`api-client` depends on `dev.ostrom:ostrom-api` (the API models of ostrom-core, `OstromJson`, `ApiError`). The version
is `ostromCoreVersion` in `gradle.properties`, and `gradle/ostrom-core.ref` is the ostrom-core commit that published it:
change both together.

- Locally: from GitHub Packages (`maven.pkg.github.com/ostrom-hq/ostrom-core`, with `gpr.user` / `gpr.key` in
  `~/.gradle/gradle.properties`, a token with `read:packages`), or from a clone at `../ostrom-core` with
  `ostrom.core.local=true`, which `includeBuild`s it and substitutes `ostrom-api`, `ostrom-receipts` and
  `ostrom-telemetry`.
- In CI (`ci.yml`, `release.yml`): a repository's `GITHUB_TOKEN` cannot read another repository's Maven packages, so
  ostrom-core is checked out at the commit of `gradle/ostrom-core.ref` with the read-only key `DEPLOY_KEY_CORE`, then
  built through `ostrom.core.local` (JOURNAL 05:50).
