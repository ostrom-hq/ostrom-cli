#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Usage: verify-manifest.sh <latest.json> <latest.json.sig> <root.pem>...
# Checks the base64 Ed25519 signature of <latest.json> (raw message, as sign-manifest.sh signs it) against each root
# given, the way the CLI checks it against the roots compiled in from release/trust. Prints "Verified by <id>" (the
# SHA-256 of the root's SubjectPublicKeyInfo, TeamCode's key id) and exits 0 when one root verifies; exits 1 when none
# does. Needs OpenSSL 3.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
. "$here/lib.sh"
[ $# -ge 3 ] || die "usage: verify-manifest.sh <latest.json> <latest.json.sig> <root.pem>..."
manifest="$1"; signature="$2"; shift 2
[ -f "$manifest" ] || die "no such file: $manifest"
[ -f "$signature" ] || die "no such file: $signature"
tmp=$(mktemp -d); chmod 700 "$tmp"; trap 'rm -rf "$tmp"' EXIT
tr -d ' \r\n' < "$signature" | base64 -d > "$tmp/raw.sig" 2>/dev/null \
  || tr -d ' \r\n' < "$signature" | base64 -D > "$tmp/raw.sig" 2>/dev/null \
  || die "$signature is not base64"
for root in "$@"; do
  [ -f "$root" ] || die "no such file: $root"
  if openssl pkeyutl -verify -pubin -inkey "$root" -rawin -in "$manifest" -sigfile "$tmp/raw.sig" >/dev/null 2>&1; then
    openssl pkey -pubin -in "$root" -outform DER > "$tmp/root.der"
    printf 'Verified by %s\n' "$(sha256_of "$tmp/root.der")"
    exit 0
  fi
done
printf 'verify-manifest: no root verifies the signature of %s\n' "$manifest" >&2
exit 1
