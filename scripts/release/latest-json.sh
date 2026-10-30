#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Usage: latest-json.sh <version> <releases-base-url> <dist-dir>
# Prints the update manifest read by `ostrom update` and the installers:
#   {"version","floor","date","assets":{"<target>":{"url","sha256","size"}}}
# The date is RELEASE_DATE when set, else now (UTC). The floor is the lowest version `ostrom update --version` and
# `--rollback` may install (security audit S2-39): RELEASE_FLOOR when set, else release-floor.txt at the repository's
# root; MAJOR.MINOR.PATCH, never above the version released. It is signed with the manifest (sign-manifest.sh).
set -eu
here=$(cd "$(dirname "$0")" && pwd)
. "$here/lib.sh"
[ $# -eq 3 ] || die "usage: latest-json.sh <version> <releases-base-url> <dist-dir>"
version="$1"; base="$2"; dist="$3"
date_utc="${RELEASE_DATE:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}"
floor="${RELEASE_FLOOR:-$(tr -d ' \n\r' < "$here/../../release-floor.txt")}"
printf '%s\n' "$floor" | grep -Eq '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$' \
  || die "the version floor is not MAJOR.MINOR.PATCH: '$floor'"
core=$(printf '%s\n' "$version" | sed 's/^v//; s/-.*//')
lowest=$(printf '%s\n%s\n' "$floor" "$core" | sort -t. -k1,1n -k2,2n -k3,3n | head -n 1)
[ "$lowest" = "$floor" ] || die "the version floor $floor is above the version released, $version"

assets=""
for target in $TARGETS; do
  f=$(archive_of "$dist" "$version" "$target")
  entry=$(printf '"%s":{"url":"%s","sha256":"%s","size":%s}' \
    "$target" "$(asset_url "$base" "$version" "$f")" "$(sha256_of "$f")" "$(size_of "$f")")
  assets="${assets:+$assets,}$entry"
done
printf '{"version":"%s","floor":"%s","date":"%s","assets":{%s}}\n' "$version" "$floor" "$date_utc" "$assets"
