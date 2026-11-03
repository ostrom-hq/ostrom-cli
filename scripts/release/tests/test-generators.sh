#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
here=$(cd "$(dirname "$0")" && pwd)
. "$here/lib.sh"
rel="$here/.."
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
make_fixture "$tmp/dist" 1.2.3
base=https://example.test/releases
sum() { sha256_file "$tmp/dist/ostrom-1.2.3-$1"; }

# Homebrew
brew=$(sh "$rel/brew-formula.sh" 1.2.3 "$base" "$tmp/dist")
assert_contains "brew: class" "$brew" 'class Ostrom < Formula'
assert_contains "brew: license" "$brew" 'license "Apache-2.0"'
assert_contains "brew: version" "$brew" 'version "1.2.3"'
assert_contains "brew: macOS arm url" "$brew" "url \"$base/download/1.2.3/ostrom-1.2.3-macos-arm64.tar.gz\""
assert_contains "brew: macOS arm sha" "$brew" "sha256 \"$(sum macos-arm64.tar.gz)\""
assert_contains "brew: linux x64 sha" "$brew" "sha256 \"$(sum linux-x64.tar.gz)\""
assert_contains "brew: linux arm sha" "$brew" "sha256 \"$(sum linux-arm64.tar.gz)\""
assert_contains "brew: installs the binary" "$brew" 'bin.install "ostrom"'
assert_contains "brew: has a test block" "$brew" 'ostrom'
assert_fails "brew: usage" sh "$rel/brew-formula.sh"

# Scoop
scoop=$(sh "$rel/scoop-manifest.sh" 1.2.3 "$base" "$tmp/dist")
assert_eq "scoop: valid JSON, version" "1.2.3" "$(printf '%s' "$scoop" | jq -r .version)"
assert_eq "scoop: url" "$base/download/1.2.3/ostrom-1.2.3-windows-x64.zip" \
  "$(printf '%s' "$scoop" | jq -r '.architecture["64bit"].url')"
assert_eq "scoop: hash" "$(sum windows-x64.zip)" "$(printf '%s' "$scoop" | jq -r '.architecture["64bit"].hash')"
assert_eq "scoop: bin" "ostrom.exe" "$(printf '%s' "$scoop" | jq -r .bin)"
assert_eq "scoop: license" "Apache-2.0" "$(printf '%s' "$scoop" | jq -r .license)"
# Scoop's autoupdate (checkver -u) takes the sum from the SHA256SUMS published next to the archive, as Scoop reads it:
# $version, $baseurl and $basename come from the autoupdate url; $sha256 is a 64-hex group; \s is a space.
auto() { printf '%s' "$scoop" | jq -r ".autoupdate.architecture[\"64bit\"]$1"; }
url=$(auto .url | sed 's/\$version/1.2.3/g')
baseurl=${url%/*}; basename=${url##*/}
assert_eq "scoop: autoupdate hash next to the archive" "$baseurl/SHA256SUMS" "$(auto .hash.url | sed "s|\\\$baseurl|$baseurl|")"
(cd "$tmp/dist" && for f in ostrom-*; do printf '%s  %s\n' "$(sha256_file "$f")" "$f"; done) > "$tmp/SHA256SUMS"
re=$(auto .hash.regex | sed -e 's/\$sha256/([a-fA-F0-9]{64})/' -e "s/\\\$basename/$(printf '%s' "$basename" | sed 's/[.]/[.]/g')/" \
  -e 's/\\s/[[:space:]]/g')
assert_eq "scoop: autoupdate finds the archive's sum in SHA256SUMS" "$(sum windows-x64.zip)" \
  "$(grep -E "$re" "$tmp/SHA256SUMS" | cut -d' ' -f1)"
assert_fails "scoop: usage" sh "$rel/scoop-manifest.sh"

# winget
sh "$rel/winget-manifests.sh" 1.2.3 "$base" "$tmp/dist" "$tmp/winget"
for f in OstromHQ.Ostrom.yaml OstromHQ.Ostrom.installer.yaml OstromHQ.Ostrom.locale.en-US.yaml; do
  assert_eq "winget: $f exists" yes "$([ -s "$tmp/winget/$f" ] && echo yes || echo no)"
done
v=$(cat "$tmp/winget/OstromHQ.Ostrom.yaml")
assert_contains "winget: version file id" "$v" 'PackageIdentifier: OstromHQ.Ostrom'
assert_contains "winget: version file type" "$v" 'ManifestType: version'
assert_contains "winget: version" "$v" 'PackageVersion: 1.2.3'
i=$(cat "$tmp/winget/OstromHQ.Ostrom.installer.yaml")
assert_contains "winget: installer type" "$i" 'ManifestType: installer'
assert_contains "winget: portable in zip" "$i" 'NestedInstallerType: portable'
assert_contains "winget: nested file" "$i" 'RelativeFilePath: ostrom.exe'
assert_contains "winget: url" "$i" "InstallerUrl: $base/download/1.2.3/ostrom-1.2.3-windows-x64.zip"
upper=$(sum windows-x64.zip | tr 'a-f' 'A-F')
assert_contains "winget: uppercase sha" "$i" "InstallerSha256: $upper"
l=$(cat "$tmp/winget/OstromHQ.Ostrom.locale.en-US.yaml")
assert_contains "winget: locale type" "$l" 'ManifestType: defaultLocale'
assert_contains "winget: license" "$l" 'License: Apache-2.0'
assert_fails "winget: usage" sh "$rel/winget-manifests.sh"
finish
