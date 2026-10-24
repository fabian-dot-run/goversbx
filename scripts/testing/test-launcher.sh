#!/usr/bin/env bash
# Launcher tests (spec 0001 slice 2, test level `launcher`): drives scripts/goversbx `run` against
# scripts/testing/fake-sbx and the real daemon jar in dry-run. One daemon on free ports, a temp state
# dir and config dir; nothing on the machine is touched. Part of `./gradlew check`
# (:daemon:launcherTest), runnable alone after `./gradlew :daemon:shadowJar`:
#
#   scripts/testing/test-launcher.sh
#
# bash 3.2 compatible (macOS), needs curl and java.
set -u
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/goversbx-launcher-test.XXXXXX")"
export FAKE_SBX_STATE="$TMP/sbx" SBX="$REPO/scripts/testing/fake-sbx"
export GOVERSBX_STATE_DIR="$TMP/state" XDG_CONFIG_HOME="$TMP/config" GOVERSBX_SBX_DRY_RUN=true
export GOVERSBX_AUTO_OPEN_DASHBOARD=false
export GOVERSBX_SANDBOX_API_PORT=$((20000 + RANDOM % 20000)) GOVERSBX_ADMIN_PORT=$((40001 + RANDOM % 20000))
L="$REPO/scripts/goversbx"
ADMIN="http://127.0.0.1:$GOVERSBX_ADMIN_PORT"
failures=0

cleanup() { "$L" stop >/dev/null 2>&1; rm -rf "$TMP"; }
trap cleanup EXIT

pass() { printf 'PASS  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n      %s\n' "$1" "$2"; failures=$((failures + 1)); }

workspace() { # $1 = dir name, $2.. = hosts (none: no project file); prints the path
  local ws="$TMP/ws/$1"; shift
  mkdir -p "$ws"
  if [ $# -gt 0 ]; then
    mkdir -p "$ws/.goversbx"
    { echo "version: 1"; echo "hosts:"; for h in "$@"; do echo "  - $h"; done; } > "$ws/.goversbx/network.yaml"
  fi
  echo "$ws"
}

requests() { curl -s -H "Authorization: Bearer $(cat "$GOVERSBX_STATE_DIR/admin-token")" "$ADMIN/api/requests"; }
pending_set_of() { # $1 = sandbox; prints the hosts JSON array of its pending host set, if any
  requests | tr '}' '\n' | grep "\"sandboxName\":\"$1\"" | grep '"origin":"PROJECT_FILE"' | grep '"status":"PENDING"' \
    | sed -n 's/.*"hosts":\(\[[^]]*\]\).*/\1/p' | head -n 1
}
approve_set_of() { # $1 = sandbox
  local id
  id="$(requests | tr '}' '\n' | grep "\"sandboxName\":\"$1\"" | grep '"origin":"PROJECT_FILE"' | grep '"status":"PENDING"' \
    | sed -n 's/.*"id":"\([^"]*\)".*/\1/p' | head -n 1)"
  curl -s -o /dev/null -X POST -H "Authorization: Bearer $(cat "$GOVERSBX_STATE_DIR/admin-token")" \
    -H 'Content-Type: application/json' -d '{"decidedBy":"launcher-test"}' "$ADMIN/api/requests/$id/approve"
}
line_of() { grep -n -F -- "$1" "$FAKE_SBX_STATE/calls.log" | head -n 1 | cut -d: -f1; }

"$L" start >/dev/null 2>&1 || { echo "daemon did not start"; tail -20 "$GOVERSBX_STATE_DIR/daemon.log"; exit 1; }

# 1 + 2: order create -> register -> attach; the set is pending when the session attaches.
ws="$(workspace one a.example b.example)"
out="$("$L" run --name n1 claude "$ws" 2>&1)"; rc=$?
c="$(line_of "create --kit")"; r="$(line_of "run n1")"; l="$(sed -n "$((c + 1)),\$p" "$FAKE_SBX_STATE/calls.log" | grep -n '^ls' | head -n 1 | cut -d: -f1)"
if [ "$rc" -eq 0 ] && [ -n "$c" ] && [ -n "$r" ] && [ -n "$l" ] && [ $((c + l)) -lt "$r" ] \
   && grep -q -- "--name n1 claude $ws" "$FAKE_SBX_STATE/calls.log"; then
  pass "1 create, then the daemon's registration (sbx ls), then attach"
else fail "1 order" "rc=$rc calls: $(tr '\n' '|' < "$FAKE_SBX_STATE/calls.log")"; fi
if [ "$(pending_set_of n1)" = '["a.example","b.example"]' ] \
   && printf '%s' "$out" | grep -q "goversbx: project host set (2 hosts from $ws/.goversbx/network.yaml) is waiting for approval"; then
  pass "2 the set is pending and announced before attaching"
else fail "2 set waiting" "set=$(pending_set_of n1) out=$out"; fi

# 3: invalid file - problem shown, no request, session still attaches.
ws="$(workspace bad)"; mkdir -p "$ws/.goversbx"; printf 'version: 1\nhost: [a.example]\n' > "$ws/.goversbx/network.yaml"
out="$("$L" run --name n3 claude "$ws" 2>&1)"
if printf '%s' "$out" | grep -q "$ws/.goversbx/network.yaml is invalid - no hosts requested" \
   && printf '%s' "$out" | grep -q "unknown key 'host'" && [ -z "$(pending_set_of n3)" ] && grep -q '^run n3$' "$FAKE_SBX_STATE/calls.log"; then
  pass "3 invalid file: problems printed, nothing requested, attached"
else fail "3 invalid file" "$out"; fi

# 4: no file - nothing printed about host sets.
ws="$(workspace none)"
out="$("$L" run --name n4 claude "$ws" 2>&1)"
if ! printf '%s' "$out" | grep -qi "host set\|WARNING" && grep -q '^run n4$' "$FAKE_SBX_STATE/calls.log"; then
  pass "4 no file: silent, attached"
else fail "4 no file" "$out"; fi

# 5: daemon trouble never blocks (wrong token).
ws="$(workspace five c.example)"
out="$(GOVERSBX_ADMIN_TOKEN=wrong "$L" run --name n5 claude "$ws" 2>&1)"
if [ "$(printf '%s' "$out" | grep -c WARNING)" -eq 1 ] && grep -q '^run n5$' "$FAKE_SBX_STATE/calls.log"; then
  pass "5 registration fails: one warning, attached anyway"
else fail "5 daemon trouble" "$out"; fi

# 6: create fails - its status and output, no register, no run.
ws="$(workspace six d.example)"
out="$(FAKE_SBX_CREATE_FAIL=1 "$L" run --name n6 claude "$ws" 2>&1)"; rc=$?
if [ "$rc" -eq 3 ] && printf '%s' "$out" | grep -q "fake create failure" && ! grep -q '^run n6$' "$FAKE_SBX_STATE/calls.log" \
   && ! grep -q -- '--sandbox n6' "$GOVERSBX_STATE_DIR/daemon.log"; then
  pass "6 create fails: exit status passed on, nothing else runs"
else fail "6 create fails" "rc=$rc out=$out"; fi

# 7: existing registered sandbox re-raises the new host; an unregistered one stays silent.
approve_set_of n1
printf '  - e.example\n' >> "$TMP/ws/one/.goversbx/network.yaml"
out="$("$L" run n1 2>&1)"
if [ "$(pending_set_of n1)" = '["e.example"]' ] && printf '%s' "$out" | grep -q "project host set (1 host from"; then
  pass "7a existing sandbox: only the new host is requested"
else fail "7a existing sandbox" "set=$(pending_set_of n1) out=$out"; fi
ws="$(workspace seven f.example)"; "$SBX" create --name n7 claude "$ws" >/dev/null
out="$("$L" run n7 2>&1)"
if ! printf '%s' "$out" | grep -qi "host set\|WARNING: the daemon"; then
  pass "7b existing unregistered sandbox: silent"
else fail "7b unregistered" "$out"; fi

# 8: no --name - the name comes from sbx create's output.
ws="$(workspace eight g.example)"
out="$("$L" run claude "$ws" 2>&1)"
if [ "$(pending_set_of claude-eight)" = '["g.example"]' ] && grep -q '^run claude-eight$' "$FAKE_SBX_STATE/calls.log"; then
  pass "8 no --name: name parsed from 'Created sandbox', registered and attached"
else fail "8 no --name" "set=$(pending_set_of claude-eight) out=$out"; fi

if [ "$failures" -gt 0 ]; then
  echo "$failures launcher test(s) failed; daemon log tail:"; tail -20 "$GOVERSBX_STATE_DIR/daemon.log"; exit 1
fi
echo "all launcher tests passed"
