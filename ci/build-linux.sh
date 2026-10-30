#!/usr/bin/env bash
# Builds the linuxX64 release binary and leaves it at dist/ostrom (contract of the end-to-end bench, ostrom-e2e).
# The binary honours OSTROM_URL, OSTROM_HOME and OSTROM_HOOK_DIALECT today; OSTROM_TOKEN and
# OSTROM_CLAUDE_BIN / OSTROM_CODEX_BIN come with login and the launcher.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew --no-daemon :cli:linkReleaseExecutableLinuxX64
mkdir -p dist
cp cli/build/bin/linuxX64/releaseExecutable/ostrom.kexe dist/ostrom
chmod 755 dist/ostrom
echo "dist/ostrom: $(dist/ostrom version)"
