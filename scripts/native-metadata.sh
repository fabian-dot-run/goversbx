#!/usr/bin/env bash
# Regenerates the daemon's native-image reachability metadata (reflection, resources, proxies) by
# running scripts/testing/daemon-smoke.sh against the JVM jar under GraalVM's tracing agent.
# Re-run after changing what the daemon serializes, serves or reflects on, then commit the result:
#
#   GRAALVM_HOME=/path/to/graalvm scripts/native-metadata.sh
#
# Output: daemon/src/main/resources/META-INF/native-image/run.fabian/goversbx-daemon/ (overwritten).
# Libraries the GraalVM reachability-metadata repository already covers are filtered out by
# native-image's own merge; what's left is mostly Jackson on our records and the admin UI files.

set -eu

GRAALVM_HOME="${GRAALVM_HOME:?set GRAALVM_HOME to a GraalVM installation}"
REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$REPO_DIR/daemon/src/main/resources/META-INF/native-image/run.fabian/goversbx-daemon"
JAR="$REPO_DIR/daemon/build/libs/goversbx-daemon.jar"

(cd "$REPO_DIR" && ./gradlew -q :daemon:shadowJar)
rm -rf "$OUT"
mkdir -p "$OUT"
"$REPO_DIR/scripts/testing/daemon-smoke.sh" \
  "$GRAALVM_HOME/bin/java" "-agentlib:native-image-agent=config-output-dir=$OUT" -jar "$JAR"
ls -la "$OUT"
