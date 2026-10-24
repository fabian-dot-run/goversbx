# Epic 0001: Project host set

Status: accepted (2026-10-23); slice 1 done (v0.3.0), slice 2 draft (`slice-2.md`)

## Problem

Every new sandbox for a project needs the same handful of hosts before any real work can start:
package registries, build-tool distribution servers, the Git host, a docs site. Today each of them
costs one blocked request, one `request_network_access` call and one click in the dashboard, in
sequence, as the agent runs into them one by one. For a JVM project that is easily ten rounds
before the first build succeeds. The user wants to start a sandbox and get going.

## Goal

A project can declare its standard set of hosts, and a new sandbox for that project gets them
unlocked at the start with at most one human decision, without weakening the trust model: the
human still decides, and the agent cannot grant itself hosts.

## Non-goals

- Replacing the per-host request flow. It stays the path for everything not in the set.
- Shipping host lists with goversbx (see "Deferred" below).
- Changing sbx's own policy model (global rules, kits' allow-lists).

## Constraints that shape the design

- **The workspace is agent-writable.** A file in the project that grants hosts is an
  agent-controlled policy. It must never be applied without a human seeing its content, unless the
  exact content was approved before (slice 3). This is the same class of concern as threat-model
  F2/F4.
- **The daemon learns about a sandbox at its first MCP call**, through reactive registration.
  There is no `sbx create` event. The only point that truly is "at start" is the host launcher
  `scripts/goversbx run`, and only for sandboxes created through it.
- **Applying a grant** is `sbx policy allow network <host> --sandbox <name>`, one host per call
  today. Whether the comma-separated form works together with `--sandbox` is unverified.
- Grants are permanent for the sandbox. A local sbx deny rule silently defeats a grant (open
  follow-up from the MVP).

## Options considered

| | Where the set lives | Trigger | Approval | Verdict |
|---|---|---|---|---|
| A. sbx only (project kit allow-list or global rules) | kit / sbx policy | `sbx create` | none | Rejected: no visibility or audit, recreate to change, global rules leak to every sandbox, outside goversbx's workflow |
| B. Launcher-driven | project file | `goversbx run` | terminal y/N | Too thin alone: bash 3.2 logic, only for launcher-created sandboxes, no history; kept as a *trigger* (slice 2) |
| C1. Agent requests a list | project file | agent tool call | one click | Smallest step, but the agent shapes the request and it only happens at the first 403 |
| C2. Daemon reads the project file at registration | project file | registration | one click | **Chosen**: fits the bounded contexts, works for every creation path, the human sees exactly what the file says |
| D. Host-side profiles | user config | registration / dashboard | one click, later auto | **Chosen** as a complement: user-supplied profiles are trusted by construction and reusable across projects |

## Decision

C2 + D, delivered in three slices, with B as a trigger in slice 2:

1. **Slice 1 - batch request from the project file** (`slice-1.md`). A project declares hosts in
   `.goversbx/network.yaml`, optionally referencing user-supplied profiles from
   `~/.config/goversbx/profiles/`. When a sandbox is registered, the daemon resolves the set and
   raises one request that the human approves or denies as a whole. Every set still needs a
   human decision.
2. **Slice 2 - launcher as the trigger.** `goversbx run` registers the sandbox with the daemon
   right after creating it and triggers the set, so the request is waiting in the dashboard before
   the agent's first 403.
3. **Slice 3 - trust on first use.** The daemon pins the hash of an approved project file per
   workspace. The same file for a later sandbox of the same workspace is applied without a click
   and logged; a changed file raises a request again, with a diff against the approved version.

## Deferred, with reasons

- **Shipped host lists / built-in profiles (e.g. "gradle", "npm").** Deliberately not now. Two
  bad options: lists that auto-update are a supply-chain attack vector straight into every user's
  policy; lists that ship frozen go stale, and a hostname can change owners while the grants we
  derived from it are permanent and cannot be revoked by us. Updates would have to be possible,
  but controlled (signed, reviewed, explicit). A public/private registry of profiles may be a
  later epic; until then profiles are user-supplied only, and goversbx has no lists to maintain.
- **Partial approval** (deselecting single hosts of a set). Workaround in slice 1: deny, edit
  the file, trigger again.
- **Expiry of grants.** Out of scope for the whole epic; sbx rules are permanent today.
- **Nested profiles** (a profile referencing profiles). Not until a use case shows up.
