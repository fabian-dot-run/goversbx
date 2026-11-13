#!/bin/sh
# Install and startup hook of the goversbx kit (sandbox-kit/kits/goversbx/goversbx.yaml). Runs as
# the `agent` user; GOVERSBX_DAEMON_HOST/_PORT come from the kit args. For every agent it finds in
# this sandbox it registers the goversbx daemon's MCP endpoint and makes sure the
# goversbx-network-access skill is where that agent looks for skills. Add-only and idempotent: the
# startup run happens next to a live session, and again on every boot.
#
# The header value is a HARDCODED placeholder, the same for every sandbox and every agent. The sbx
# proxy rewrites it to the real sandbox name once the daemon has registered the sandbox via
# `sbx secret set-custom` (reactively, on the first unsubstituted call). It MUST match the daemon's
# GOVERSBX_IDENTITY_PLACEHOLDER, and it must stay a literal no agent interpolates (no `${`, `{env:`,
# leading `!`). The endpoint is registered in the agent itself and not through the sbx MCP gateway
# on purpose: gateway calls start on the host and bypass the proxy, so the daemon would see the raw
# placeholder from every sandbox (docs/specs/0003-sbx-env-native/epic.md, D8).
#
# An agent that is not installed is skipped silently. A config file that exists but is not valid
# JSON is never overwritten: that agent's step fails, the others still run, and the script exits 1.
DAEMON_HOST="${GOVERSBX_DAEMON_HOST:-host.docker.internal}"
DAEMON_PORT="${GOVERSBX_DAEMON_PORT:-8787}"
URL="http://${DAEMON_HOST}:${DAEMON_PORT}/mcp"
PLACEHOLDER="goversbx-sandbox-identity-placeholder-v1"
SKILL_NAME="goversbx-network-access"
SKILL_SRC="/usr/share/goversbx/skills/$SKILL_NAME"
export PATH="$HOME/.local/bin:$HOME/.claude/local:$HOME/.bun/bin:$PATH"

failed=""
say() { echo "goversbx: $*"; }
has() { command -v "$1" >/dev/null 2>&1; }

# Links the bundled skill into a skills directory unless something is already there - the runtime
# exposes it itself wherever the workload declares a skills directory (agent-skill@1).
link_skill() { # $1 = skills directory
  [ -d "$SKILL_SRC" ] || return 0
  [ -e "$1/$SKILL_NAME" ] && return 0
  mkdir -p "$1" && ln -s "$SKILL_SRC" "$1/$SKILL_NAME"
}

# Merges one entry into a JSON config with whatever JS runtime is there (pi is a Node app, omp a
# Bun app). $1 = file, $2 = top-level key, $3 = entry as JSON, $4 = JSON for a missing file.
merge_json_js() {
  empty="${4:-}"
  [ -n "$empty" ] || empty='{}'
  mkdir -p "$(dirname "$1")"
  GOVERSBX_FILE="$1" GOVERSBX_KEY="$2" GOVERSBX_ENTRY="$3" GOVERSBX_EMPTY="$empty" "$JS_RUNTIME" -e '
const fs = require("fs");
const path = process.env.GOVERSBX_FILE;
const config = JSON.parse(fs.existsSync(path) ? fs.readFileSync(path, "utf8") : process.env.GOVERSBX_EMPTY);
const key = process.env.GOVERSBX_KEY;
config[key] = config[key] || {};
config[key]["goversbx-network"] = JSON.parse(process.env.GOVERSBX_ENTRY);
fs.writeFileSync(path, JSON.stringify(config, null, 2) + "\n");
'
}

JS_RUNTIME=""
if has node; then JS_RUNTIME=node; elif has bun; then JS_RUNTIME=bun; fi

# --- Claude Code -----------------------------------------------------------------------------
register_claude() {
  # `claude mcp add` exits 1 when the entry already exists.
  claude mcp add goversbx-network "$URL" --transport http --scope user \
    --header "X-Goversbx-Sandbox-Name: $PLACEHOLDER" >/dev/null 2>&1 || true
  link_skill "$HOME/.claude/skills"
  # Always-loaded rule in Claude's user memory. Needed because the sandbox's own CLAUDE.md says
  # "Approval required -> ask the user to run sbx policy approval", while a kit's agent context is
  # only a pointer under "## Kits" (also with agent-context@2, sbx 0.51.0) and a skill only loads
  # when Claude picks it - observed on a real host: Claude followed the sbx text until told "use
  # goversbx". The block is wrapped in markers and written once per content, so a user's own
  # CLAUDE.md content is kept and a rerun changes nothing.
  f="$HOME/.claude/CLAUDE.md"
  mkdir -p "$HOME/.claude" && touch "$f" || return 1
  block="$(cat <<'GOVERSBX'
<!-- goversbx:start -->
## Blocked network access in this sandbox: use goversbx

This sandbox has the goversbx kit. When the sandbox proxy blocks a request - HTTP 403 with
"Approval required for <host>" or "Blocked by network policy ... no matching allow rule" - or
you need a host that is probably not allowed yet, do NOT ask the user to run
`sbx policy approval ...` or `sbx policy allow ...` themselves. Call `request_network_access`
on the `goversbx-network` MCP server instead (skill: `goversbx-network-access`): it asks the
human on the host, waits for the decision and applies it, then retry. For this case it
supersedes the "When a request is blocked" guidance in the sandbox's CLAUDE.md; fall back to
that guidance only if the tool is unavailable, or for "Blocked by org policy" / "Blocked by
local rule", which goversbx cannot lift.
<!-- goversbx:end -->
GOVERSBX
)"
  current="$(sed -n '/<!-- goversbx:start -->/,/<!-- goversbx:end -->/p' "$f")"
  if [ "$current" != "$block" ]; then
    sed -i '/<!-- goversbx:start -->/,/<!-- goversbx:end -->/d' "$f" && printf '%s\n' "$block" >> "$f"
  fi
}

# --- opencode --------------------------------------------------------------------------------
# Global config ~/.config/opencode/opencode.json, "mcp": { name: { type: "remote", url, headers } };
# skills in ~/.agents/skills. opencode is a standalone binary, so the merge uses jq.
register_opencode() {
  cfg="${OPENCODE_CONFIG:-$HOME/.config/opencode/opencode.json}"
  has jq || { say "jq not found - cannot merge $cfg"; return 1; }
  mkdir -p "$(dirname "$cfg")"
  [ -f "$cfg" ] || printf '{}' > "$cfg"
  tmp="$(mktemp)"
  if jq --arg url "$URL" --arg placeholder "$PLACEHOLDER" \
       '.mcp = (.mcp // {}) | .mcp["goversbx-network"] = {
          "type": "remote", "url": $url, "enabled": true,
          "headers": { "X-Goversbx-Sandbox-Name": $placeholder } }' "$cfg" > "$tmp"; then
    cmp -s "$tmp" "$cfg" && rm -f "$tmp" || mv "$tmp" "$cfg"
  else
    rm -f "$tmp"; say "$cfg is not valid JSON, not overwriting"; return 1
  fi
  link_skill "$HOME/.agents/skills"
}

# --- pi (earendil-works/pi >= 1.0) -----------------------------------------------------------
# User-level ~/.pi/agent/mcp.json (a project's .pi/mcp.json is only read once trusted).
# "exposure": "direct" declares the tools like built-ins; pi's default would hide them in codemode.
register_pi() {
  merge_json_js "${PI_MCP_CONFIG:-$HOME/.pi/agent/mcp.json}" mcpServers \
    "{\"url\":\"$URL\",\"headers\":{\"X-Goversbx-Sandbox-Name\":\"$PLACEHOLDER\"},\"exposure\":\"direct\",\"description\":\"Request outbound network access for this sandbox (human-approved)\"}" \
    || return 1
  link_skill "$HOME/.agents/skills"
}

# --- oh-my-pi (omp) --------------------------------------------------------------------------
# <agentDir>/mcp.json and <agentDir>/skills; <agentDir> is profile-scoped (OMP_PROFILE/PI_PROFILE),
# PI_CODING_AGENT_DIR overrides the default profile's directory, OMP_AGENT_DIR overrides all.
register_omp() {
  profile="${OMP_PROFILE:-${PI_PROFILE:-}}"
  if [ -n "${OMP_AGENT_DIR:-}" ]; then agent_dir="$OMP_AGENT_DIR"
  elif [ -n "$profile" ]; then agent_dir="$HOME/.omp/profiles/$profile/agent"
  else agent_dir="${PI_CODING_AGENT_DIR:-$HOME/.omp/agent}"; fi
  merge_json_js "$agent_dir/mcp.json" mcpServers \
    "{\"type\":\"http\",\"url\":\"$URL\",\"headers\":{\"X-Goversbx-Sandbox-Name\":\"$PLACEHOLDER\"}}" \
    '{"$schema":"https://raw.githubusercontent.com/can1357/oh-my-pi/main/packages/coding-agent/src/config/mcp-schema.json"}' \
    || return 1
  link_skill "$agent_dir/skills"
}

run() { # $1 = agent label, $2 = function
  if "$2"; then say "registered with $1 ($URL)"; else say "could not register with $1"; failed="$failed $1"; fi
}

has claude && run "Claude Code" register_claude
{ has opencode || [ -d "$HOME/.config/opencode" ]; } && run opencode register_opencode
if [ -n "$JS_RUNTIME" ]; then
  { has pi || [ -d "$HOME/.pi" ]; } && run pi register_pi
  { has omp || [ -d "$HOME/.omp" ]; } && run oh-my-pi register_omp
fi

[ -z "$failed" ] || { say "failed for:$failed"; exit 1; }
exit 0
