# Epic 0006: the goversbx endpoint through sbx's MCP gateway - spike

Status: spike, not started (recorded 2026-11-14). Nothing here is decided or measured beyond what
epic 0003 already measured. A spec follows only if the spike's first question comes out well.

## Why

The goversbx kit is generic except for one part: `register-mcp.sh` registers the daemon's MCP
endpoint in each agent's own config format (Claude Code, opencode, pi, oh-my-pi - one section per
agent, and none for Codex yet). sbx has an MCP gateway that it wires into every agent it supports.
If the goversbx endpoint could go through it, the script and every per-agent section would go, and
agents sbx adds later would work without a change here. That is the vision's second point: adopt
what sbx offers when no goversbx feature has to change fundamentally.

## Why it does not work today

Epic 0003 D8 / E7 / E8 (sbx 0.49.1 and 0.51.0; not repeated on 0.53): the gateway calls upstream
from the **host**. The call never passes the sandbox's proxy, so the identity placeholder is not
substituted and the daemon sees the same raw placeholder from every sandbox - also for a `command`
server, whose process learns nothing about the sandbox. D8's rule stands: the gateway path only
with an identity the sandbox cannot choose.

## The idea to test

Not one shared registration with a placeholder, but **one registration per sandbox** whose header
carries that sandbox's token as a host-side secret:

```
sbx mcp add goversbx-<sandbox> --url http://127.0.0.1:<port>/mcp --skip-ssrf-check \
    --header 'X-Goversbx-Sandbox-Name: ${token}'
sbx secret set mcp:goversbx-<sandbox>:token          # the per-sandbox token, never in the sandbox
sbx mcp load goversbx-<sandbox> --sandbox <sandbox>
```

Identity would then be "which registration is attached to which sandbox", decided on the host by
the daemon, instead of "which secret the proxy substitutes".

## Questions, in this order - stop at the first no

1. **Can a sandbox attach a registration that is not its own?** In dynamic mode (the default
   without `--static-mcp`) the agent has `mcp-find`, `mcp-add`, `mcp-config-set`. If it can add
   `goversbx-<other>` to its own gateway, it can act as the other sandbox - the forgery goversbx
   exists to prevent (threat-model F1). Measure: from inside sandbox A, try to add B's registration;
   also with A created `--static-mcp none` and the registration loaded afterwards with
   `sbx mcp load`. **If this cannot be closed reliably, the spike ends here.**
2. **Does the header secret arrive, per sandbox?** Two sandboxes, two registrations, one logging
   endpoint: each call must carry its own token. Does it need the local gateway
   (`mcp.forceLocalGateway`; the CLI says header secrets "require a locally connected server")?
3. **How does a sandbox get its registration without `goversbx run`?** Today registration is
   reactive: an unknown sandbox calls with the raw placeholder and is registered. Through the
   gateway a sandbox has no endpoint before the daemon attaches one, so the daemon would have to
   notice new sandboxes itself (it already runs `sbx ls`). Otherwise a plain `sbx env run` gets no
   goversbx, and epic 0003's goal 2 ("with or without the goversbx CLI") is lost.
4. **Lifecycle.** Does a loaded server survive stop/start, `sbx kit add`, `sbx env run`? What
   removes the registration and its secret when the sandbox is removed? Is `sbx mcp load` allowed on
   a static sandbox at all?
5. **What the user sees.** Tool names (`mcp.useServerNamePrefixes`), one more entry in
   `sbx mcp ls` per sandbox, and whether sbx's organization MCP policies would start governing the
   goversbx tools.

## What would change if it holds

- The kit keeps the network rule (maybe not even that - the sandbox no longer calls the daemon),
  the skill and the agent context; `register-mcp.sh` and its lifecycle hooks go, except Claude's
  `CLAUDE.md` block (epic 0003 E9), which is not about MCP.
- The daemon gains `sbx mcp add/load/rm` and `sbx secret set mcp:...` calls and loses
  `sbx secret set-custom` for identity; `goversbx upgrade --exec` shrinks to the same calls.
- The direct path stays available for sandboxes where the gateway is switched off.

## Not part of this

MCP servers for other services (GitLab and the like) through the gateway: that already works
without goversbx and is a project's own `sbxenv.yaml` business (see the notes for epic 0004).
