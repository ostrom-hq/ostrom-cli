#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Usage: latest-json.sh <version> <releases-base-url> <dist-dir>
# Prints the update manifest read by `ostrom update` and the installers:
#   {"version","date","assets":{"<target>":{"url","sha256","size"}}}
# The date is RELEASE_DATE when set, else now (UTC).
set -eu
here=$(cd "$(dirname "$0")" && pwd)
. "$here/lib.sh"
[ $# -eq 3 ] || die "usage: latest-json.sh <version> <releases-base-url> <dist-dir>"
version="$1"; base="$2"; dist="$3"
date_utc="${RELEASE_DATE:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}"

assets=""
for target in $TARGETS; do
  f=$(archive_of "$dist" "$version" "$target")
  entry=$(printf '"%s":{"url":"%s","sha256":"%s","size":%s}' \
    "$target" "$(asset_url "$base" "$version" "$f")" "$(sha256_of "$f")" "$(size_of "$f")")
  assets="${assets:+$assets,}$entry"
done
printf '{"version":"%s","date":"%s","assets":{%s}}\n' "$version" "$date_utc" "$assets"
