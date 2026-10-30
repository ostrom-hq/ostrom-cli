#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Usage: winget-manifests.sh <version> <releases-base-url> <dist-dir> <out-dir>
# Writes the three winget manifest files (version, installer, defaultLocale) of OstromHQ.Ostrom into <out-dir>.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
. "$here/lib.sh"
[ $# -eq 4 ] || die "usage: winget-manifests.sh <version> <releases-base-url> <dist-dir> <out-dir>"
version="$1"; base="$2"; dist="$3"; out="$4"
f=$(archive_of "$dist" "$version" windows-x64)
sha_upper=$(sha256_of "$f" | tr 'a-f' 'A-F')
mkdir -p "$out"
cat > "$out/OstromHQ.Ostrom.yaml" <<YAML
# yaml-language-server: \$schema=https://aka.ms/winget-manifest.version.1.9.0.schema.json
PackageIdentifier: OstromHQ.Ostrom
PackageVersion: $version
DefaultLocale: en-US
ManifestType: version
ManifestVersion: 1.9.0
YAML
cat > "$out/OstromHQ.Ostrom.installer.yaml" <<YAML
# yaml-language-server: \$schema=https://aka.ms/winget-manifest.installer.1.9.0.schema.json
PackageIdentifier: OstromHQ.Ostrom
PackageVersion: $version
InstallerType: zip
NestedInstallerType: portable
NestedInstallerFiles:
  - RelativeFilePath: ostrom.exe
    PortableCommandAlias: ostrom
Installers:
  - Architecture: x64
    InstallerUrl: $(asset_url "$base" "$version" "$f")
    InstallerSha256: $sha_upper
ManifestType: installer
ManifestVersion: 1.9.0
YAML
cat > "$out/OstromHQ.Ostrom.locale.en-US.yaml" <<YAML
# yaml-language-server: \$schema=https://aka.ms/winget-manifest.defaultLocale.1.9.0.schema.json
PackageIdentifier: OstromHQ.Ostrom
PackageVersion: $version
PackageLocale: en-US
Publisher: Ostrom
PublisherUrl: https://ostrom.run
PackageName: Ostrom
PackageUrl: https://ostrom.run
License: Apache-2.0
LicenseUrl: https://github.com/ostrom-hq/ostrom-cli/blob/main/LICENSE
ShortDescription: Governed AI coding agents, gates and signed receipts.
Tags:
  - ai
  - cli
  - agents
ManifestType: defaultLocale
ManifestVersion: 1.9.0
YAML
