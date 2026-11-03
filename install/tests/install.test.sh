#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Tests of install.sh against a fake release served by python3 -m http.server on localhost.
here=$(cd "$(dirname "$0")" && pwd)
. "$here/../../scripts/release/tests/lib.sh"
source_installer="$here/../install.sh"
tmp=$(mktemp -d)
server_pid=""
cleanup() { [ -z "$server_pid" ] || kill "$server_pid" 2>/dev/null; rm -rf "$tmp"; }
trap cleanup EXIT

# The release's cosign key, played by a key made here: the installer under test is install.sh with this public key in
# place of the pinned one. cosign signs a blob as ECDSA P-256 over its SHA-256, DER, in base64: openssl makes the same.
openssl ecparam -name prime256v1 -genkey -noout -out "$tmp/cosign.key" 2>/dev/null
openssl ec -in "$tmp/cosign.key" -pubout -out "$tmp/cosign.pub" 2>/dev/null
installer="$tmp/install.sh"
awk -v pub="$tmp/cosign.pub" '
  /^COSIGN_PUB=/ { skipping = 1; printf "COSIGN_PUB=\047"; while ((getline line < pub) > 0) { if (n++) printf "\n"; printf "%s", line }; next }
  skipping && /-----END PUBLIC KEY-----\047/ { skipping = 0; print "\047"; next }
  skipping { next }
  { print }
' "$source_installer" > "$installer"
grep -q 'BEGIN PUBLIC KEY' "$installer" || { echo "the test key was not put in the installer" >&2; exit 1; }
sign() { # <file>: <file>.sig as cosign writes it
  openssl dgst -sha256 -sign "$tmp/cosign.key" "$1" | openssl base64 -A > "$1.sig"
}

# Host target, computed independently of the installer.
case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) host=linux-x64 ;;
  Linux-aarch64|Linux-arm64) host=linux-arm64 ;;
  Darwin-arm64) host=macos-arm64 ;;
  *) host=linux-x64 ;;
esac

# --- the fake release -------------------------------------------------------
site="$tmp/site"
mk_release() { # <version> <target>
  v="$1"; t="$2"
  d="$site/download/$v"; mkdir -p "$d" "$tmp/pkg-$v"
  printf '#!/bin/sh\necho "ostrom %s"\n' "$v" > "$tmp/pkg-$v/ostrom"; chmod +x "$tmp/pkg-$v/ostrom"
  printf 'Apache\n' > "$tmp/pkg-$v/LICENSE"
  tar -czf "$d/ostrom-$v-$t.tar.gz" -C "$tmp/pkg-$v" ostrom LICENSE
  ( cd "$d" && printf '%s  %s\n' "$(sha256_file "ostrom-$v-$t.tar.gz")" "ostrom-$v-$t.tar.gz" > SHA256SUMS )
  sign "$d/SHA256SUMS"
}
mk_release 1.2.3 "$host"
mk_release 1.3.0 "$host"
mkdir -p "$site/latest/download"
printf '{"version":"1.3.0","date":"2026-10-30T10:00:00Z","assets":{}}\n' > "$site/latest/download/latest.json"
# a release whose checksum file lies
mk_release 2.0.0 "$host"
printf '%s  ostrom-2.0.0-%s.tar.gz\n' "0000000000000000000000000000000000000000000000000000000000000000" "$host" > "$site/download/2.0.0/SHA256SUMS"
sign "$site/download/2.0.0/SHA256SUMS"
# a release whose checksum file has no line for our archive
mk_release 2.1.0 "$host"
printf '%s  something-else.tar.gz\n' "0000000000000000000000000000000000000000000000000000000000000000" > "$site/download/2.1.0/SHA256SUMS"
sign "$site/download/2.1.0/SHA256SUMS"
# a release whose archive was swapped after the checksum was published
mk_release 2.2.0 "$host"
printf 'tampered\n' >> "$site/download/2.2.0/ostrom-2.2.0-$host.tar.gz"
# a release whose SHA256SUMS was changed after it was signed (its archive matches the new checksum)
mk_release 2.3.0 "$host"
mkdir -p "$tmp/pkg-evil"; printf '#!/bin/sh\necho "not ostrom"\n' > "$tmp/pkg-evil/ostrom"; chmod +x "$tmp/pkg-evil/ostrom"
tar -czf "$site/download/2.3.0/ostrom-2.3.0-$host.tar.gz" -C "$tmp/pkg-evil" ostrom
( cd "$site/download/2.3.0" && printf '%s  %s\n' "$(sha256_file "ostrom-2.3.0-$host.tar.gz")" "ostrom-2.3.0-$host.tar.gz" > SHA256SUMS )
# a release without its signature
mk_release 2.4.0 "$host"
rm "$site/download/2.4.0/SHA256SUMS.sig"

( cd "$site" && python3 -u -m http.server 0 --bind 127.0.0.1 > "$tmp/server.log" 2>&1 & echo $! > "$tmp/server.pid" )
server_pid=$(cat "$tmp/server.pid")
i=0; port=""
# A cold macOS runner can take several seconds to start Python: wait up to 30 s.
while [ $i -lt 300 ] && [ -z "$port" ]; do
  port=$(sed -n 's/.*port \([0-9][0-9]*\).*/\1/p' "$tmp/server.log" | head -1); i=$((i + 1)); sleep 0.1
done
[ -n "$port" ] || { echo "http.server did not start" >&2; cat "$tmp/server.log" >&2; exit 1; }
base="http://127.0.0.1:$port"

HOME_N=0
run_installer() { # env assignments in "$@", HOME isolated per call
  HOME_N=$((HOME_N + 1)); home="$tmp/home-$HOME_N"; mkdir -p "$home"
  HOME="$home" OSTROM_DOWNLOAD_BASE="$base" "$@" sh "$installer" > "$tmp/out.log" 2>&1
  status=$?; LAST_HOME="$home"; return $status
}
runi() { run_installer env "$@"; }

# --- cases ------------------------------------------------------------------
if runi OSTROM_VERSION=1.2.3; then ok "pinned version installs"; else ko "pinned version installs"; cat "$tmp/out.log" >&2; fi
assert_eq "binary installed in ~/.ostrom/bin" "ostrom 1.2.3" "$("$LAST_HOME/.ostrom/bin/ostrom")"
assert_eq "binary is executable" yes "$([ -x "$LAST_HOME/.ostrom/bin/ostrom" ] && echo yes || echo no)"
assert_contains "tells how to put it on PATH" "$(cat "$tmp/out.log")" '.ostrom/bin'
assert_eq "no stray temp dirs left in HOME" 0 "$(find "$LAST_HOME" -name 'ostrom-install*' | wc -l | tr -d ' ')"

runi OSTROM_VERSION=v1.2.3 && ok "leading v accepted" || ko "leading v accepted"

if runi; then ok "latest resolves through latest.json"; else ko "latest resolves through latest.json"; cat "$tmp/out.log" >&2; fi
assert_eq "latest installed 1.3.0" "ostrom 1.3.0" "$("$LAST_HOME/.ostrom/bin/ostrom")"

runi OSTROM_VERSION=1.2.3 OSTROM_INSTALL_DIR="$tmp/custom/bin" && ok "custom install dir" || ko "custom install dir"
assert_eq "binary in the custom dir" "ostrom 1.2.3" "$("$tmp/custom/bin/ostrom")"

runi OSTROM_VERSION=1.2.3 OSTROM_NO_MODIFY_PATH=1 && ok "no-modify-path" || ko "no-modify-path"
assert_eq "no profile written when asked not to" 0 "$(find "$LAST_HOME" -maxdepth 1 -name ".profile" -o -maxdepth 1 -name ".zshrc" -o -maxdepth 1 -name ".bashrc" | wc -l | tr -d " ")"

runi OSTROM_VERSION=1.2.3 SHELL=/bin/zsh
assert_contains "zsh profile gets the PATH line" "$(cat "$LAST_HOME/.zshrc" 2>/dev/null)" '.ostrom/bin'
runi OSTROM_VERSION=1.2.3 SHELL=/bin/bash
assert_contains "bash profile gets the PATH line" "$(cat "$LAST_HOME/.bashrc" 2>/dev/null)" '.ostrom/bin'

# the PATH line is added only once
runi OSTROM_VERSION=1.2.3 SHELL=/bin/zsh
home1="$LAST_HOME"
HOME="$home1" OSTROM_DOWNLOAD_BASE="$base" OSTROM_VERSION=1.2.3 SHELL=/bin/zsh sh "$installer" >/dev/null 2>&1
assert_eq "PATH line not duplicated" 1 "$(grep -c '.ostrom/bin' "$home1/.zshrc")"

# integrity
if runi OSTROM_VERSION=2.0.0; then ko "wrong checksum must be refused"; else ok "wrong checksum is refused"; fi
assert_contains "says why" "$(cat "$tmp/out.log")" 'checksum'
assert_eq "nothing installed after a wrong checksum" no "$([ -e "$LAST_HOME/.ostrom/bin/ostrom" ] && echo yes || echo no)"
if runi OSTROM_VERSION=2.1.0; then ko "archive absent from SHA256SUMS must be refused"; else ok "archive absent from SHA256SUMS is refused"; fi
assert_eq "nothing installed without a checksum line" no "$([ -e "$LAST_HOME/.ostrom/bin/ostrom" ] && echo yes || echo no)"
if runi OSTROM_VERSION=2.2.0; then ko "swapped archive must be refused"; else ok "swapped archive is refused"; fi
if runi OSTROM_VERSION=9.9.9; then ko "unknown version must fail"; else ok "unknown version fails"; fi
if runi OSTROM_VERSION=1.2.3 OSTROM_TARGET=plan9-mips; then ko "unknown target must fail"; else ok "unsupported target fails"; fi
assert_contains "names the unsupported target" "$(cat "$tmp/out.log")" 'plan9-mips'
if runi OSTROM_VERSION='1.2.3/../../x'; then ko "path-like version must fail"; else ok "path-like version is refused"; fi

# cosign: used when present
stub="$tmp/stub-ok"; mkdir -p "$stub"; printf '#!/bin/sh\necho "cosign called: $*" >> "%s/cosign.calls"\nexit 0\n' "$tmp" > "$stub/cosign"; chmod +x "$stub/cosign"
runi PATH="$stub:$PATH" OSTROM_VERSION=1.2.3 && ok "cosign present and happy" || ko "cosign present and happy"
assert_contains "cosign verified the checksum file with the pinned key" "$(cat "$tmp/cosign.calls")" 'verify-blob'
assert_contains "cosign skips the transparency log" "$(cat "$tmp/cosign.calls")" '--insecure-ignore-tlog'
stub2="$tmp/stub-bad"; mkdir -p "$stub2"; printf '#!/bin/sh\nexit 1\n' > "$stub2/cosign"; chmod +x "$stub2/cosign"
if runi PATH="$stub2:$PATH" OSTROM_VERSION=1.2.3; then ko "failing cosign must refuse"; else ok "failing cosign refuses"; fi
assert_eq "nothing installed after a bad signature" no "$([ -e "$LAST_HOME/.ostrom/bin/ostrom" ] && echo yes || echo no)"
runi PATH="$stub2:$PATH" OSTROM_VERSION=1.2.3 OSTROM_SKIP_COSIGN=1 && ok "cosign can be skipped explicitly" || ko "cosign can be skipped explicitly"

# the signature, without cosign: openssl checks it against the pinned key
runi OSTROM_VERSION=1.2.3 && ok "a signed release installs" || ko "a signed release installs"
assert_contains "says the signature was checked" "$(cat "$tmp/out.log")" 'Signature verified'
if runi OSTROM_VERSION=2.3.0; then ko "a SHA256SUMS changed after signing must be refused"; else ok "a SHA256SUMS changed after signing is refused"; fi
assert_contains "says the signature does not verify" "$(cat "$tmp/out.log")" 'signature'
assert_eq "nothing installed after a bad signature (openssl)" no "$([ -e "$LAST_HOME/.ostrom/bin/ostrom" ] && echo yes || echo no)"
if runi OSTROM_VERSION=2.4.0; then ko "a release without its signature must be refused"; else ok "a release without its signature is refused"; fi
assert_eq "nothing installed without a signature" no "$([ -e "$LAST_HOME/.ostrom/bin/ostrom" ] && echo yes || echo no)"
runi OSTROM_VERSION=2.3.0 OSTROM_SKIP_COSIGN=1 && ok "the signature check can be skipped explicitly" \
  || ko "the signature check can be skipped explicitly"

# no verifier at all (neither cosign nor openssl on the PATH): installs on the checksum, and says so
bare="$tmp/bare-path"; mkdir -p "$bare"
for tool in sh curl wget sha256sum shasum perl uname mktemp rm sed head awk tar gzip mkdir chmod mv grep cut cat; do
  found=$(command -v "$tool" 2>/dev/null) && ln -s "$found" "$bare/$tool"
done
runi PATH="$bare" OSTROM_VERSION=1.2.3 && ok "installs without any verifier" || ko "installs without any verifier"
assert_contains "warns that the signature was not checked" "$(cat "$tmp/out.log")" 'was not checked'

# a download cut short runs nothing: the script only acts from its last line
cut_at=$(grep -n 'mv -f' "$installer" | head -1 | cut -d: -f1)
head -n "$cut_at" "$installer" > "$tmp/truncated.sh"
HOME_N=$((HOME_N + 1)); cut_home="$tmp/home-$HOME_N"; mkdir -p "$cut_home"
served=$(grep -c 'GET ' "$tmp/server.log")
HOME="$cut_home" OSTROM_DOWNLOAD_BASE="$base" OSTROM_VERSION=1.2.3 sh "$tmp/truncated.sh" > "$tmp/out.log" 2>&1
assert_eq "a truncated installer installs nothing" no "$([ -e "$cut_home/.ostrom/bin/ostrom" ] && echo yes || echo no)"
assert_eq "a truncated installer downloads nothing" "$served" "$(grep -c 'GET ' "$tmp/server.log")"

# never asks for root
assert_eq "no sudo in the installer" 0 "$(grep -c 'sudo' "$installer" | tr -d ' ')"
finish
