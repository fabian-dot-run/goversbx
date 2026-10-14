#!/bin/sh
# DRAFT - durable-startup command for the "goversbx" kit, modeled on the observed
# "claude" kit's registration of mcp-gateway (see /etc/durable-startup.d/*/manifest.json
# on a running sandbox). NOT yet confirmed to be the real host-side kit-authoring mechanism -
# see docs/plan.md "Open questions", item 2. Run as the `agent` user.
#
# No jar/launcher to install - the MCP server runs in the daemon (outside every sandbox) and
# is reached over HTTP, exactly like mcp-gateway. GOVERSBX_DAEMON_HOST/_PORT would be set by
# whatever provisions this sandbox (defaults shown match the daemon's own defaults).
#
# The sandbox-name header is sourced from $GOVERSBX_SANDBOX_TOKEN, NOT $SANDBOX_NAME. That env
# var only exists if an operator already called the daemon's
#   POST /api/sandboxes/<this-sandbox-name>/register
# (or ran `sbx secret set-custom` directly) for this sandbox *before* this script runs - that
# registers a placeholder via `sbx secret set-custom`, which the sbx proxy then rewrites to the
# real sandbox name in every outbound HTTP header on requests to the daemon. The value in
# $GOVERSBX_SANDBOX_TOKEN here is always the placeholder, never the real value - the real
# substitution happens at the network layer, outside this sandbox, which is what makes this
# trustworthy in a way a self-reported $SANDBOX_NAME header is not (see docs/plan.md's
# 2026-10-14 "sbx secret set-custom" revision). If registration was skipped, this env var won't
# exist and the header will be empty - the daemon then falls back to "unknown-sandbox".
set -e
export PATH="$HOME/.local/bin:$HOME/.claude/local:$PATH"
DAEMON_HOST="${GOVERSBX_DAEMON_HOST:-daemon.internal}"
DAEMON_PORT="${GOVERSBX_DAEMON_PORT:-8787}"
claude mcp add goversbx-network "http://${DAEMON_HOST}:${DAEMON_PORT}/mcp" \
  --transport http --header "X-Goversbx-Sandbox-Name: ${GOVERSBX_SANDBOX_TOKEN}" --scope user || true
