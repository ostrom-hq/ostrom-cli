#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Ostrom installer:  curl -fsSL https://ostrom.run/install.sh | sh
#
# Environment:
#   OSTROM_VERSION          version to install (default: the latest, read from latest.json)
#   OSTROM_INSTALL_DIR      where to put the binary (default: ~/.ostrom/bin); no root needed
#   OSTROM_DOWNLOAD_BASE    releases URL (default: https://github.com/ostrom-hq/ostrom-cli/releases)
#   OSTROM_NO_MODIFY_PATH   set to 1 to leave your shell profile alone
#   OSTROM_SKIP_COSIGN      set to 1 to skip the signature check of SHA256SUMS
#   OSTROM_TARGET           force a target (linux-x64, linux-arm64, macos-arm64), for tests
#
# SHA256SUMS is checked against its signature and the key pinned below, with cosign when it is installed, else with
# openssl (cosign signs a blob as ECDSA P-256 over its SHA-256: openssl checks the same signature). The archive is then
# checked against SHA256SUMS. With neither tool, nothing is installed: the checksum alone comes from the same place as
# the archive.
#   OSTROM_SKIP_COSIGN=1 skips the signature check (the checksum is still checked).
# A download with curl is never redirected from HTTPS to HTTP (wget has no such option; the signature covers it).
#
# Everything runs from main, called on the last line: a download cut short runs nothing.
set -eu

BASE="${OSTROM_DOWNLOAD_BASE:-https://github.com/ostrom-hq/ostrom-cli/releases}"
INSTALL_DIR="${OSTROM_INSTALL_DIR:-$HOME/.ostrom/bin}"

COSIGN_PUB='-----BEGIN PUBLIC KEY-----
MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAENbn5GmwW348HxCtTPQbNfcvHmSZG
G+IBNKubzv51TIua776a3875eoe5isQYKYmVwe5yTUU0v2k8Pu7WXQWoBg==
-----END PUBLIC KEY-----'

say() { printf '%s\n' "$*"; }
fail() { printf 'ostrom install: %s\n' "$*" >&2; exit 1; }

fetch() { # <url> <dest>
  # from HTTPS, curl never follows a redirect to HTTP; an http:// mirror keeps its own redirects
  case "$1" in https://*) redirects='=https' ;; *) redirects='all' ;; esac
  if command -v curl >/dev/null 2>&1; then curl -fsSL --proto-redir "$redirects" -o "$2" "$1" || return 1
  elif command -v wget >/dev/null 2>&1; then wget -q -O "$2" "$1" || return 1
  else fail "curl or wget is required"; fi
}

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | cut -d' ' -f1
  else fail "sha256sum or shasum is required"; fi
}

detect_target() {
  if [ -n "${OSTROM_TARGET:-}" ]; then printf '%s\n' "$OSTROM_TARGET"; return; fi
  os=$(uname -s); arch=$(uname -m)
  case "$os-$arch" in
    Linux-x86_64|Linux-amd64) printf 'linux-x64\n' ;;
    Linux-aarch64|Linux-arm64) printf 'linux-arm64\n' ;;
    Darwin-arm64) printf 'macos-arm64\n' ;;
    *) printf '%s-%s\n' "$os" "$arch" ;;
  esac
}

verifier() { # the tool that checks the signature, before anything is downloaded, or stop
  if [ "${OSTROM_SKIP_COSIGN:-}" = "1" ]; then tool=none
  elif command -v cosign >/dev/null 2>&1; then tool=cosign
  elif command -v openssl >/dev/null 2>&1; then tool=openssl
  else
    fail "neither cosign nor openssl is installed, so the signature of SHA256SUMS cannot be checked: refusing to install. Install cosign or openssl, or set OSTROM_SKIP_COSIGN=1 to check the checksum only"
  fi
}

verify_signature() { # <release url> <work dir>: SHA256SUMS against its signature and the pinned key, or stop
  if [ "$tool" = none ]; then
    say "Signature check skipped (OSTROM_SKIP_COSIGN=1): only the checksum is checked."
    return 0
  fi
  fetch "$1/SHA256SUMS.sig" "$2/SHA256SUMS.sig" \
    || fail "SHA256SUMS.sig could not be downloaded: refusing to install"
  printf '%s\n' "$COSIGN_PUB" > "$2/cosign.pub"
  if [ "$tool" = cosign ]; then
    cosign verify-blob --key "$2/cosign.pub" --insecure-ignore-tlog --signature "$2/SHA256SUMS.sig" "$2/SHA256SUMS" \
      >/dev/null 2>&1 || fail "the signature of SHA256SUMS does not verify: refusing to install"
  else
    tr -d ' \r\n' < "$2/SHA256SUMS.sig" > "$2/SHA256SUMS.sig.b64"
    openssl base64 -d -A -in "$2/SHA256SUMS.sig.b64" -out "$2/SHA256SUMS.sig.der" 2>/dev/null \
      && openssl dgst -sha256 -verify "$2/cosign.pub" -signature "$2/SHA256SUMS.sig.der" "$2/SHA256SUMS" >/dev/null 2>&1 \
      || fail "the signature of SHA256SUMS does not verify: refusing to install"
  fi
  say "Signature verified with $tool."
}

main() {
  target=$(detect_target)
  case "$target" in
    linux-x64|linux-arm64|macos-arm64) ;;
    *) fail "unsupported platform: $target (supported: linux-x64, linux-arm64, macos-arm64; on Windows use install.ps1)" ;;
  esac
  verifier

  work=$(mktemp -d "${TMPDIR:-/tmp}/ostrom-install.XXXXXX")
  trap 'rm -rf "$work"' EXIT INT TERM

  version="${OSTROM_VERSION:-}"
  if [ -z "$version" ]; then
    say "Looking up the latest version..."
    fetch "$BASE/latest/download/latest.json" "$work/latest.json" || fail "cannot download latest.json from $BASE"
    version=$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$work/latest.json" | head -1)
    [ -n "$version" ] || fail "latest.json has no version"
  fi
  version=${version#v}
  case "$version" in
    *[!0-9A-Za-z.+_-]*|"") fail "invalid version: $version" ;;
  esac

  archive="ostrom-$version-$target.tar.gz"
  rel="$BASE/download/$version"

  say "Installing Ostrom $version ($target)..."
  fetch "$rel/SHA256SUMS" "$work/SHA256SUMS" || fail "cannot download SHA256SUMS for $version ($rel)"
  fetch "$rel/$archive" "$work/$archive" || fail "cannot download $archive ($rel)"

  verify_signature "$rel" "$work"
  expected=$(awk -v f="$archive" '$2 == f || $2 == "*" f { print $1; exit }' "$work/SHA256SUMS")
  [ -n "$expected" ] || fail "checksum for $archive not found in SHA256SUMS: refusing to install"
  actual=$(sha256_of "$work/$archive")
  [ "$expected" = "$actual" ] || fail "checksum mismatch for $archive (expected $expected, got $actual): refusing to install"

  mkdir -p "$work/unpack"
  tar -xzf "$work/$archive" -C "$work/unpack" || fail "cannot unpack $archive"
  [ -f "$work/unpack/ostrom" ] || fail "$archive does not contain the ostrom binary"
  mkdir -p "$INSTALL_DIR"
  chmod 755 "$work/unpack/ostrom"
  mv -f "$work/unpack/ostrom" "$INSTALL_DIR/ostrom"
  say "Installed $INSTALL_DIR/ostrom"

  case ":$PATH:" in
    *":$INSTALL_DIR:"*) say "Run: ostrom login" ;;
    *)
      line="export PATH=\"$INSTALL_DIR:\$PATH\""
      if [ "${OSTROM_NO_MODIFY_PATH:-}" = "1" ]; then
        say "Add Ostrom to your PATH:"; say "  $line"
      else
        case "${SHELL:-}" in
          */zsh) profile="$HOME/.zshrc" ;;
          */bash) profile="$HOME/.bashrc" ;;
          *) profile="$HOME/.profile" ;;
        esac
        if [ -f "$profile" ] && grep -qF "$INSTALL_DIR" "$profile"; then
          :
        else
          printf '\n# Ostrom\n%s\n' "$line" >> "$profile"
        fi
        say "Added $INSTALL_DIR to your PATH in $profile."
        say "Open a new terminal, or run:  $line"
      fi
      say "Then run: ostrom login"
      ;;
  esac
}

main "$@"
