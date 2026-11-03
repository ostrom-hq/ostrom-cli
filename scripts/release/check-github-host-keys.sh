#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Usage: check-github-host-keys.sh <workflow>
# The taps job of the release workflow trusts only the GitHub host keys it pins (StrictHostKeyChecking=yes), never the
# first key it is shown. Checks that the pinned keys are the ones GitHub publishes (https://api.github.com/meta, which
# changes when GitHub rotates a key) and that they verify the real github.com.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
. "$here/lib.sh"
[ $# -eq 1 ] || die "usage: check-github-host-keys.sh <workflow>"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
grep -E '^ *github\.com (ssh-|ecdsa-)' "$1" | sed 's/^ *//' | sort -u > "$tmp/pinned" || true
[ -s "$tmp/pinned" ] || die "$1 pins no GitHub host key"
curl -fsS https://api.github.com/meta | jq -r '.ssh_keys[] | "github.com " + .' | sort -u > "$tmp/published"
diff -u "$tmp/published" "$tmp/pinned" || die "the host keys pinned in $1 are not the ones GitHub publishes"
# No identity: the connection stops at authentication, after the host key is checked.
if ssh -o BatchMode=yes -o IdentitiesOnly=yes -o IdentityFile=/dev/null -o StrictHostKeyChecking=yes \
  -o UserKnownHostsFile="$tmp/pinned" -T git@github.com 2> "$tmp/ssh.log"; then :; fi
grep -q 'Permission denied (publickey)' "$tmp/ssh.log" || { cat "$tmp/ssh.log" >&2; die "github.com was not verified"; }
echo "GitHub host keys pinned in $1: $(wc -l < "$tmp/pinned" | tr -d ' '), the published ones, github.com verified"
