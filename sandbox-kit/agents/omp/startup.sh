#!/bin/sh
# DRAFT - durable-startup command for the "goversbx" kit on sandboxes running oh-my-pi (omp,
# can1357/oh-my-pi). Same caveat as the other agents: the host-side kit-registration mechanism is
# not yet confirmed (docs/plan.md, open question 2). Run as the `agent` user. Assumes the kit is
# unpacked with the same layout as the packageKit zip (this script at agents/omp/, the shared
# skill at skill/).
#
# Facts from omp's own docs/mcp-config.md and docs/skills.md (+ src/discovery/builtin.ts):
# - User-level MCP servers live in <agentDir>/mcp.json, skills in <agentDir>/skills/<name>/SKILL.md.
#   <agentDir> is ~/.omp/agent by default, but profile-scoped: under a named profile
#   (OMP_PROFILE / PI_PROFILE) it is ~/.omp/profiles/<name>/agent and omp does NOT read the default
#   profile's files. PI_CODING_AGENT_DIR overrides the default-profile directory. PI_CONFIG_DIR
#   (moves the whole config root) is not handled here - set OMP_AGENT_DIR explicitly in that case.
# - Remote entries need "type": "http" plus url/headers. Header values are resolved before every
#   request: a value starting with `!` runs as a command, a value that is exactly the name of a set
#   env var is replaced by it, and `${VAR}` is expanded at discovery. The placeholder literal below
#   is none of these, so omp sends it verbatim - and it MUST match the daemon's
#   GOVERSBX_IDENTITY_PLACEHOLDER.
# - The ~/.agents/skills provider is opt-in for user-level skills in omp, so the skill goes into
#   omp's own native skills directory instead (loaded by default; requires a `description`).
# - omp ignores a malformed mcp.json with a warning; this script fails instead of overwriting it.
# - omp is a Bun app, so Node may not exist in an omp sandbox: the merge runs with node if present,
#   otherwise bun.
set -e
DAEMON_HOST="${GOVERSBX_DAEMON_HOST:-host.docker.internal}"
DAEMON_PORT="${GOVERSBX_DAEMON_PORT:-8787}"
SANDBOX_IDENTITY_PLACEHOLDER="goversbx-sandbox-identity-placeholder-v1"
KIT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"

PROFILE="${OMP_PROFILE:-${PI_PROFILE:-}}"
if [ -n "${OMP_AGENT_DIR:-}" ]; then
  AGENT_DIR="$OMP_AGENT_DIR"
elif [ -n "$PROFILE" ]; then
  AGENT_DIR="$HOME/.omp/profiles/$PROFILE/agent"
else
  AGENT_DIR="${PI_CODING_AGENT_DIR:-$HOME/.omp/agent}"
fi

if command -v node >/dev/null 2>&1; then
  JS_RUNTIME=node
elif command -v bun >/dev/null 2>&1; then
  JS_RUNTIME=bun
else
  echo "goversbx: neither node nor bun found, cannot update $AGENT_DIR/mcp.json" >&2
  exit 1
fi

mkdir -p "$AGENT_DIR"
GOVERSBX_URL="http://${DAEMON_HOST}:${DAEMON_PORT}/mcp" \
GOVERSBX_PLACEHOLDER="$SANDBOX_IDENTITY_PLACEHOLDER" \
OMP_MCP_CONFIG="$AGENT_DIR/mcp.json" \
"$JS_RUNTIME" -e '
const fs = require("fs");
const path = process.env.OMP_MCP_CONFIG;
const config = fs.existsSync(path)
  ? JSON.parse(fs.readFileSync(path, "utf8"))
  : { "$schema": "https://raw.githubusercontent.com/can1357/oh-my-pi/main/packages/coding-agent/src/config/mcp-schema.json" };
config.mcpServers = config.mcpServers || {};
config.mcpServers["goversbx-network"] = {
  type: "http",
  url: process.env.GOVERSBX_URL,
  headers: { "X-Goversbx-Sandbox-Name": process.env.GOVERSBX_PLACEHOLDER }
};
fs.writeFileSync(path, JSON.stringify(config, null, 2) + "\n");
'

mkdir -p "$AGENT_DIR/skills"
rm -rf "$AGENT_DIR/skills/goversbx-network-access"
cp -R "$KIT_DIR/skill/goversbx-network-access" "$AGENT_DIR/skills/"
