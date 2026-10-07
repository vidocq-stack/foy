#!/bin/bash
# Usage: foy-tck/tck-tally.sh <surefire-log>  → "<class> <run> <failures+errors>" lines, sorted.
grep -E 'Tests run: .* -- in servlet' "$1" \
  | sed -E 's/.*Tests run: ([0-9]+), Failures: ([0-9]+), Errors: ([0-9]+).* -- in (.*)/\4 \1 \2 \3/' \
  | awk '{print $1, $2, $3 + $4}' | sort
