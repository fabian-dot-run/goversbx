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
# The sandbox-name header below is a HARDCODED LITERAL placeholder - the same string for every
# sandbox, baked into this script at kit-authoring time, NOT read from any per-sandbox env var.
# This is deliberate: `claude mcp add --header` resolves its value once, at registration time -
# if it read a per-sandbox env var instead, whatever that var held at THIS moment would be frozen
# into ~/.claude.json forever, even if the real secret registration happens later (sbx applies
# `secret set-custom` to already-running sandboxes immediately, without restart - confirmed via
# docker/sbx-releases' own GitHub release notes - so registration can legitimately happen well
# after this script already ran). With a hardcoded literal, none of that matters: the daemon's sbx
# proxy rewrites THIS placeholder to the real sandbox name on every outbound request, live, however
# long after registration happens - this script's own timing is irrelevant.
#
# MUST match the daemon's GOVERSBX_IDENTITY_PLACEHOLDER exactly (default shown below) - if an
# operator changes one, they must change the other.
#
# Registration itself (what makes the substitution real) does not have to happen before this
# script runs at all: the daemon registers a sandbox reactively, the first time it sees this raw,
# unsubstituted placeholder arrive in a request from it (see docs/plan.md's second 2026-10-14
# revision) - no operator action required in the common case. An operator can still pre-register
# via POST /api/sandboxes/<name>/register to skip the one-retry round trip.
set -e
export PATH="$HOME/.local/bin:$HOME/.claude/local:$PATH"
DAEMON_HOST="${GOVERSBX_DAEMON_HOST:-daemon.internal}"
DAEMON_PORT="${GOVERSBX_DAEMON_PORT:-8787}"
SANDBOX_IDENTITY_PLACEHOLDER="goversbx-sandbox-identity-placeholder-v1"
claude mcp add goversbx-network "http://${DAEMON_HOST}:${DAEMON_PORT}/mcp" \
  --transport http --header "X-Goversbx-Sandbox-Name: ${SANDBOX_IDENTITY_PLACEHOLDER}" --scope user || true
