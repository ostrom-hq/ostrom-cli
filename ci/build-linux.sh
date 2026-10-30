#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Builds the linuxX64 release binary and leaves it at dist/ostrom (contract of the end-to-end bench, ostrom-e2e).
# The binary honours OSTROM_URL, OSTROM_HOME and OSTROM_HOOK_DIALECT today; OSTROM_TOKEN and
# OSTROM_CLAUDE_BIN / OSTROM_CODEX_BIN come with login and the launcher.
# ostrom-core comes from GitHub Packages (gpr.user and gpr.key: a token with read:packages), or, as in this repository's
# CI, from a checkout at ../ostrom-core of the commit in gradle/ostrom-core.ref, with ostrom.core.local=true in
# ~/.gradle/gradle.properties (DEPLOY_KEY_CORE, JOURNAL 05:50).
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew --no-daemon :cli:linkReleaseExecutableLinuxX64
mkdir -p dist
cp cli/build/bin/linuxX64/releaseExecutable/ostrom.kexe dist/ostrom
chmod 755 dist/ostrom
echo "dist/ostrom: $(dist/ostrom version)"
