# Slice 2: the launcher triggers the host set

Status: done (2026-10-24), released in 0.4.0. Question 4 verified on the host (sbx 0.49.1): a
secret set right after `sbx create` is substituted on the first call; the sandbox already reports
`running` right after create. `launcher` scenarios 1-8 green in `./gradlew check`; e2e scenarios 9
and 10 (`LauncherSequenceTest`) green on the host. After the e2e run the launcher streams
`sbx create`'s progress live (tee) instead of printing it only once create finished.

## Problem

Slice 1 raises the host set when the daemon registers a sandbox. That happens at the sandbox's
first MCP call - in practice the agent's first `request_network_access`, i.e. after the first
403. The set is there "early", not "at start": the agent has already stalled once, and the human
gets the set and a single request at the same moment. `goversbx run` is the one place that really
is at start, but today it ends in `exec sbx run ...`, which creates and attaches in one step, so
nothing can happen between "sandbox exists" and "agent runs".

## Goal

`goversbx run` for a new sandbox puts the project's host set in front of the human before the
agent's session starts, and tells the user in the terminal what is waiting. Nothing changes for
sandboxes started without the launcher (slice 1 still covers them).

## Scope

- `goversbx run` splits a new sandbox's start into `sbx create` -> register with the daemon ->
  `sbx run <name>` (attach).
- Registration through the existing admin endpoint `POST /api/sandboxes/{name}/register`, which
  since slice 1 also raises the set (no daemon change expected).
- A terminal summary of what was raised.
- Optionally wait for the decision before attaching (see open question 3).

Not in this slice: auto-apply without a click (slice 3); the launcher reading or validating the
project file itself (the daemon stays the only reader); changes to the kits.

## Behavior

**New sandbox.** `goversbx run [--goversbx-kit K] <sbx run args>`:

1. Start the daemon if needed (unchanged).
2. `sbx create --kit <kit> [--kit-arg ...] <same args>`. If it fails, exit with its status and
   output; nothing else runs.
3. Determine the sandbox's name: `--name` if given, else the name sbx reports for the created
   sandbox (open question 1).
4. `POST /api/sandboxes/<name>/register` with the admin token from the state dir (same lookup as
   `goversbx dashboard`). The response's evaluations (slice 1 shape) are summarized:
   - `CREATED`: `goversbx: project host set (<n> hosts from <file>) is waiting for approval in the dashboard`
     and the dashboard is opened if none is connected (the daemon's auto-open already does this).
   - `INVALID`: `goversbx: <file> is invalid - no hosts requested:` plus each problem.
   - `NO_FILE` / no workspace: nothing is printed.
5. `exec sbx run <name>` - attach, as before.

Registration or summary failing never blocks the session: on any error (daemon unreachable,
non-2xx, unparseable body) the launcher prints one warning line and attaches anyway. Slice 1's
reactive registration remains the fallback.

**Existing sandbox.** Unchanged (`exec sbx run "$@"`, no kit), plus: if the daemon already knows
the sandbox, call `POST /api/sandboxes/<name>/host-set-requests` and print the same summary, so
editing the project file and restarting the session raises the new hosts. A `404` (sandbox never
registered, e.g. created without the kit) is silent.

**Parsing.** The launcher is bash 3.2 without `jq`. It extracts only `kind`, the host count and
`source` (and `problems` for `INVALID`) from the daemon's JSON. To keep that robust, the register
and trigger responses gain nothing new; if the extraction turns out brittle, the daemon gets a
`?format=text` variant of both endpoints that returns the summary lines ready to print (open
question 2).

## Scenarios

Test levels: `launcher` is new - `scripts/testing/test-launcher.sh` drives `scripts/goversbx`
against `scripts/testing/fake-sbx` and the real daemon jar in dry-run, bash 3.2 compatible,
wired into `./gradlew check`. `e2e` is the existing suite against a real sbx.

1. **Order.** Given a new sandbox name, when `goversbx run --name n claude <ws>` runs, then
   fake-sbx saw `create ... --name n claude <ws>` before `run n`, and the daemon logged the
   registration of `n` in between. `launcher`
2. **Set waiting before attach.** Given `<ws>/.goversbx/network.yaml` with two hosts, when the
   launcher runs, then a pending host set for `n` exists when `sbx run n` is invoked, and the
   terminal shows the waiting line with `2 hosts`. `launcher`
3. **Invalid file.** Given a file with an unknown key, then the terminal shows the file path and
   the problem, no request exists, and the session still attaches. `launcher`
4. **No file.** Given a workspace without the file, then nothing about host sets is printed and the
   session attaches. `launcher`
5. **Daemon trouble never blocks.** Given the register call fails (wrong token / daemon stopped
   after start), then one warning line is printed and `sbx run n` still runs. `launcher`
6. **Create fails.** Given `sbx create` exits non-zero, then the launcher exits with that status,
   prints sbx's output, and never calls register or `run`. `launcher`
7. **Existing sandbox re-raises.** Given a registered existing sandbox whose file gained a host,
   when `goversbx run` attaches to it, then a pending set with just the new host exists and the
   summary line was printed; for an unregistered existing sandbox nothing is printed. `launcher`
8. **No name given.** Given `goversbx run claude <ws>` without `--name`, then the launcher finds
   the created sandbox's name and registers that one. `launcher` (fake-sbx prints sbx's real
   create output) and `e2e`
9. **End to end.** Given a real sbx and a workspace with a file naming the set host, when the
   launcher starts a sandbox, then the pending set exists before any MCP call from the sandbox,
   approving it makes the host reachable, and the agent's first `request_network_access` for it
   is answered at once. `e2e`
10. **Registering a not-yet-started sandbox works.** The identity secret set right after
    `sbx create` (sandbox created, maybe stopped) is substituted once the session runs: the first
    MCP call resolves without "please retry". `e2e`

## Decisions (2026-10-24)

1. Name: parse `Created sandbox <name>` from `sbx create`, `sbx ls` diff as fallback.
2. The register and trigger endpoints get `?format=text`; the launcher only prints lines.
3. No waiting before attach in this slice.
4. Verified (e2e `LauncherSequenceTest.aSecretSetRightAfterCreateIsSubstitutedOnTheFirstCall`):
   yes, so the sequence is create -> register -> attach as specified.

Amendments while implementing:

- The launcher calls `register` (JSON, status only) and then `host-set-requests?format=text` for the
  lines; only the trigger endpoint got the text format, since the set raised by the registration
  shows up there as `ALREADY_PENDING` with the same "waiting for approval" line. `ALL_GRANTED`
  prints one line too ("every host is already granted").
- Scenario 9 runs the real launcher with a wrapper as its sbx that passes everything through but the
  final `sbx run <name>` (which would attach an interactive agent).

## Open questions (as drafted)

1. **Name without `--name`.** Options: (a) parse `✓ Created sandbox <name>` from `sbx create`'s
   output; (b) diff `sbx ls` before/after; (c) make `--name` mandatory for `goversbx run` and
   derive one like sbx does. Proposal: (a), with (b) as the fallback if the line is missing;
   the e2e suite pins the output format.
2. **JSON in bash.** Parse the existing JSON with `grep`/`sed`, or add `?format=text` to the two
   endpoints so the daemon formats the summary? Proposal: `?format=text` - formatting stays in Java
   (tested, native-image-traced), the launcher only prints lines.
3. **Wait for the decision before attaching?** (a) never; (b) opt-in flag `--wait-for-hosts`
   (blocks up to N seconds or until decided); (c) always when a set was created. Proposal: (a) for
   this slice - the set is raised before the agent starts, and an approved set answers the agent's
   pending singles anyway (slice 1); (b) can follow if it's missed.
4. **Does sbx substitute a secret set before the sandbox first ran?** Not verified. If not,
   registration has to happen after `sbx run` started the sandbox, which needs a different
   sequence (background registration while attaching). Verified by scenario 10 before anything
   else is built.
