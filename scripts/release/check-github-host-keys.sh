#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Usage: check-github-host-keys.sh [--update]
# The taps job of release.yml trusts only the GitHub host keys pinned in github_known_hosts (StrictHostKeyChecking=yes,
# no system known_hosts), never the first key it is shown. This checks that the pins are the keys GitHub publishes
# (https://api.github.com/meta, which changes when GitHub rotates a key) and that they alone verify the real
# github.com, with the taps job's ssh options. --update rewrites the pins from the published keys.
# GITHUB_TOKEN, when set, is sent to the API (its unauthenticated limit is shared by the runners' addresses).
set -eu
here=$(cd "$(dirname "$0")" && pwd)
. "$here/lib.sh"
pins="$here/github_known_hosts"
case "${1-}" in ''|--update) ;; *) die "usage: check-github-host-keys.sh [--update]" ;; esac
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
if [ -n "${GITHUB_TOKEN-}" ]; then
  curl -fsS -H "Authorization: Bearer $GITHUB_TOKEN" -o "$tmp/meta" https://api.github.com/meta
else
  curl -fsS -o "$tmp/meta" https://api.github.com/meta
fi
jq -r '.ssh_keys[] | "github.com " + .' "$tmp/meta" | sort -u > "$tmp/published"
[ -s "$tmp/published" ] || die "api.github.com/meta lists no SSH key"
if [ "${1-}" = --update ]; then
  { echo "# SPDX-License-Identifier: Apache-2.0"
    echo "# GitHub's SSH host keys (https://api.github.com/meta), pinned for release.yml's taps job."
    echo "# Rewritten by check-github-host-keys.sh --update; checked by release-checks.yml and the release."
    cat "$tmp/published"; } > "$pins"
fi
grep -v '^#' "$pins" | sort -u > "$tmp/pinned" || true
diff -u "$tmp/published" "$tmp/pinned" \
  || die "the pins in $pins are not the keys GitHub publishes: run $0 --update and review the change"
# The taps job's options, with no identity: the connection stops at authentication, after the host key is checked.
if ssh -o BatchMode=yes -o IdentitiesOnly=yes -o IdentityFile=/dev/null -o StrictHostKeyChecking=yes \
  -o GlobalKnownHostsFile=/dev/null -o UserKnownHostsFile="$pins" -T git@github.com 2> "$tmp/ssh.log"; then :; fi
grep -q 'Permission denied (publickey)' "$tmp/ssh.log" \
  || { cat "$tmp/ssh.log" >&2; die "the pins in $pins do not verify github.com"; }
echo "GitHub host keys: $(wc -l < "$tmp/pinned" | tr -d ' ') pinned, the published ones, and they verify github.com"
