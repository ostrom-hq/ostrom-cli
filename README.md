# ostrom-cli

`ostrom`, the command line of Ostrom: start a coding agent under your team's harness, answer approvals, verify
receipts. One Kotlin/Native binary for macOS (arm64), Linux (x64, arm64) and Windows (x64).

```
ostrom                 home screen
ostrom claude          start Claude Code under the harness (also codex, opencode, gemini, grok, hermes, pi)
ostrom doctor          check this device
ostrom --help          every command
```

Status: the socle. `version`, `doctor`, `config` and the home screen work; the other commands of the spec are
declared with their help and exit with 70 (`not implemented yet`).

- Modules and how to add a command or an agent adapter: [docs/MODULES.md](docs/MODULES.md)
- Build: CI builds and tests every target (`.github/workflows/ci.yml`). Locally: `./gradlew jvmTest koverVerify`
  for the common tests and their coverage, `./gradlew linuxX64Test` or `./gradlew macosArm64Test` natively.

Licence: Apache 2.0.
