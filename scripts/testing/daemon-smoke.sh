#!/usr/bin/env bash
# Drives a running daemon through every runtime path, against scripts/testing/fake-sbx instead of a
# real sbx. Works with any way of starting the daemon - the JVM jar or the native binary:
#
#   scripts/testing/daemon-smoke.sh daemon/build/native/nativeCompile/goversbx-daemon
#   scripts/testing/daemon-smoke.sh java -jar daemon/build/libs/goversbx-daemon.jar
#
# Covered: health on both listeners, the admin UI's static files (classpath resources), the admin
# JSON API (Jackson records + java.time), the SSE event stream, the MCP Streamable HTTP endpoint
# (initialize, tools/list, tools/call for both tools), reactive registration (spawning `sbx ls`,
# parsing its JSON, `sbx secret set-custom`) and approval (`sbx policy allow`). That is what
# native-image needs reflection/resource metadata for, so scripts/native-metadata.sh runs this
# under the tracing agent, and CI runs it against the built binary before releasing it.
#
# Knobs: SMOKE_SANDBOX_PORT=18787, SMOKE_ADMIN_PORT=18788. Needs curl and jq.

set -u

[ $# -gt 0 ] || { echo "usage: $0 <command that starts the daemon...>" >&2; exit 2; }

REPO_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
SANDBOX_PORT="${SMOKE_SANDBOX_PORT:-18787}"
ADMIN_PORT="${SMOKE_ADMIN_PORT:-18788}"
MCP="http://127.0.0.1:$SANDBOX_PORT/mcp"
ADMIN="http://127.0.0.1:$ADMIN_PORT"
PLACEHOLDER="goversbx-sandbox-identity-placeholder-v1"
SANDBOX="smoke-sandbox"
WORK="$(mktemp -d)"
FAILURES=0
DAEMON_PID=""

pass() { printf 'PASS %s\n' "$*"; }
fail() { printf 'FAIL %s\n' "$*"; FAILURES=$((FAILURES + 1)); }
die()  { fail "$*"; exit 1; }

cleanup() {
  [ -n "$DAEMON_PID" ] && kill "$DAEMON_PID" 2>/dev/null && wait "$DAEMON_PID" 2>/dev/null
  if [ "$FAILURES" -eq 0 ]; then
    echo "SMOKE PASSED"
  else
    echo "SMOKE FAILED ($FAILURES failure(s)) - daemon log:"; tail -40 "$WORK/daemon.log"
  fi
  rm -rf "$WORK"
}
trap cleanup EXIT

mkdir -p "$WORK/fake-sbx-state"
echo "$SANDBOX" > "$WORK/fake-sbx-state/sandboxes"

FAKE_SBX_STATE="$WORK/fake-sbx-state" \
GOVERSBX_SBX_BINARY="$REPO_DIR/scripts/testing/fake-sbx" \
GOVERSBX_SBX_DRY_RUN=false \
GOVERSBX_SANDBOX_API_BIND=127.0.0.1 \
GOVERSBX_SANDBOX_API_PORT="$SANDBOX_PORT" \
GOVERSBX_ADMIN_PORT="$ADMIN_PORT" \
  "$@" >"$WORK/daemon.log" 2>&1 &
DAEMON_PID=$!

for _ in $(seq 1 60); do
  curl -sf -o /dev/null "$ADMIN/api/health" && curl -sf -o /dev/null "http://127.0.0.1:$SANDBOX_PORT/api/health" && break
  kill -0 "$DAEMON_PID" 2>/dev/null || die "daemon exited during startup"
  sleep 0.5
done
curl -sf "$ADMIN/api/health" | grep -q '"ok"' && pass "admin /api/health" || die "admin /api/health"
curl -sf "http://127.0.0.1:$SANDBOX_PORT/api/health" | grep -q '"ok"' && pass "sandbox-facing /api/health" \
  || die "sandbox-facing /api/health"

for f in / /app.js /style.css /sw.js; do
  size="$(curl -sf -o /dev/null -w '%{size_download}' "$ADMIN$f")"
  [ "${size:-0}" -gt 0 ] && pass "static $f ($size bytes)" || fail "static $f"
done

# SSE: collect events in the background for the rest of the run. Javalin only streams when the
# client asks for it (EventSource sends this header); without it the response is an empty 200.
curl -sN --max-time 30 -H 'Accept: text/event-stream' "$ADMIN/api/events" >"$WORK/events.txt" 2>/dev/null &
SSE_PID=$!

mcp_session() { # $1 = header value; prints the session id
  local hdr="$WORK/hdr.$$.$RANDOM"
  curl -sf -D "$hdr" -o /dev/null -X POST "$MCP" -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" -H "X-Goversbx-Sandbox-Name: $1" \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke","version":"0"}}}'
  local sid; sid="$(grep -i '^mcp-session-id:' "$hdr" | tr -d '\r' | cut -d' ' -f2)"
  curl -sf -o /dev/null -X POST "$MCP" -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" -H "X-Goversbx-Sandbox-Name: $1" -H "mcp-session-id: $sid" \
    -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'
  echo "$sid"
}

mcp_call() { # $1 = header value, $2 = session id, $3 = JSON-RPC body
  curl -sf --max-time 60 -X POST "$MCP" -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" -H "X-Goversbx-Sandbox-Name: $1" -H "mcp-session-id: $2" -d "$3"
}

sid="$(mcp_session "$PLACEHOLDER")"
[ -n "$sid" ] && pass "MCP initialize (session $sid)" || die "MCP initialize returned no session id"

tools="$(mcp_call "$PLACEHOLDER" "$sid" '{"jsonrpc":"2.0","id":2,"method":"tools/list"}')"
echo "$tools" | grep -q request_network_access && echo "$tools" | grep -q check_network_access_status \
  && pass "MCP tools/list" || fail "MCP tools/list: $tools"

out="$(mcp_call "$PLACEHOLDER" "$sid" '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"request_network_access","arguments":{"host":"example.org","reason":"smoke","timeoutSeconds":1}}}')"
echo "$out" | grep -q "registering it now" && pass "placeholder -> reactive registration" \
  || fail "placeholder call: $out"
grep -q "smoke-sandbox" "$WORK/daemon.log" && grep -q "set-custom" "$WORK/daemon.log" \
  && pass "daemon ran sbx ls + sbx secret set-custom" || fail "no sbx ls/set-custom in the daemon log"

sid2="$(mcp_session "$SANDBOX")"
mcp_call "$SANDBOX" "$sid2" '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"request_network_access","arguments":{"host":"example.org","reason":"smoke","timeoutSeconds":20}}}' \
  >"$WORK/call.txt" &
CALL_PID=$!

req=""
for _ in $(seq 1 20); do
  req="$(curl -sf "$ADMIN/api/requests?status=pending" | jq -c '[.[] | select(.host == "example.org")] | first // empty')"
  [ -n "$req" ] && break
  sleep 0.5
done
[ -n "$req" ] && [ "$(echo "$req" | jq -r .sandboxName)" = "$SANDBOX" ] && echo "$req" | jq -e '.requestedAt | type == "string"' >/dev/null \
  && pass "pending request in /api/requests (ISO timestamp)" || die "no pending request: $req"
id="$(echo "$req" | jq -r .id)"

check="$(mcp_call "$SANDBOX" "$sid2" "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"check_network_access_status\",\"arguments\":{\"requestId\":\"$id\"}}}")"
echo "$check" | grep -qi pending && pass "MCP check_network_access_status" || fail "check status: $check"

decided="$(curl -sf -X POST "$ADMIN/api/requests/$id/approve" -H 'Content-Type: application/json' -d '{"decidedBy":"smoke"}')"
[ "$(echo "$decided" | jq -r .status)" = APPROVED ] && [ "$(echo "$decided" | jq -r .sbxApplied)" = true ] \
  && pass "approve -> sbx policy allow applied" || fail "approve: $decided"

wait "$CALL_PID"
grep -q "Approved" "$WORK/call.txt" && pass "blocked tool call returned Approved" || fail "tool call: $(cat "$WORK/call.txt")"

reg="$(curl -sf -X POST "$ADMIN/api/sandboxes/$SANDBOX/register")"
[ -n "$reg" ] && pass "POST /api/sandboxes/{name}/register" || fail "register endpoint"

sleep 1
kill "$SSE_PID" 2>/dev/null; wait "$SSE_PID" 2>/dev/null
grep -q "event: *ready" "$WORK/events.txt" && grep -q "event: *created" "$WORK/events.txt" && grep -q "event: *decided" "$WORK/events.txt" \
  && pass "SSE ready/created/decided" || fail "SSE events: $(cat "$WORK/events.txt")"

[ "$FAILURES" -eq 0 ]
