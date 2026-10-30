# Modules of the CLI, and how to add to them

The CLI is one Kotlin/Native binary, `ostrom`, built for macosArm64, linuxX64, linuxArm64 and mingwX64. Every module
also has a `jvm()` target that only runs the common tests and measures coverage (Kover, 100 % of lines and branches);
it is never shipped. Every dependency must be published for the four native targets and the JVM. The conventions of
all Ostrom repositories are in `ostrom-core/docs/CONVENTIONS.md`; this page only adds what is particular to the CLI.

## The modules

| Module | Package | Holds | Depends on |
| --- | --- | --- | --- |
| `platform` | `dev.ostrom.cli.platform` | everything that touches the OS: processes sharing the terminal, stdin with a deadline, unbuffered stdout/stderr, environment, files, `config.toml`, PATH lookup | okio |
| `hooks` | `dev.ostrom.cli.hooks` | `ostrom hook gate\|session\|gates-refresh`: event parsing (all agents' dialects), the gate, the reply | `platform` |
| `api-client` | `dev.ostrom.cli.api` | the server over HTTPS (Ktor, platform engine) | ostrom-core (once published) |
| `launcher` | `dev.ostrom.cli.launcher` | the agents (`Agent`) and starting one in this terminal | `platform` |
| `mcp` | `dev.ostrom.cli.mcp` | the local MCP server on stdio (`ostrom mcp`) | `platform` |
| `relay` | `dev.ostrom.cli.relay` | the launch's local relay on `127.0.0.1` (Ktor CIO): hooks, MCP and inference reach Ostrom through it | `platform` |
| `cli` | `dev.ostrom.cli` | the command tree (Clikt), `main`, the wiring (`CliDependencies`), the executable | all of the above |

Rules that keep the binary fast and the modules apart:

- Only `platform` uses cinterop. Its C shims are in `platform/src/nativeInterop/cinterop/` (`posix.def` for macOS and
  Linux, `windows.def` for Windows). Shared POSIX Kotlin lives in `src/posixMain`, added to `macosMain` and
  `linuxMain`; Windows code in `src/mingwMain`.
- `hooks` stays light: no Clikt, no Ktor, no coroutines. `main` sends `ostrom hook …` straight to `HookRunner` before
  building anything else (`cli/.../Main.kt`). A hook that needs the server reads a local cache and refreshes it in a
  detached `ostrom hook gates-refresh`, as the prototype does; it never waits on the network.
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

## The local relay

While `ostrom <agent>` runs, the launcher starts a `LocalRelay`
(`LocalRelayStarterImpl(report).start(RelayConfig(...))`) on `127.0.0.1` and a port chosen by the system, and closes
it when the agent exits. The agent's hooks, its MCP client
and, when the launch relays it, its inference reach Ostrom through it with the relay's random capability
(`LocalRelay.token`, 64 hexadecimal characters from `secureRandomBytes`); the relay adds the launch's agent token,
which never enters the agent's environment (audit A12/A13).

- Every request needs the capability (`Authorization: Bearer`, `x-api-key` or `x-ostrom-local-token`, compared in
  constant time): 401 otherwise. An `Origin` header or a `Host` that is not the loopback: 403.
- Only an allow-list is forwarded, everything else is 404 (never an open proxy): the hook routes
  (`/api/v1/sessions…`, `/api/v1/gates…`, `/api/v1/checks`, `/api/v1/notifications`, as they are, 2 MiB, 15 s, 70 s
  for a gate wait), `POST /mcp/{server}` for the launch's servers (to `/api/v1/mcp/{server}`, streamed, 401/403 from
  Ostrom become 502), and with inference `/v1/messages`, `/v1/chat/completions`, `/v1/models` (to
  `/api/v1/inference/…`, 4 MiB, the response streamed, only the provider headers forwarded).
- Ostrom unreachable or too slow: 502 with `{"error":{"type":"unreachable","message":"Ostrom is unreachable (…)"}}`,
  and one line for the user per outage through the starter's `report`; Ktor's own logger is silenced.
- `hooks` never depends on `relay`: it speaks plain HTTP to the loopback URL and capability the launcher hands it
  (how they reach the hook is the launch flow's choice, not this module's).

## Exit codes

`cli/.../ExitCode.kt`, from the spec (`CLI.md` § 2.3). A command declared but not built exits with 70
(`NOT_IMPLEMENTED`) and says so on stderr; it disappears once every command is built.

## Add a command

1. Find its declaration in `cli/.../commands/PendingCommands.kt` (every command of the spec is declared there with
   its options and arguments, as `PendingCommand` or `GroupCommand`).
2. Move it to its own file in `cli/.../commands/`, extend `CliktCommand` instead of `PendingCommand`, take
   `CliDependencies` in the constructor, and write `run()`. Put the logic in a use case (`XxxUseCase` /
   `XxxUseCaseImpl`) in the module it belongs to, or in a `cli/.../<topic>/` package when it only assembles others
   (`doctor/Doctor.kt` is the example).
3. In `pendingCommands(...)` replace the declaration with `named("<name>")` and add the instance to the
   `implemented` list in `OstromCli.tree()`.
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

## Add a platform primitive

Declare an interface and its `Impl` in `platform/src/commonMain`, the platform half as `internal expect fun`, and the
`actual`s in `posixMain`, `mingwMain` and `jvmMain` (the last one for the tests only, see above). Keep the logic in
common code (as `standardInputOf` does for the stdin shim) so the 100 % coverage measures it. When the OS API is awkward from Kotlin, write a small `static inline`
C function in the `.def` file and call it. Test it for real in `posixTest` / `mingwTest`.

## Build and CI

Do not build on a shared Mac directly: CI builds everything. Locally, if unavoidable, go through
`~/git/ostrom-hq/.coord/build-lock.sh ./gradlew …`.

Every pull request runs two required checks on Linux:

- `coverage`: `scripts/check-conventions.py` (module graph, broad catches that drop a cancellation), detekt (zero issue), `jvmTest`, and
  `koverVerify` at 100 % of lines and branches. On failure, `scripts/uncovered.py` lists what is not covered.
- `native-tests`: `linuxX64Test`; the linuxArm64 and mingwX64 test binaries and the release binaries of the three
  Linux-built targets; `scripts/measure.py` measures `ostrom hook gate` and `ostrom version` start-up (fails above a
  15 ms median) and the binary sizes; `ci/build-linux.sh` leaves `dist/ostrom` for the end-to-end bench.

On `main`, on tags, and on demand (`workflow_dispatch` with `full_validation`), three more jobs run: the
cross-compiled test binaries of every module on `ubuntu-24.04-arm` and on `windows-latest` (no Gradle there; the
Windows start-up budget is 40 ms, process creation costs more), and macosArm64 tests and binary on `macos-15`.

`OSTROM_NETWORK_TESTS=1` turns on the real HTTPS test of `api-client` (each platform's TLS and certificate store).

What a Windows runner proves and a Linux cross-compilation does not: the child process sharing the console and
receiving Ctrl-C, stdin read through `PeekNamedPipe`, UTF-8 output on the console, WinHttp's TLS. All are in the
`windows` job; none can be checked without it.

## End-to-end bench

`ci/build-linux.sh` builds the linuxX64 release binary into `dist/ostrom`. The binary reads `OSTROM_URL` (server),
`OSTROM_HOME` (the CLI folder, instead of `~/.config/ostrom`) and `OSTROM_HOOK_DIALECT` today; `OSTROM_TOKEN` and
`OSTROM_CLAUDE_BIN` / `OSTROM_CODEX_BIN` arrive with `login` and the launcher.

## ostrom-core

`api-client` will depend on `dev.ostrom:ostrom-api` (the API models of ostrom-core), read from GitHub Packages
(`maven.pkg.github.com/ostrom-hq/ostrom-core`, with `GITHUB_ACTOR` / `GITHUB_TOKEN`, or `gpr.user` / `gpr.key` in
`~/.gradle/gradle.properties`). Set `ostromCoreVersion` in `gradle.properties` once a version is published. A clone of
ostrom-core next to this one (`../ostrom-core`, or `../../ostrom-core` from a worktree) replaces the package through
`includeBuild`, so a contract change can be tried here before it is released.
