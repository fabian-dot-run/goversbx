# Changelog

All notable changes to goversbx. The section of a release is what `gh release create` publishes as
its notes (`scripts/release-notes.sh`), and what `brew info goversbx` points at. Format after
[Keep a Changelog](https://keepachangelog.com/); versions follow semver (0.x: minor = feature).

## Unreleased

## 0.6.0 - 2026-11-14

goversbx now sits on `sbx env`: one kit for every agent, kept once in sbx's user defaults, and
`goversbx run` in a project directory is the whole setup. Needs sbx 0.51 or newer; tested against
sbx 0.53.0. There is no 0.5.0.

### Added

- **`goversbx run` is now the whole setup of a project, on `sbx env`.** `cd` into the project and
  run it: it starts the daemon, checks sbx's user defaults, writes `sbxenv.yaml` if there is none
  (`workspace: .`), creates the sandbox with `sbx env create`, registers it and attaches with
  `sbx env run`. It composes with whatever the project's own `sbxenv.yaml` declares (kits, env, name).
- `goversbx setup`: keeps exactly one entry for the goversbx kit in `~/.sbxenv.yaml` (the path of
  this install, `args.daemonPort` when the daemon is not on 8787) and asks once for the default
  agent. Comments, order and everything else in the file stay byte for byte; the previous content is
  saved as `~/.sbxenv.yaml.goversbx-bak`. `setup --check` reports without writing. With the kit in
  the user defaults, a plain `sbx env run` gets goversbx too.
- `goversbx init`: writes the project's `sbxenv.yaml` if there is none.
- **Every verb goversbx does not know goes to sbx unchanged** (`goversbx ls`, `goversbx policy log`,
  `goversbx env rm`); `goversbx sbx ...` reaches the sbx verbs goversbx has too (`run`, `stop`,
  `setup`, `version`, `help`). `goversbx help` prints both.
- `goversbx status` says whether the user defaults are in order.
- **`goversbx upgrade [NAME]`** puts goversbx onto a sandbox that already exists: it runs
  `sbx kit add` (in a terminal - sbx asks to approve the kit's network rules and recreates the
  container; files in the sandbox's home stay) and registers the sandbox. Without a name it takes
  the sandbox whose workspace is the current directory; several are listed, none is touched.
  `--refresh` re-applies the kit, for example after a goversbx update. For sandboxes sbx cannot add
  a kit to - created by sbx older than 0.51, or whose original kit no longer exists -
  `goversbx upgrade <name> --exec` does the kit's work by hand (network rule, files, MCP
  registration); that does not survive a recreate, and it brings the skill only where the agent's
  skills directory is writable (sbx mounts it read-only for Claude Code in a sandbox without mixin
  kits - the MCP tools work without it). `goversbx run` says so when it attaches to a sandbox
  without the kit.

### Changed

- **Breaking: `goversbx run <agent> <path> [sbx run flags]` is gone.** `run` takes `--agent`,
  `--name`, `--force` and `-- AGENT ARGS`; everything else is `goversbx env run ...`. The old form is
  refused with one line. An unknown first argument is no longer an error of goversbx - sbx answers it.
- **Needs sbx 0.51 or newer.** sbx 0.51 turned the built-in agents into v3 kits and refuses to
  combine them with v1/v2 kits, so the four kits `goversbx-claude`, `goversbx-pi`, `goversbx-omp`
  and `goversbx-opencode` are replaced by **one v3 kit, `goversbx`, for every agent**
  (`share/goversbx/kits/goversbx`). It brings the same things as before - the network rule to reach
  the daemon, the skill, the MCP registration, the `CLAUDE.md` rule for Claude - and composes with
  other v3 mixins. `--kit-arg goversbx-<agent>.daemonPort=…` is now `--kit-arg goversbx.daemonPort=…`.
- `goversbx run --goversbx-kit …` and `GOVERSBX_KIT` are gone (one kit); the flag is refused with a
  hint.
- The kit can be added to an existing sandbox created by sbx 0.51+: `sbx kit add <name> <kit dir>`
  in a terminal. Recreating is only needed for older sandboxes.
- The daemon hands the sandbox identity token to `sbx secret set-custom` on stdin (`--value-stdin`)
  instead of as an argument, so it no longer shows up in the host's process list, and passes
  `--no-input` on every sbx call so a prompt (for example Docker sign-in) fails with a message
  instead of hanging an approval.
- A request for a host that was already approved for the sandbox is no longer approved automatically
  (`decidedBy: goversbx`): it is an ordinary pending request a human decides again.
- A request in the admin API's JSON has exactly one `host` again; `hosts`, `origin`, `source`,
  `profiles` and `hostResults` are gone.

### Fixed

- The daemon answers in English whatever the host's language is. On a German macOS the MCP tool's
  argument errors came back in German ("erforderliche Eigenschaft 'reason' nicht gefunden").

### Removed

- Project host sets are gone (spec 0003, D10): `<workspace>/.goversbx/network.yaml` and the profiles
  in `~/.config/goversbx/profiles/` are no longer read, `POST /api/sandboxes/{name}/host-set-requests`
  answers 404, and the dashboard shows one host per row again. `goversbx run` still creates the
  sandbox, registers it with the daemon and attaches; it no longer asks for a host set. For hosts
  every sandbox may reach use `sbx policy allow network <host>`; everything else is requested per
  host by the agent.

### Upgrading

`brew upgrade goversbx` (update sbx to 0.51 or newer first), then restart the daemon
(`goversbx stop && goversbx start`, or `brew services restart goversbx`). The next `goversbx run` in a project directory asks once for your
default agent and adds the kit to `~/.sbxenv.yaml`.

Existing sandboxes keep working as sbx sandboxes but no longer have goversbx: the four old kits are
gone. Run `goversbx upgrade` in the project (or `goversbx upgrade <name>`); where sbx refuses that -
sandboxes created before sbx 0.51 or with one of the old kits - use `goversbx upgrade <name> --exec`
or recreate the sandbox with `goversbx run`. Scripts that call `goversbx run <agent> <path>` need
the new form. `.goversbx/network.yaml` files and `~/.config/goversbx/profiles/` can be deleted.

## 0.4.0 - 2026-10-24

The project host set now waits in the dashboard before the agent starts. Verified end to end against sbx 0.49.1.

### Added

- `goversbx run` raises the project host set before the agent starts: it creates the sandbox,
  registers it with the daemon and prints what is waiting ("project host set (3 hosts from ...) is
  waiting for approval in the dashboard", or the problems of an invalid file), then attaches. For an
  existing sandbox it asks for hosts the file gained since. Nothing here blocks a session: problems
  are one warning line, and the agent's first call still registers the sandbox as before.
- `POST /api/sandboxes/{name}/host-set-requests?format=text`: the same evaluation as ready-to-print lines.

### Changed

- `goversbx run` now runs `sbx create` and then `sbx run <name>` instead of one `sbx run`; sbx's
  create progress is shown live as before.

### Upgrading

`brew upgrade goversbx`, then restart the daemon. Existing sandboxes need nothing.

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
