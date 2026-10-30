#!/bin/sh
# Tiny assertion helpers shared by the release script tests (POSIX sh).
FAILS=0
TESTS=0

ok() { TESTS=$((TESTS + 1)); printf 'ok   %s\n' "$1"; }
ko() { TESTS=$((TESTS + 1)); FAILS=$((FAILS + 1)); printf 'FAIL %s\n' "$1" >&2; }

# assert_contains <name> <haystack> <needle>
assert_contains() {
  case "$2" in
    *"$3"*) ok "$1" ;;
    *) ko "$1 (missing: $3)"; printf '%s\n' "$2" | sed 's/^/    | /' >&2 ;;
  esac
}

# assert_eq <name> <expected> <actual>
assert_eq() {
  if [ "$2" = "$3" ]; then ok "$1"; else ko "$1 (expected '$2', got '$3')"; fi
}

# assert_fails <name> <command...>: the command must exit non-zero
assert_fails() {
  name="$1"; shift
  if "$@" >/dev/null 2>&1; then ko "$name (expected a failure)"; else ok "$name"; fi
}

finish() {
  printf '%s tests, %s failed\n' "$TESTS" "$FAILS"
  [ "$FAILS" -eq 0 ]
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# make_fixture <dir> <version>: a dist directory with the five archives and their SHA256SUMS.
make_fixture() {
  dir="$1"; version="$2"
  mkdir -p "$dir"
  for t in linux-x64 linux-arm64 macos-arm64; do
    printf 'binary %s %s\n' "$t" "$version" > "$dir/ostrom-$version-$t.tar.gz"
  done
  printf 'binary windows %s\n' "$version" > "$dir/ostrom-$version-windows-x64.zip"
  ( cd "$dir" && for f in ostrom-*; do
      if command -v sha256sum >/dev/null 2>&1; then sha256sum "$f"; else shasum -a 256 "$f"; fi
    done > SHA256SUMS )
}
