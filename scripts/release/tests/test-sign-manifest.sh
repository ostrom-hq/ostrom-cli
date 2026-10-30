#!/bin/sh
here=$(cd "$(dirname "$0")" && pwd)
. "$here/lib.sh"
script="$here/../sign-manifest.sh"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
openssl genpkey -algorithm ed25519 -out "$tmp/key.pem" 2>/dev/null
openssl pkey -in "$tmp/key.pem" -pubout -out "$tmp/pub.pem" 2>/dev/null
printf '{"version":"1.0.0"}\n' > "$tmp/latest.json"

RELEASE_SIGNING_KEY=$(cat "$tmp/key.pem") sh "$script" "$tmp/latest.json"
assert_eq "signature file written" "yes" "$([ -s "$tmp/latest.json.sig" ] && echo yes || echo no)"
base64 -d < "$tmp/latest.json.sig" > "$tmp/raw.sig" 2>/dev/null || base64 -D < "$tmp/latest.json.sig" > "$tmp/raw.sig"
assert_eq "raw Ed25519 signature is 64 bytes" "64" "$(wc -c < "$tmp/raw.sig" | tr -d ' ')"
assert_eq "signature is one line" "1" "$(wc -l < "$tmp/latest.json.sig" | tr -d ' ')"
if openssl pkeyutl -verify -pubin -inkey "$tmp/pub.pem" -rawin -in "$tmp/latest.json" -sigfile "$tmp/raw.sig" >/dev/null 2>&1
then ok "signature verifies with the public key"; else ko "signature verifies with the public key"; fi
printf 'tampered\n' >> "$tmp/latest.json"
if openssl pkeyutl -verify -pubin -inkey "$tmp/pub.pem" -rawin -in "$tmp/latest.json" -sigfile "$tmp/raw.sig" >/dev/null 2>&1
then ko "tampered manifest must not verify"; else ok "tampered manifest does not verify"; fi
assert_fails "no key is an error" env -u RELEASE_SIGNING_KEY sh "$script" "$tmp/latest.json"
assert_fails "missing file is an error" env RELEASE_SIGNING_KEY=x sh "$script" "$tmp/nope.json"
finish
