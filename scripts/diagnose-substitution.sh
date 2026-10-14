#!/usr/bin/env bash
# Diagnoses how the sbx proxy substitutes `sbx secret set-custom` placeholders, to find out why the
# daemon kept receiving the raw placeholder in scripts/e2e-claude.sh. Run ON THE HOST:
#
#   scripts/diagnose-substitution.sh
#
# Creates one throwaway sandbox and a header-echo server on the host (scripts/testing/HeaderEcho.java),
# registers one sandbox-scoped custom secret per `--host` variant, each with its own placeholder,
# then sends ONE plain-HTTP request from the sandbox through the proxy carrying all placeholders and
# reports which ones arrived substituted. Plus an HTTPS sanity check against httpbin.org, an
# Authorization-header variant, and a plain-HTTP POST with curl and with Node (what Claude Code uses)
# before and after registration - Claude saw "Error POSTing to endpoint: EOF" once secrets existed.
# Paste the whole output back. Needs: sbx, java 21+, curl, jq. bash 3.2 compatible.
#
# Knobs: SBX=sbx, ECHO_PORT=8799, SANDBOX_NAME=goversbx-diag-<ts>, SANDBOX_HOST=host.docker.internal,
#        HTTPS_PROBE_HOST=httpbin.org, KEEP_SANDBOX=1

set -u
SBX="${SBX:-sbx}"
PORT="${ECHO_PORT:-8799}"
SB="${SANDBOX_NAME:-goversbx-diag-$(date +%Y%m%d%H%M%S)}"
SANDBOX_HOST="${SANDBOX_HOST:-host.docker.internal}"
HTTPS_HOST="${HTTPS_PROBE_HOST:-httpbin.org}"
REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUN_DIR="$HOME/.cache/goversbx-e2e/$SB"
ECHO_URL="http://$SANDBOX_HOST:$PORT"

# label|--host value - plain-HTTP variants, all checked in one request to the echo server
VARIANTS="v1|$SANDBOX_HOST
v2|localhost
v3|$SANDBOX_HOST:$PORT
v4|localhost:$PORT
v5|*.docker.internal
v6|**"

ECHO_PID=""
CREATED=0
PLACEHOLDERS=""
mkdir -p "$RUN_DIR"

say() { printf '%s\n' "$*"; }
hdr() { printf '\n== %s\n' "$*"; }

cleanup() {
  hdr "Cleanup"
  for p in $PLACEHOLDERS; do
    "$SBX" secret rm --sandbox "$SB" --placeholder "$p" --force >/dev/null 2>&1
  done
  if [ "$CREATED" = 1 ]; then
    if [ "${KEEP_SANDBOX:-0}" = 1 ]; then say "kept sandbox $SB"; else
      "$SBX" rm --force "$SB" >/dev/null 2>&1 && say "removed sandbox $SB" || say "remove manually: $SBX rm --force $SB"
    fi
  fi
  [ -n "$ECHO_PID" ] && kill "$ECHO_PID" 2>/dev/null && say "stopped echo server (log: $RUN_DIR/echo.log)"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

in_sb() { "$SBX" exec -e "ECHO_URL=$ECHO_URL" "$SB" sh -c "$1" 2>&1; }

posts() { # $1 = label
  say "  curl POST: $(in_sb 'curl -sS -o /dev/null -w "HTTP %{http_code}" --max-time 15 -X POST -d x "$ECHO_URL/post"')"
  say "  node POST: $(in_sb 'command -v node >/dev/null || { echo "node not installed"; exit 0; }
    node -e "fetch(process.env.ECHO_URL + \"/post\", {method: \"POST\", body: \"x\"})
      .then(r => console.log(\"HTTP \" + r.status))
      .catch(e => console.log(\"error: \" + (e.cause ? (e.cause.code || e.cause.message) : e.message)))"')"
  say "  (NODE_USE_ENV_PROXY in sandbox: $(in_sb 'echo ${NODE_USE_ENV_PROXY:-<unset>}'))"
}

hdr "Setup"
for t in "$SBX" java curl jq; do command -v "$t" >/dev/null 2>&1 || { say "missing: $t"; exit 1; }; done
say "sbx version: $("$SBX" version 2>/dev/null | head -1 || echo '?')"
java "$REPO_DIR/scripts/testing/HeaderEcho.java" "$PORT" >"$RUN_DIR/echo.log" 2>&1 &
ECHO_PID=$!
for _ in $(seq 1 20); do curl -s -o /dev/null "http://127.0.0.1:$PORT/" && break; sleep 1; done
curl -s -o /dev/null "http://127.0.0.1:$PORT/" || { say "echo server did not start:"; cat "$RUN_DIR/echo.log"; exit 1; }
say "echo server on host port $PORT"
"$SBX" create --name "$SB" claude >"$RUN_DIR/create.log" 2>&1 || { cat "$RUN_DIR/create.log"; exit 1; }
CREATED=1
say "sandbox $SB created"
say "policy localhost:$PORT: $("$SBX" policy allow network "localhost:$PORT" --sandbox "$SB" 2>&1 | tail -1)"
say "policy $HTTPS_HOST: $("$SBX" policy allow network "$HTTPS_HOST" --sandbox "$SB" 2>&1 | tail -1)"
say "reach echo from sandbox: $(in_sb 'curl -sS -o /dev/null -w "HTTP %{http_code}" --max-time 10 "$ECHO_URL/ping"')"
say "proxy env in sandbox: $(in_sb 'echo "HTTP_PROXY=${HTTP_PROXY:-<unset>} NO_PROXY=${NO_PROXY:-<unset>}"')"

hdr "POST before any custom secret exists"
posts before

hdr "Register one sandbox-scoped custom secret per --host variant"
CURL_HEADERS=""
while IFS='|' read -r label host; do
  p="gvdiag-$label-$RANDOM$RANDOM"
  PLACEHOLDERS="$PLACEHOLDERS $p"
  eval "PH_$label=\$p"
  out="$("$SBX" secret set-custom --sandbox "$SB" --host "$host" --env "GOVERSBX_DIAG_$label" \
    --placeholder "$p" --value "SUBSTITUTED-$label" 2>&1)" && status=ok || status="FAILED: $out"
  say "  $label --host $host -> $status"
  CURL_HEADERS="$CURL_HEADERS -H 'X-Diag-$label: $p'"
done <<EOF
$VARIANTS
EOF
pH="gvdiag-https-$RANDOM$RANDOM"; PLACEHOLDERS="$PLACEHOLDERS $pH"
out="$("$SBX" secret set-custom --sandbox "$SB" --host "$HTTPS_HOST" --env GOVERSBX_DIAG_https \
  --placeholder "$pH" --value "SUBSTITUTED-https" 2>&1)" && status=ok || status="FAILED: $out"
say "  https --host $HTTPS_HOST -> $status"
say "env in sandbox (placeholders visible?): $(in_sb 'env | grep GOVERSBX_DIAG_ | sort | tr "\n" " "')"
sleep 3

hdr "Plain HTTP through the proxy: which placeholders arrived substituted?"
# Authorization carries v6's placeholder ("**" should match any host): if X-Diag-v6 stays raw but
# Authorization is substituted, only Authorization is processed.
resp="$(in_sb "curl -sS --max-time 15 \"\$ECHO_URL/diag\" $CURL_HEADERS -H 'Authorization: Bearer $PH_v6'")"
echo "$resp" > "$RUN_DIR/plain-http.json"
while IFS='|' read -r label host; do
  got="$(echo "$resp" | jq -r --arg h "x-diag-$label" '.headers[$h] // "<missing>"' 2>/dev/null)"
  case "$got" in
    "SUBSTITUTED-$label") verdict="SUBSTITUTED" ;;
    gvdiag-*) verdict="raw placeholder" ;;
    *) verdict="?? ($got)" ;;
  esac
  printf '  %-4s --host %-28s %s\n' "$label" "$host" "$verdict"
done <<EOF
$VARIANTS
EOF
auth="$(echo "$resp" | jq -r '.headers.authorization // "<missing>"' 2>/dev/null)"
say "  Authorization: Bearer <v6 placeholder> arrived as: $auth"
[ -z "$(echo "$resp" | jq -r '.method' 2>/dev/null)" ] && say "  (no JSON from echo server - raw response: $resp)"

hdr "HTTPS sanity check: https://$HTTPS_HOST/headers"
hresp="$(in_sb "curl -sS --max-time 20 https://$HTTPS_HOST/headers -H 'X-Diag-Https: $pH'")"
echo "$hresp" > "$RUN_DIR/https.json"
if echo "$hresp" | grep -q "SUBSTITUTED-https"; then say "  X-Diag-Https: SUBSTITUTED"
elif echo "$hresp" | grep -q "$pH"; then say "  X-Diag-Https: raw placeholder"
else say "  inconclusive - response: $(echo "$hresp" | head -c 300)"; fi

hdr "POST after custom secrets exist"
posts after

hdr "sbx secret ls (raw)"
"$SBX" secret ls 2>&1 | grep -i -E "diag|SCOPE|NAME|$SB" | head -20
say "--- with --sandbox:"
"$SBX" secret ls --sandbox "$SB" 2>&1 | head -20

say
say "Full responses: $RUN_DIR/plain-http.json, $RUN_DIR/https.json; echo server log: $RUN_DIR/echo.log"
