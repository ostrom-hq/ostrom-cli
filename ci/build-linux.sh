#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Builds the linuxX64 release binary and leaves it at dist/ostrom (contract of the end-to-end bench, ostrom-e2e).
# The binary honours OSTROM_URL, OSTROM_HOME and OSTROM_HOOK_DIALECT today; OSTROM_TOKEN and
# OSTROM_CLAUDE_BIN / OSTROM_CODEX_BIN come with login and the launcher.
# ostrom-core comes from GitHub Packages: GITHUB_TOKEN (or gpr.user / gpr.key in ~/.gradle/gradle.properties) must be
# a token with read:packages on ostrom-hq/ostrom-core (in a workflow: permissions packages: read).
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -z "${GITHUB_TOKEN:-}" ] && ! grep -qs '^gpr.key=' "${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties"; then
  echo "build-linux.sh: set GITHUB_TOKEN (read:packages) to read ostrom-core from GitHub Packages" >&2
  exit 1
fi
./gradlew --no-daemon :cli:linkReleaseExecutableLinuxX64
mkdir -p dist
cp cli/build/bin/linuxX64/releaseExecutable/ostrom.kexe dist/ostrom
chmod 755 dist/ostrom
echo "dist/ostrom: $(dist/ostrom version)"
