# Epic 0005: remote approvals over Matrix - decide a request from the phone, end-to-end encrypted

Status: draft (2026-11-14) - nothing implemented, nothing spiked

## Problem

A request can only be decided where the dashboard is: in a browser on the machine the daemon runs
on (the admin API is loopback-only and token-gated, threat-model F3). Away from that machine an
agent that hits a wall waits until the human is back - the babysitting the vision wants gone.

Opening the admin API to the outside is not an option. What works without any inbound connection
is a messenger the daemon talks to outbound: it posts the request into a conversation, the human
answers there.

## Goal

With Matrix configured, every pending request appears as a message in one end-to-end encrypted
room - sandbox, host, reason - and the human decides it from any Matrix client. The message is
then edited to show the outcome, whichever channel decided (Matrix or dashboard). The dashboard
keeps working unchanged; the first decision wins.

Without Matrix configured, nothing changes and nothing has to be set up (vision: easy entry).

## Non-goals

- Other messengers. Telegram, Slack and Discord have real buttons but no end-to-end encryption for
  bots; the user chose Matrix for that reason (2026-11-13). The channel is a port, a second adapter
  is possible later.
- Hosting a homeserver, or creating the bot account. The user brings an account for the bot.
- More than one approver room, or per-sandbox rooms.
- Chat commands beyond deciding a request (no `list`, no `revoke` - "living with approvals" in the
  vision covers those first in the dashboard).
- Credential requests (epic 0004) in the first slices. The port carries a generic "request to
  decide" so 0004 can join; its narrowing UI (untick permissions) has no Matrix equivalent yet.

## Building blocks (verified vs. to verify)

| # | Fact | Status |
|---|---|---|
| 1 | `NetworkAccessListener` + `NetworkAccessService.subscribe()` publish `requestCreated`/`requestDecided`; built so "other channels can subscribe later, in a `notification` context of their own" (`plan.md`, 2026-10-14). | implemented |
| 2 | `approve`/`deny` are on the concrete `NetworkAccessService`, deliberately not in `networkaccess.api` (admin-only). Another bounded context cannot call them today. | implemented |
| 3 | Matrix has no buttons. A reaction is an `m.reaction` event with `m.relates_to: {rel_type: m.annotation, event_id, key}`. Clients show existing reactions as chips; tapping a chip adds the same reaction. | Matrix spec |
| 4 | **Reactions are not encrypted, also in an encrypted room** (MSC2677: "normally not encrypted", the relation data is cleartext by design). The homeserver sees who reacted to which event with which key. Events are signed by the homeserver, not by the user's device, so **a homeserver can forge a reaction of any of its users**. | MSC2677, matrix-js-sdk; forgery is the consequence, **verify on a test server in the spike** |
| 5 | A message (`m.room.message`, also a reply or an edit) in an encrypted room is Megolm-encrypted and authenticated by the sending device's key. With the sender's device verified (cross-signing), a homeserver can neither read nor forge it. | Matrix spec |
| 6 | The daemon ships as a GraalVM native binary; a code path no contract test exercises has no metadata (`AGENTS.md`). There is no maintained pure-Java Olm/Megolm. | implemented / searched 2026-11-14 |
| 7 | JVM options for E2EE: **Trixnity** (Kotlin Multiplatform SDK, E2EE, JVM target, native crypto library); **jOlm** (binding to libolm, which is deprecated in favour of vodozemac); **matrix-rust-sdk** has Kotlin bindings published for Android only (AAR + JNA). None is known to work in a native image. | searched 2026-11-14; **native-image compatibility unverified** |
| 8 | pantalaimon (the E2EE proxy that would have let the daemon speak plain HTTP) was archived on 2026-04-08 and depends on libolm. | GitHub |
| 9 | Sync is a long-poll `GET /sync` from the daemon outwards; no inbound port, no webhook. | Matrix spec |

## The decision reaction is not end-to-end protected (fact 4)

This is the one point where "Matrix with reactions" does not deliver what Matrix was chosen for.
The request card is encrypted. The tap that approves it is not, and the homeserver could make it
up. A forged *denial* only delays the agent; a forged *approval* opens a host for a sandbox without
a human - the first guarantee in the vision.

Who can do it: whoever runs (or has compromised) the homeserver of an approver's account. They
also need a pending request worth approving, so in practice an operator working together with a
rogue agent.

Options:

| | Approve by | One tap | Homeserver can forge an approval | Notes |
|---|---|---|---|---|
| A | reaction ✅ | yes | **yes** | fine when the approver's homeserver is the user's own |
| B | encrypted reply to the card (`y`, `ok`, `approve`) | no (swipe, one letter, send) | no (fact 5, sender device must be verified) | deny may stay a reaction ❌ |
| C | reaction with a per-request secret: the bot seeds several decoy chips, the *encrypted* card says which one approves; any other one denies | yes | only by guessing (1 in n, one try per request) | one tap kept; weaker than B, more to explain |

**Proposal:** B is the default; A is a config switch (`matrix.approveBy=reaction`) for people who
run their own homeserver and say so. Denying with ❌ works in both. C is noted, not built. This is
open question 1 - the user picked reactions before fact 4 was on the table.

## Where the encryption runs (facts 6-8)

| | How | For | Against |
|---|---|---|---|
| 1 | Trixnity inside the daemon | one process, one binary | Kotlin + coroutines + ktor + a store in a Java daemon; a native crypto library inside a native image; all of it needs contract-suite metadata; unverified |
| 2 | A helper process `goversbx-matrix` (Rust, matrix-rust-sdk - the reference implementation), started by the daemon, JSON lines over stdio | the daemon stays free of native libraries and of Matrix; crypto is the audited mainstream stack; same shape as the `sbx` adapters (a port over a child process); a fake helper makes contract tests trivial | a second language and toolchain in the repo and in the release workflow; a second binary in the Homebrew formula |
| 3 | An existing CLI (`matrix-commander-rs` or similar) as that helper | no Rust code of our own | its output format and reaction/verification support become our contract; one more thing to install - against "easy entry" |

**Proposal:** 2, decided by the spike (slice 0): if Trixnity runs in the native binary with
acceptable effort, 1 wins on simplicity for the user (one binary); otherwise 2.

## Decision (draft)

### `networkaccess`: a published way to decide

New interface in `networkaccess.api`, implemented by `NetworkAccessService`:

```java
public interface NetworkAccessDecisionApi {
    List<NetworkAccessRequestView> pending();
    NetworkAccessRequestView approve(UUID requestId, String decidedBy);   // AlreadyDecided -> typed result, not an exception across contexts
    NetworkAccessRequestView deny(UUID requestId, String decidedBy);
    Runnable subscribe(NetworkAccessListener listener);
}
```

It is handed only to the remote-approval context in `Main`, never to `mcp` - an agent-facing
context must not hold it. `decidedBy` becomes `matrix:@user:server`, shown in the dashboard.

### New bounded context `remoteapproval`

- **Application** `RemoteApprovalService`: listens for `requestCreated` -> `channel.post(card)`,
  remembers request id <-> channel message handle; on a decision from the channel checks the
  sender against the allowlist, then calls `approve`/`deny`; on `requestDecided` (from anywhere)
  -> `channel.resolve(handle, outcome)`. Channel calls never run on the listener's thread (the
  listener contract is "quick, must not throw") - one worker with a queue.
- **Driven port `ApprovalChannel`**: `post(card) -> handle`, `resolve(handle, outcome)`, and a
  callback `decision(handle, senderId, APPROVE|DENY, verifiedSender)`. No Matrix types.
- **Adapter `matrix`**: implementation per the spike (in-process SDK or helper process).

### What the human sees

```
claude-shop wants registry.npmjs.org
Reason: npm install fails with 403 from the sandbox proxy
Reply "y" to approve - react ❌ to deny                (approveBy=reply)
```

The bot adds ❌ (and ✅ with `approveBy=reaction`) itself so the chips are there to tap, and ignores
its own reactions. After the decision the card is edited (`m.replace`):
`✅ approved by @fabian:example.org - sbx: applied` / `❌ denied by ...` / `decided in the
dashboard`. A failed `sbx policy allow` shows in the edit, like in the dashboard.

Sandbox name, host and reason are sent as plain text `body` only - no `formatted_body` built from
agent input (threat-model F4: the reason is attacker-chosen text; no markup, no mentions, length
capped).

### Who may decide

- `matrix.approvers`: a list of full user ids. Empty = the channel posts but accepts nothing.
- The room is created by the user and the bot is invited; the bot joins only the configured room
  id and only on an invite from an approver. It never follows another invite.
- A decision counts only if: sender is an approver, the event relates to a card of a *pending*
  request posted in *this* daemon run or loaded from its state, and - for `approveBy=reply` - the
  event decrypted and its sender device is cross-signing verified.
- Removing a reaction or sending a second answer does nothing: a request is decided once
  (`AlreadyDecidedException` today).
- The bot verifies with the approver once at setup (emoji/SAS or QR, whatever the spike's stack
  supports); an unverified approver device can deny but not approve.

### Configuration and state

- `~/.config/goversbx/config.properties`: `matrix.homeserver`, `matrix.user`, `matrix.room`,
  `matrix.approvers`, `matrix.approveBy`. All absent by default = feature off.
- Secrets never in the config file: access token, device id and the crypto store live in
  `~/.local/state/goversbx/matrix/`, owner-only, like `admin-token`.
- `goversbx matrix setup` (the one new verb - sbx has nothing like it): asks for homeserver, bot
  user and password (or login token), logs in, stores the session, prints the bot's user id to
  invite, waits for the invite, runs the verification, sends a test card. `goversbx status` says
  whether the channel is connected.
- Card handles (request id <-> event id) are in memory, like the requests themselves; when pending
  requests become durable ("living with approvals"), the handles go with them. On start the bot
  edits nothing it cannot map.

### Daemon behaviour

- Matrix down or misconfigured never blocks a request: the dashboard path is unaffected, the
  failure is logged once per outage and shown in `goversbx status`.
- A card that could not be posted is retried while the request is pending.
- `autoOpenDashboard` keeps its meaning. Open question 4: whether a connected Matrix channel
  should suppress the auto-open.

## Threat model additions (`docs/threat-model.adoc`)

- **New trust boundary**: the approver's homeserver and Matrix client devices. With
  `approveBy=reaction` the homeserver is trusted to not forge approvals (fact 4) - stated in the
  README next to the switch. With `approveBy=reply` it is trusted for availability only.
- **A new way to approve exists outside loopback.** Whoever controls an approver's verified
  Matrix device can approve. Same class as an unlocked laptop with the dashboard open, but the
  phone is a second device to lose.
- **The bot's session** (access token + crypto store) on the host lets its holder read every card
  and post as the bot, but not approve (the bot is not an approver; its own events are ignored).
- **F3 stays closed**: nothing listens, the admin API is not exposed, a sandbox cannot reach the
  channel - unless the homeserver's host is allowed for that sandbox, and even then it holds no
  approver credentials. A request *for the configured homeserver host* gets a warning line on the
  card and in the dashboard.
- **F4**: the reason reaches a phone lock screen and a chat history. Plain text, capped, no markup.
  Requests leave the machine for the first time - encrypted for the room's members, but metadata
  (that a request happened, when, who answered) is visible to the homeserver.

## Slices

0. **Spike** (throwaway code, result recorded here as evidence): (a) fact 4 on a test homeserver -
   forge a reaction as the server admin; (b) Trixnity in a native image: login, encrypted send,
   decrypt a reply, verification, metadata effort; (c) the Rust helper: same four steps, binary
   size, build in the release workflow on macOS arm64; (d) how Element X / Element / FluffyChat
   show seeded reaction chips, edits and a reply on the lock screen. Picks the stack and settles
   open question 1 with facts.
1. **Cards out**: `NetworkAccessDecisionApi`, `remoteapproval` context, `ApprovalChannel` port,
   Matrix adapter posting encrypted cards and editing them on `requestDecided`; config, state dir,
   `goversbx matrix setup` (login, join, test card), `status`. No decisions accepted yet - already
   useful as a notification on the phone. Contract tests against a fake channel endpoint.
2. **Decisions in**: device verification in setup, approver allowlist, deny by reaction, approve
   per open question 1, first-decision-wins against the dashboard, threat-model section, README.
   e2e against a real homeserver behind an opt-in `GOVERSBX_E2E_MATRIX_*` knob.
3. (candidate) credential requests (epic 0004) as cards; (candidate) a second `ApprovalChannel`.

Scenarios (Given/When/Then with test levels) are written per slice, after the spike - the
contract depends on its outcome.

## Open questions (for the user, before acceptance)

1. **Approve by reaction or by encrypted reply?** See "The decision reaction is not end-to-end
   protected". Proposal: reply by default, reaction as a switch for a self-run homeserver. Which
   homeserver will the approver account be on - your own, or a public one (matrix.org)?
2. **A Rust helper binary in the repo and the Homebrew formula** - acceptable if the spike shows
   Trixnity does not survive the native image? The alternative then is shipping Matrix support
   only in a JVM build.
3. **One approver (you) or several?** The allowlist is a list either way; with several, the card
   names who decided.
4. **Dashboard auto-open** while Matrix is connected: keep, suppress, or suppress only when no
   dashboard was open in the last n minutes?
5. **`goversbx matrix setup` as a new verb** - or keep the launcher as it is and document the
   config keys plus a one-off `goversbx-daemon matrix-login`?
6. **Bot account**: is asking the user to register one (and to create the room and invite it)
   acceptable for "easy entry", or should setup create the room and invite the approver itself?
