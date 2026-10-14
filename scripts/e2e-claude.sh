#!/usr/bin/env bash
# End-to-end test of the goversbx MVP against a real sbx host, using a throwaway Claude sandbox.
# Run this ON THE HOST (where `sbx` works), from anywhere inside the repo:
#
#   scripts/e2e-claude.sh
#
# What it does, each step printed as PASS/FAIL:
#   1. builds the daemon and starts it for real (GOVERSBX_SBX_DRY_RUN=false)
#   0. validates the Claude kit (`sbx kit validate sandbox-kit/kits/goversbx-claude`)
#   2. creates two throwaway sandboxes WITH the kit (`sbx create --kit <kit> --name ... claude <ws>`)
#   3. checks that the kit's network allow-list lets the sandbox reach the daemon (falls back to an
#      explicit `sbx policy allow network localhost:<port> --sandbox ...` and reports a FAIL if not)
#   4. agent side via curl inside the sandbox (same path Claude takes: through the sbx proxy, with the
#      placeholder header): first call -> "please retry" + reactive registration, second call ->
#      pending request that must carry the REAL sandbox name -> approved by this script via the
#      admin API -> the daemon's `sbx policy allow` must have worked -> the host becomes reachable
#   5. checks what the kit installed (MCP registration, skill), then the same flow with real Claude
#      Code (`claude -p`) on a second host
#   6. removes the sandbox and stops the daemon
#
# Needs on the host: sbx, java (21+), curl, jq. bash 3.2 compatible (macOS).
#
# Knobs (env vars):
#   TARGET_HOST=example.org          host the curl-driven agent asks for (must be blocked beforehand)
#   CLAUDE_TARGET_HOST=example.net   host Claude asks for (must be blocked beforehand)
#   SKIP_CLAUDE=1                    skip step 5
#   KEEP_SANDBOX=1                   don't remove the sandbox at the end (for debugging)
#   SANDBOX_NAME=...                 name for the throwaway sandbox (default goversbx-e2e-<timestamp>)
#   GOVERSBX_SANDBOX_API_PORT/GOVERSBX_ADMIN_PORT   daemon ports (default 8787/8788)
#   SBX=sbx                          sbx binary
#   SANDBOX_DAEMON_HOST=host.docker.internal   how the sandbox reaches the host
#   IDENTITY_TARGET_HOST=localhost             set-custom --host (how the proxy sees that host)
#   CLAUDE_TIMEOUT=300               seconds to wait for `claude -p`
#
# Confirmed on a real host (sbx v0.48.0): `sbx create --name N claude [DIR]`, `sbx exec -e K=V N CMD`,
# `sbx policy allow network localhost:PORT --sandbox N`, `sbx ls --format json`, `sbx secret
# set-custom --sandbox N --host H --env UPPER_NAME --placeholder P --value V` (no port in --host),
# substitution only with `--host localhost` for traffic to host.docker.internal. Script mechanics are
# also checkable without a host via scripts/testing/fake-sbx - see docs/status.md.

set -u

SBX="${SBX:-sbx}"
TARGET_HOST="${TARGET_HOST:-example.org}"
CLAUDE_TARGET_HOST="${CLAUDE_TARGET_HOST:-example.net}"
SANDBOX_NAME="${SANDBOX_NAME:-goversbx-e2e-$(date +%Y%m%d%H%M%S)}"
SANDBOX_API_PORT="${GOVERSBX_SANDBOX_API_PORT:-8787}"
ADMIN_PORT="${GOVERSBX_ADMIN_PORT:-8788}"
SANDBOX_DAEMON_HOST="${SANDBOX_DAEMON_HOST:-host.docker.internal}"
# The sbx proxy sees sandbox traffic to host.docker.internal as "localhost"; only `set-custom --host
# localhost` is substituted there (verified with scripts/diagnose-substitution.sh, sbx v0.48.0).
IDENTITY_TARGET_HOST="${IDENTITY_TARGET_HOST:-localhost}"
CLAUDE_TIMEOUT="${CLAUDE_TIMEOUT:-300}"
PLACEHOLDER="goversbx-sandbox-identity-placeholder-v1"

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
KIT_DIR="$REPO_DIR/sandbox-kit/kits/goversbx-claude"
KIT_ARGS=""
# The kit defaults to port 8787; only pass --kit-arg when the daemon runs elsewhere.
[ "$SANDBOX_API_PORT" = 8787 ] || KIT_ARGS="--kit-arg goversbx-claude.daemonPort=$SANDBOX_API_PORT"
RUN_DIR="$HOME/.cache/goversbx-e2e/$SANDBOX_NAME"
WORKSPACE="$RUN_DIR/workspace"
DAEMON_LOG="$RUN_DIR/daemon.log"
ADMIN="http://127.0.0.1:$ADMIN_PORT"
DAEMON_URL_FROM_SANDBOX="http://$SANDBOX_DAEMON_HOST:$SANDBOX_API_PORT"

SANDBOX_B="$SANDBOX_NAME-b"   # second throwaway sandbox, only to check identities stay per sandbox
IDENTITY_HOST_B="goversbx-e2e-identity-check.invalid"   # never contacted; its request gets denied

DAEMON_PID=""
CREATED_SANDBOXES=""
FAILURES=0

c_green=$'\033[32m'; c_red=$'\033[31m'; c_dim=$'\033[2m'; c_off=$'\033[0m'
[ -t 1 ] || { c_green=""; c_red=""; c_dim=""; c_off=""; }

step() { printf '\n== %s\n' "$*"; }
pass() { printf '%sPASS%s %s\n' "$c_green" "$c_off" "$*"; }
fail() { printf '%sFAIL%s %s\n' "$c_red" "$c_off" "$*"; FAILURES=$((FAILURES + 1)); }
info() { printf '%s     %s%s\n' "$c_dim" "$*" "$c_off"; }
die()  { fail "$*"; exit 1; }

cleanup() {
  step "Cleanup"
  if [ -n "$DAEMON_PID" ] && kill -0 "$DAEMON_PID" 2>/dev/null; then
    kill "$DAEMON_PID" 2>/dev/null; wait "$DAEMON_PID" 2>/dev/null
    info "daemon stopped (log: $DAEMON_LOG)"
  fi
  for sb in $CREATED_SANDBOXES; do
    if [ "${KEEP_SANDBOX:-0}" = 1 ]; then
      info "KEEP_SANDBOX=1 - sandbox $sb kept; remove with: $SBX rm --force $sb"
      continue
    fi
    # Best effort: sandbox-scoped custom secrets are keyed by placeholder; remove ours explicitly in
    # case removing the sandbox doesn't (sbx docs: `sbx secret rm ... --placeholder ...`).
    "$SBX" secret rm --sandbox "$sb" --placeholder "$PLACEHOLDER" --force >/dev/null 2>&1
    "$SBX" rm --force "$sb" >/dev/null 2>&1 && info "sandbox $sb removed" \
      || info "could not remove $sb - remove manually: $SBX rm --force $sb"
  done
  echo
  if [ "$FAILURES" -eq 0 ]; then
    printf '%sE2E PASSED%s\n' "$c_green" "$c_off"
  else
    printf '%sE2E FAILED (%d failure(s))%s - daemon log kept at %s\n' "$c_red" "$FAILURES" "$c_off" "$DAEMON_LOG"
  fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# Run a shell snippet inside the sandbox. Env for the snippet is passed with -e.
in_sandbox() {
  "$SBX" exec \
    -e "DAEMON_URL=$DAEMON_URL_FROM_SANDBOX" \
    -e "PLACEHOLDER=$PLACEHOLDER" \
    -e "WORKSPACE=$WORKSPACE" \
    "$SANDBOX_NAME" sh -c "$1"
}

# HTTP status of a GET to https://<host> from inside the sandbox (goes through the sbx proxy).
sandbox_status_of() {
  in_sandbox "curl -sS -o /dev/null -w '%{http_code}' --max-time 20 https://$1 2>/dev/null || true"
}

pending_request_json() { # $1 = host
  curl -sS "$ADMIN/api/requests?status=pending" \
    | jq -c --arg h "$1" '[.[] | select(.host == $h)] | first // empty'
}

wait_for_pending() { # $1 = host, $2 = seconds; prints the request JSON
  local deadline=$(( $(date +%s) + $2 )) r
  while [ "$(date +%s)" -lt "$deadline" ]; do
    r="$(pending_request_json "$1")"
    [ -n "$r" ] && { echo "$r"; return 0; }
    sleep 2
  done
  return 1
}

approve() { # $1 = request id; prints the decided request JSON
  curl -sS -X POST "$ADMIN/api/requests/$1/approve" \
    -H 'Content-Type: application/json' -d '{"decidedBy":"e2e-claude.sh"}'
}

check_approval() { # $1 = approve response JSON, $2 = host, $3 = label
  local applied out
  applied="$(echo "$1" | jq -r '.sbxApplied')"
  out="$(echo "$1" | jq -r '.sbxOutput')"
  if [ "$(echo "$1" | jq -r '.status')" = APPROVED ] && [ "$applied" = true ]; then
    pass "$3: approved, daemon ran sbx policy allow (output: ${out:-<empty>})"
  else
    fail "$3: approval did not apply the policy (sbxApplied=$applied, output: $out)"
  fi
  # sbx v0.48's plain `policy ls N` only prints a summary ("allow 12 hosts from ..."), not the hosts,
  # so try the structured output; the reachability check after this is the authoritative proof.
  if "$SBX" policy ls "$SANDBOX_NAME" --format json 2>/dev/null | grep -q -F "$2"; then
    pass "$3: sbx policy ls $SANDBOX_NAME --format json lists $2"
  else
    info "$3: could not confirm $2 via sbx policy ls (summary output only) - the reachability check decides"
  fi
}

# ---------------------------------------------------------------------------------------------
step "Preflight"
for tool in "$SBX" java curl jq; do
  command -v "$tool" >/dev/null 2>&1 || die "'$tool' not found on PATH"
done
for port in "$SANDBOX_API_PORT" "$ADMIN_PORT"; do
  curl -s -o /dev/null --max-time 2 "http://127.0.0.1:$port/" && die "port $port is already in use - stop the running daemon first"
done
pass "sbx, java, curl, jq present; ports $SANDBOX_API_PORT/$ADMIN_PORT free"
info "sandbox: $SANDBOX_NAME, run dir: $RUN_DIR"
if out="$("$SBX" kit validate "$KIT_DIR" 2>&1)"; then
  pass "sbx kit validate $KIT_DIR"
  [ -n "$out" ] && info "$out"
else
  echo "$out"; die "sbx kit validate $KIT_DIR failed"
fi

# ---------------------------------------------------------------------------------------------
step "1. Build and start the daemon (real sbx calls)"
mkdir -p "$WORKSPACE"
(cd "$REPO_DIR" && ./gradlew -q :daemon:shadowJar) >"$RUN_DIR/build.log" 2>&1 \
  || { cat "$RUN_DIR/build.log"; die "daemon build failed"; }
GOVERSBX_SBX_DRY_RUN=false \
GOVERSBX_SBX_BINARY="$SBX" \
GOVERSBX_IDENTITY_TARGET_HOST="$IDENTITY_TARGET_HOST" \
GOVERSBX_IDENTITY_SANDBOX_PATTERN="$SANDBOX_NAME(-b)?" \
GOVERSBX_SANDBOX_API_PORT="$SANDBOX_API_PORT" \
GOVERSBX_ADMIN_PORT="$ADMIN_PORT" \
  java -jar "$REPO_DIR/daemon/build/libs/goversbx-daemon.jar" >"$DAEMON_LOG" 2>&1 &
DAEMON_PID=$!
for _ in $(seq 1 30); do
  curl -s -o /dev/null "$ADMIN/api/health" && break
  sleep 1
done
curl -s -o /dev/null "$ADMIN/api/health" || { tail -30 "$DAEMON_LOG"; die "daemon did not come up"; }
pass "daemon running (pid $DAEMON_PID), admin UI $ADMIN"
info "sandbox identity registration limited to $SANDBOX_NAME and $SANDBOX_B - other sandboxes are not touched"

# ---------------------------------------------------------------------------------------------
step "2. Create throwaway sandboxes"
# shellcheck disable=SC2086 # KIT_ARGS is deliberately word-split
if out="$("$SBX" create --kit "$KIT_DIR" $KIT_ARGS --name "$SANDBOX_NAME" claude "$WORKSPACE" 2>&1)"; then
  CREATED_SANDBOXES="$SANDBOX_NAME"
  pass "sbx create --kit $KIT_DIR $KIT_ARGS --name $SANDBOX_NAME claude $WORKSPACE"
  echo "$out" | grep -i -E "warn|kit" | while IFS= read -r l; do info "$l"; done
else
  echo "$out"; die "sbx create failed"
fi
# Second sandbox (no workspace, same kit): created now so the first reactive registration covers
# both, which is what can reveal identities colliding (both share the same placeholder).
# shellcheck disable=SC2086
if out="$("$SBX" create --kit "$KIT_DIR" $KIT_ARGS --name "$SANDBOX_B" claude 2>&1)"; then
  CREATED_SANDBOXES="$CREATED_SANDBOXES $SANDBOX_B"
  pass "sbx create --kit ... --name $SANDBOX_B claude (second sandbox, for the identity-isolation check)"
else
  fail "sbx create --name $SANDBOX_B claude failed - identity-isolation check will be skipped: $out"
fi
status="$("$SBX" ls --format json 2>/dev/null | jq -r --arg n "$SANDBOX_NAME" '.[] | select(.name == $n) | .status')"
info "sandbox status after create: ${status:-unknown}"
if out="$(in_sandbox 'echo ok' 2>&1)" && [ "$(echo "$out" | tail -1)" = ok ]; then
  pass "sbx exec works"
else
  echo "$out"; die "sbx exec $SANDBOX_NAME failed (sandbox not running? try: $SBX run --name $SANDBOX_NAME)"
fi

# ---------------------------------------------------------------------------------------------
step "3. The kit's network allow-list lets the sandbox reach the daemon"
health_from_sandbox() { in_sandbox 'curl -sS --max-time 10 "$DAEMON_URL/api/health" 2>&1'; }
h="$(health_from_sandbox)"
if echo "$h" | grep -q '"ok"'; then
  pass "sandbox reaches $DAEMON_URL_FROM_SANDBOX through the kit's permissions.network.allow"
else
  fail "kit's network allow-list did not let the sandbox reach the daemon ($h) - falling back to explicit policy rules to test the rest"
  for sb in $CREATED_SANDBOXES; do
    "$SBX" policy allow network "localhost:$SANDBOX_API_PORT" --sandbox "$sb" >/dev/null 2>&1
    "$SBX" policy allow network "$SANDBOX_DAEMON_HOST:$SANDBOX_API_PORT" --sandbox "$sb" >/dev/null 2>&1
  done
  h="$(health_from_sandbox)"
  echo "$h" | grep -q '"ok"' && info "reachable with explicit sbx policy allow network localhost:$SANDBOX_API_PORT" \
    || die "sandbox cannot reach $DAEMON_URL_FROM_SANDBOX/api/health even with explicit rules: $h"
fi
# Header substitution only happens if the request goes through the sbx proxy.
info "$(in_sandbox 'echo "proxy in sandbox: ${HTTP_PROXY:-${http_proxy:-<unset>}}, NO_PROXY=${NO_PROXY:-${no_proxy:-<unset>}}"')"

# ---------------------------------------------------------------------------------------------
step "4. Agent side via curl (through the sbx proxy, placeholder header) - target $TARGET_HOST"
code="$(sandbox_status_of "$TARGET_HOST")"
if [ "$code" = 200 ]; then
  die "$TARGET_HOST is already reachable from the sandbox - pick a blocked host with TARGET_HOST=..."
fi
pass "$TARGET_HOST is blocked before the test (HTTP $code)"
info "(the blocked attempt may leave a pending entry in '$SBX policy approval ls' - harmless)"

# One MCP session per call; prints the text of the tool result.
MCP_CALL_SNIPPET='
set -e
hdr=$(mktemp)
init='"'"'{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"goversbx-e2e","version":"0"}}}'"'"'
curl -sS -D "$hdr" -o /dev/null -X POST "$DAEMON_URL/mcp" -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" -H "X-Goversbx-Sandbox-Name: $PLACEHOLDER" -d "$init"
sid=$(grep -i "^mcp-session-id:" "$hdr" | tr -d "\r" | cut -d" " -f2)
[ -n "$sid" ] || { echo "NO-SESSION"; exit 0; }
curl -sS -o /dev/null -X POST "$DAEMON_URL/mcp" -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" -H "X-Goversbx-Sandbox-Name: $PLACEHOLDER" \
  -H "mcp-session-id: $sid" -d "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"
curl -sS --max-time 150 -X POST "$DAEMON_URL/mcp" -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" -H "X-Goversbx-Sandbox-Name: $PLACEHOLDER" \
  -H "mcp-session-id: $sid" \
  -d "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"request_network_access\",\"arguments\":{\"host\":\"$TARGET\",\"reason\":\"goversbx e2e test\",\"timeoutSeconds\":$TIMEOUT}}}"
'
mcp_request_access() { # $1 = host, $2 = timeoutSeconds, $3 = sandbox (default: the main one)
  "$SBX" exec -e "DAEMON_URL=$DAEMON_URL_FROM_SANDBOX" -e "PLACEHOLDER=$PLACEHOLDER" \
    -e "TARGET=$1" -e "TIMEOUT=$2" "${3:-$SANDBOX_NAME}" sh -c "$MCP_CALL_SNIPPET" 2>&1
}

first="$(mcp_request_access "$TARGET_HOST" 5)"
if echo "$first" | grep -q "registering it now"; then
  pass "first call: daemon saw the unsubstituted placeholder and asked to retry"
elif echo "$first" | grep -q "Still pending\|Approved"; then
  info "first call went straight through - this sandbox was already registered"
else
  fail "first call: unexpected response: $first"
fi
info "$(grep "sbx ls: running sandboxes" "$DAEMON_LOG" | tail -1 | sed 's/.* - //')"
if grep -q "sbx secret set-custom registered sandbox=$SANDBOX_NAME " "$DAEMON_LOG"; then
  pass "daemon registered $SANDBOX_NAME via sbx secret set-custom"
elif ! echo "$first" | grep -q "registering it now"; then
  info "no registration in this run (sandbox was already registered)"
else
  fail "daemon did not register $SANDBOX_NAME - daemon log:"
  grep -E "sbx ls|set-custom|Failed to register" "$DAEMON_LOG" | tail -10
fi

sleep 3
mcp_request_access "$TARGET_HOST" 90 >"$RUN_DIR/second-call.txt" &
SECOND_PID=$!
if req="$(wait_for_pending "$TARGET_HOST" 45)"; then
  name="$(echo "$req" | jq -r '.sandboxName')"
  if [ "$name" = "$SANDBOX_NAME" ]; then
    pass "pending request arrived with the REAL sandbox name ($name) - proxy substitution works"
  elif [ "$name" = "$SANDBOX_B" ]; then
    fail "pending request has sandboxName '$name' (the OTHER test sandbox) - sandbox-scoped secrets with the same placeholder collide; approvals would go to the wrong sandbox"
  else
    fail "pending request has sandboxName '$name', expected '$SANDBOX_NAME'"
  fi
  check_approval "$(approve "$(echo "$req" | jq -r '.id')")" "$TARGET_HOST" "curl agent"
else
  fail "no pending request for $TARGET_HOST within 45s - most likely the placeholder is not being substituted"
  info "check: $SBX secret ls ; daemon log $DAEMON_LOG ; sbx secret ls --sandbox $SANDBOX_NAME should list TARGETS $IDENTITY_TARGET_HOST"
fi
wait "$SECOND_PID" 2>/dev/null
second="$(cat "$RUN_DIR/second-call.txt")"
echo "$second" | grep -q "Approved" && pass "second call returned 'Approved' to the agent" \
  || fail "second call did not return 'Approved': $second"

code="$(sandbox_status_of "$TARGET_HOST")"
[ "$code" = 200 ] && pass "$TARGET_HOST now reachable from the sandbox (HTTP 200)" \
  || fail "$TARGET_HOST still not reachable from the sandbox (HTTP $code)"

# ---------------------------------------------------------------------------------------------
step "4b. Identities stay per sandbox (both sandboxes share the same placeholder)"
case " $CREATED_SANDBOXES " in
  *" $SANDBOX_B "*)
    # $SANDBOX_B was running during the first registration, so it should already be registered;
    # allow a couple of "please retry" rounds in case it wasn't.
    out=""
    for _ in 1 2 3; do
      out="$(mcp_request_access "$IDENTITY_HOST_B" 1 "$SANDBOX_B")"
      echo "$out" | grep -q "registering it now" || break
      sleep 3
    done
    if req="$(wait_for_pending "$IDENTITY_HOST_B" 20)"; then
      name="$(echo "$req" | jq -r '.sandboxName')"
      if [ "$name" = "$SANDBOX_B" ]; then
        pass "request from $SANDBOX_B arrived as $SANDBOX_B - the shared placeholder resolves per sandbox"
      else
        fail "request from $SANDBOX_B arrived as '$name' - sandbox-scoped secrets with the same placeholder collide"
      fi
      curl -sS -o /dev/null -X POST "$ADMIN/api/requests/$(echo "$req" | jq -r '.id')/deny" \
        -H 'Content-Type: application/json' -d '{"decidedBy":"e2e-claude.sh"}'
      info "denied the check request (no sbx policy change)"
    else
      fail "no pending request from $SANDBOX_B for $IDENTITY_HOST_B within 20s (last response: $out)"
    fi
    ;;
  *) info "skipped - second sandbox was not created" ;;
esac

# ---------------------------------------------------------------------------------------------
if [ "${SKIP_CLAUDE:-0}" = 1 ]; then
  step "5. Real Claude Code - skipped (SKIP_CLAUDE=1)"
else
  step "5. Real Claude Code (claude -p) - target $CLAUDE_TARGET_HOST"
  code="$(sandbox_status_of "$CLAUDE_TARGET_HOST")"
  if [ "$code" = 200 ]; then
    fail "$CLAUDE_TARGET_HOST is already reachable - pick a blocked host with CLAUDE_TARGET_HOST=..."
  else
    pass "$CLAUDE_TARGET_HOST is blocked before the test (HTTP $code)"
    # Nothing installed by hand any more - all of this must come from `sbx create --kit`.
    out="$("$SBX" exec "$SANDBOX_NAME" bash -lc 'claude mcp list' 2>&1)"
    if echo "$out" | grep -q "goversbx-network"; then
      pass "kit registered the MCP endpoint (claude mcp list shows goversbx-network)"
      info "$(echo "$out" | grep goversbx-network)"
    else
      fail "kit did not register goversbx-network with claude: $out"
    fi
    if "$SBX" exec "$SANDBOX_NAME" test -f /home/agent/.claude/skills/goversbx-network-access/SKILL.md; then
      pass "kit installed the skill at ~/.claude/skills/goversbx-network-access/SKILL.md"
    else
      fail "skill missing at ~/.claude/skills/goversbx-network-access/SKILL.md"
    fi
    out="$("$SBX" exec "$SANDBOX_NAME" sh -c 'grep -rl "goversbx" /home/agent "$WORKSPACE_DIR" --include="*.md" 2>/dev/null | grep -v /skills/ | head -5' 2>&1)"
    [ -n "$out" ] && info "kit agent instructions found in: $(echo $out)" \
      || info "no kit agent-instructions file found (optional - the skill is what Claude uses)"

    PROMPT="Use the request_network_access tool of the goversbx-network MCP server to request network access to $CLAUDE_TARGET_HOST with the reason 'goversbx e2e test' and timeoutSeconds 90. If it says the sandbox identity is not registered yet, wait a few seconds and call it again with the same arguments. Do nothing else. Finally print the tool's last response verbatim."
    "$SBX" exec -e "PROMPT=$PROMPT" "$SANDBOX_NAME" bash -lc \
      'claude -p "$PROMPT" --allowedTools "mcp__goversbx-network" < /dev/null' >"$RUN_DIR/claude.txt" 2>&1 &
    CLAUDE_PID=$!

    approved=0
    deadline=$(( $(date +%s) + CLAUDE_TIMEOUT ))
    while kill -0 "$CLAUDE_PID" 2>/dev/null && [ "$(date +%s)" -lt "$deadline" ]; do
      if [ "$approved" = 0 ] && req="$(pending_request_json "$CLAUDE_TARGET_HOST")" && [ -n "$req" ]; then
        name="$(echo "$req" | jq -r '.sandboxName')"
        [ "$name" = "$SANDBOX_NAME" ] && pass "Claude's request arrived with the real sandbox name" \
          || fail "Claude's request has sandboxName '$name', expected '$SANDBOX_NAME'"
        check_approval "$(approve "$(echo "$req" | jq -r '.id')")" "$CLAUDE_TARGET_HOST" "claude"
        approved=1
      fi
      sleep 2
    done
    if kill -0 "$CLAUDE_PID" 2>/dev/null; then
      kill "$CLAUDE_PID" 2>/dev/null
      fail "claude -p did not finish within ${CLAUDE_TIMEOUT}s"
    fi
    wait "$CLAUDE_PID" 2>/dev/null
    [ "$approved" = 1 ] || fail "Claude never created a pending request for $CLAUDE_TARGET_HOST"
    if grep -q "Approved" "$RUN_DIR/claude.txt"; then
      pass "Claude received 'Approved'"
    else
      fail "Claude's output does not contain 'Approved' (full output: $RUN_DIR/claude.txt)"
      tail -20 "$RUN_DIR/claude.txt"
    fi
    code="$(sandbox_status_of "$CLAUDE_TARGET_HOST")"
    [ "$code" = 200 ] && pass "$CLAUDE_TARGET_HOST now reachable from the sandbox (HTTP 200)" \
      || fail "$CLAUDE_TARGET_HOST still not reachable from the sandbox (HTTP $code)"
  fi
fi

[ "$FAILURES" -eq 0 ]
