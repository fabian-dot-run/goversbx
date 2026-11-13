#!/usr/bin/env bash
# Launcher tests (test level `launcher`; spec 0003 slice 3): drives scripts/goversbx `setup`, `init`,
# `run` and the pass-through against scripts/testing/fake-sbx and the real daemon jar in dry-run.
# One daemon on free ports, a temp state dir, a temp HOME (so ~/.sbxenv.yaml is a test file) and a
# temp config dir; nothing on the machine is touched. A `curl` shim on PATH records every call the
# launcher makes to the daemon (and puts the /api/sandboxes/ ones into fake-sbx's calls.log, for
# order). Part of `./gradlew check` (:daemon:launcherTest), runnable alone after
# `./gradlew :daemon:shadowJar`:
#
#   scripts/testing/test-launcher.sh
#
# bash 3.2 compatible (macOS), needs curl and java.
set -u
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
# macOS's $TMPDIR ends in "/"; a doubled slash would make the paths printed here differ from the
# normalized ones the daemon prints (first seen on the release runner).
tmp_base="${TMPDIR:-/tmp}"; tmp_base="${tmp_base%/}"
TMP="$(mktemp -d "$tmp_base/goversbx-launcher-test.XXXXXX")"
export FAKE_SBX_STATE="$TMP/sbx" SBX="$REPO/scripts/testing/fake-sbx"
export GOVERSBX_STATE_DIR="$TMP/state" XDG_CONFIG_HOME="$TMP/config" GOVERSBX_SBX_DRY_RUN=true
export GOVERSBX_AUTO_OPEN_DASHBOARD=false
export GOVERSBX_SANDBOX_API_PORT=$((20000 + RANDOM % 20000)) GOVERSBX_ADMIN_PORT=$((40001 + RANDOM % 20000))
export HOME="$TMP/home"
mkdir -p "$HOME"
L="$REPO/scripts/goversbx"
KIT="$REPO/sandbox-kit/kits/goversbx"
DEFAULTS="$HOME/.sbxenv.yaml"
ADMIN="http://127.0.0.1:$GOVERSBX_ADMIN_PORT"
CALLS="$FAKE_SBX_STATE/calls.log"
failures=0

cleanup() { "$L" stop >/dev/null 2>&1; rm -rf "$TMP"; }
trap cleanup EXIT

pass() { printf 'PASS  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n      %s\n' "$1" "$2"; failures=$((failures + 1)); }

workspace() { # $1 = dir name; prints the path of a new, empty project directory
  local ws="$TMP/ws/$1"
  mkdir -p "$ws"
  echo "$ws"
}

# The curl shim: logs its arguments, notes daemon calls about a sandbox in the sbx call log, then
# runs the real curl.
export REAL_CURL CURL_LOG="$TMP/curl.log"
REAL_CURL="$(command -v curl)" || { echo "curl not found"; exit 1; }
mkdir -p "$TMP/bin" "$FAKE_SBX_STATE"
cat > "$TMP/bin/curl" <<'SHIM'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$CURL_LOG"
for a in "$@"; do
  case "$a" in */api/sandboxes/*) printf 'curl %s\n' "${a#*/api/}" >> "$FAKE_SBX_STATE/calls.log" ;; esac
done
exec "$REAL_CURL" "$@"
SHIM
chmod +x "$TMP/bin/curl"
PATH="$TMP/bin:$PATH"
: > "$CALLS"; : > "$CURL_LOG"

line_of() { grep -n -x -F -- "$1" "$CALLS" | head -n 1 | cut -d: -f1; }
count_of() { grep -c -x -F -- "$1" "$CALLS"; }
registrations_of() { count_of "curl sandboxes/$1/register"; }
calls() { tr '\n' '|' < "$CALLS"; }
daemon_up() { "$REAL_CURL" -s --max-time 2 "$ADMIN/api/health" 2>/dev/null | grep -q '"ok"'; }
port_arg() { grep -c "daemonPort: \"$GOVERSBX_SANDBOX_API_PORT\"" "$DEFAULTS"; }

# --- before any daemon runs -------------------------------------------------------------------

# S11: no default agent and no terminal - exit 3, the command to run, no file.
out="$("$L" setup </dev/null 2>&1)"; rc=$?
if [ "$rc" -eq 3 ] && printf '%s' "$out" | grep -q -- "goversbx setup --agent <name>" && [ ! -e "$DEFAULTS" ]; then
  pass "S11 setup without an agent and without a terminal: exit 3, nothing written"
else fail "S11 setup needs an agent" "rc=$rc out=$out"; fi

# S16: the old form and unknown flags are refused before anything starts.
ws="$(workspace sixteen)"; : > "$CALLS"
out1="$(cd "$ws" && "$L" run claude "$ws" 2>&1)"; rc1=$?
out2="$(cd "$ws" && "$L" run --detach 2>&1)"; rc2=$?
if [ "$rc1" -eq 1 ] && [ "$rc2" -eq 1 ] && printf '%s' "$out1" | grep -q "takes no agent or path any more" \
   && printf '%s' "$out2" | grep -q "goversbx env run" && [ "$(printf '%s\n' "$out1" | wc -l)" -eq 1 ] \
   && [ ! -s "$CALLS" ] && ! daemon_up && [ ! -e "$ws/sbxenv.yaml" ]; then
  pass "S16 'run <agent> <path>' and unknown flags: one line, exit 1, no sbx call, no daemon"
else fail "S16 old form refused" "rc1=$rc1 rc2=$rc2 out1=$out1 out2=$out2 calls=$(calls)"; fi

# S21: everything else is sbx's - same arguments, sbx's exit status, no daemon.
: > "$CALLS"
"$L" ls --format json >/dev/null 2>&1; rc_ls=$?
"$L" policy log >/dev/null 2>&1
(cd "$(workspace twentyone)" && printf 'agent: shell\n' > sbxenv.yaml && "$L" env rm --force >/dev/null 2>&1)
"$L" sbx stop x >/dev/null 2>&1
"$L" nonsense-verb a b >/dev/null 2>&1; rc_bad=$?
out="$("$L" stop x 2>&1)"; rc_stop=$?
if [ "$rc_ls" -eq 0 ] && [ "$rc_bad" -eq 1 ] && [ "$(calls)" = "ls --format json|policy log|env rm --force|stop x|nonsense-verb a b|" ] \
   && [ "$rc_stop" -eq 1 ] && printf '%s' "$out" | grep -q "goversbx sbx stop x" && ! daemon_up; then
  pass "S21 pass-through: sbx gets the arguments unchanged, its status comes back, no daemon"
else fail "S21 pass-through" "rc_ls=$rc_ls rc_bad=$rc_bad rc_stop=$rc_stop calls=$(calls) out=$out"; fi

# sbx too old: one line, exit 1.
out="$(FAKE_SBX_VERSION=0.49.2 "$L" setup --agent claude 2>&1)"; rc=$?
if [ "$rc" -eq 1 ] && printf '%s' "$out" | grep -q "sbx 0.51 or newer is needed, found 0.49" && [ ! -e "$DEFAULTS" ]; then
  pass "setup refuses an sbx older than 0.51"
else fail "old sbx" "rc=$rc out=$out"; fi

# S1 (through the launcher): setup --agent writes the user defaults; a second run says nothing.
out="$("$L" setup --agent claude 2>&1)"; rc=$?
again="$("$L" setup 2>&1)"; rc_again=$?
if [ "$rc" -eq 0 ] && grep -q '^agent: claude$' "$DEFAULTS" && grep -q -F -- "- source: $KIT" "$DEFAULTS" \
   && [ "$(port_arg)" -eq 1 ] && printf '%s' "$out" | grep -q "~/.sbxenv.yaml: created" \
   && [ "$rc_again" -eq 0 ] && [ -z "$again" ]; then
  pass "setup --agent creates ~/.sbxenv.yaml with agent, kit and daemonPort; a second run is silent"
else fail "setup creates the defaults" "rc=$rc out=$out again=$again file=$(cat "$DEFAULTS" 2>/dev/null)"; fi

# S20: init.
ws="$(workspace twenty)"
out="$(cd "$ws" && "$L" init 2>&1)"
expected='schemaVersion: "1"
workspace: .
# kits:               # project mixins; goversbx itself comes from ~/.sbxenv.yaml
#   - docker.io/sbx/playwright:1.61.1'
plain="$(cat "$ws/sbxenv.yaml")"
out_again="$(cd "$ws" && "$L" init 2>&1)"; rc_again=$?
out_agent="$(cd "$ws" && "$L" init --agent codex 2>&1)"; rc_agent=$?
ws_b="$(workspace twenty-b)"; (cd "$ws_b" && "$L" init --agent codex >/dev/null 2>&1)
ws_c="$(workspace twenty-c)"; (cd "$ws_c" && "$L" init --agent claude >/dev/null 2>&1)
if [ "$plain" = "$expected" ] && [ "$(cat "$ws/sbxenv.yaml")" = "$expected" ] && [ "$rc_again" -eq 0 ] \
   && printf '%s' "$out_again" | grep -q "left as it is" && [ "$rc_agent" -eq 1 ] \
   && [ "$(sed -n 2p "$ws_b/sbxenv.yaml")" = "agent: codex" ] && ! grep -q '^agent:' "$ws_c/sbxenv.yaml" && ! daemon_up; then
  pass "S20 init: scaffold, agent line only when it differs from the default, an existing file is left alone"
else fail "S20 init" "plain=$plain out=$out again=$out_again rc_agent=$rc_agent b=$(cat "$ws_b/sbxenv.yaml")"; fi

# --- with the daemon ----------------------------------------------------------------------------

"$L" start >/dev/null 2>&1 || { echo "daemon did not start"; tail -20 "$GOVERSBX_STATE_DIR/daemon.log"; exit 1; }
requests() { "$REAL_CURL" -s -H "Authorization: Bearer $(cat "$GOVERSBX_STATE_DIR/admin-token")" "$ADMIN/api/requests"; }

# S12: empty directory - scaffold, then plan -> create -> register -> run, never with a path.
ws="$(workspace twelve)"; : > "$CALLS"
out="$(cd "$ws" && "$L" run 2>&1)"; rc=$?
p="$(line_of "env plan")"; c="$(line_of "env create")"; g="$(line_of "curl sandboxes/claude-twelve/register")"; r="$(line_of "env run")"
if [ "$rc" -eq 0 ] && [ "$(cat "$ws/sbxenv.yaml")" = "$expected" ] && [ -n "$p" ] && [ -n "$c" ] && [ -n "$g" ] && [ -n "$r" ] \
   && [ "$p" -lt "$c" ] && [ "$c" -lt "$g" ] && [ "$g" -lt "$r" ] && ! grep -q "^env .*$TMP" "$CALLS" \
   && grep -q -- 'secret set-custom --sandbox claude-twelve ' "$GOVERSBX_STATE_DIR/daemon.log" \
   && printf '%s' "$out" | grep -q "wrote sbxenv.yaml" && [ "$(requests)" = "[]" ]; then
  pass "S12 new project: scaffold, env plan, env create, register, env run - no path argument"
else fail "S12 new project" "rc=$rc p=$p c=$c g=$g r=$r calls=$(calls) out=$out"; fi

# S14: the sandbox exists - no create, nothing asked of the daemon, env run once.
: > "$CALLS"
out="$(cd "$ws" && "$L" run 2>&1)"; rc=$?
if [ "$rc" -eq 0 ] && [ "$(count_of "env create")" -eq 0 ] && [ "$(registrations_of claude-twelve)" -eq 0 ] \
   && [ "$(count_of "env run")" -eq 1 ]; then
  pass "S14 existing sandbox: attached as-is, no create, nothing asked of the daemon"
else fail "S14 existing sandbox" "rc=$rc calls=$(calls) out=$out"; fi

# S13: an existing sbxenv.yaml is byte-identical afterwards (and decides agent and name).
ws="$(workspace thirteen)"; : > "$CALLS"
printf '# mine\nschemaVersion: "1"\nagent: shell\nname: my-own\nworkspace: .\nenv:\n  A: b\n' > "$ws/sbxenv.yaml"
cp "$ws/sbxenv.yaml" "$TMP/thirteen.before"
out="$(cd "$ws" && "$L" run 2>&1)"
if cmp -s "$ws/sbxenv.yaml" "$TMP/thirteen.before" && [ "$(registrations_of my-own)" -eq 1 ] \
   && ! printf '%s' "$out" | grep -q "wrote sbxenv.yaml"; then
  pass "S13 an existing sbxenv.yaml is left byte-identical; its name is the one registered"
else fail "S13 existing project file" "out=$out calls=$(calls)"; fi

# S15: --name, --force and agent arguments reach the right sbx calls.
ws="$(workspace fifteen)"; : > "$CALLS"
out="$(cd "$ws" && "$L" run --name n7 --force -- --resume 2>&1)"
if [ "$(calls)" = "version|env plan --name n7|ls --format json|env create --name n7 --force|curl sandboxes/n7/register|env run --name n7 --force -- --resume|" ]; then
  pass "S15 --name, --force and '-- ARGS' are passed to plan, create and run"
else fail "S15 flags" "calls=$(calls) out=$out"; fi

# --agent: reaches the scaffold; refused with an existing file before anything runs.
ws="$(workspace agent)"; : > "$CALLS"
(cd "$ws" && "$L" run --agent codex >/dev/null 2>&1)
out="$(cd "$ws" && "$L" run --agent codex 2>&1)"; rc=$?
if [ "$(sed -n 2p "$ws/sbxenv.yaml")" = "agent: codex" ] && [ "$(registrations_of codex-agent)" -eq 1 ] \
   && [ "$rc" -eq 1 ] && printf '%s' "$out" | grep -q "set agent: in sbxenv.yaml" && [ "$(count_of "env run")" -eq 1 ]; then
  pass "run --agent writes it into the scaffold; with an existing sbxenv.yaml it is refused"
else fail "run --agent" "rc=$rc out=$out file=$(cat "$ws/sbxenv.yaml") calls=$(calls)"; fi

# S17: create fails - its status and output, no register, no run.
ws="$(workspace seventeen)"; : > "$CALLS"
out="$(cd "$ws" && FAKE_SBX_CREATE_FAIL=1 "$L" run 2>&1)"; rc=$?
out_tty="$(cd "$ws" && FAKE_SBX_NEEDS_APPROVAL=1 "$L" run 2>&1)"; rc_tty=$?
if [ "$rc" -eq 3 ] && printf '%s' "$out" | grep -q "fake create failure" && [ "$(count_of "env run")" -eq 0 ] \
   && [ "$(registrations_of claude-seventeen)" -eq 0 ] && ! grep -q -- '--sandbox claude-seventeen ' "$GOVERSBX_STATE_DIR/daemon.log" \
   && [ "$rc_tty" -eq 1 ] && printf '%s' "$out_tty" | grep -q -- "--force"; then
  pass "S17 create fails or is not approved: status and message passed on, nothing else runs"
else fail "S17 create fails" "rc=$rc out=$out rc_tty=$rc_tty out_tty=$out_tty calls=$(calls)"; fi

# S18: daemon trouble never blocks (wrong token).
ws="$(workspace eighteen)"; : > "$CALLS"
out="$(cd "$ws" && GOVERSBX_ADMIN_TOKEN=wrong "$L" run 2>&1)"
if [ "$(printf '%s' "$out" | grep -c WARNING)" -eq 1 ] && [ "$(count_of "env run")" -eq 1 ]; then
  pass "S18 registration fails: one warning, attached anyway"
else fail "S18 daemon trouble" "$out"; fi

# S19: a stale kit path is corrected before the plan; a duplicate goes; the rest of the file stays.
ws="$(workspace nineteen)"; : > "$CALLS"
printf '# my defaults\nschemaVersion: "1"\nagent: claude   # default\nworkspace: ${{ env.projectDir }}\nkits:\n  - docker.io/sbx/playwright:1.61.1\n  - source: /old/place/share/goversbx/kits/goversbx\n  - /older/kits/goversbx\n' > "$DEFAULTS"
out="$(cd "$ws" && "$L" run 2>&1)"
wanted="$(printf '# my defaults\nschemaVersion: "1"\nagent: claude   # default\nworkspace: ${{ env.projectDir }}\nkits:\n  - docker.io/sbx/playwright:1.61.1\n  - source: %s\n    args:\n      daemonPort: "%s"' "$KIT" "$GOVERSBX_SANDBOX_API_PORT")"
if [ "$(cat "$DEFAULTS")" = "$wanted" ] && printf '%s' "$out" | grep -q "~/.sbxenv.yaml: goversbx kit path corrected" \
   && grep -q "/old/place" "$DEFAULTS.goversbx-bak" && [ "$(line_of "version")" -lt "$(line_of "env plan")" ] \
   && [ "$(count_of "env run")" -eq 1 ]; then
  pass "S19 a stale kit path is corrected (with a backup) before env plan; everything else is untouched"
else fail "S19 stale path" "out=$out file=$(cat "$DEFAULTS")"; fi

# The project file lists the goversbx kit too: one warning, the run continues.
ws="$(workspace dup)"; : > "$CALLS"
printf 'schemaVersion: "1"\nworkspace: .\nkits:\n  - source: /somewhere/kits/goversbx\n' > "$ws/sbxenv.yaml"
out="$(cd "$ws" && "$L" run 2>&1)"
if [ "$(printf '%s' "$out" | grep -c "lists the goversbx kit too")" -eq 1 ] && [ "$(count_of "env run")" -eq 1 ]; then
  pass "a goversbx kit in the project file is one warning, not a failure"
else fail "duplicate kit warning" "$out"; fi

# A plan the launcher cannot read: no create of its own, one warning, env run does the rest.
ws="$(workspace noname)"; : > "$CALLS"
out="$(cd "$ws" && FAKE_SBX_PLAN_NO_NAME=1 "$L" run 2>&1)"; rc=$?
if [ "$rc" -eq 0 ] && [ "$(printf '%s' "$out" | grep -c WARNING)" -eq 1 ] && [ "$(count_of "env create")" -eq 0 ] \
   && [ "$(count_of "env run")" -eq 1 ] && ! grep -q "^curl sandboxes/" "$CALLS"; then
  pass "an unreadable plan: one warning, no pre-registration, env run still runs"
else fail "unreadable plan" "rc=$rc out=$out calls=$(calls)"; fi

# The plan fails: sbx's message and status, nothing else.
ws="$(workspace planfail)"; : > "$CALLS"
out="$(cd "$ws" && FAKE_SBX_PLAN_FAIL=1 "$L" run 2>&1)"; rc=$?
if [ "$rc" -eq 1 ] && printf '%s' "$out" | grep -q "fake plan failure" && [ "$(count_of "env run")" -eq 0 ] \
   && [ "$(count_of "env create")" -eq 0 ]; then
  pass "a failing plan ends the run with sbx's message and status, before create"
else fail "plan fails" "rc=$rc out=$out calls=$(calls)"; fi

# S22: status reports the state of the user defaults.
ok="$("$L" status 2>&1 | sed -n 3p)"
grep -v "kits/goversbx" "$DEFAULTS" | grep -v -e "args:" -e "daemonPort" > "$TMP/d" && cp "$TMP/d" "$DEFAULTS"
drift="$("$L" status 2>&1 | sed -n 3p)"
if [ "$ok" = "user defaults: ok" ] && [ "$drift" = "user defaults: 1 finding(s) - run goversbx setup" ]; then
  pass "S22 status shows 'user defaults: ok' and the finding count after the entry was removed"
else fail "S22 status" "ok=$ok drift=$drift"; fi

# upgrade is reserved; help shows goversbx's verbs, then sbx's.
out="$("$L" upgrade 2>&1)"; rc=$?
help="$("$L" help 2>&1)"
if [ "$rc" -eq 1 ] && printf '%s' "$out" | grep -q "kit add <sandbox> $KIT" \
   && printf '%s' "$help" | grep -q "goversbx sbx ARGS" && printf '%s' "$help" | grep -q "fake sbx help"; then
  pass "upgrade is reserved and names 'sbx kit add'; help prints goversbx's verbs and then sbx's"
else fail "upgrade/help" "rc=$rc out=$out"; fi

if [ "$failures" -gt 0 ]; then
  echo "$failures launcher test(s) failed; daemon log tail:"; tail -20 "$GOVERSBX_STATE_DIR/daemon.log"; exit 1
fi
echo "all launcher tests passed"
