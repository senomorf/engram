#!/usr/bin/env bash
# Fail closed unless the survivability matrix rows and landmine verdicts are recorded and the
# device-QA checklist is fully checked off (the documented v1 release gate, AGENTS Releasing).
# Stable releases depend on this in release.yml; rc tags skip it. Paths are parameterized so the
# unit test (check-release-evidence.test.sh) can point at fixtures.
set -euo pipefail

matrix="${1:-lab/survivability-matrix.md}"
deviceqa="${2:-docs/device-qa.md}"
fail=0

[[ -f "$matrix" ]] || { echo "::error::missing $matrix" >&2; exit 1; }
[[ -f "$deviceqa" ]] || { echo "::error::missing $deviceqa" >&2; exit 1; }

# a survivability data row is "| N | path | std | xmp | rec | mpf | motion |"; the header ("| #")
# and separator ("|---") do not start with a digit, so this skips them
row_re='^\|[[:space:]]*[0-9]+[a-z]?[[:space:]]*\|'
# a landmine line is "N. name:"; a still-empty verdict ends at the colon
landmine_re='^[0-9]+\..*:[[:space:]]*$'

while IFS= read -r line; do
  if [[ "$line" =~ $row_re ]]; then
    IFS='|' read -ra cols <<<"$line"
    # splitting "| N | path | std | xmp | rec | mpf | motion |" on "|" yields a leading and a
    # trailing empty field, so: [0]="" [1]=N [2]=path [3..7]=the five verdicts [8]=""
    for i in 3 4 5 6 7; do
      cell="${cols[i]:-}"
      if [[ -z "${cell//[[:space:]]/}" ]]; then
        echo "::error::$matrix row ${cols[1]//[[:space:]]/}: result cell $((i - 2)) is empty" >&2
        fail=1
      fi
    done
  elif [[ "$line" =~ $landmine_re ]]; then
    echo "::error::$matrix landmine verdict not recorded: $line" >&2
    fail=1
  fi
done <"$matrix"

# the device-QA checklist must be fully checked ("- [ ]" boxes all filled to "- [x]")
unchecked=$(grep -c '^[[:space:]]*- \[ \]' "$deviceqa" || true)
if [[ "$unchecked" -gt 0 ]]; then
  echo "::error::$deviceqa has $unchecked unchecked item(s); the device-QA pass is not complete" >&2
  fail=1
fi

if [[ "$fail" -ne 0 ]]; then
  echo "::error::release evidence incomplete (survivability matrix / device QA); see above" >&2
  exit 1
fi
echo "release evidence present: survivability matrix filled and device QA complete"
