#!/usr/bin/env bash
# Unit tests for derive-version.sh. The security contract: any tag carrying a shell
# metacharacter (release-tag injection, release.yml) must be rejected before a value can
# reach a workflow run: block; valid tags derive the right name/core/code/prerelease.
#
# The reject payloads are single-quoted on purpose: they must reach the script verbatim,
# the way a hostile tag would, not expand in this test's own shell.
# shellcheck disable=SC2016
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
script="$here/derive-version.sh"
fails=0

accept() { # tag name core code prerelease
  local tag="$1" out want
  if ! out="$(bash "$script" "$tag" 2>/dev/null)"; then
    echo "FAIL: expected '$tag' to be accepted"
    fails=$((fails + 1))
    return
  fi
  want="$(printf 'name=%s\ncore=%s\ncode=%s\nprerelease=%s' "$2" "$3" "$4" "$5")"
  if [ "$out" != "$want" ]; then
    echo "FAIL: '$tag' derived:"
    printf '%s\n' "$out"
    echo "  expected:"
    printf '%s\n' "$want"
    fails=$((fails + 1))
  fi
}

reject() { # tag
  local tag="$1"
  if bash "$script" "$tag" >/dev/null 2>&1; then
    echo "FAIL: expected '$tag' to be rejected"
    fails=$((fails + 1))
  fi
}

# valid tags
accept 'v1.2.3' '1.2.3' '1.2.3' '1002003' 'false'
accept 'v0.1.0' '0.1.0' '0.1.0' '1000' 'false'
accept 'v0.1.1-rc1' '0.1.1-rc1' '0.1.1' '1001' 'true'
accept 'v10.20.30' '10.20.30' '10.20.30' '10020030' 'false'
accept 'v1.2.3-rc.2' '1.2.3-rc.2' '1.2.3' '1002003' 'true'

# injection payloads and malformed tags must fail closed
reject 'v1.2.3$(id)'
reject 'v1.2.3-$(id)'
reject 'v1.0.0-`id`'
reject 'v1.2.3;whoami'
reject 'v1.2.3 rc1'
reject 'v1.2.3|cat'
reject 'v1.2.3-rc1;curl evil'
reject 'v1.2'
reject '1.2.3'
reject 'notatag'
reject ''

if [ "$fails" -gt 0 ]; then
  echo "$fails derive-version test(s) failed"
  exit 1
fi
echo "all derive-version tests passed"
