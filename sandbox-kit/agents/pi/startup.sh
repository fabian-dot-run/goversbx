#!/bin/sh
# DRAFT - durable-startup command for the "goversbx" kit on sandboxes running the pi coding agent
# (earendil-works/pi >= 1.0, which supports MCP in core). Same caveat as agents/claude/startup.sh:
# the host-side kit-registration mechanism is not yet confirmed (docs/plan.md, open question 2).
# Run as the `agent` user. Assumes the kit is unpacked with the same layout as the packageKit zip
# (this script at agents/pi/, the shared skill at skill/).
#
# Two steps:
# 1. Add a "goversbx-network" entry to pi's user-level MCP config (~/.pi/agent/mcp.json), keeping
#    every other entry. The JSON is edited directly rather than via `pi mcp add`, so this works
#    without pi on PATH and without depending on its CLI flags (pi 1.2.0 does have --header and
#    --exposure; `pi mcp add ... --header ... --exposure direct --description ...` writes an entry
#    byte-identical to this one, verified). The user-level file is used on purpose: pi only reads
#    a project's .pi/mcp.json after the project is explicitly trusted. A malformed existing file
#    makes this fail loudly instead of being overwritten.
#    - The header value is the same hardcoded placeholder literal as for every other agent - the
#      sbx proxy rewrites it to the real sandbox name (docs/plan.md, "reactive registration"). It
#      MUST match the daemon's GOVERSBX_IDENTITY_PLACEHOLDER, and must never contain `${` or start
#      with `!`, because pi interpolates env vars and runs commands in header values.
#    - "exposure": "direct" declares the two tools to the model like built-ins. pi's default
#      ("codemode") would only make them callable from codemode scripts, not visible as tools.
# 2. Install the shared skill into ~/.agents/skills/, the Agent Skills location pi scans.
set -e
DAEMON_HOST="${GOVERSBX_DAEMON_HOST:-daemon.internal}"
DAEMON_PORT="${GOVERSBX_DAEMON_PORT:-8787}"
SANDBOX_IDENTITY_PLACEHOLDER="goversbx-sandbox-identity-placeholder-v1"
PI_MCP_CONFIG="${PI_MCP_CONFIG:-$HOME/.pi/agent/mcp.json}"
SKILLS_DIR="${AGENTS_SKILLS_DIR:-$HOME/.agents/skills}"
KIT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"

mkdir -p "$(dirname "$PI_MCP_CONFIG")"
GOVERSBX_URL="http://${DAEMON_HOST}:${DAEMON_PORT}/mcp" \
GOVERSBX_PLACEHOLDER="$SANDBOX_IDENTITY_PLACEHOLDER" \
PI_MCP_CONFIG="$PI_MCP_CONFIG" \
node -e '
const fs = require("fs");
const path = process.env.PI_MCP_CONFIG;
const config = fs.existsSync(path) ? JSON.parse(fs.readFileSync(path, "utf8")) : {};
config.mcpServers = config.mcpServers || {};
config.mcpServers["goversbx-network"] = {
  url: process.env.GOVERSBX_URL,
  headers: { "X-Goversbx-Sandbox-Name": process.env.GOVERSBX_PLACEHOLDER },
  exposure: "direct",
  description: "Request outbound network access for this sandbox (human-approved)"
};
fs.writeFileSync(path, JSON.stringify(config, null, 2) + "\n");
'

mkdir -p "$SKILLS_DIR"
rm -rf "$SKILLS_DIR/goversbx-network-access"
cp -R "$KIT_DIR/skill/goversbx-network-access" "$SKILLS_DIR/"
