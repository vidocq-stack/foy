#!/bin/bash
# Usage: foy-tck/tck-tally.sh <surefire-log>
#   → "<class> <run> <bad> <skipped>" lines (bad = failures + errors), sorted with LC_ALL=C.
# A class passes run - bad - skipped tests: a skipped test is never a pass.
# Compare two tallies (old, new) class by class; both files must have this four-column format
# (a three-column tally from before the skipped column shifts the fields and breaks the check):
#   LC_ALL=C join old.txt new.txt \
#     | awk '$5 != $2 || $6 > $3 || $7 > $4 || $5-$6-$7 < $2-$3-$4 {print "REGRESSION", $0}'
#   (run count changed, more failures, a pass moved to skipped, or fewer passes)
#   LC_ALL=C join -v1 old.txt new.txt; LC_ALL=C join -v2 old.txt new.txt   # must both be empty
# Totals: awk '{r+=$2; b+=$3; s+=$4} END {print r - b - s " passing / " r " run, " s " skipped"}' new.txt
if [ $# -ne 1 ] || [ ! -f "$1" ]; then
    echo "usage: $0 <surefire-log>" >&2
    exit 2
fi
grep -E 'Tests run: .* -- in servlet' "$1" \
  | sed -E 's/.*Tests run: ([0-9]+), Failures: ([0-9]+), Errors: ([0-9]+), Skipped: ([0-9]+).* -- in (.*)/\5 \1 \2 \3 \4/' \
  | awk '{print $1, $2, $3 + $4, $5}' | LC_ALL=C sort
