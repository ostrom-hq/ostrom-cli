#!/bin/sh
# Runs every scripts/release/tests/test-*.sh; fails if one fails.
here=$(cd "$(dirname "$0")" && pwd)
status=0
for t in "$here"/test-*.sh; do
  printf '== %s\n' "$(basename "$t")"
  sh "$t" || status=1
done
exit $status
