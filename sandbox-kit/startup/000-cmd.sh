#!/bin/sh
# DRAFT - durable-startup command for the "goversbx" kit, modeled on the observed
# "claude" kit's registration of mcp-gateway (see /etc/durable-startup.d/*/manifest.json
# on a running sandbox). NOT yet confirmed to be the real host-side kit-authoring mechanism -
# see docs/plan.md "Open questions", item 2. Run as the `agent` user.
#
# No jar/launcher to install - the MCP server runs in the daemon (outside every sandbox) and
# is reached over HTTP, exactly like mcp-gateway. GOVERSBX_DAEMON_HOST/_PORT would be set by
# whatever provisions this sandbox (defaults shown match the daemon's own defaults).
# $SANDBOX_NAME is resolved here, once, at registration time - not read later from the
# agent's own shell - and baked into a static header. This is a known, documented trust
# limitation (see docs/plan.md, open questions 3 and 5): the header is only as trustworthy as
# this startup script's own environment, not independently verified by the daemon.
set -e
export PATH="$HOME/.local/bin:$HOME/.claude/local:$PATH"
DAEMON_HOST="${GOVERSBX_DAEMON_HOST:-daemon.internal}"
DAEMON_PORT="${GOVERSBX_DAEMON_PORT:-8787}"
claude mcp add goversbx-network "http://${DAEMON_HOST}:${DAEMON_PORT}/mcp" \
  --transport http --header "X-Goversbx-Sandbox-Name: ${SANDBOX_NAME}" --scope user || true
