#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Usage: scoop-manifest.sh <version> <releases-base-url> <dist-dir>   (prints ostrom.json)
set -eu
here=$(cd "$(dirname "$0")" && pwd)
. "$here/lib.sh"
[ $# -eq 3 ] || die "usage: scoop-manifest.sh <version> <releases-base-url> <dist-dir>"
version="$1"; base="$2"; dist="$3"
f=$(archive_of "$dist" "$version" windows-x64)
# autoupdate (Scoop's checkver -u) reads the archive's line of the SHA256SUMS published with the release, instead of
# hashing the download (AUDIT-SECU-PUBLIC P9). It catches a corrupt or swapped download; it is no stronger than the
# release itself, since Scoop does not check the SHA256SUMS signature (docs/RELEASES.md, Verifying).
cat <<JSON
{
  "version": "$version",
  "description": "Ostrom command line: governed AI coding agents, gates and signed receipts",
  "homepage": "https://ostrom.run",
  "license": "Apache-2.0",
  "architecture": {
    "64bit": {
      "url": "$(asset_url "$base" "$version" "$f")",
      "hash": "$(sha256_of "$f")"
    }
  },
  "bin": "ostrom.exe",
  "checkver": {
    "github": "https://github.com/ostrom-hq/ostrom-cli"
  },
  "autoupdate": {
    "architecture": {
      "64bit": {
        "url": "https://github.com/ostrom-hq/ostrom-cli/releases/download/\$version/ostrom-\$version-windows-x64.zip",
        "hash": {
          "url": "\$baseurl/SHA256SUMS",
          "regex": "\$sha256\\\\s+\$basename"
        }
      }
    }
  }
}
JSON
