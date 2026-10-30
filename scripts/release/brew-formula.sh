#!/bin/sh
# Usage: brew-formula.sh <version> <releases-base-url> <dist-dir>   (prints Formula/ostrom.rb)
set -eu
here=$(cd "$(dirname "$0")" && pwd)
. "$here/lib.sh"
[ $# -eq 3 ] || die "usage: brew-formula.sh <version> <releases-base-url> <dist-dir>"
version="$1"; base="$2"; dist="$3"
block() { # <target> <indent>
  f=$(archive_of "$dist" "$version" "$1")
  printf '%surl "%s"\n%ssha256 "%s"\n' "$2" "$(asset_url "$base" "$version" "$f")" "$2" "$(sha256_of "$f")"
}
cat <<RUBY
class Ostrom < Formula
  desc "Ostrom command line: governed AI coding agents, gates and signed receipts"
  homepage "https://ostrom.run"
  version "$version"
  license "Apache-2.0"

  on_macos do
    on_arm do
$(block macos-arm64 '      ')
    end
  end

  on_linux do
    on_arm do
$(block linux-arm64 '      ')
    end
    on_intel do
$(block linux-x64 '      ')
    end
  end

  def install
    bin.install "ostrom"
  end

  test do
    assert_match version.to_s, shell_output("#{bin}/ostrom version")
  end
end
RUBY
