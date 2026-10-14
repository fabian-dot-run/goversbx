#!/usr/bin/env bash
# End-to-end test of the goversbx MVP against a real sbx host, using a throwaway `shell` sandbox
# with oh-my-pi (omp, can1357/oh-my-pi) installed into it - sbx has no built-in omp agent.
# Run this ON THE HOST (where `sbx` works), from anywhere inside the repo:
#
#   scripts/e2e-omp.sh
#
# What it does, each step printed as PASS/FAIL:
#   1. builds the daemon and starts it for real (GOVERSBX_SBX_DRY_RUN=false)
#   0. validates the omp kit (`sbx kit validate sandbox-kit/kits/goversbx-omp`)
#   2. creates two throwaway `shell` sandboxes WITH the kit, installs omp into each
#   3. checks that the kit's network allow-list lets the sandbox reach the daemon (falls back to an
#      explicit `sbx policy allow network localhost:<port> --sandbox ...` and reports a FAIL if not)
#   4. agent side via curl inside the sandbox (same path omp takes: through the sbx proxy, with the
#      placeholder header): first call -> "please retry" + reactive registration, second call ->
#      pending request that must carry the REAL sandbox name -> approved by this script via the
#      admin API -> the daemon's `sbx policy allow` must have worked -> the host becomes reachable
#   5. checks what the kit installed (mcp.json content, skill file), then asks real omp (`omp -p`)
#      to curl a second blocked host WITHOUT mentioning goversbx - it must request access on its
#      own. Needs ANTHROPIC_API_KEY (or another provider key omp accepts); skipped if unset.
#   6. removes the sandbox and stops the daemon
#
# Needs on the host: sbx, java (21+), curl, jq. bash 3.2 compatible (macOS).
#
# Knobs (env vars):
#   TARGET_HOST=example.org        host the curl-driven agent asks for (must be blocked beforehand)
#   OMP_TARGET_HOST=example.net    host omp asks for (must be blocked beforehand)
#   SKIP_OMP=1                     skip step 5 (also auto-skipped if ANTHROPIC_API_KEY is unset)
#   KEEP_SANDBOX=1                 don't remove the sandbox at the end (for debugging)
#   SANDBOX_NAME=...               name for the throwaway sandbox (default goversbx-e2e-omp-<ts>)
#   GOVERSBX_SANDBOX_API_PORT/GOVERSBX_ADMIN_PORT   daemon ports (default 8787/8788)
#   SBX=sbx                        sbx binary
#   SANDBOX_DAEMON_HOST=host.docker.internal   how the sandbox reaches the host
#   IDENTITY_TARGET_HOST=localhost             set-custom --host (how the proxy sees that host)
#   OMP_TIMEOUT=300                 seconds to wait for `omp -p`
#
# `bun.sh` must be reachable from the sandbox to install bun, then omp via `bun install -g
# @oh-my-pi/pi-coding-agent` (omp's own "Bun (recommended)" method - verified for real). Deliberately
# NOT omp's own curl installer (`curl -fsSL https://omp.sh/install | sh`): its prebuilt-binary
# fallback resolves a fresh, randomly-named `*.r2.cloudflarestorage.com` signed URL on every run (the
# bucket subdomain is per-account, the path per-release), so there is no stable host to ever allow in
# advance - confirmed by actually running it here, which got a working omp.sh/build.stencil.so
# redirect chain but then a 403 from `<random-id>.r2.cloudflarestorage.com`. bun avoids that path
# entirely; omp's own `package.json` ("bin": {"omp": "src/cli.ts"}) also confirms the npm package
# alone does NOT work without bun to execute the TypeScript entrypoint - `npm install -g` installs
# fine but the resulting `omp` fails with "env: 'bun': No such file or directory" (also confirmed by
# actually running it). Mechanics only otherwise (docs/plan.md's omp section): omp's own
# `-p`/`--print` one-shot flag, `yolo` being the DEFAULT approval mode (auto-approves read/write/exec
# so a headless `-p` run never blocks on an interactive prompt), and the `~/.omp/agent/mcp.json` +
# `~/.omp/agent/skills/` paths are sourced from omp's docs (docs/cli-reference.md,
# docs/approval-mode.md, docs/mcp-config.md) - omp has no CLI subcommand to list MCP servers
# headlessly (`/mcp list` is a slash command inside an interactive session only), so this script
# checks the config file directly instead. An actual autonomous tool call (this script's step 5) was
# never run for real before - that's what this script is for.

set -u

SBX="${SBX:-sbx}"
TARGET_HOST="${TARGET_HOST:-example.org}"
OMP_TARGET_HOST="${OMP_TARGET_HOST:-example.net}"
SANDBOX_NAME="${SANDBOX_NAME:-goversbx-e2e-omp-$(date +%Y%m%d%H%M%S)}"
SANDBOX_API_PORT="${GOVERSBX_SANDBOX_API_PORT:-8787}"
ADMIN_PORT="${GOVERSBX_ADMIN_PORT:-8788}"
SANDBOX_DAEMON_HOST="${SANDBOX_DAEMON_HOST:-host.docker.internal}"
# The sbx proxy sees sandbox traffic to host.docker.internal as "localhost"; only `set-custom --host
# localhost` is substituted there (verified with scripts/diagnose-substitution.sh, sbx v0.48.0).
IDENTITY_TARGET_HOST="${IDENTITY_TARGET_HOST:-localhost}"
OMP_TIMEOUT="${OMP_TIMEOUT:-300}"
PLACEHOLDER="goversbx-sandbox-identity-placeholder-v1"

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
KIT_DIR="$REPO_DIR/sandbox-kit/kits/goversbx-omp"
KIT_ARGS=""
# The kit defaults to port 8787; only pass --kit-arg when the daemon runs elsewhere.
[ "$SANDBOX_API_PORT" = 8787 ] || KIT_ARGS="--kit-arg goversbx-omp.daemonPort=$SANDBOX_API_PORT"
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

in_sandbox() {
  "$SBX" exec \
    -e "DAEMON_URL=$DAEMON_URL_FROM_SANDBOX" \
    -e "PLACEHOLDER=$PLACEHOLDER" \
    -e "WORKSPACE=$WORKSPACE" \
    "$SANDBOX_NAME" sh -c "$1"
}

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
    -H 'Content-Type: application/json' -d '{"decidedBy":"e2e-omp.sh"}'
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
step "2. Create throwaway shell sandboxes with the omp kit, install omp"
# shellcheck disable=SC2086 # KIT_ARGS is deliberately word-split
if out="$("$SBX" create --kit "$KIT_DIR" $KIT_ARGS --name "$SANDBOX_NAME" shell "$WORKSPACE" 2>&1)"; then
  CREATED_SANDBOXES="$SANDBOX_NAME"
  pass "sbx create --kit $KIT_DIR $KIT_ARGS --name $SANDBOX_NAME shell $WORKSPACE"
  echo "$out" | grep -i -E "warn|kit" | while IFS= read -r l; do info "$l"; done
else
  echo "$out"; die "sbx create failed"
fi
# shellcheck disable=SC2086
if out="$("$SBX" create --kit "$KIT_DIR" $KIT_ARGS --name "$SANDBOX_B" shell 2>&1)"; then
  CREATED_SANDBOXES="$CREATED_SANDBOXES $SANDBOX_B"
  pass "sbx create --kit ... --name $SANDBOX_B shell (second sandbox, for the identity-isolation check)"
else
  fail "sbx create --name $SANDBOX_B shell failed - identity-isolation check will be skipped: $out"
fi
status="$("$SBX" ls --format json 2>/dev/null | jq -r --arg n "$SANDBOX_NAME" '.[] | select(.name == $n) | .status')"
info "sandbox status after create: ${status:-unknown}"
if out="$(in_sandbox 'echo ok' 2>&1)" && [ "$(echo "$out" | tail -1)" = ok ]; then
  pass "sbx exec works"
else
  echo "$out"; die "sbx exec $SANDBOX_NAME failed (sandbox not running? try: $SBX run --name $SANDBOX_NAME)"
fi
# bun is required both to install omp (its npm package's bin is a .ts file executed by bun) and for
# the kit's own MCP merge script (which prefers node, falls back to bun, else skips). The omp kit's
# permissions.network.allow only covers the daemon host, not general internet access - whatever the
# sandbox's default sbx policy doesn't already allow (e.g. a fresh deny-all/balanced host) has to be
# granted explicitly here, same fallback pattern as step 3's daemon-reachability check. bun's
# installer resolves github.com/oven-sh/bun/releases/..., which redirects through github.com's own
# release-asset CDN at release-assets.githubusercontent.com (confirmed with a real `curl -L`; a
# fixed, non-randomized host, unlike omp's own installer's per-release R2 subdomain - see the
# comment above). If GitHub changes that CDN host again, the failure below names the sandbox so you
# can `sbx policy allow network <host>:443 --sandbox <name>` it and re-run.
for sb in $CREATED_SANDBOXES; do
  "$SBX" policy allow network "bun.sh:443" --sandbox "$sb" >/dev/null 2>&1
  "$SBX" policy allow network "github.com:443" --sandbox "$sb" >/dev/null 2>&1
  "$SBX" policy allow network "release-assets.githubusercontent.com:443" --sandbox "$sb" >/dev/null 2>&1
  if out="$("$SBX" exec "$sb" sh -c 'curl -fsSL https://bun.sh/install | bash && export PATH="$HOME/.bun/bin:$PATH" && bun install -g @oh-my-pi/pi-coding-agent' 2>&1)"; then
    pass "bun + omp installed in $sb"
  else
    echo "$out"; die "bun/omp install failed in $sb - bun.sh, github.com and release-assets.githubusercontent.com are allowed for $sb, but a release-asset CDN redirect may need yet another host: sbx policy allow network <host>:443 --sandbox $sb, then re-run"
  fi
done

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
info "$(in_sandbox 'echo "proxy in sandbox: ${HTTP_PROXY:-${http_proxy:-<unset>}}, NO_PROXY=${NO_PROXY:-${no_proxy:-<unset>}}"')"

# ---------------------------------------------------------------------------------------------
step "4. Agent side via curl (through the sbx proxy, placeholder header) - target $TARGET_HOST"
code="$(sandbox_status_of "$TARGET_HOST")"
if [ "$code" = 200 ]; then
  die "$TARGET_HOST is already reachable from the sandbox - pick a blocked host with TARGET_HOST=..."
fi
pass "$TARGET_HOST is blocked before the test (HTTP $code)"
info "(the blocked attempt may leave a pending entry in '$SBX policy approval ls' - harmless)"

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
        -H 'Content-Type: application/json' -d '{"decidedBy":"e2e-omp.sh"}'
      info "denied the check request (no sbx policy change)"
    else
      fail "no pending request from $SANDBOX_B for $IDENTITY_HOST_B within 20s (last response: $out)"
    fi
    ;;
  *) info "skipped - second sandbox was not created" ;;
esac

# ---------------------------------------------------------------------------------------------
if [ "${SKIP_OMP:-0}" = 1 ]; then
  step "5. Real omp (omp -p) - skipped (SKIP_OMP=1)"
elif [ -z "${ANTHROPIC_API_KEY:-}" ]; then
  step "5. Real omp (omp -p) - skipped (ANTHROPIC_API_KEY not set)"
else
  step "5. Real omp (omp -p) - target $OMP_TARGET_HOST"
  code="$(sandbox_status_of "$OMP_TARGET_HOST")"
  if [ "$code" = 200 ]; then
    fail "$OMP_TARGET_HOST is already reachable - pick a blocked host with OMP_TARGET_HOST=..."
  else
    pass "$OMP_TARGET_HOST is blocked before the test (HTTP $code)"
    # omp has no CLI subcommand to list MCP servers headlessly (/mcp list is an interactive slash
    # command only, docs/mcp-config.md) - check the config file the kit wrote instead.
    out="$("$SBX" exec "$SANDBOX_NAME" sh -c 'cat /home/agent/.omp/agent/mcp.json 2>&1')"
    if echo "$out" | grep -q "goversbx-network"; then
      pass "kit registered the MCP endpoint (~/.omp/agent/mcp.json has goversbx-network)"
    else
      fail "kit did not register goversbx-network in ~/.omp/agent/mcp.json: $out"
    fi
    if "$SBX" exec "$SANDBOX_NAME" test -f /home/agent/.omp/agent/skills/goversbx-network-access/SKILL.md; then
      pass "kit installed the skill at ~/.omp/agent/skills/goversbx-network-access/SKILL.md"
    else
      fail "skill missing at ~/.omp/agent/skills/goversbx-network-access/SKILL.md"
    fi

    # A natural prompt that does NOT mention goversbx: checks omp picks goversbx on its own. `yolo`
    # is omp's DEFAULT approval mode (docs/approval-mode.md) - read/write/exec all auto-approve, so
    # a plain `omp -p` run never blocks on an interactive approval prompt; no extra flag needed.
    PROMPT="curl https://$OMP_TARGET_HOST and tell me the final HTTP status code."
    "$SBX" exec -e "PROMPT=$PROMPT" -e "WS=$WORKSPACE" -e "ANTHROPIC_API_KEY=$ANTHROPIC_API_KEY" "$SANDBOX_NAME" bash -lc \
      'cd "$WS" 2>/dev/null; omp -p "$PROMPT" < /dev/null' >"$RUN_DIR/omp.txt" 2>&1 &
    OMP_PID=$!

    approved=0
    deadline=$(( $(date +%s) + OMP_TIMEOUT ))
    while kill -0 "$OMP_PID" 2>/dev/null && [ "$(date +%s)" -lt "$deadline" ]; do
      if [ "$approved" = 0 ] && req="$(pending_request_json "$OMP_TARGET_HOST")" && [ -n "$req" ]; then
        name="$(echo "$req" | jq -r '.sandboxName')"
        [ "$name" = "$SANDBOX_NAME" ] && pass "omp's request arrived with the real sandbox name" \
          || fail "omp's request has sandboxName '$name', expected '$SANDBOX_NAME'"
        check_approval "$(approve "$(echo "$req" | jq -r '.id')")" "$OMP_TARGET_HOST" "omp"
        approved=1
      fi
      sleep 2
    done
    if kill -0 "$OMP_PID" 2>/dev/null; then
      kill "$OMP_PID" 2>/dev/null
      fail "omp -p did not finish within ${OMP_TIMEOUT}s"
    fi
    wait "$OMP_PID" 2>/dev/null
    if [ "$approved" = 1 ]; then
      pass "omp used goversbx on its own (prompt did not mention it)"
    else
      fail "omp never created a pending request for $OMP_TARGET_HOST - it did not use goversbx on its own:"
      tail -20 "$RUN_DIR/omp.txt"
    fi
    if grep -q "200" "$RUN_DIR/omp.txt"; then
      pass "omp retried after the approval and reported 200"
    else
      info "omp's answer does not mention 200 (full output: $RUN_DIR/omp.txt): $(tail -3 "$RUN_DIR/omp.txt")"
    fi
    code="$(sandbox_status_of "$OMP_TARGET_HOST")"
    [ "$code" = 200 ] && pass "$OMP_TARGET_HOST now reachable from the sandbox (HTTP 200)" \
      || fail "$OMP_TARGET_HOST still not reachable from the sandbox (HTTP $code)"
  fi
fi

[ "$FAILURES" -eq 0 ]
