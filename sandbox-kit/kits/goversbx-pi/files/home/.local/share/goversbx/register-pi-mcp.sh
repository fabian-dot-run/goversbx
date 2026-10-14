#!/bin/sh
# setup.startup command of the goversbx-pi kit (sandbox-kit/kits/goversbx-pi/spec.yaml), for the pi
# coding agent (earendil-works/pi >= 1.0, which supports MCP in core). Runs as the `agent` user on
# every sandbox start; GOVERSBX_DAEMON_HOST/_PORT come from the kit's environment (kit args).
# The skill itself is placed by the kit's files/home/.agents/skills/ (the Agent Skills location pi
# scans), so all this script does is:
#
# Add a "goversbx-network" entry to pi's user-level MCP config (~/.pi/agent/mcp.json), keeping
# every other entry. The JSON is edited directly rather than via `pi mcp add`, so this works
# without pi on PATH and without depending on its CLI flags (pi 1.2.0 does have --header and
# --exposure; `pi mcp add ... --header ... --exposure direct --description ...` writes an entry
# byte-identical to this one, verified). The user-level file is used on purpose: pi only reads
# a project's .pi/mcp.json after the project is explicitly trusted. A malformed existing file
# makes this fail loudly instead of being overwritten.
# - The header value is the same hardcoded placeholder literal as for every other agent - the
#   sbx proxy rewrites it to the real sandbox name (docs/plan.md, "reactive registration"). It
#   MUST match the daemon's GOVERSBX_IDENTITY_PLACEHOLDER, and must never contain `${` or start
#   with `!`, because pi interpolates env vars and runs commands in header values.
# - "exposure": "direct" declares the two tools to the model like built-ins. pi's default
#   ("codemode") would only make them callable from codemode scripts, not visible as tools.
# pi is a Node app; without node there is no pi to configure, so the script skips instead of failing.
set -e
DAEMON_HOST="${GOVERSBX_DAEMON_HOST:-host.docker.internal}"
DAEMON_PORT="${GOVERSBX_DAEMON_PORT:-8787}"
SANDBOX_IDENTITY_PLACEHOLDER="goversbx-sandbox-identity-placeholder-v1"
PI_MCP_CONFIG="${PI_MCP_CONFIG:-$HOME/.pi/agent/mcp.json}"

if ! command -v node >/dev/null 2>&1; then
  echo "goversbx: node not found - pi does not seem to be installed, skipping $PI_MCP_CONFIG" >&2
  exit 0
fi

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
