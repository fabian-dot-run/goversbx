#!/usr/bin/env bash
# Re-records daemon/src/binaryTest/resources/client-fixtures/*.json from the real MCP clients:
# Claude Code (must be on PATH, any login state), pi and opencode (installed into a scratch npm
# prefix). Each client connects to the JVM daemon through scripts/testing/record-mcp-traffic.py and
# lists its tools - no model credentials needed. oh-my-pi has no headless way to connect without a
# model, so it has no fixture.
#
#   scripts/testing/record-client-fixtures.sh
#
# Needs: java, python3, jq, npm, claude. Run it after a client release that changes how it talks
# MCP, then run the binary test suite (./gradlew :daemon:binaryTestNative) and commit the fixtures.

set -eu

REPO_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$REPO_DIR/daemon/src/binaryTest/resources/client-fixtures"
WORK="$(mktemp -d)"
DAEMON_PORT=18787
PROXY_PORT=18790
PIDS=""
trap 'for p in $PIDS; do kill "$p" 2>/dev/null; done; rm -rf "$WORK"' EXIT

(cd "$REPO_DIR" && ./gradlew -q :daemon:shadowJar)
npm install --prefix "$WORK/npm" --no-fund --no-audit @earendil-works/pi-coding-agent opencode-ai >/dev/null
BIN="$WORK/npm/node_modules/.bin"

GOVERSBX_SANDBOX_API_PORT=$DAEMON_PORT GOVERSBX_ADMIN_PORT=18788 GOVERSBX_SANDBOX_API_BIND=127.0.0.1 \
  java -jar "$REPO_DIR/daemon/build/libs/goversbx-daemon.jar" >"$WORK/daemon.log" 2>&1 &
PIDS="$PIDS $!"
python3 "$REPO_DIR/scripts/testing/record-mcp-traffic.py" $PROXY_PORT $DAEMON_PORT "$WORK/recorded.jsonl" 2>/dev/null &
PIDS="$PIDS $!"
for _ in $(seq 1 30); do curl -sf -o /dev/null "http://127.0.0.1:$DAEMON_PORT/api/health" && break; sleep 1; done

URL="http://127.0.0.1:$PROXY_PORT/mcp"
export HOME="$WORK/home"
mkdir -p "$HOME/.config/opencode"
claude mcp add goversbx-network "$URL" --transport http --header "X-Goversbx-Sandbox-Name: fixture-claude" --scope user >/dev/null
claude mcp list
"$BIN/pi" mcp add goversbx-network --url "$URL" --header X-Goversbx-Sandbox-Name=fixture-pi --exposure direct >/dev/null
"$BIN/pi" mcp list
printf '{"mcp":{"goversbx-network":{"type":"remote","url":"%s","headers":{"X-Goversbx-Sandbox-Name":"fixture-opencode"}}}}' "$URL" \
  > "$HOME/.config/opencode/opencode.json"
"$BIN/opencode" mcp list
sleep 2

for c in claude pi opencode; do
  jq -s --arg c "fixture-$c" --arg date "$(date +%Y-%m-%d)" '
    [ .[] | select((.headers["X-Goversbx-Sandbox-Name"] // .headers["x-goversbx-sandbox-name"]) == $c) ] as $steps
    | ($steps[] | select((.body|fromjson?|.method)=="initialize") | .body|fromjson|.params.clientInfo) as $info
    | {
        client: "\($info.name) \($info.version)",
        recordedAt: $date,
        note: "Recorded from the real client with scripts/testing/record-client-fixtures.sh; replayed by RecordedClientReplayTest. Session id and sandbox header are substituted at replay time.",
        steps: [ $steps[] | {
          method,
          headers: (.headers | with_entries(select(.key | ascii_downcase | IN("accept","content-type","user-agent","mcp-protocol-version","mcp-method")))),
          usesSession: (.headers | keys | map(ascii_downcase) | index("mcp-session-id") != null),
          body,
          expectStatus: .response.status,
          expectJsonRpcError: (.response.body | test("\"error\""))
        } ]
      }' "$WORK/recorded.jsonl" > "$OUT/$c.json"
  echo "$c: $(jq -r '.client + ", " + (.steps|length|tostring) + " requests"' "$OUT/$c.json")"
done
