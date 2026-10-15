#!/usr/bin/env bash
# Threat-model F3: is the loopback-bound admin API (127.0.0.1:<adminPort> on the host) reachable
# from inside a sandbox once a loopback host is allowed? The admin API has no auth, so if a sandbox
# can reach it, it can self-approve any request for any sandbox - a full bypass of the human gate.
#
# Run on the HOST (not from inside a sandbox). Needs: sbx, curl. Bash 3.2 compatible (macOS).
#
#   scripts/diagnose-admin-reachability.sh
#     SBX=sbx                      sbx binary
#     ADMIN_PORT=8788              daemon admin port (GOVERSBX_ADMIN_PORT)
#     SANDBOX_API_PORT=8787        daemon sandbox-facing port
#     SANDBOX=f3-admin-probe       throwaway sandbox name
#     DAEMON_HOST=host.docker.internal
#     KEEP_SANDBOX=1               don't remove the sandbox at the end
#
# It assumes a goversbx daemon is already running on the host (scripts/goversbx start). It does NOT
# grant anything through goversbx; it uses `sbx policy allow` directly to put the admin port on the
# sandbox's allow list, which is exactly the precondition F3 is about.
set -u

SBX="${SBX:-sbx}"
ADMIN_PORT="${ADMIN_PORT:-8788}"
SANDBOX_API_PORT="${SANDBOX_API_PORT:-8787}"
SANDBOX="${SANDBOX:-f3-admin-probe}"
DAEMON_HOST="${DAEMON_HOST:-host.docker.internal}"
KEEP_SANDBOX="${KEEP_SANDBOX:-}"

say()  { printf 'f3: %s\n' "$*"; }
fail() { printf 'f3: FAIL: %s\n' "$*"; }
pass() { printf 'f3: PASS: %s\n' "$*"; }

command -v "$SBX" >/dev/null 2>&1 || { say "no '$SBX' on PATH"; exit 2; }
curl -s --max-time 3 "http://127.0.0.1:$ADMIN_PORT/api/health" | grep -q '"ok"' \
  || { say "daemon admin not healthy on 127.0.0.1:$ADMIN_PORT - start it with scripts/goversbx start"; exit 2; }

cleanup() { [ -n "$KEEP_SANDBOX" ] || "$SBX" rm "$SANDBOX" >/dev/null 2>&1; }
trap cleanup EXIT

say "creating throwaway sandbox '$SANDBOX'"
"$SBX" create --name "$SANDBOX" claude "$(pwd)" >/dev/null 2>&1 \
  || { say "sbx create failed"; exit 2; }

# The F3 precondition: a loopback host (the admin port) is on the sandbox's allow list. This is what
# a careless human approval of "localhost:8788" - or a `**` grant - would produce. We set it directly.
for target in "localhost:$ADMIN_PORT" "$DAEMON_HOST:$ADMIN_PORT"; do
  "$SBX" policy allow network "$target" --sandbox "$SANDBOX" >/dev/null 2>&1
done

probe() { # $1 = url to curl from inside the sandbox
  "$SBX" exec "$SANDBOX" -- curl -s --max-time 5 -o /dev/null -w '%{http_code}' "$1" 2>/dev/null
}

say "probing the admin API from inside the sandbox via $DAEMON_HOST:$ADMIN_PORT"
health="$("$SBX" exec "$SANDBOX" -- curl -s --max-time 5 "http://$DAEMON_HOST:$ADMIN_PORT/api/health" 2>/dev/null)"
code="$(probe "http://$DAEMON_HOST:$ADMIN_PORT/api/health")"
say "admin /api/health -> HTTP ${code:-<none>}, body: ${health:-<empty>}"

if printf '%s' "$health" | grep -q '"ok"'; then
  fail "the loopback admin API IS reachable from the sandbox once localhost:$ADMIN_PORT is allowed."
  say  "That means the 127.0.0.1 bind is NOT a boundary against a sandbox: the proxy re-originates"
  say  "to the host's loopback. A sandbox that gets any loopback host (or **) approved can then"
  say  "self-approve. Checking whether it can actually approve a request ..."
  # Create a pending request as this sandbox, then try to approve it from inside the sandbox.
  rid="$(curl -s "http://127.0.0.1:$ADMIN_PORT/api/requests" | tr ',' '\n' | grep -o '"id":"[0-9a-f-]*"' | head -1 | sed 's/.*"\([0-9a-f-]*\)"$/\1/')"
  if [ -n "$rid" ]; then
    acode="$(probe "http://$DAEMON_HOST:$ADMIN_PORT/api/requests/$rid/approve")"
    say "self-approve POST from inside the sandbox -> HTTP ${acode:-<none>} (200 = full bypass)"
  fi
  say "Fix: authenticate the admin API (a startup token the UI holds, the sandbox never does);"
  say "the goversbx-layer guard only stops the admin port being granted THROUGH goversbx."
  exit 1
else
  pass "the loopback admin API is NOT reachable from the sandbox even with localhost:$ADMIN_PORT allowed."
  say  "The proxy does not re-originate to the host's loopback for this path, so the 127.0.0.1 bind"
  say  "holds as a boundary. F3 drops to informational (defense-in-depth still worth it)."
  exit 0
fi
