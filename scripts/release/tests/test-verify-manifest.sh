#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
here=$(cd "$(dirname "$0")" && pwd)
. "$here/lib.sh"
script="$here/../verify-manifest.sh"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
# two roots, as during a rotation: the signing key is the second one
for name in old next other; do
  openssl genpkey -algorithm ed25519 -out "$tmp/$name.key" 2>/dev/null
  openssl pkey -in "$tmp/$name.key" -pubout -out "$tmp/$name.pem" 2>/dev/null
done
printf '{"version":"1.0.0"}\n' > "$tmp/latest.json"
RELEASE_SIGNING_KEY=$(cat "$tmp/next.key") sh "$here/../sign-manifest.sh" "$tmp/latest.json"
openssl pkey -pubin -in "$tmp/next.pem" -outform DER > "$tmp/next.der"
id=$(sha256_file "$tmp/next.der")

out=$(sh "$script" "$tmp/latest.json" "$tmp/latest.json.sig" "$tmp/old.pem" "$tmp/next.pem" 2>&1)
assert_eq "the root that signed is named, whatever its place" "Verified by $id" "$out"
assert_fails "a signature no root made is refused" sh "$script" "$tmp/latest.json" "$tmp/latest.json.sig" "$tmp/old.pem" "$tmp/other.pem"
assert_fails "a retired root alone does not verify" sh "$script" "$tmp/latest.json" "$tmp/latest.json.sig" "$tmp/old.pem"
printf 'tampered\n' >> "$tmp/latest.json"
assert_fails "a changed manifest is refused" sh "$script" "$tmp/latest.json" "$tmp/latest.json.sig" "$tmp/next.pem"
printf 'not base64 !!\n' > "$tmp/bad.sig"
assert_fails "a signature that is not base64 is refused" sh "$script" "$tmp/latest.json" "$tmp/bad.sig" "$tmp/next.pem"
assert_fails "a missing file is an error" sh "$script" "$tmp/nope.json" "$tmp/latest.json.sig" "$tmp/next.pem"
assert_fails "a missing root is an error" sh "$script" "$tmp/latest.json" "$tmp/latest.json.sig" "$tmp/nope.pem"
assert_fails "no root is a usage error" sh "$script" "$tmp/latest.json" "$tmp/latest.json.sig"
finish
