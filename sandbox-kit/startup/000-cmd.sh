#!/bin/sh
# DRAFT - durable-startup command for the "goversbx" kit, modeled on the observed
# "claude" kit's registration of mcp-gateway (see /etc/durable-startup.d/*/manifest.json
# on a running sandbox). NOT yet confirmed to be the real host-side kit-authoring mechanism -
# see plan.md "Open questions", item 2. Run as the `agent` user.
set -e
export PATH="$HOME/.local/bin:$HOME/.claude/local:$PATH"
claude mcp add goversbx-network "/opt/goversbx/bin/goversbx-mcp" --scope user || true
