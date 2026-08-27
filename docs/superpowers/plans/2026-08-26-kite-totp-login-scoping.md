# Scoping: automating the daily Kite login with TOTP — 2026-08-26

**Status: SUPERSEDED 2026-08-28 — BUILT, DEPLOYED AND ARMED. Everything below is the pre-build
scoping and is kept for its reasoning, not its status.**

> ⚠️ **The line that used to sit here read "SCOPING ONLY — no code, no credentials, no login
> attempted."** All three are now false. A real login HAS been run against the live broker.
>
> **What actually happened, in order:**
>
> - **Built and merged** [#1501](https://github.com/prashantm912/artha-yantra-2/pull/1501)
>   `d2924f06` — 3 Codex rounds + 1 same-vendor round, 3 Criticals and 6 Majors closed.
> - **Boot catch-up** [#1510](https://github.com/prashantm912/artha-yantra-2/pull/1510) and the
>   **watchdog half** [#1511](https://github.com/prashantm912/artha-yantra-2/pull/1511): a cron
>   never backfills, so a late boot got no login AND no alert. The box started 08:40 on 08-27
>   and 08:41 on 08-26 — this was the common case, not an edge case.
> - **First real login, 2026-08-27 21:25 IST: FAILED at AUTHORIZE** —
>   `UNEXPECTED_RESPONSE (redirect carried no request_token)`. Credential POST and 2FA both
>   SUCCEEDED, so the TOTP generation and credential handling are demonstrated working against
>   the real broker. Only the final step failed.
> - **Fixed** [#1515](https://github.com/prashantm912/artha-yantra-2/pull/1515) `ff6ef539`: the
>   authorize step is on the LOGIN host, not the API host. ⚠️ **WHY it failed is NOT settled** —
>   an intermediate 302 carrying no token, or cookie scope; only the failing STEP was measured,
>   and the fix is identical either way. Do not let the fix imply the mechanism.
> - **ARMED 2026-08-28** (owner decision), deployed and probed. The first morning run is the
>   real test of the fix.
>
> **Where §2a's security objection landed:** the owner placed the three secret files, so the
> TOTP seed now sits beside the password on this box — the trade §3 named, accepted knowingly.
> `crossOriginCookies` remains EMPTY and the fix deliberately removed the need to send a
> credential across origins rather than authorising one.
>
> **Live traps now live in CLAUDE.md** (auto-login bullet), which is where an operator will
> actually look. This file is history.
**Basis:** read-only exploration on 2026-08-26. Claims labelled `computed` / `sourced` / `recalled` / `assumed`.

## Verdict

**Feasible and small.** Everything from `request_token` onward already exists and is tested; the missing piece is three
HTTP steps (credential POST → TOTP POST → authorize GET) plus an RFC-6238 code generator (~30 lines of JDK crypto, **no
new dependency**), landing in the same hand-rolled, WireMock-testable Kite REST pattern this repo built deliberately for
exactly this class of work.

**The single biggest objection is not technical.** Automating the login requires storing the TOTP seed next to the
password, which **collapses Zerodha's 2FA into 1FA on this box** — and the two login endpoints involved are
**undocumented** and can break without notice at 06:00 with the market opening at 09:15. **The failure design matters
more than the happy path.** Secondary objection: this may sit outside Zerodha's terms — an **owner call**, flagged, not
ruled on here.

---

## 0. Premise verification — none refuted

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| 1 | `KiteAuthController` receives `request_token` and exchanges immediately | **CONFIRMED** | `KiteAuthController.java:45-47`; javadoc `:40-44` "single-use and dies in minutes". A manual `POST /api/v1/auth/kite/session` fallback exists at `:65-68` |
| 2 | `LiveSessionWireClient` posts `/session/token` with `SHA-256(api_key + request_token + api_secret)` | **CONFIRMED** | `LiveSessionWireClient.java:44-61`. ⚠️ `:62-67` — a rejected token throws `KITE_TOKEN_EXPIRED` with the comment **"re-login is the only cure, never retry"** |
| 3 | `KiteSessionStore` persists the token; tokens die ~06:00 IST | **CONFIRMED** | `:70-79` AES-GCM persist; `:129-138` `tokenValidUntil()`; restart-reload `:45-67` |
| 4 | `LiveInstrumentDumpGateway` throws `KITE_TOKEN_EXPIRED`; the 08:30 sync needs a live token | **CONFIRMED** | `LiveInstrumentDumpGateway.java:64-71`; `InstrumentSyncScheduler.java:31` cron `0 30 8 * * MON-FRI` |
| 5 | Everything from `request_token` onward exists; only the browser leg is manual | **CONFIRMED** | callback → `LiveKiteSessionService.exchange` (`:54-60`: exchange → persist → status → **`rearmFeed()`**) → wire client → store. `loginUrl()` at `:49-51` |

### ⚠️ Nothing in this codebase clears the token at 06:00

`computed`: `tokenValidUntil()` is **advisory only** — no scheduler reads it. The only clearers are `markExpired()`
(`KiteSessionStore.java:89-92`) and `clear()`, and `markExpired` is called **solely** from `SessionHealthProbe.probeNow()`
(`:53-56`) when the 5-minute `GET /user/profile` probe (`:36`, `fixedDelay = 300_000`) returns 401/403.

**So the real mechanism is:** Kite invalidates server-side around 06:00 → our probe notices **within ~5 minutes** → state
flips `TOKEN_EXPIRED`, the token is cleared, every outbound REST call short-circuits and retries are suppressed.

### What actually degrades if no login happens at all

The H26-plan finding checks out: `InstrumentRegistry` is rebuilt **from the database** at startup and live "defers to the
08:30 scheduler" (`InstrumentRegistry.java:16-18`, `SyncBootstrap.java:12-18`), so **the stack already runs each morning
on yesterday's persisted master.**

- **Hard-fails:** the 08:30 instrument sync; `LiveQuoteGateway` and `LiveHistoricalCandleGateway` (both take
  `AccessTokenProvider`, `LiveKiteConfig.java:246-288`) — live quotes, cache-first candle tail re-fetches and gap backfill
  all short-circuit; the ticker factory throws *"no live Kite session yet — complete the morning ritual first"*
  (`LiveKiteConfig.java:216-222`), so **no tick feed → no bars → no scalper signals**; the daily `ContractCanary`.
- **Degrades only:** `SessionGateway.sessionActive()` (`:72-86`) returns false into `FeedPipeline` and the status surface.
  Upstox-token surfaces (analytics, SPAN margin, the 18:20 canary) are unaffected. Evening EOD/bhavcopy does not need Kite.
- **Timeline:** ~06:00–06:05 state flips → **08:30 sync fails** (new contracts/expiries never load) → **09:15 open with no
  tick feed; the live engine is data-starved for the whole session.** Swing/EOD survive on bhavcopy.

---

## 1. What is missing

Only the browser leg that produces `request_token`. `recalled` — **not verifiable from this repo and MUST be captured
from a real login before any stub is written**: (a) a credential POST returning a `request_id`, (b) a TOTP/2FA POST
referencing it, establishing session cookies, (c) a GET of the authorize URL `loginUrl()` already builds, whose redirect
`Location` carries `request_token`.

⚠️ **Steps (a) and (b) are NOT part of the published Kite Connect API.** Only (c)'s starting URL is.

## 2. The three units

All in `services/market-data-service`, package `…kite.session.autologin`, live-profile-gated, **feature-flagged off by
default**. Nothing outside market-data-service changes; the output is a `request_token` handed to the **existing**
`exchange()` — a seam already proven callable outside the browser callback (`KiteAuthController:66-68`).

| Unit | What | Tested |
|---|---|---|
| **1 — credential step** | `LoginWireClient` mirroring the `SessionWireClient` split; new base-URL property (the login host differs from `api.kite.trade`) so WireMock can stand in; cookie-carrying client | WireMock: assert form fields and captured `request_id`; **a 4xx must be reported terminal and non-retryable** |
| **2 — TOTP step** | A pure `Totp` (seed + clock → 6 digits, HMAC-SHA1, 30 s step, **JDK only**) + the 2FA call. Seed is a third Docker secret, read per call, never retained or logged | `Totp` against **RFC 6238 Appendix B published vectors** (the RFC's own test key — no real seed near tests); WireMock wrong-code 4xx asserting **no second attempt** |
| **3 — authorize + orchestrate** | Same client with redirects disabled, reads `request_token` from `Location`; orchestrator hands it to the existing `exchange()`. `@Scheduled` (IST, calendar-gated, `monitorTaskScheduler`), after 06:00 and well before 08:30, **gated on `state() != CONNECTED`** so a manual login makes it a no-op | WireMock end-to-end into the existing stubbed `/session/token` (`KiteOauthIntegrationTest` pattern); redirect-without-token → terminal + alert; already-CONNECTED → no-op |

**Sequencing:** `Totp` first (pure, RFC-vectored), then the wire client, then orchestrator + schedule + alerts.
**Tier: HOLD** — touches the live feed's daily precondition and handles secrets ⇒ rationed pre-merge Codex review.

---

## 2a. ⚠️ OWNER ANSWER 2026-08-26 — TOTP IS CURRENTLY **DISABLED** ON THE ACCOUNT (can be enabled any time)

This settles open question 3 below, and **it changes §3's central argument in the owner's favour.** Read it before §3.

**§3 says automating "collapses 2FA into 1FA". That framing assumed TOTP was ALREADY enrolled** and that we would be
copying an existing second factor onto this box. With TOTP disabled today, the comparison is different:

| | attacker **without** host access | attacker **with** host access |
|---|---|---|
| **today** (TOTP off) | no TOTP barrier | full access |
| **after enrolling + storing the seed** | **now faces TOTP** — a barrier that does not exist today | full access (unchanged) |

**So enrolling TOTP for this purpose is plausibly a NET IMPROVEMENT to the account's overall posture**, with a specific
carve-out for this host. **The honest objection narrows** from *"you are destroying your 2FA"* to
**"host compromise becomes full interactive account access"** — still serious, still the thing to weigh, but a materially
smaller claim than §3 as written. §3 is left intact below rather than rewritten, because its reasoning is correct for the
case it assumed; this section states which case actually applies.

**Two consequences:**

1. ⚠️ **Enrolling TOTP is now a PREREQUISITE, not an assumption** — and §3's suggested mitigation ("enrol a *separate*
   external TOTP used only for this flow") **may not apply**: the seed enrolled would be *the* account's second factor,
   not a spare. If a separate enrolment is possible, it is still worth preferring; if not, say so plainly rather than
   implying a spare exists.
2. ⚠️ **Sequencing — do not change the login and automate it in the same step.** Enable TOTP → confirm the **manual**
   login works end-to-end → only then automate. Otherwise a newly-changed login flow and an untested automation arrive
   together, at 06:00, for a 09:15 open.

⚠️ **A consequence for §1 that is easy to miss:** if TOTP is off today, the current manual login does **not** use it, so
the three-step flow this document scopes describes a login **that does not exist yet on this account**. The one-time
manual observation in open question 1 must therefore be taken **AFTER** TOTP is enrolled — observing today's flow would
capture the wrong contract.

## 3. ⚠️ The security trade — stated plainly

**Automating this collapses two-factor authentication into one factor on this machine.** Today the seed lives on a
separate device: the second factor is *something you have*, apart from *something you know*. Automated, the seed sits in
the same directory, readable by the same OS identity, as the password.

**Anyone or anything that can read `deploy/secrets/` then holds everything needed to log into the Zerodha account
interactively** — not merely an API session. That is a **categorically larger blast radius** than today's stored
artifacts: `api_key`/`api_secret` alone cannot log into the broker account, and the access token dies daily. **No storage
mechanism removes this trade**; storage choice only narrows *who on this box* can read it.

⚠️ **Rotation is worse here than for any other secret.** Rotating a TOTP seed means **disabling and re-enrolling 2FA** on
the account. Secret rotation is already a parked, owner-gated residual of SEC-01 — this makes that residual harder.

**What is already good, so the trade is weighed fairly:** `ay.ps1:96` defines `Get-SecretAclViolations` and `:292` runs it
on **every `up`** — the ACL guard is enforced, not aspirational; `.env` carries exactly three ACEs (owner / SYSTEM /
Administrators, verified live 2026-08-26). The Docker-secrets convention is consistent across `postgres_password`,
`kite_api_key`, `kite_api_secret`, `artha_master_key`, `upstox_analytics_token`, with per-call file reads and a fail-fast
startup check (`LiveKiteConfig:34-60`).

**Recommended storage (no values ever specified):** three new Docker secret files under the existing convention, surfaced
via `*-file` properties, read per call, never retained or logged, added to `liveCredentialsFailFast` **only when the flag
is on** (the stack must still boot without them when off), and added to `Get-SecretAclViolations`' path set. Why not
alternatives: this inherits the ACL guard, the gitignore and the fail-fast check with zero new mechanism; Windows
DPAPI/credential-manager cannot reach inside Linux containers; an external secrets manager is a new moving part whose
dominant residual risk (host compromise ⇒ full account access) is **identical** anyway.

**Mitigation worth building in:** where the account permits it, enrol a **separate external TOTP** used only for this
flow (`recalled` — verify), so the stored seed is not the same one the owner's phone holds.

## 4. ⚠️ Failure design — the part that will actually bite

The credential and TOTP endpoints are undocumented internals. Zerodha can change paths, payloads, or add a captcha or
device check **without notice**. A break lands ~06:00–07:30 IST with the open at 09:15 — roughly a 90-minute human window.

- **ONE attempt per trigger. NEVER a retry loop on credential or TOTP failure.** A wrong-password loop is how accounts get
  locked, and a locked **broker account** on a market morning is strictly worse than the ritual this replaces. The
  codebase already encodes this doctrine one step along (`LiveSessionWireClient:63` *"re-login is the only cure, never
  retry"*; `SessionHealthProbe` suppresses retries).
- **Classify:** 4xx on login/2FA/authorize = **terminal for the day** (alert, stop, manual flow remains the path);
  network/5xx = at most **one** delayed re-attempt, then terminal; an unexpected page or redirect shape = terminal — that
  is the *"Zerodha changed something"* signature.
- **Alarm channel: `NtfyClient`** — the existing owner-alert path in this service (contract-canary precedent). ⚠️ Telegram
  lives in strategy-signal's notifier module and is **not reachable from market-data-service**. Send the failure *class*
  only — never credentials, never response bodies that might embed them.
- **Maximum time-to-owner-notification: 5 minutes**, fired synchronously from the orchestrator, not inferred from a
  downstream symptom. An attempt at ~07:30 alerting by ~07:35 leaves ~55 min before the 08:30 sync and ~100 min before the
  open for the manual fallback.
- ⚠️ **Silence is also a failure.** If state is not CONNECTED by a deadline (~08:15) a watchdog must alert **regardless of
  why** — the job not running looks identical to the job succeeding, from the outside.

## 5. What it buys — and what it does not

**Buys:** removal of the daily manual ritual; a stack already CONNECTED before the 08:30 sync and the 09:15 open without
owner presence; faster restart recovery on mornings the owner is away. `sourced`: cold-morning recovery measured Kite
login latency at ~131 s of a ~182 s recovery — automation removes the *human wait*, not the 131 s.

**Does NOT buy: the ₹500/month subscription.** That is ledger **H26**, a separate decision (owner chose Upstox-only with
Kite dormant). **The two items are independent, and this one is worth doing even if H26 proceeds** — H26 is HOLD-tier and
gated on an identity redesign plus a week-long budget measurement, so Kite remains the primary live path for the interim,
and every interim morning carries both the ritual and the 06:00→09:15 failure window this de-risks.

> ⚠️ **But note the interaction, and hold both decisions in view together:** H26's surviving justifications are **cost**
> and **removing this login**. Shipping auto-login **weakens H26's case to cost alone**.

## 6. ⚠️ Terms of service — OWNER DECISION, not ruled on here

What to check: the **Kite Connect developer terms** and the **account Terms of Use** for language on automated or scripted
access **to the login flow specifically** (the API terms cover the API; the login page is not the API); Zerodha's stated
position in their developer forum (`recalled` — they have publicly discussed TOTP auto-login patterns; verify currency);
whether SEBI's retail algo framework touches automated *session establishment* as distinct from *order placement* — this
platform is paper/research today, which may matter to that reading; and the practical enforcement risk against the manual
fallback remaining available. **If the reading is unfavourable, the status quo stands and this document is the record of
why it was declined.**

## 7. Open questions not settleable from code

1. **The exact login-flow contract** (paths, field names, response shapes, cookie names) — `recalled` only. Must be
   captured by a **one-time manual observation of the owner's own login** before any stub is written. The stubs can then
   only ever pin *our recorded understanding* of an undocumented API.
2. **Exact server-side expiry semantics** — is ~06:00 a hard kill, and does a connected WS ticker survive past it until
   reconnect? Affects only how early the job can be scheduled.
3. ✅ **ANSWERED by the owner 2026-08-26: TOTP is currently DISABLED and can be enabled any time.** See §2a — this makes enrolment a PREREQUISITE and narrows §3's objection. Superseded, kept for provenance: ⚠️ **Whether the account's 2FA is TOTP at all.** If it is app-approval or SMS, unit 2 does not apply as scoped and
   enrolment is a prerequisite. **The owner knows; the code does not.**
4. **Host clock discipline** — TOTP needs ±30 s of true time. `assumed` Windows time sync is on; verify before build.
5. ⚠️ **Captcha / device-check risk** — a captcha would make this approach **non-viable as scoped**, and bypassing one is
   out of bounds by this platform's own rules.
6. **Scheduling window** — the earliest time a fresh login reliably succeeds after the old token dies (interacts with Q2).
   ~07:30 is a placeholder for the owner to set.
7. **SEC-01 residual** — whether the parked rotation decision changes shape given a stored seed (rotation = 2FA
   re-enrolment). Re-raise when SEC-01 is next reviewed.
