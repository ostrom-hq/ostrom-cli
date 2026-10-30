#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Shared helpers for the release scripts (POSIX sh).

die() { printf '%s\n' "$*" >&2; exit 1; }

# sha256_of <file>: lowercase hex digest.
sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# size_of <file>: size in bytes.
size_of() { wc -c < "$1" | tr -d ' '; }

# archive_of <dist> <version> <target>: path of the archive of a target (zip on Windows, tar.gz elsewhere).
archive_of() {
  case "$3" in
    windows-*) ext=zip ;;
    *) ext=tar.gz ;;
  esac
  f="$1/ostrom-$2-$3.$ext"
  [ -f "$f" ] || die "missing archive: $f"
  printf '%s\n' "$f"
}

# asset_url <base> <version> <file>
asset_url() { printf '%s/download/%s/%s\n' "$1" "$2" "$(basename "$3")"; }

# shellcheck disable=SC2034  # used by the scripts that source this file
TARGETS="linux-x64 linux-arm64 macos-arm64 windows-x64"
