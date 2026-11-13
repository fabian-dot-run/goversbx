# Product vision

As of 2026-11-13. This is what goversbx is for and how to decide what goes into it. The current
state is in [`status.md`](status.md), the features in [`specs/`](specs/README.md), the design
history in [`plan.md`](plan.md).

## What comes first

1. **A proactive permission gate that makes switching projects cheap.** An agent in a sandbox asks
   for what it lacks, at the moment it lacks it, and a human answers with one click. Nothing has
   to be decided up front, per project, before work can start - so moving to another project, or
   starting a new one, costs `cd` and `goversbx run`. Sensible defaults (agent, kit, workspace)
   live once on the machine and apply to every project.
2. **Follow sbx.** As soon as sbx itself supports something goversbx does by hand, goversbx adopts
   the sbx way - as long as that needs no fundamental change to a goversbx feature. goversbx is a
   thin layer on sbx, not a second sandbox tool beside it.
3. **Easy entry.** Someone can install goversbx and use it without knowing its functions in
   detail, and without knowing sbx's policy, kit or secret commands. Install, run, click approve.

When these pull against anything below, they win.

## The problem

Agents in [Docker Sandboxes](https://docs.docker.com/ai/sandboxes/) work behind walls: the network
policy only lets known hosts through, and the sandbox holds no credentials. Each wall the agent
hits stops it until a human runs the right `sbx` command on the host. The usual ways out are both
bad - open everything in advance (and lose the wall), or babysit every sandbox.

goversbx turns each wall into a short dialogue: the agent says what it needs and why, the human
sees exactly that and decides, and the decision is applied to that one sandbox.

## What it looks like when it works

```
brew install goversbx        # once
cd ~/some-project
goversbx run                 # daemon up, defaults checked, sandbox created, agent attached
```

The first run asks one question (which agent), ever. After that every project starts the same way,
and every sbx user on the machine gets goversbx in every `sbx env` sandbox, with or without the
`goversbx` command. While the agent works, a request appears in the dashboard - host, reason,
sandbox - and one click lets it continue.

## What goversbx guarantees

- **A human decides, per request.** Nothing an agent can write - a file in the workspace, a kit it
  can reach - grants access on its own.
- **The daemon is outside every sandbox.** It holds the host's `sbx` CLI and the admin token; a
  sandbox can neither reach its admin API nor control it.
- **A sandbox cannot pretend to be another one.** Identity is a per-sandbox secret the sbx proxy
  injects; the agent only ever holds a placeholder.
- **Least and scoped.** An approval applies to one host and one sandbox. Credentials, when they
  come, are minted for one resource, with an expiry, for one sandbox.
- **The user's files stay the user's.** goversbx owns one entry in `~/.sbxenv.yaml` and scaffolds
  a project file only where there is none.

## How to decide

| Question | Answer |
|---|---|
| sbx now does X natively - do we switch? | Yes, if no goversbx feature has to change fundamentally (0.6.0: one v3 kit, `sbx env`, user defaults, `--value-stdin`). No, if a guarantee would be lost (the sbx MCP gateway loses the sandbox's identity - the kit keeps registering the endpoint itself). |
| Does a new feature need configuration before first use? | Then it needs a default that works without it. A feature only experts can switch on is not done. |
| Does it add a goversbx concept the user has to learn? | Prefer sbx's concept, or none. Project host sets were removed for this reason: a second config file and a bounded context for something per-host requests already cover. |
| Does it add a verb to the CLI? | Only if sbx has no way to do it. Every other verb passes through to sbx. |
| Would it let an agent widen its own access? | No - not now, not "on recreate", not "if the file is hash-pinned". |

## Where it goes

1. **0.6.0 - native on `sbx env`** (epic 0003): one kit for every agent, kept once in the user
   defaults; `goversbx setup` / `init` / `run`; everything else is sbx.
2. **`goversbx upgrade`**: put goversbx onto sandboxes that already exist.
3. **Credential requests** (epic 0004): the agent asks for a credential, the human approves or
   narrows it, goversbx mints a scoped, expiring token for that sandbox. GitLab first.
4. **Living with approvals**: revoke and expire them, keep pending requests across a daemon
   restart, an audit log, the reason for a denial back to the agent.

## What it is not

- Not a policy engine or a secret vault - sbx's policy and credential store stay the source of truth.
- Not an organisation-wide governance tool - one person, one machine, their own sandboxes.
- Not a replacement for the sbx CLI, and not usable without it.
