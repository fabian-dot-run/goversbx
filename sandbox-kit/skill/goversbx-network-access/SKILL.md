---
name: goversbx-network-access
description: Request outbound network access to a new host from inside this sandbox, with a human approving or denying the request. Use this when a command fails because of a network/proxy block, or before attempting to reach a host you know is not already allowed, instead of giving up or retrying blindly.
---

# Requesting network access

This sandbox restricts outbound network access by default. When you hit a blocked request (for
example a `403` from the sandbox proxy), or you already know you'll need to reach a host that
probably isn't on the allow list, use the `goversbx-network` MCP server instead of giving up or
looping on retries.

## Tools

The server provides two tools. Depending on your agent, they may appear with a server prefix
(for example `mcp__goversbx-network__request_network_access`) - they are the same tools.

- **`request_network_access(host, reason, timeoutSeconds?)`** — asks a human to approve or deny
  access to `host`. Give a specific, honest `reason` — it's the only context the human sees when
  deciding. This call waits up to `timeoutSeconds` (default 45, max 120) for a decision:
  - `approved` — the host is now allowed for this sandbox, permanently. Retry your original
    request.
  - `denied` — do not retry this host; tell the user it was denied and why you needed it.
  - `pending` — no decision yet. The response includes a `requestId`; wait a bit and call
    `check_network_access_status` with it rather than calling `request_network_access` again for
    the same host.

- **`check_network_access_status(requestId)`** — checks a pending request without blocking.
  Use this after a `pending` result, spaced out over a reasonable interval (seconds to low tens of
  seconds) rather than tight-looping.

If `request_network_access` responds that this sandbox's identity isn't registered yet and asks
you to retry, that's expected for a brand-new sandbox's very first call - just call
`request_network_access` again with the same arguments after a few seconds. No `requestId` was
created yet in that case, so retry the original call rather than calling
`check_network_access_status`.

## When to use this vs. just retrying

- A single transient network error is not a reason to call this tool — only reach for it when the
  failure is clearly an access/policy block, or you can tell in advance you'll need a host that
  isn't part of your normal toolchain.
- Approval is a human-in-the-loop step and may take anywhere from seconds to minutes (or never, if
  no one is watching). Don't block the rest of your work on it — if the task can proceed without
  the blocked host, do that first and come back to it.
- Once a host is approved for this sandbox, it stays approved - don't re-request a host that was
  already approved earlier in the same session.
