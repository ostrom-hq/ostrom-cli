# ostrom-cli contributor instructions

This is the native command-line tool (Kotlin/Native: macosArm64, linuxX64, linuxArm64, mingwX64; + jvm for tests only). Apache-2.0.

**The conventions are in [ostrom-core `docs/CONVENTIONS.md`](https://github.com/ostrom-hq/ostrom-core/blob/main/docs/CONVENTIONS.md) (local clone: `../ostrom-core/docs/CONVENTIONS.md`).** They follow Nathan Fallet's practices (teamcode, asonar,
pkg) and bind every contributor, human or agent. This file only summarizes the most important rules; when in
doubt, the conventions file wins, and it can be updated: re-read it before each pull request.

All repository content (code, comments, docs, commits, pull requests) is in English.

## Specific to this repository

Never depend on server modules: only ostrom-core's `api`, `client` and protocol modules. Processes and PTY go through thin cinterop headers (`src/nativeInterop/cinterop`), as teamcode's `process.h`. End-to-end tests run the real binary against the real server, with a fake agent that plays the hooks; the hook start-up time has a tested budget. Budgets tested in CI on the release binary: hook < 15 ms, `ostrom --version` < 30 ms (CONVENTIONS.md section 16.3).

## The 15 rules that matter most

1. Read CONVENTIONS.md before each pull request; it wins over habits from other projects.
2. Layers and graph: `api` ← `client` ← `cli`; `domain` ← `infrastructure`, `presentation` ← `app`. Domain and API depend on no other project module; the CLI never on server modules.
3. Interfaces `XxxRepository`, `XxxUseCase`, `XxxService`; implementations `XxxImpl` (never `IXxx`). One per file, grouped by feature.
4. Business rules live in use cases (`suspend operator fun invoke`), never in routes or CLI commands. Constructor injection; Koin wired only in the composition root, no `koin-ktor` plugin.
5. Ktor: typed `@Resource` classes from ostrom-core, one `Route.xxxRoutes(XxxRoutesDependencies)` per feature, mappers as extension functions.
6. Expected refusals are domain exceptions extending one `OstromException(kind)` base, routed by type in the single StatusPages `Throwable` handler (on Kotlin/Native StatusPages matches exact classes only) to 400/401/403/404/409/402 with a stable `ApiError` key; 500 only for the unforeseen, never echoing its message (ENG-007).
7. **Never swallow `CancellationException`**: rethrow it before any broad catch, no `runCatching` around suspend code, cleanup in `withContext(NonCancellable)`. `scripts/check-conventions.py` fails the build otherwise (INF-019).
8. Contract changes are additive: new fields optional, removal only after the optional phase; every sealed subclass has a stable `@SerialName` (ENG-009).
9. Never log credentials, tokens, prompts, response bodies or raw provider errors.
10. Configuration is read once from `OSTROM_*` variables into a typed object; no feature switch in an environment variable (ENG-050).
11. Tests: TDD (red first), `kotlin.test` + `runTest` + hand-written fakes in `commonTest` (no MockK), lowerCamelCase sentence names. 100 % lines and branches of commonMain via the test-only `jvm()` target (Kover), plus `linuxX64Test`; integration on real PostgreSQL, end-to-end on the real binary, load tests for the server.
12. Kotlin/Native only in production: no JVM-only library (Exposed, JDBC, Netty, Logback, MockK). Ktor CIO server, Ktor engine-defaults client, sqlx4k, cryptography-kotlin + OpenSSL prebuilt, Okio, Clikt.
13. Gradle: versions only in `gradle/libs.versions.toml` (same versions in every repository), convention plugin `ostrom.kmp` from `build-logic/`, module build files hold dependencies only. No heavy local build on the shared Mac: CI, or `~/git/ostrom-hq/.coord/build-lock.sh`.
14. CI: Linux on every pull request (detekt, check-conventions, jvmTest + koverVerify, linuxX64Test, PostgreSQL integration); macOS, Windows and arm64 runs on tags or `full_validation` only; actions pinned by SHA.
15. Git: one branch per topic in its own worktree, never push to `main`, Conventional Commits in English, squash-merge after green CI and a review with nothing blocking; evidence in the pull request, never in a shared status file.

## Coordination

Agents working at night read `~/git/ostrom-hq/.coord/BRIEF.md` first, log their work in `.coord/JOURNAL.md` and put
questions in `.coord/QUESTIONS.md` with their recommendation, then continue with it. Never modify another agent's
branch or worktree, never kill a process you did not start.
