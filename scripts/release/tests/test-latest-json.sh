#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
here=$(cd "$(dirname "$0")" && pwd)
. "$here/lib.sh"
script="$here/../latest-json.sh"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
make_fixture "$tmp/dist" 1.2.3
base=https://example.test/releases

out=$(RELEASE_DATE=2026-10-30T10:00:00Z sh "$script" 1.2.3 "$base" "$tmp/dist")
assert_eq "valid JSON" "1.2.3" "$(printf '%s' "$out" | jq -r .version)"
assert_eq "date" "2026-10-30T10:00:00Z" "$(printf '%s' "$out" | jq -r .date)"
assert_eq "four targets" "linux-arm64,linux-x64,macos-arm64,windows-x64" \
  "$(printf '%s' "$out" | jq -r '.assets | keys | join(",")')"
assert_eq "url layout" "$base/download/1.2.3/ostrom-1.2.3-linux-x64.tar.gz" \
  "$(printf '%s' "$out" | jq -r '.assets["linux-x64"].url')"
assert_eq "windows is a zip" "$base/download/1.2.3/ostrom-1.2.3-windows-x64.zip" \
  "$(printf '%s' "$out" | jq -r '.assets["windows-x64"].url')"
assert_eq "sha256 from the file" "$(sha256_file "$tmp/dist/ostrom-1.2.3-macos-arm64.tar.gz")" \
  "$(printf '%s' "$out" | jq -r '.assets["macos-arm64"].sha256')"
assert_eq "size in bytes" "$(wc -c < "$tmp/dist/ostrom-1.2.3-linux-arm64.tar.gz" | tr -d ' ')" \
  "$(printf '%s' "$out" | jq -r '.assets["linux-arm64"].size')"
assert_eq "size is a number" "number" "$(printf '%s' "$out" | jq -r '.assets["linux-x64"].size | type')"

assert_fails "missing archive is an error" sh "$script" 9.9.9 "$base" "$tmp/dist"
rm "$tmp/dist/ostrom-1.2.3-linux-x64.tar.gz"
assert_fails "missing target is an error" sh "$script" 1.2.3 "$base" "$tmp/dist"
assert_fails "usage" sh "$script"
finish
