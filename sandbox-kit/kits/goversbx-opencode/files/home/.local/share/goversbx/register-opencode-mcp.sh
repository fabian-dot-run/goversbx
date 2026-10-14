#!/bin/sh
# setup.install/startup command of the goversbx-opencode kit
# (sandbox-kit/kits/goversbx-opencode/spec.yaml), for opencode (anomalyco/opencode, built into sbx
# as a first-class agent: `sbx create opencode ...`). Runs as the `agent` user;
# GOVERSBX_DAEMON_HOST/_PORT come from the kit's environment (kit args).
#
# Facts from opencode's own docs (packages/web/src/content/docs/{mcp-servers,config,skills}.mdx,
# fetched via `gh api`):
# - Global config is ~/.config/opencode/opencode.json, under "mcp": { "<name>": {...} }. A remote
#   server needs "type": "remote" plus "url" and optional "headers" (mcp-servers.mdx).
# - Header values interpolate "{env:VARIABLE_NAME}" (config.mdx) - the placeholder literal below
#   matches neither that nor a bare env var name, so opencode sends it verbatim, same as the pi/omp
#   kits intend.
# - opencode scans ~/.agents/skills/<name>/SKILL.md for global skills (skills.mdx: "Global
#   agent-compatible"), the same location pi uses, so the kit's skill copy reuses that path.
# - opencode ships as a standalone binary (curl -fsSL https://opencode.ai/install | bash is the
#   primary install path), not guaranteed to have Node/Bun, so the merge uses jq instead of
#   node -e/bun -e, matching the pattern of skipping gracefully when the needed tool is absent.
set -e
DAEMON_HOST="${GOVERSBX_DAEMON_HOST:-host.docker.internal}"
DAEMON_PORT="${GOVERSBX_DAEMON_PORT:-8787}"
SANDBOX_IDENTITY_PLACEHOLDER="goversbx-sandbox-identity-placeholder-v1"
OPENCODE_CONFIG="${OPENCODE_CONFIG:-$HOME/.config/opencode/opencode.json}"

if ! command -v jq >/dev/null 2>&1; then
  echo "goversbx: jq not found - cannot safely merge $OPENCODE_CONFIG, skipping" >&2
  exit 0
fi

mkdir -p "$(dirname "$OPENCODE_CONFIG")"
[ -f "$OPENCODE_CONFIG" ] || printf '{}' > "$OPENCODE_CONFIG"

tmp="$(mktemp)"
if ! jq --arg url "http://${DAEMON_HOST}:${DAEMON_PORT}/mcp" \
     --arg placeholder "$SANDBOX_IDENTITY_PLACEHOLDER" \
     '.mcp = (.mcp // {}) | .mcp["goversbx-network"] = {
        "type": "remote",
        "url": $url,
        "enabled": true,
        "headers": { "X-Goversbx-Sandbox-Name": $placeholder }
      }' "$OPENCODE_CONFIG" > "$tmp"; then
  rm -f "$tmp"
  echo "goversbx: $OPENCODE_CONFIG is not valid JSON, not overwriting" >&2
  exit 1
fi
mv "$tmp" "$OPENCODE_CONFIG"
