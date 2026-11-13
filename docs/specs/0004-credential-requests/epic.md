# Epic 0004: credential requests - the agent asks, the human approves, goversbx mints a scoped token

Status: draft (2026-11-10) - nothing implemented; depends on epic 0003 slice 1 (v3 kit)

## Problem

goversbx lifts *network* walls. The next wall an agent hits is *authorization*: it can reach
`gitlab.example.com` but has no token, so it either stops or the human pastes a broad personal
token into the sandbox (or into a global `sbx secret`). Both are bad: the first costs a round trip
for every task, the second hands the agent far more than the task needs, for far longer, and
unaudited.

## Goal

An agent calls `request_credential` ("GitLab, project `acme/shop`, read+write repository, 1 day,
because I need to push branch `fix-123`"). The human sees exactly that in the dashboard, may narrow
it (fewer permissions, shorter expiry) and approves. The daemon - on the host, holding the user's
own GitLab admin credential, never visible to the sandbox - mints a **narrowly scoped,
expiring** token for exactly that resource, attaches it to **that one sandbox** as an sbx secret
(the agent only ever sees a placeholder; the proxy injects the real value), and revokes it when
it expires, when the human revokes it in the dashboard, or when the sandbox disappears.

## Non-goals

- A general secret vault. goversbx mints and revokes; storage stays in sbx's host credential store.
- Providers other than GitLab in this epic (the port is generic; GitHub via App installation tokens
  is the obvious second adapter, deferred).
- Letting a project file grant credentials without a human (same rule as host sets: files in the
  workspace are agent-writable).

## Building blocks (verified vs. to verify)

| # | Fact | Status |
|---|---|---|
| 1 | `sbx secret set-custom --sandbox <n> --host <h> --env <VAR> --value-stdin` stores a sandbox-scoped secret; the sandbox's `<VAR>` holds a placeholder; the proxy replaces the placeholder **in request headers only**, never bodies. | docs (0.51) |
| 2 | Adding/updating/removing a secret takes effect in running sandboxes without restart. | docs, for `sbx secret set` service secrets; **verify for set-custom, and whether `--env` reaches an already-running sandbox** (docs suggest setting the env var by hand for existing sandboxes) |
| 3 | Git over HTTPS authenticates with **Basic** (`base64(user:token)`) - a placeholder inside base64 is not substituted. `credential@1` has `scheme: basic, username: …` injection, but only for kit-declared services and only at create (not `kit add`-able). | docs; **the git path is the main technical risk** |
| 4 | GitLab **project/group access tokens**: `POST /projects/:id/access_tokens` (`name`, `scopes`, `access_level`, `expires_at`), revocable (`DELETE …/access_tokens/:id`), rotatable, audited, bot user per token. Free on self-managed, Premium+ on gitlab.com. Needs Maintainer+ on the project. | GitLab REST docs (stable) |
| 5 | GitLab **fine-grained personal access tokens** (per-endpoint permissions, project/group boundaries): **beta**, creation documented in the UI; a REST/GraphQL creation endpoint is not documented yet. | GitLab docs 2026; **verify on the target GitLab version** |
| 6 | The daemon already holds per-sandbox identity (proxy-verified `X-Goversbx-Sandbox-Name`), runs `sbx` on the host, has an approval UI with SSE + notifications, and knows when a sandbox is gone (`sbx ls`). | implemented |

## Decision

### New bounded context `credentialaccess` (same hexagonal shape as `networkaccess`)

- **Domain**: `CredentialRequest` (sandbox, provider, resource, requested permissions, expiry,
  reason) → `CredentialGrant` (provider token id, granted permissions, expires_at, secret handle,
  state `active|expired|revoked|failed`). The **token value is never part of the domain**: the
  adapter mints it and pipes it straight into `sbx secret set-custom --value-stdin`; the daemon
  keeps only the provider's token id for revocation.
- **Driven port `CredentialProvider`** (`mint(resource, permissions, expiry) → MintedToken`,
  `revoke(tokenId)`, `describe(resource)`); first adapter **GitLab**, strategy chosen per provider
  config:
  - `project-access-token` (default now, fact 4): token named `goversbx-<sandbox>-<short id>`,
    `access_level` derived from the permissions (reporter for read, developer for write),
    `scopes` from a fixed mapping (`read_repository`, `write_repository`, `read_api`, `api`).
  - `fine-grained-pat` once fact 5 has a creation API - same port, finer permission model.
- **Driven port `SandboxSecretPort`**: `attach(sandbox, hosts, envVar, value)` /
  `detach(sandbox, handle)` over `sbx secret set-custom/rm --sandbox`.
- **Provider config on the host** (`~/.config/goversbx/providers/gitlab.yaml`): base URL, the
  admin credential as an sbx-style dynamic source (`command: glab auth token` / `ref: op://…`),
  default/max expiry, allowed permission set, optional allow-list of groups/projects. Never in a
  workspace.

### MCP surface (the `goversbx` kit, existing endpoint)

- `request_credential(provider, resource, permissions[], reason, expiresInHours?, timeoutSeconds?)`
  → `Approved - GITLAB_TOKEN (placeholder) is valid for acme/shop until … with read_repository,
  write_repository` / `Denied` / `pending + requestId`.
- `check_credential_status(requestId)`; `list_credentials()` (what this sandbox holds, no values).
- Skill + agent-context text: when to ask (401/403 from a provider, missing token), how to use the
  placeholder (`GITLAB_TOKEN` for `glab`/API calls with `PRIVATE-TOKEN`/`Authorization: Bearer`;
  git: see slice 2).

### Dashboard

One row per request: provider, resource, permissions as checkboxes (human can untick), expiry
picker (≤ provider max), reason; on approval shows the minted token's id/expiry (never the value).
A "Credentials" view lists active grants per sandbox with **Revoke**. Network: if the provider host
is not yet granted to the sandbox, the same approval also grants it (one decision).

### Lifecycle

- Expiry: GitLab enforces `expires_at`; the daemon additionally detaches the sbx secret at expiry.
- Sandbox gone (`sbx ls` no longer lists it, or `sbx env rm`): revoke token + remove secret.
- Daemon restart: grants persisted (ids, expiry, sandbox - no values) in the state dir, owner-only,
  like `sandbox-registrations.json`; on start, reconcile against `sbx ls` and the provider.
- Audit: every mint/revoke logged with sandbox, resource, permissions, approver.

### Git over HTTPS (fact 3) - decided by the spike, in this order

1. `git config http.https://<gitlab>/.extraHeader "Authorization: Bearer <placeholder>"` - header
   substitution works; **verify GitLab accepts Bearer for git-over-HTTP with a project access token**.
2. `PRIVATE-TOKEN`-style header via `extraHeader` if (1) is rejected.
3. Fallback: an optional `goversbx-gitlab` v3 mixin declaring `credential@1` (`service: gitlab`,
   `scheme: basic`, `username: oauth2`, kit arg `host`) in the project's `sbxenv.yaml`; the daemon
   then stores the minted token with `sbx secret set gitlab --sandbox` instead of `set-custom`.
   Costs: creation-time only (no `upgrade`), a credential-binding approval, and the gitlab host must
   be known at create.

## Threat model additions (`docs/threat-model.adoc`)

- The agent chooses the *request*, never the grant: permissions are mapped server-side from a
  closed set, resource must match the provider allow-list, expiry is capped.
- Identity is the proxy-substituted sandbox name (existing F-entries) - a sandbox cannot request on
  behalf of another.
- The minted token never touches the sandbox, the daemon log, argv, or the state file.
- The admin credential is resolved per call from the host source and dropped; a compromise of the
  daemon process is still a compromise of that credential - document, and recommend a dedicated
  GitLab bot/service account with Maintainer only where needed.
- Resource names in requests are displayed escaped and verified against the provider
  (`describe`) before the human sees them, so a look-alike name cannot trick the approver.

## Slices

0. **Spike**: facts 2, 3 and 5 on a real 0.51 host + a GitLab test project; result picks the git
   path and the token strategy.
1. **GitLab API access**: context, provider config, project access token mint/revoke, `set-custom
   --value-stdin` attach, MCP tools, dashboard row + approval with narrowing, expiry detach,
   contract tests against a fake GitLab + `FakeSbx`, e2e against a real GitLab (opt-in knob).
2. **git over HTTPS** with the spike's winner; skill text.
3. **Revocation & reconciliation**: credentials view, revoke, sandbox-gone cleanup, restart
   reconcile, audit log.
4. (candidate) fine-grained PAT strategy; (candidate) GitHub App installation tokens as second
   provider.

## Open questions (for the user, before acceptance)

1. **Which GitLab**: gitlab.com (project access tokens need Premium there) or self-managed (version?
   fine-grained PAT beta enabled?)
2. **Admin credential**: your own PAT (simple, broad) or a dedicated bot/service account
   (recommended)? Source: `glab auth token`, 1Password `op://`, other?
3. **Default and maximum expiry** (proposal: default 8 h, max 7 days) and whether "permanent for
   this sandbox" (like network approvals) should exist at all - proposal: no.
4. **Scope of a grant**: one project per request (proposal) or also groups?
5. **Name**: `request_credential` / "credential requests", or broader "access requests" with network
   and credentials as two kinds of the same request in one tool?
