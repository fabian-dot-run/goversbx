#!/usr/bin/env bash
# Builds the native daemon, runs the contract test suite against it, and packs a release tarball
# for the current platform:
#
#   GRAALVM_HOME=/path/to/graalvm VERSION=0.2.0 scripts/package-release.sh
#
# -> build/release/goversbx-<version>-<os>-<arch>.tar.gz (+ .sha256), laid out as
#      goversbx-<version>/bin/goversbx            launcher (scripts/goversbx)
#      goversbx-<version>/bin/goversbx-daemon     native daemon, no JVM needed
#      goversbx-<version>/share/goversbx/kits/    sbx kits, used by `goversbx run`
#      goversbx-<version>/share/goversbx/VERSION
#      goversbx-<version>/share/goversbx/CHANGELOG.md
# The launcher finds the daemon and kits relative to itself, so the unpacked tarball works as is;
# the Homebrew formula (packaging/homebrew/) installs the same files. native-image can't
# cross-compile, so run this on each target platform (CI: .github/workflows/release.yml).
#
# VERSION defaults to the git tag on HEAD without its leading "v", else "0.0.0-dev".

set -eu

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
GRAALVM_HOME="${GRAALVM_HOME:?set GRAALVM_HOME to a GraalVM installation}"
export GRAALVM_HOME

if [ -z "${VERSION:-}" ]; then
  VERSION="$(git -C "$REPO_DIR" describe --tags --exact-match 2>/dev/null | sed 's/^v//' || true)"
  VERSION="${VERSION:-0.0.0-dev}"
fi
OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
case "$(uname -m)" in
  arm64|aarch64) ARCH=arm64 ;;
  x86_64|amd64) ARCH=x86_64 ;;
  *) ARCH="$(uname -m)" ;;
esac
NAME="goversbx-$VERSION"
OUT_DIR="$REPO_DIR/build/release"
STAGE="$OUT_DIR/$NAME"
TARBALL="$OUT_DIR/$NAME-$OS-$ARCH.tar.gz"

echo "== building and contract-testing goversbx $VERSION for $OS-$ARCH"
# binaryTestNative = trace the contract suite against the jar (nativeMetadata), build the native
# binary from that trace (nativeCompile), then run the same suite against the binary. Nothing gets
# packed unless every test passes on both.
(cd "$REPO_DIR" && ./gradlew --no-daemon --console=plain :sandbox-kit:check :daemon:check :daemon:binaryTestNative)
BIN="$REPO_DIR/daemon/build/native/nativeCompile/goversbx-daemon"

echo "== packing $TARBALL"
rm -rf "$STAGE" "$TARBALL" "$TARBALL.sha256"
mkdir -p "$STAGE/bin" "$STAGE/share/goversbx"
cp "$REPO_DIR/scripts/goversbx" "$STAGE/bin/goversbx"
cp "$BIN" "$STAGE/bin/goversbx-daemon"
chmod 755 "$STAGE/bin/goversbx" "$STAGE/bin/goversbx-daemon"
cp -R "$REPO_DIR/sandbox-kit/kits" "$STAGE/share/goversbx/kits"
find "$STAGE/share/goversbx/kits" -name '*.sh' -exec chmod 755 {} +
echo "$VERSION" > "$STAGE/share/goversbx/VERSION"
cp "$REPO_DIR/CHANGELOG.md" "$STAGE/share/goversbx/CHANGELOG.md"
cp "$REPO_DIR/README.md" "$REPO_DIR/CHANGELOG.md" "$STAGE/"
tar -C "$OUT_DIR" -czf "$TARBALL" "$NAME"
rm -rf "$STAGE"

if command -v shasum >/dev/null 2>&1; then sha="$(shasum -a 256 "$TARBALL" | cut -d' ' -f1)"
else sha="$(sha256sum "$TARBALL" | cut -d' ' -f1)"; fi
echo "$sha" > "$TARBALL.sha256"
echo "$TARBALL"
echo "sha256 $sha"
