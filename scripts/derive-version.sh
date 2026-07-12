#!/usr/bin/env bash
# Derive and validate the release version from a git tag (design D24). Security: the tag is
# attacker-influenced (anyone with tag-push access) and its value later expands into signed
# build steps, so validate it here and fail closed. The accepted charset excludes every shell
# metacharacter ($ ` ; | & ( ) space), so a tag that passes is inert wherever it is expanded.
# Kept in a file, not inline in release.yml, so it can be unit-tested (derive-version.test.sh).
set -euo pipefail

tag="${1:?usage: derive-version.sh <tag>}"

# strict semver vMAJOR.MINOR.PATCH with an optional dot/alnum prerelease suffix (rc tags).
semver='^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$'
if [[ ! "$tag" =~ $semver ]]; then
  echo "::error::invalid release tag '$tag' (expected vMAJOR.MINOR.PATCH[-suffix])" >&2
  exit 1
fi

ver="${tag#v}"
core="${ver%%-*}" # drop any -rc suffix for the numeric code
IFS=. read -r maj min pat <<<"$core"
code=$((10#$maj * 1000000 + 10#$min * 1000 + 10#$pat)) # 10# forces base-10 (avoid octal on 0-padded parts)
prerelease=false
[[ "$tag" == *-* ]] && prerelease=true

printf 'name=%s\n' "$ver"
printf 'core=%s\n' "$core"
printf 'code=%s\n' "$code"
printf 'prerelease=%s\n' "$prerelease"
