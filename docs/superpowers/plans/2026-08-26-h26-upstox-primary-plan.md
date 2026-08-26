# H26 — Upstox primary, Kite rate-limit fallback: implementation plan

**Date:** 2026-08-26 · **Tier:** HOLD + live-engine + migration-bearing · **Status:** PLAN ONLY, no code written
**Author:** Fable planner (subagent); premises verified against the working tree, refutations re-verified by the Architect.

> ⚠️ **Read §4 (kill criterion) and §7 (the cheaper alternative) BEFORE §1.** This plan may conclude that H26 should
> NOT be built. Two of its three original motivations are dead or weak, and a much smaller change may buy the surviving one.

---

## 0. Premise verification (STEP 0)

All load-bearing premises confirmed except one, which is **refuted**.

| # | Premise | Verdict | Evidence |
|---|---|---|---|
| 1 | Source flags select mutually exclusive beans; flipping REMOVES the Kite bean | **CONFIRMED** | `LiveKiteConfig.java:200-203` (ticker), `:242-245` (quotes, `havingValue="kite", matchIfMissing=true`), `:268-272` (candles) |
| 2 | No composite, no delegate, no breaker, no "on 429 try Kite" | **CONFIRMED** | `UpstoxQuoteGateway.java:41-49`; the only breaker in the feed path is `kite-ticker` (`LiveKiteConfig.java:231`). No composite class anywhere under `services/` |
| 3 | `LiveInstrumentDumpGateway` unconditional, no source flag | **CONFIRMED** | `LiveKiteConfig.java:291-299` — the only bean in that config with no `@ConditionalOnProperty` |
| 4 | Master = Kite `/instruments/{NSE,BSE,NFO,BFO}` CSV, 08:30 IST | **CONFIRMED** | `LiveInstrumentDumpGateway.java:35,77`; `InstrumentSyncScheduler.java:31` |
| 5 | Dump needs a live Kite token, throws `KITE_TOKEN_EXPIRED` | **CONFIRMED** | `LiveInstrumentDumpGateway.java:64-71`; tokens die ~06:00 IST (`KiteSessionStore.java:129`) |
| 6 | Kite `instrument_token` is the canonical handle for EVERY feed incl. Upstox | **CONFIRMED as _handle_, not identity** | `UpstoxTickerHandle.java:21-22`; `TickNormalizer.java:29-31`; `SubscriptionRegistry.java:120-134,242-249`; `UpstoxLiveTickFeedAdapter.java:70-71,161-179`. ⚠️ **Stored identity is `(exchange, tradingsymbol)`** — `V002__instruments.sql` (token nullable, non-unique) + `docs/symbol-normalization.md`. **This materially eases the fix** |
| 7 | Upstox supplies the master login-free but carries no Kite token | **CONFIRMED** | `UpstoxFnoMasterClient.java:30,41,84`; `UpstoxEquityMasterClient.java:27,58`; `UpstoxInstrumentMaster.java:33-43` has no Kite-token field |
| 8 | No `UpstoxHistoricalCandleGateway`; `source.candles` = kite \| openalgo | **CONFIRMED** | `application.yml:127`; binders at `LiveKiteConfig.java:268-288`, `OpenAlgoConfig.java:53`. "API work done" is only PARTLY true — parsing exists for expired/stock-chain, no live-path gateway |
| 9 | Three dormant transport-only seams (U1/U2/U3) | **CONFIRMED** | `UpstoxMarketFeedConfig.java:42`; `UpstoxQuoteGateway.java:53`; `UpstoxOptionChainQuoteSource.java:33` |
| 10 | Step 1 (rate instrumentation, #1464) shipped | **CONFIRMED** | `UpstoxRateLimiter.java:146,151,158,163`; `KiteCallExecutor.java:102,110` |
| 11 | The two false "Kite stays the fallback" comments corrected | **CONFIRMED** | `UpstoxQuoteGateway.java:41-49` carries the correction in place (#1475) |
| 12 | BE-twin population is **140**, "recorded in `TokenResolverAdapter`'s javadoc" | ⚠️ **REFUTED, then RE-MEASURED** | `140` appears **zero times** in that file. The javadoc says **27** (`:52-53`), **twelve** (`:71`), **160 of ~549** (`:77-78`). **Architect measured the live DB 2026-08-26: tokenless-with-twin = 25, inactive-with-active-twin = 145, inactive-with-token = 583.** So 140 was plausibly right when written (08-21) — **the population MOVES.** The error is the false citation plus quoting a drifting number as fixed. **Re-derive; never quote** |
| 13 | Outage/resilience motivation is dead | **NOT RE-DERIVABLE FROM CODE** (`recalled`) | This plan justifies nothing on resilience |
| 14 | Live rate reading (413/1800 etc.) | **NOT RE-MEASURED by the planner** | `recalled`; re-measure at build time |

**Additional finding that shrinks the crux** (`sourced` `InstrumentRegistry.java:16-19,44-59`): the token→key registry is
rebuilt **from the database at startup**, and live boot "defers to the 08:30 scheduler — the morning token may not exist
yet". **The system already runs every morning 06:00→08:30 on yesterday's persisted Kite tokens.** So "no Kite login today"
degrades to "instruments = yesterday's rows" — survivable for everything except **newly listed contracts** (new weekly
expiries above all).

> **The identity problem is therefore NOT "replace the identity scheme". It is "make the master's daily refresh
> login-free and token-optional".** That is a much smaller item than the ledger row implies.

---

## 1. Design

Four phases / seven units. Step 1 already shipped.

```
Phase A (identity)       U-A1 migration V059
                         U-A2 Upstox master sync + grammar-parity shadow soak
Phase B (REST composite) U-B1 composite quotes
                         U-B2 Upstox candles + composite candles
Phase C (ticker)         U-C1 failover ticker      (gated on the §17.3 latency A/B)
Phase D (un-gate)        U-D1 Kite session optional  <- point of no return
```

### 1.1 The composite seam

**A new flag VALUE, never a new flag.** `artha.marketdata.source.quotes` gains `upstox-primary`; the existing
`kite` / `upstox` values stay **byte-identical**. That is what lets every unit ship dark. A `CompositeQuoteGateway` binds
on `havingValue="upstox-primary"` and *constructs* both delegates, so the conditional structure stays one-bean-per-port
with no `@Primary` juggling.

**Failover triggers (REST families):**

1. **Pre-wire refusal** — `UpstoxRateLimiter.tryAcquire()` returning false (`:179-199`). Fires *before* any wire call and
   is already instrumented via `ay_upstox_live_refused_total`. This is the H26-mandated trigger; no 429 parsing needed.
2. **Transport failure** — 429/5xx/timeout, plus a resilience4j breaker `upstox-quotes`; half-open probes trip it back.
3. **Mapping gap (partial failure)** — unmapped keys are **routed** to Kite within the same call (split the batch, merge
   the maps), not "fallen back to". A key **neither** source serves increments `ay_md_composite_unserved_total{family}`.
   **Silent absence is the one behaviour that must not be generalised.**
4. **Kite-session guard** — fallback fires only when `sessionActive()` (`LiveKiteConfig.java:72-86`); otherwise the partial
   Upstox result is served and `ay_md_fallback_unavailable_total` counts it. Fallback is **best-effort by design** after
   step 5 — **state that in the yml comment so we do not mint false doc comment #3.**

**The ticker is a different animal and is honestly NOT a rate-limit problem** — the Upstox WS consumes no REST budget
beyond the authorize call. Its composite fails over on **feed health** (reconnect-policy exhaustion, or a
no-ticks-while-subscribed watchdog during session hours) and swaps at a bar boundary. Justification: completing the
primary/fallback contract — **not** outage resilience.

### 1.2 The identity decision — RESOLVED, not handed down as a fork

**Decision: no PK migration, no wholesale re-key.** Keep `(exchange, tradingsymbol)` as identity (it already is), demote
the Kite token from "mandatory daily-refreshed handle" to "optional enrichment", add a persisted `upstox_instrument_key`,
and mint deterministic surrogate tokens **only** for rows lacking a Kite token.

The brief's two options are not really a fork — each alone fails:

- *Surrogate-only* breaks the Kite fallback: surrogate tokens cannot go on the Kite wire, which is token-addressed.
- *Migration-only* re-plumbs `RawTick` / `SubscriptionRegistry` / `InstrumentRegistry` / `TickNormalizer` / both ticker
  adapters — the widest possible blast radius through the live tick path, for zero functional gain.

Concretely:

1. **V059** — add `upstox_instrument_key TEXT NULL`, `kite_last_seen_at`, `upstox_last_seen_at`.
   ⚠️ **`instruments_staging` is `LIKE instruments INCLUDING DEFAULTS` created at V002 — the migration must ALTER BOTH.**
2. **`UpstoxInstrumentDumpGateway` + sync pass** — parses `complete.json.gz`, synthesises canonical Kite-grammar
   tradingsymbols from the structured tuple, upserts. ⚠️ **Per-source tombstone scoping is load-bearing:** each source may
   only tombstone rows it previously asserted (`*_last_seen_at`), or the Upstox sync silently deactivates every Kite-only
   row — including all `-BE` twins, reigniting H29/H36 **at scale**.
3. **Grammar-synthesis parity harness — the crux inside the crux.** Kite's F&O tradingsymbol grammar (especially
   compressed weekly-option forms) is not trivially synthesisable, and a plausible-but-wrong symbol creates a **duplicate
   row, not an error**. So the sync ships in **shadow mode** for ≥5 sessions, writing nothing authoritative, with a daily
   diff over the full F&O universe. **Verify check: `ay_instrument_master_synth_mismatch_total` = 0 across ≥5 sessions
   including one weekly expiry, red→green.** Until green, the Upstox master may not become authoritative.
4. **Surrogate tokens** — deterministic hash of `(exchange, tradingsymbol)` with the high bit set, collision-checked at
   sync time, minted only for Kite-tokenless rows. ⚠️ **Boundary guard:** every Kite wire boundary must REFUSE and COUNT a
   high-bit token (`ay_kite_surrogate_token_refused_total`) — a surrogate reaching the Kite wire is precisely the H29-class
   silent fail-soft we must make loud.
5. **BE twins** — Kite-dump artifacts; per-source tombstoning preserves them untouched, and Upstox equity keys are
   ISIN-addressed (`NSE_EQ|<ISIN>`) so the BE-suffix grammar problem never arises on the Upstox path. Verify twin-count
   stability across the first Upstox-authoritative sync. ⚠️ The population **moves** — measured 25 / 145 / 583 on 2026-08-26.

---

## 2. Units

| Unit | Verify check (red→green) | Parity exposure | Migration | Ships dark? | Codex slot? |
|---|---|---|---|---|---|
| **U-A1** V059 + staging ALTER | flyway-init forced + `to_regclass` / `information_schema` probe; grant tests | none | **V059** | yes | **yes** |
| **U-A2** Upstox master sync, shadow diff, surrogates, per-source tombstones | mismatch counter = 0 over ≥5 sessions incl. one expiry day; twin-count stability | identity corruption IS the exposure — the diff harness is the proof | rides V059 | yes (default off; shadow writes nothing authoritative) | **yes** |
| **U-B1** `CompositeQuoteGateway` | limiter refusal → Kite serves; unmapped key → Kite serves; both fail → `unserved` increments | live-engine surface; rerun Golden + Parity per house rule | none | yes | **yes** |
| **U-B2** `UpstoxHistoricalCandleGateway` + composite | WireMock A/B: same instrument-day via both gateways → value-identical bars, or a documented bounded delta; `source` label = the fetching impl | ⚠️ `upsertAuthoritativeAll` **REPLACES** values (#507) — a wrong Upstox bar silently rewrites history | none | yes | **yes** |
| **U-C1** `FailoverTickerHandle` | forced-failover IT: kill the Upstox WS mid-stream → Kite takes over, tokens replayed, no downstream key change | tick path = live signal input; §17.3 latency A/B must be green first | none | yes | **yes** |
| **U-D1** Un-gate the Kite session | a deliberately-skipped login: Upstox paths green, degradations counted not alarmed, next-day recovery clean | operational doctrine | none | **no — this IS the flip** | **yes** |

All units: owner sign-off per the HOLD tier; nothing auto-merges.

---

## 3. Sequencing

**A → B → C → D**, and A1 → A2.

- **A before B** — the composite's partial-failure semantics are only *decidable* once the master records, per row, which
  handles exist. Reversing costs a rework cycle; it breaks nothing.
- **B before C** — the §17.3 latency A/B wants Upstox already carrying quote/candle load so it measures the real
  contention regime.
- **C before D** — un-gating the login while the ticker is still Kite-primary means a skipped login **kills the live tick
  feed**. This is the only ordering violation that breaks a live money path rather than merely costing rework.
- **Point of no return: inside D** — the first live session the Upstox master syncs *authoritatively* without a same-day
  Kite dump. From then on `instruments` holds rows and updates Kite never asserted; if the grammar synthesis or tombstone
  scoping is wrong, the corruption sits in the identity table everything joins through. Everything up to and including C
  is one flag-flip from Kite-primary, reversible in minutes. **This is why A2's shadow soak gates the entire plan, and why
  per-source tombstoning must land in A2 rather than D.**

---

## 4. ⚠️ Kill criterion — read before building anything

**(1) The rate motivation dies if the projection does not fit.** Before U-B1 merges, compute from ONE FULL WEEK of step-1
metrics:

```
projected_upstox_30m = current ay_upstox 30m peak
                     + Σ over migrating Kite families of (Kite 30m request rate × Upstox call-shape factor)
```

The call-shape factor is mandatory, not optional: the OI snapshot's ~71 batched `/quote` calls per ~2-minute cycle map to
~6 `/v2/option/chain` calls under Upstox, so **raw request transfer is the WRONG model**.

> **STOP RULE: if `projected_upstox_30m > 1440` (80% of the 1800 ceiling), STOP.** Upstox-primary would make the rate
> problem *worse*, not better, and the item survives only on motivation (2).

⚠️ **The raw-transfer model ALREADY FAILS** — Kite QUOTE at roughly 2.5k per 30 min against about 1,387 remaining Upstox
headroom. Only the remapped model can pass, which is exactly why the modelling step is mandatory rather than a formality.

Conversely, if re-measured full-session Kite usage never exceeds ~50% of its own family budgets, **the rate motivation is
already moot** and the item should be re-scoped to identity + login only (owner call).

**(2) The login motivation dies if A2 cannot go green.** If the grammar-synthesis mismatch counter cannot reach 0 (or a
bounded, enumerable exception set) within roughly three weeks of shadow soak, the login-free master is not achievable at
acceptable risk: stop at B, keep the 06:00 ritual, and record H26 as partially delivered.

---

## 5. What could go wrong SILENTLY

1. **A plausible-but-wrong synthesised tradingsymbol** duplicates an instrument row — subscriptions split across two keys,
   candles land under the wrong symbol, every test green (no fixture holds next week's weekly). Caught only by the shadow
   diff, which is why it gates authority.
2. **Cross-source tombstoning** — the Upstox sync deactivates Kite-only rows (`-BE` twins, index naming variants such as
   `INDIA VIX`); fail-soft and invisible, the exact H29 shape.
3. **Surrogate token reaching the Kite wire** — Kite answers `400 invalid token` and `LiveHistoricalCandleGateway`
   fail-softs to stale cache silently.
4. **Candle `source` label poisoning on fallback** — a Kite-fallback fetch persisted as `upstox` (or vice versa) destroys
   provenance diagnosability (#507). The label must come from the *fetching* delegate, per call.
5. **Tick-semantics drift** — Upstox `vtt` volume vs Kite cumulative-day volume, price scaling, `ltt`-absent ticks
   defaulting to receive-time (`UpstoxLiveTickFeedAdapter.java:183-186`). Bars come out *slightly* different; CI green,
   deploy green, indicators drift.
6. **Silent-absence generalisation** — a buggy composite reproduces today's drop-unmapped behaviour with a green suite,
   because the fallback branch is never exercised. The red-proof must restore the literal no-delegate body.
7. **Batch/live budget inversion at 09:15** — Upstox batch jobs fill the 30-minute window just before the open, forcing
   constant fallback to Kite. "Upstox primary" in name only, and nothing alarms because Kite serves fine.
8. **Stale Upstox CDN master on expiry morning** (12h cache, `UpstoxFnoMasterClient.java:47`) — new weekly contracts
   unresolvable on the primary; with no Kite login that day, unsubscribable entirely.

---

## 6. Open questions for the Architect

1. **Upstox v3 historical-candle API** — interval coverage, intraday depth, per-call ranges, and whether it is entitled on
   the analytics token. Needs a live probe spike before U-B2 can be sized.
2. **Upstox WS instrument cap** vs Kite's 3,000-per-connection — determines whether U-C1 needs multi-connection work.
3. **Owner acceptance of best-effort fallback** — after D, a no-login day leaves newly listed contracts uncovered. Is that
   acceptable, or must the login ritual survive as *recommended* rather than required?
4. **Kite QUOTE decomposition** — how much of the ~13.5k partial-day requests is the OI snapshot cycle vs signal-path spot
   quotes. Needed for §4. One week of `ay_broker_rest_calls_total{family}` plus a call-site census.
5. **Surrogate token range safety** — confirm from live data that Kite tokens never set the chosen high bit.

---

## 7. ⚠️ The cheaper alternative that may obsolete half of this

The owner asked whether the daily Kite login can be automated with TOTP. `computed` from the code: **everything from
`request_token` onward is already built** — `KiteAuthController:46` receives it, and `LiveSessionWireClient` exchanges it
via `POST /session/token` with `SHA-256(api_key + request_token + api_secret)`. Automating adds three steps: a credential
POST yielding `request_id`; an RFC-6238 TOTP POST yielding session cookies; an authorize GET whose redirect carries
`request_token`.

**If feasible, it removes motivation (2) entirely** — the motivation the identity blocker otherwise defeats.

### ⚠️ 7a. Motivation (4), added by the owner 2026-08-26: COST — and it does not behave like the others

**Kite Connect costs ₹500/month; the Upstox API is free.** ≈ **₹6,000/year**, certain and recurring — not a projection,
unlike the rate-limit case. It is also **the only motivation TOTP automation does NOT touch**: automating the login still
leaves the subscription bill. So on the face of it, cost rescues H26 from the "maybe do not build it" verdict above.

**But it does not rescue H26 AS SPECIFIED, and this is the sharpest point in this plan.**

The Kite Connect subscription is billed per app per month **regardless of usage**. **Keeping Kite as a fallback keeps
paying it in full.** A demoted, rarely-exercised Kite fallback saves ₹0. The saving materialises only if Kite is removed
**entirely** — no fallback, no session, no dump, no subscription.

That splits H26 into two end states with very different risk profiles, and the owner must pick one:

| | **(a) Upstox primary + Kite fallback** — H26 as written | **(b) Upstox only** — Kite removed |
|---|---|---|
| Buys | burst headroom; login removal | **₹6,000/yr**; login removal |
| Costs | ₹500/mo continues | every failure mode in §5 becomes **unmitigated** |
| Safety net | Kite covers mapping gaps, rate refusals, feed loss | **none** — a bad synthesised symbol or an Upstox outage has no second source |
| Reversibility | one flag flip, minutes | re-subscribing, re-authing, re-syncing |

⚠️ **(b) contradicts a constraint this plan was written under** — "Kite must remain fully working throughout" — and it
removes the very thing §1.1's composite exists to provide. The composite work (U-B1, U-C1) is **only worth building for
(a)**; under (b) it is scaffolding you would delete on the last day.

⚠️ **And note what (b) does to the §5 catalogue:** every silent-failure mode there is currently survivable *because Kite
answers when Upstox cannot*. Under (b) the shadow-soak gate in U-A2 stops being a precaution and becomes the only thing
standing between a grammar bug and an unrecoverable identity table.

**Recommendation on cost:** ₹6,000/yr is a real saving but a small one against a multi-phase migration through the live
money path, and it is **not** obtainable from the plan as sequenced. Decide (a) vs (b) **before** Phase A, because it
changes which units are worth building at all. If (b), the plan needs re-planning around a no-fallback end state — that is
a materially riskier item than this document describes, and it should not be reached by drifting there from (a).

### 7b. Why motivation (1) is weaker than assumed

Motivation (1) is weaker than assumed. `computed` 2026-08-26: `kite-quote` is `limit-for-period: 1 / 1s` =
**3,600/hr sustained**; Upstox binds at **1800 per 30 min = 3,600/hr sustained**. **The sustained ceilings are identical.**
Upstox permits bursts that Kite does not, and its unchunked batch means the same instrument demand costs *fewer* requests —
but "we are switching for headroom" is not supported by a ceiling comparison.

**The honest trade for the TOTP path:** it requires storing the **TOTP seed alongside the password**, which collapses 2FA
into 1FA. Secret ROTATION is a parked owner-gated residual of SEC-01, and a seed is awkward to rotate (it means
re-enrolling 2FA). The login endpoints are undocumented and can change without notice — a break lands at 06:00 for a 09:15
open, so it needs a loud alarm and a manual fallback, never a silent retry. SEC-01 itself is **closed** (`.env` is
owner/SYSTEM/Administrators only, verified live 2026-08-26, with an `ay.ps1` `Get-SecretAclViolations` guard on every
`up`), so the storage posture is sound — **the objection is the factor collapse, not the file permissions.** Whether it
fits the broker's terms is an **owner decision**, not an engineering one.

> **Recommendation: settle §7a (cost end-state (a) vs (b)) and §7 (TOTP), and run §4's projection, BEFORE building Phase A.**
>
> The motivation stack after 2026-08-26 looks like this:
>
> | motivation | status |
> |---|---|
> | (3) outage resilience | **dead** — the 08-19/20 outages were the host's network; Upstox failed in the same window |
> | (1) rate headroom | **weak** — sustained ceilings are identical (3,600/hr both); needs §4's remapped projection to say anything |
> | (2) 06:00 login | **possibly solved far more cheaply** by §7's TOTP automation |
> | (4) cost, ₹6,000/yr | **real and certain — but NOT delivered by this plan as sequenced** (§7a: a Kite fallback keeps paying the bill) |
>
> So the item is no longer justified by what the ledger row claims. It is justified — if at all — by **cost**, and cost
> demands the **(b) no-fallback** end state that this plan explicitly does not describe. Concluding **"do not build H26 as
> written"** and re-planning around (b) is a legitimate and possibly correct outcome; drifting from (a) into (b) unplanned
> is the failure mode to avoid.

---

## 8. Defect found while planning (fold into U-B1)

`UpstoxQuoteGateway` builds ONE batch and passes it straight to `UpstoxQuoteClient.quotes(...)`, whose own javadoc says the
endpoint serves "up to ~500 instruments at once". **There is NO chunking anywhere in that path** — contrast
`LiveQuoteGateway`, which splits at `KITE_QUOTE_BATCH_SIZE` (default 250). Dormant today; it would surface **on cutover
day** with a large watchlist. U-B1 owns adding chunking plus a test that a >500-key request is split.
