#!/usr/bin/env bash
# Renders the Homebrew formula for one release:
#
#   packaging/homebrew/render-formula.sh <owner/repo> <version> <sha256 of the darwin-arm64 tarball> > goversbx.rb
#
# <owner/repo> is the public GitHub repo the release tarball is attached to (not the tap repo).
set -eu
[ $# -eq 3 ] || { echo "usage: $0 <owner/repo> <version> <sha256>" >&2; exit 2; }
repo="$1"; version="${2#v}"; sha="$3"
case "$sha" in *[!0-9a-f]*|"") echo "not a sha256: $sha" >&2; exit 2 ;; esac
[ "${#sha}" -eq 64 ] || { echo "not a sha256: $sha" >&2; exit 2; }
sed -e "s|@REPO@|$repo|g" -e "s|@VERSION@|$version|g" -e "s|@SHA256@|$sha|g" \
  "$(dirname "$0")/goversbx.rb.template"
