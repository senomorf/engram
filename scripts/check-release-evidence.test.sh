#!/usr/bin/env bash
# Unit test for check-release-evidence.sh: it must pass on complete evidence and fail closed on
# any gap (an empty matrix cell, an unrecorded landmine verdict, an unchecked device-QA item).
set -euo pipefail

script="$(dirname "$0")/check-release-evidence.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
pass=0
fail=0

filled_matrix() {
  cat >"$1" <<'EOF'
| # | Path | std | xmp | rec | mpf | motion |
|---|------|-----|-----|-----|-----|--------|
| 1 | Control | ok | ok | ok | ok | ok |
| 2b | GPhotos Takeout | ok | transformed | ok | ok | gone |

1. Ultra HDR MPF repair: ok, verified
2. Motion Photo coexistence: gone
EOF
}
checked_qa() { printf -- '- [x] item one\n- [x] item two\n' >"$1"; }

# run <expected-exit> <label> <matrix> <deviceqa>
run() {
  local exp="$1" label="$2" got=0
  bash "$script" "$3" "$4" >/dev/null 2>&1 || got=$?
  # normalize any non-zero to 1 (the script exits 1 on incomplete evidence)
  [[ "$got" -ne 0 ]] && got=1
  if [[ "$got" -eq "$exp" ]]; then
    pass=$((pass + 1))
  else
    fail=$((fail + 1))
    echo "FAIL: $label (expected exit $exp, got $got)"
  fi
}

# complete evidence passes
filled_matrix "$tmp/m.md"
checked_qa "$tmp/q.md"
run 0 "complete evidence" "$tmp/m.md" "$tmp/q.md"

# an empty matrix result cell fails closed
printf '| 1 | Control | ok | | ok | ok | ok |\n1. x: ok\n' >"$tmp/m-empty.md"
checked_qa "$tmp/q2.md"
run 1 "empty matrix result cell" "$tmp/m-empty.md" "$tmp/q2.md"

# an unrecorded landmine verdict fails closed
filled_matrix "$tmp/m-land.md"
printf '3. Unrecorded landmine:\n' >>"$tmp/m-land.md"
checked_qa "$tmp/q3.md"
run 1 "unrecorded landmine verdict" "$tmp/m-land.md" "$tmp/q3.md"

# an unchecked device-QA item fails closed
filled_matrix "$tmp/m4.md"
printf -- '- [x] done\n- [ ] not done\n' >"$tmp/q-open.md"
run 1 "unchecked device-QA item" "$tmp/m4.md" "$tmp/q-open.md"

echo "check-release-evidence.test.sh: $pass passed, $fail failed"
[[ "$fail" -eq 0 ]]
