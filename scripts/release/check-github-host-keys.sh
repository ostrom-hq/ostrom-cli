#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Usage: check-github-host-keys.sh [--update | --ssh-options]
# The taps job of release.yml trusts only the GitHub host keys pinned in github_known_hosts, never the first key it is
# shown nor the system known_hosts. With no argument, checks that every pinned key is one GitHub publishes
# (https://api.github.com/meta, which drops a key GitHub rotates out) and that each pinned key type alone verifies the
# real github.com, with the options the taps job uses. --update rewrites the pins from the published keys.
# --ssh-options prints those options (no network): the taps job takes them from here, so they cannot drift apart.
# GITHUB_TOKEN, when set, is sent to the API (its unauthenticated limit is shared by the runners' addresses).
set -eu
here=$(cd "$(dirname "$0")" && pwd)
. "$here/lib.sh"
pins="$here/github_known_hosts"
usage="usage: check-github-host-keys.sh [--update | --ssh-options]"
[ $# -le 1 ] || die "$usage"
mode=${1-check}
case "$mode" in check|--update|--ssh-options) ;; *) die "$usage" ;; esac
strict="-o StrictHostKeyChecking=yes -o GlobalKnownHostsFile=/dev/null -o UserKnownHostsFile=$pins"
if [ "$mode" = --ssh-options ]; then printf '%s\n' "$strict"; exit 0; fi

tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
meta() { curl -fsS --retry 3 --retry-all-errors --max-time 20 -o "$tmp/meta" "$@" https://api.github.com/meta; }
if [ -n "${GITHUB_TOKEN-}" ]; then
  # the header from a file, so the token is not in the process list; a token the API refuses falls back to none
  install -m 600 /dev/null "$tmp/auth"; printf 'Authorization: Bearer %s\n' "$GITHUB_TOKEN" > "$tmp/auth"
  meta -H "@$tmp/auth" || meta
else
  meta
fi
jq -r '.ssh_keys[] | "github.com " + .' "$tmp/meta" | sort -u > "$tmp/published"
[ -s "$tmp/published" ] || die "api.github.com/meta lists no SSH key"
if [ "$mode" = --update ]; then
  { echo "# SPDX-License-Identifier: Apache-2.0"
    echo "# GitHub's SSH host keys (https://api.github.com/meta), pinned for release.yml's taps job."
    echo "# Rewritten by check-github-host-keys.sh --update; checked by release-checks.yml and the release."
    cat "$tmp/published"; } > "$pins"
fi
grep -v '^#' "$pins" | sort -u > "$tmp/pinned" || true
[ -s "$tmp/pinned" ] || die "$pins pins no key: run $0 --update and review the change"
stale=$(comm -23 "$tmp/pinned" "$tmp/published")
[ -z "$stale" ] || die "GitHub no longer publishes these pinned keys (rotated): run $0 --update and review the change:
$stale"
added=$(comm -13 "$tmp/pinned" "$tmp/published")
[ -z "$added" ] || echo "::warning::GitHub publishes host keys not pinned yet (run $0 --update): $added"
# One connection per pinned key type, with no identity: it stops at authentication, after the host key is checked.
for type in $(cut -d' ' -f2 "$tmp/pinned" | sort -u); do
  case "$type" in ssh-rsa) algorithms=rsa-sha2-512,rsa-sha2-256 ;; *) algorithms=$type ;; esac
  # shellcheck disable=SC2086 # $strict is a list of options
  if ssh -o BatchMode=yes -o ConnectTimeout=15 -o IdentitiesOnly=yes -o IdentityFile=/dev/null \
    -o HostKeyAlgorithms="$algorithms" $strict -T git@github.com 2> "$tmp/ssh.log"; then :; fi
  grep -q 'Permission denied (publickey)' "$tmp/ssh.log" \
    || { cat "$tmp/ssh.log" >&2; die "the pinned $type key does not verify github.com"; }
done
echo "GitHub host keys: $(wc -l < "$tmp/pinned" | tr -d ' ') pinned, all published, each type verifies github.com"
