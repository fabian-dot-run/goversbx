#!/usr/bin/env bash
# Prints the CHANGELOG.md section of one version (without its heading), for the GitHub release:
#
#   scripts/release-notes.sh 0.3.0            # or v0.3.0
#
# Exits 1 (and prints nothing) if CHANGELOG.md has no "## <version>" section, so a release without
# notes is noticed. bash 3.2 compatible (macOS).
set -eu
version="${1#v}"
changelog="$(cd "$(dirname "$0")/.." && pwd)/CHANGELOG.md"
notes="$(awk -v v="$version" '
  /^## / { if (found) exit; if ($2 == v) { found = 1; next } }
  found { print }
' "$changelog")"
[ -n "$(printf '%s' "$notes" | tr -d '[:space:]')" ] || { echo "CHANGELOG.md has no section for $version" >&2; exit 1; }
# Drop blank lines before the first and after the last line of text.
printf '%s\n' "$notes" | awk 'NF { started = 1 } started { print }' | sed -e :a -e '/^\n*$/{$d;N;ba' -e '}'
