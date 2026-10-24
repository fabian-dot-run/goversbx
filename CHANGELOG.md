# Changelog

All notable changes to goversbx. The section of a release is what `gh release create` publishes as
its notes (`scripts/release-notes.sh`), and what `brew info goversbx` points at. Format after
[Keep a Changelog](https://keepachangelog.com/); versions follow semver (0.x: minor = feature).

## Unreleased

### Added

- `goversbx run` raises the project host set before the agent starts: it creates the sandbox,
  registers it with the daemon and prints what is waiting ("project host set (3 hosts from ...) is
  waiting for approval in the dashboard", or the problems of an invalid file), then attaches. For an
  existing sandbox it asks for hosts the file gained since. Nothing here blocks a session: problems
  are one warning line, and the agent's first call still registers the sandbox as before.
- `POST /api/sandboxes/{name}/host-set-requests?format=text`: the same evaluation as ready-to-print lines.

## 0.3.0 - 2026-10-23

Project host sets: a project declares its standard hosts once, a new sandbox of it gets them with
one decision instead of one request and one click per host. First feature built spec-first
(`docs/specs/0001-project-host-set/`). Verified end to end against sbx 0.49.1.

### Added

- **Project host sets.** `<workspace>/.goversbx/network.yaml` declares `hosts` and/or `profiles`;
  profiles are your own files in `~/.config/goversbx/profiles/<name>.yaml` (goversbx ships none,
  on purpose). When the daemon registers a new sandbox it reads the file from every workspace
  `sbx ls` reports and raises **one** request listing every host. You approve or deny the set as a
  whole; approval applies all hosts with one `sbx policy allow network a,b,c --sandbox <name>`
  and shows each host's outcome.
- An approved set also answers the sandbox's pending single requests for hosts it contains, and a
  `request_network_access` for a host already granted to the sandbox is approved at once, without
  asking you again.
- Explicit trigger for operators and scripts: `POST /api/sandboxes/{name}/host-set-requests`
  (admin token) - `201` created, `200` already pending or nothing left, `400` invalid file (with the
  problems), `404` unknown sandbox. `POST /api/sandboxes/{name}/register` now also raises the set.
- Dashboard: a set is one row with every host listed, marked "project host set", with its source
  file and profiles and an "Approve all" button; per-host failures are shown.
- Sandbox registrations survive daemon restarts (`~/.local/state/goversbx/sandbox-registrations.json`):
  no "please retry" on a sandbox's first call after an upgrade or reboot.
- `./gradlew :daemon:e2eTest`: an end-to-end suite against a real sbx (throwaway sandboxes created
  with the kit, requests made from inside them through the proxy). Runs on the host or in a
  `--nested` dev sandbox.
- Spec-driven development: features start as specs under `docs/specs/`.

### Changed

- Admin API and event stream: a request now carries `hosts`, `origin` (`AGENT` / `PROJECT_FILE`),
  `source`, `profiles` and, once approved, `hostResults`; `host` stays set for single-host requests
  and is `null` for a set.
- The guard against the daemon's own admin port (threat-model F3) now sits in the request flow
  itself and applies to every path, project files included.
- Browser notifications only for requests that actually need a decision.
- Kits and MCP server info report 0.3.0; the skill tells the agent about project host sets.

### Security

- The project file is writable from inside the sandbox, so it never grants anything by itself:
  every set is decided by a human, every host is listed, hosts pass the same rules as the tool's
  `host` (no wildcards, URLs, IPs, admin port), one bad entry invalidates the file, at most 100
  hosts and 64 KiB, no symlinks, and the daemon reads only paths `sbx ls` reports as workspaces.
  See the F4 addendum in `docs/threat-model.adoc`.

### Upgrading

`brew upgrade goversbx`, then restart the daemon (`brew services restart goversbx`, or
`goversbx stop && goversbx start`). Existing sandboxes keep working without a recreate. A project
file takes effect for sandboxes registered after the restart; for a sandbox that is already
registered, call the explicit trigger once.

## 0.2.7 - 2026-10-15

- Docs and launcher: `sbx kit add` cannot apply goversbx kits (they ship files); `goversbx run`
  tells you to recreate the sandbox instead.

## 0.2.6 - 2026-10-15

- Javalin 6 -> 7 (Jetty 12 / Jakarta EE 10).

## 0.2.5 - 2026-10-15

- Dependency updates.

## 0.2.0 - 0.2.4 - 2026-10-14 / 2026-10-15

- macOS installer: GraalVM native daemon, Homebrew tap, `goversbx` launcher
  (`run`, `start`, `stop`, `status`, `logs`, `dashboard`).
- Black-box contract suite against the daemon binary; native-image metadata generated from it on
  every build (fixed the 0.2.0 binary failing with real Claude Code).
- Security hardening from the threat model: unforgeable sandbox identity (secret token substituted
  by the sbx proxy), host validation (no wildcards, URLs, IP literals), admin-port guard, token-gated
  admin API, dashboard auto-open.
- Browser notifications with approve/deny buttons.

Earlier versions were unreleased development snapshots; `docs/plan.md` has the history.
