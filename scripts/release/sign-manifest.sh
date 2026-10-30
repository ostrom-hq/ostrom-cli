#!/bin/sh
# Usage: sign-manifest.sh <file>
# Signs <file> with Ed25519 (raw message, no prehash) using the PEM PKCS#8 key in $RELEASE_SIGNING_KEY and writes
# the base64 signature (one line) to <file>.sig. Needs OpenSSL 3. The key never touches the disk unprotected
# longer than the signing itself (a 0600 file in a private temp dir, removed on exit).
set -eu
here=$(cd "$(dirname "$0")" && pwd)
. "$here/lib.sh"
[ $# -eq 1 ] || die "usage: sign-manifest.sh <file>"
file="$1"
[ -f "$file" ] || die "no such file: $file"
[ -n "${RELEASE_SIGNING_KEY:-}" ] || die "RELEASE_SIGNING_KEY is not set"
tmp=$(mktemp -d); chmod 700 "$tmp"; trap 'rm -rf "$tmp"' EXIT
( umask 077; printf '%s\n' "$RELEASE_SIGNING_KEY" > "$tmp/key.pem" )
openssl pkeyutl -sign -inkey "$tmp/key.pem" -rawin -in "$file" -out "$tmp/sig"
base64 < "$tmp/sig" | tr -d '\n' > "$file.sig"
printf '\n' >> "$file.sig"
