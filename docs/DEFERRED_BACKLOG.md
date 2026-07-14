# Deferred / Pending Backlog — Phases 0 → 6 (as of 2026-07-12)

Single source of truth for everything NOT yet done across the OpenAlgo/React master-plan phases 0–6.
The forward-work authority is `superpowers/plans/2026-06-19-openalgo-react-integration-master-plan.md`
(§16.1 phase map); current phase + the running checklist is `PHASE_GATES.md`. This file consolidates the
deferrals so a fresh session has one place to look. The per-row tables below are a provenance ledger —
new state is appended as dated update blocks (latest first), the old blocks are kept, never rewritten.

**2026-07-02 (EOD) reconciliation — both same-day audit fix queues FULLY CLOSED; forward ledger moved:**
- **Full codebase audit (41 agents) fixed same day:** P0 #407–#412 (paper pipeline 3-way, premium brackets,
  HTTP timeouts, backups, notifier fail-closed), P1 #413–#419 (option-leg brackets, BSE Thursday calendar +
  horizon canary, backtest/optimizer job spine, compose fail-closed + `ay.sh` deleted, UI trust, FeedWatchdog),
  P2 #420–#434 (route-allowlist tripwire, Map-return ratchet, V027 drops `candles_3m` cagg, Redis AOF,
  FailPolicy rail registry, explicit G1). All redeployed live 2026-07-02 ~11:00 IST.
- **Frontend UI live audit vs oipulse (2 passes) fixed same day:** Waves 1–5 + charts part B + FE-e2e repair,
  PRs #440–#475 — capture bucket alignment (T2), trending-OI window cap (T1), watermark sweep, participant
  semantics, a11y root-cause (tailwind-merge type-ramp), 09:15 pre-open gap-bar data fix (#459), **stock-chain
  OI as on-demand Upstox warm** (#472, owner picked over standing capture), FE-e2e suite 44/44 + the CI shard
  flipped BLOCKING (#474).
- **Docs archive sweep:** the scalper-to-100 roadmap + `2026-06-27-backlog/` streams, the build inventory,
  both audits, data-foundation-milestone (value-verify PASSED), data-ops-console, upstox-live-migration
  (W-U4 declined), e8-e12-numbers, pe-mirror and the FU1/FU2 plans all moved to `superpowers/plans/archive/`
  with status banners. **The single open-items ledger is now
  `superpowers/plans/2026-07-02-remaining-items.md`** — 1 net-new build (Minervini screener), owner-gated
  rows, next-session verifies, the CD-2 calendar refresh (before ~2026-11-16), and the WON'T-DO record.
  This file stays as the per-phase provenance history.

**2026-06-30 (EOD) reconciliation — §1a + §1b build tracks CLOSED; only the Minervini screener is net-new code left:**
- **Scalper signal-side §1a COMPLETE** (#371 manual-checks, #372/#373 RSI band+recovery, #374 FII-participant) and
  **frontend/oipulse §1b COMPLETE** (#375 Risk Calculator, #376 Multiple Window, #377 Futures Pre-Open, #378
  Announcement, #379 Advance Chart + Multiframe, **#390 Calendar Spread**; Event Days SKIP — static Budget slideshow).
- **Owner-decision builds landed:** E11 PE-mirror (#381/#382, 9 NIFTY-PE published + 18 SENSEX-PE drafts) + straddle
  combined-prem auto-exit (#383/#384); **E9 take-profit optimizer-tunable band (#386)**; **`instruments.exchange_token`
  populate (#387)**.
- **Phase-5 §15 200-day equity daily backfill DONE (#389)** — Upstox cash-equity daily → `candles`@1d, live-verified
  222 candles/symbol → the **Minervini Trend-Template screener is now UNBLOCKED** and is the lone remaining net-new build.
- **Live forward-paper analysis runbook (#392)** in place — after ~1 month of live-paper scalper trades, the E9 band +
  per-scalper keep/cut/tune are tuned via a counterfactual replay over real captured premium (backtest optimizer
  can't tune scalpers — parity firewall). Authority: `superpowers/plans/2026-06-30-live-signal-analysis-runbook.md`.
- **Still open (owner-gated, NOT code):** W-U4 Upstox cutover (live A/B + flip); data-foundation value-verify (oipulse
  sign-in); scalper arming numbers (E8 roster / E9 band / soft-dot gates — forward-paper outputs); Telegram scalp-alert
  opt-in (bot token). Everything the owner has decided NOT to do is consolidated in the inventory §6 (WILL NOT BE DONE).

**2026-06-30 reconciliation — scalper-to-100 build in progress + live signal path GO:**
- **12 NIFTY options-scalper strategies PUBLISHED + live**; the live signal path was verified GO (engine loads
  the 12, `signal_underlying` resolves to the live dated front contract `NIFTY26JULFUT`, 3m primary serves).
- **Scalper-to-100 epics:** E2/E4/E10 DONE; E1 #3 stock-future Market-Movers universe CODE-COMPLETE + ops-done
  + live-verified (#345-348) + E1 v2 source-selector (#354); E3/E5/E6/E7/E8/E9/E11/E12 partial or
  pending-owner-numbers (see the scalper-to-100 roadmap §1 baseline for the live per-epic state).
- **This session merged + deployed live:** #365 (3m-primary read-time rollup — the live-signal blocker fix),
  #366 (Signals page Live/Historical filter + date picker), #367 (auto-paper-trade toggle).
- **PE-mirror** bidirectional-scalper plan PARKED (owner: build later, #364).
- Still open: W-U4 Upstox cutover; the data-foundation value-verify gate (owner oipulse sign-in); Telegram
  scalp-alert opt-in (owner sets the bot token). The Phase-3 "#3 Market Movers" row below is now superseded
  (the stock-future universe is built; only the residual per-stock packages + v2 remain).
- **Full code-verified remaining-build inventory** (15 confirmed-pending + 12 owner-gated + the 4 false-flags
  rejected + the descope list + recommended build order) → `superpowers/plans/2026-06-30-remaining-build-inventory.md`.
  That doc is the authoritative "what's left" ledger; the per-row tables below remain as the phase-by-phase provenance.

**Merge state at writing (2026-06-24):** Phase 0–3 + Phase 3.5 are on `main` (#39–#44). **Phase 4
React is substantially built** — the cockpit pages (Signals/Paper/Dashboard/Strategies/Backtests/
Journal/Watchlists/Settings/Charts, #94–#103), the **React cutover** (#104, Angular `frontend-ui`
parked), the scalper cockpit (#105–#110), and oipulse Waves **W1/W2/W3** (most depth + breadth + chart
pages, through #93/#109) are all merged. **Expired-instruments OHLCV+OI backfill** (the §5 / Bucket-5 /
data-foundation Part-B archive) is **BUILT + RUNNING** (#112–#116). **Part 2 premium-as-primary
backtest replay** (options trade their own premium) is merged (#114–#119). The **Data Ops Console**
(operator UI over the backfill, B1–B6) is merged (#121) and now **DEPLOYED + live** (deploy unblocked once
the backfill idled; #219 fixed the bare `/data-ops` route). What remains is below.

**2026-06-24 session update (#136–#156) — moved DONE / changed since the per-row tables below were first written:**
- **Upstox login-free live migration (W-U1…U4):** direct-Upstox capture on the long-lived analytics token
  so a missed daily broker login can't kill the live feed — OI option-chain capture (#137), spot/FUT quotes
  (#139), v3 WS ticker (#141), F&O token→key map (#145), cutover-prep canary+runbook+OI-A/B tool (#149).
  All flag-gated **default Kite**; remaining = deploy off-hours + flip the `source.*` flags + the live latency A/B.
- **Scalper registry now 12/12** (all seeded as paper drafts): #3 Market Movers + #8 BTST/STBT (#148), and
  **#11 long-straddle via a NEW two-leg/neutral engine primitive** (#155). Short-premium SELL legs of #8/#11
  stay SPAN-deferred.
- **`OpenAlgoOrderGateway`** live broker-order impl shipped **dormant** (#154, default `execution=paper`).
- **Higher-order greeks** vanna/charm/vomma added to `black76-math` + the chain (#156).
- **SPAN `.spn` XML ingest + golden-parity harness** (#144) — real-broker-parity still owner-gated.
- New oipulse pages: **OI heatmap** (#146), **OI expiry** (#150), **Open & High** (#153); the **Scalping
  Cockpit** (#147) gained a **paper-trade panel** (#151) + **scalp-signal alerts** (#152).
- Infra: **`nse↔upstox` Modulith cycle fix** (#138, was flaky-masked), **backfill transient-resilience**
  (#140, merged-not-deployed). The per-row tables below are updated to match.

**2026-06-26 update — backtest-realism + historical-OI arc landed (#198–#214, all MERGED + DEPLOYED):**
the backtester now trades real option premium *with* OI-aware entries (`backtest.oi_confluence_gate`, #208/#209),
session/square-off enforced in replay (#206), a sweepable optimize block (#207), and a post-hoc **OI-confluence
trade attribution** surface (#201). The whole oipulse OI suite + Connecting-Dots + attribution now work on HISTORY
with no snapshot backfill (virtual read-time derivation `CandleDerivedChainReader`/`HistoricalOiReader`, #203/#204/
#210/#211) + ATM-band IV recompute (#213). **#214 was the keystone fix** — keying the OI/IV/VIX factors by
`bucket.toInstant()` (they had silently missed via an `OffsetDateTime` key across `+05:30`/`+00` sources) un-NEUTRAL'd
the factors and validated the OI thesis: April attribution Ext.Bullish 5tr/80%win vs Bearish 1tr/0%win. **Strategy-eval
learning:** derived history still forces Dow+IV NEUTRAL, so the OI edge reads MUTED on backtests — judge OI-led
strategies on FORWARD paper with real captured OI, not a weak historical backtest.

**2026-06-28 — scalper engine → 100% roadmap.** The S24 incorporation chain is closed (debloated operative doc;
W3 drift tags #251–256 + W4 gates #258–262 BUILT/inert; 2b infra live). The remaining engine work to finish the
**debloated** operative doc (the AUTOMATE_PKG remainder, ~95 packages in 12 epics E1–E12, minus ~5 S24 descopes)
is consolidated in [`superpowers/plans/archive/2026-06-28-scalper-to-100-roadmap.md`](superpowers/plans/archive/2026-06-28-scalper-to-100-roadmap.md)
— the scalper forward authority. Per-package design = the 12 `2026-06-27-backlog/` stream files (KEEP-NEEDED) +
FU1/FU2; the bloated `strategy-audit/` chain is CLOSED. Arming = owner forward-paper (2c).

**2026-06-26 update — backfill IDLE + 2b scalper tunable-infra COMPLETE (#219–#230, all MERGED + DEPLOYED):**
the expired/OI backfill is now **COMPLETE/idle** (`ExpiredBackfillAutoResume` self-resume skips all 32,543 legs),
which unblocked the two formerly-gated items: the **Data Ops Console is DEPLOYED + live** (B1–B6, #121; #219
fixed the bare `/data-ops` → `/data-ops/status` redirect), and the **scalper tunable-infra (2b) is COMPLETE** —
the fold-fix routes walk-forward folds through `OptionsPremiumReplay` (#220, `oos_fold_mean` populates), the
historical **NIFTY-FUT-CONT (NFO) + SENSEX-FUT-CONT (BFO)** continuous front-future 1m signal series are
backfilled (2b-E1 #222/#223, 2b-E2b #225), a **three-way decoupling** of `signal_underlying` / `strike_reference`
/ `underlying` landed (ADR-0003: 2b-E2 #224, 2b-E2b #225), the 12 Siva scalpers were forked into **36
instrument-agnostic tunable variants** (2b-1 #226, seed-flag passthrough #227) seeded LIVE as drafts, the
tick-wise golden runner gained 3m-primary support (2b-E3 #228 — it had 5m/15m/1h only; parity-safe additive
`case "3m"`), **36/36 functional backtests ran green** (2b-2 #229), and the backtest jobs/results pages gained a
strategy-name + returns surface with a strategy filter + pagination (#230). The 36 are **functional-screening
only** (returns NOT tradeable / overfit) — tune on FORWARD paper (2c). The niftyoi-vs-sensexoi OI-gate A/B is a
forward-paper discriminator (identical on history, where the gate is muted by Dow+IV → NEUTRAL). **W-U4 (Upstox
cutover) is now the ONLY remaining pending wave** — prepped, gated on a live market session + the owner's flip.

## Legend
- **Status:** DONE / PARTIAL / DEFERRED / GATED / NOT STARTED.
- **Target:** the phase (or condition) the work is deferred to.

---

## Phase 0 — OpenAlgo spine (MERGED, PR #39)

| Item | Status | Target | Reason |
|---|---|---|---|
| WS ticker (capability F) | **BUILT (direct-Upstox v3, #141)** — latency-gated | live A/B then flip `source.ticker=upstox` | Built as a **direct-Upstox v3 WS** (login-free on the analytics token, reuses `LiveTickerFeed`+`SubscriptionRegistry`), NOT via OpenAlgo; default Kite. Remaining gate = measure scalp tick/place-ack latency ≥1 live session (§17.3) before flipping. F&O token→key map done (#145); compose must add `ARTHA_MD_SOURCE_TICKER` before the flip (flagged in #149 runbook). |
| 20-level market depth flattening | DEFERRED | a future order-microstructure feature | Snapshot/chain path only reads best bid/ask (immune to depth-level diffs); >5 levels only needed by a future scalp-microstructure use. |
| Instruments / symbols via OpenAlgo (capability B) | DEFERRED | only if a broker swap forces it | Symbol-format mapping cost; the Kite instrument dump works. |

## Phase 1 — Data inflow (PARTIAL, PR #40/#41)

| Item | Status | Target | Reason |
|---|---|---|---|
| §5 expired-instrument OHLCV+OI backfill (ExpiryTrack historical) | **DONE / COMPLETE** | — (#112–#116) | Upstox Plus funded; the `ExpiredBackfillService` ingester (bounded strikes + sliding-window limiter + resume) loads NIFTY/SENSEX expired CE/PE/FUT per-min OHLCV+OI into `candles` (`source='BACKFILL'`). First full pull **COMPLETE/idle** as of 2026-06-26 (`ExpiredBackfillAutoResume` self-resume now skips all 32,543 legs — nothing left to fetch). See [[upstox-expired-instruments]]. |
| Native (live) intraday-OI snapshot history depth | PARTIAL | ongoing forward-capture | Live 3-min full-chain OI capture has run since 2026-06-15 (forward-accruing); deep past OI for stocks (non-expired) still bought-only. |
| §15 200-day daily history | **DONE (#389)** | — | Built option (a): `UpstoxEquityMasterClient` (NSE_EQ tradingsymbol→`NSE_EQ\|<ISIN>` key off `complete.json.gz`) + `EquityDailyBackfillService` (async, `POST /api/v1/market/admin/equity-daily-backfill`) → `candles`@1d `source=BACKFILL`, reusing the `UpstoxExpiredInstrumentsClient` client family + shared rate-limiter. **Live-verified: RELIANCE/TCS/INFY each return 222 daily candles** via `/candles?interval=1d` → the Minervini screener's 150/200-day MAs compute without DATA_GAP. |
| Live OI cutover (login-free capture) | **BUILT — deploy + flip pending (#137/#149)** | deploy after backfill, then A/B + flip | Direct-Upstox analytics-token live OI capture (login-free) shipped flag-gated default-Kite (#137); the cutover canary + runbook + OI A/B-diff tool are done (#149). Remaining: deploy off-hours + reconcile Upstox-vs-Kite per-strike OI for a session + flip `source.optionchain=upstox`. |
| §6.3 BSM-on-spot seam (stock options) | DEFERRED | future stock-options work | Index path uses Black-76-on-the-forward; no stock-options consumer yet. |

## Phase 2 — Quant libs (MOSTLY, PR #40)

| Item | Status | Target | Reason |
|---|---|---|---|
| §6 higher-order greeks (vanna/charm/vomma) | **DONE (#156)** | — | Added to `black76-math` (closed-form, FD-cross-checked golden vectors) + surfaced on the option chain (`Leg.vanna/charm/vomma`, live-only/additive, no migration). First-order set byte-identical. **Third-order gamma-sensitivity greeks (speed/zomma/color) added + FD-verified on `black76-math` + the chain `Leg` (#511, 2026-07-04)** — the "un-built" note was stale; nothing higher-order remains un-built. |

## Phase 3 — Scalper engine (MERGED, PR #42)

| Item | Status | Target | Reason |
|---|---|---|---|
| Strategy **#3 Market Movers** | **PARTIAL — index draft seeded (#148)** | full stock-universe → Track-1/Phase-5 | A NIFTY front-future LONG paper draft is seeded (#148, `scalp-market-movers-nifty.yaml`). The faithful F&O-**stock**-universe version (8/9-day breakout, equity screener, SHORT side) still needs the Phase-5 screener + daily RSI. |
| Strategy **#7 Hero-Zero** | **DONE (paper, #130)** | — | **CORRECTION:** #7 is BUY-side (long premium, defined risk) per the Siva cheat sheet ("buy side only"), NOT short-premium — so it needs NEITHER SPAN NOR live orders. Wired as a paper scalper via `HeroZeroGate` + `scalp-hero-zero-nifty.yaml` (expiry-day, 14:30–15:20, >50% OI+price sync, one-away strike via side+delta band). Golden/parity byte-identical. Live-order routing is the only remaining gate for trading it LIVE (shared with the cluster below). |
| Strategy **#11 Straddle** | **PARTIAL — long-straddle built (#155)** | short legs: SPAN + live orders | A NEW two-leg/neutral engine primitive + a LONG (defined-risk, BUY-both-ATM) paper draft seeded (#155, `scalp-straddle-nifty.yaml`). The SHORT straddle (SELL legs, unlimited risk) stays gated on SPAN sizing + live orders. |
| Strategy **#8 BTST/STBT** | **PARTIAL — long-carry built (#148)** | short legs: SPAN | A `style:btst` overnight long-premium paper draft seeded (#148, `scalp-btst-stbt-nifty.yaml`, pre-close A9 clock). The short-premium SELL legs stay SPAN-deferred. |
| **#47 SPAN appliance** (§8 marginism) | **BUILT (dormant) + .spn harness (#144); .spn path SUPERSEDED by #510** | offline/backtest fallback | margin-service (#126, marginism 0.1.1, FastAPI :8086, default-off) + a `.spn` XML ingest path + a golden-parity harness vs a synthetic fixture (#144) — confirmed marginism parses the real NSCCL `.spn` directly. ~~VERIFY-pending (OWNER-GATED, the only gap): a real `nsccl.<YYYYMMDD>.s.spn` + a known broker margin number for the same basket/date; the NSE download URL is documented (member FAOFTP tree, confirm public-vs-login before scheduling).~~ **SUPERSEDED 2026-07-04 (#510): no `.spn` file needed — Upstox computes broker-real SPAN server-side (`POST /v2/charges/margin`) on the analytics token (live-verified 1-lot short → span 337004.85 / final 188604.45). marginism stays the offline/backtest fallback.** |
| **OpenAlgoOrderGateway** (live broker order impl) | **BUILT (dormant, #154)** | owner arms after the latency gate | `RestOpenAlgoOrderGateway` → OpenAlgo `POST /api/v1/placeorder` (verified vs checkout), WireMock-tested, gateway-failure-never-propagates; bound **only** when `artha.scalper.execution=live` (default `paper` ⇒ `DisabledOrderGateway` places nothing). Owner arms it after the §17.3 place-ack latency gate; never auto-fires. |
| **§18.1 order read endpoints** (orderbook/positions/tradebook/funds) + React `/orders` page | **BUILT (dormant, #131)** | live-verify | OpenAlgo `openalgo/wire/` anti-corruption DTOs + gated `RestOpenAlgoOrderReadGateway` + `GET /api/v1/orders/*` + read-only `/orders` page, WireMock-tested, ships off (`artha.openalgo.order-read-enabled=false`). Live-broker verify deferred. |
| **Full-auto execution** (no human "Take") | DEFERRED | a later flag | Semi-auto (human "Take") is the v1 safety boundary. |
| **Manual-verification checklist UI** (verify + confirm panel) | **DONE** (#125) | — | React `ManualVerifyChecklist` on `/signals` + `/scalper` (soft-warning + override gating, the V009 manual checks — 16 as of 2026-06-27 — + confluence dots, client-only). |
| **Per-check server audit** (which boxes ticked) | DEFERRED | only if an override/exception trail is needed | Would add a `TakenRequest` field (request-schema drift + TS regen). |
| **Historical scalp backtests** | **DONE (functional, #226–#229)** | forward-paper tuning (2c) | The 36 instrument-agnostic scalper variants run full-window functional backtests on the complete §5 expired-premium archive (36/36 green, #229) — signal on NIFTY-FUT-CONT, premium-as-primary, three-way decoupled. Returns are functional-screening only (NOT tradeable / overfit); OI-led variants read MUTED on history. Final tuning is on FORWARD paper. |

## Phase 3.5 — OI-analytics fidelity + faithful strategies (PR #43 done; #44 OPEN)

| Item | Status | Target | Reason |
|---|---|---|---|
| Tier-2 OI fidelity T2.1–T2.8 (18 dots, #5 ≥50% ΔOI pre-gate) | **DONE** (#43) | — | — |
| Monthly-expiry OI suppression (S24 caveat) | **DONE** (#43) | — | — |
| Strategies #4 Gap / #12 Trend-Change / #9 Morning-Trade | **DONE** (#43) | — | — |
| #2 Open=High **front-Future v1 proxy** | superseded (#43) | — | Replaced by the per-strike faithful grading below. |
| #2 Open=High **per-strike Table-1/Table-2 faithful grading** | **DONE (#44)** | — | New `/options/strike-session-stats` endpoint derives per-strike session OHLC+volume from `options_chain_snapshots`. A standalone **Open & High Strategy** oipulse page + trigger-probability series also added (#153). |
| **OiPulse ≥90% AI badge** (#2) | DEFERRED | Phase 4 (OiPulse-parity) | External proprietary oipulse.com AI model; not ours; the faithful Table-1/Table-2 HIGH tier is our equivalent. |
| **drasticFloor per-index tuning** (#6 `drastic_oi`) | DEFERRED | live calibration | The source gives no number; default `50000` is a placeholder; tune per index (NIFTY vs BANKNIFTY) once live OI magnitudes are observed. |
| ~~**Native 3-min option-snapshot capture**~~ | **MOOT (2026-07-04)** | — | Row premise (5-min capture) is stale: live capture already runs at ~1-min granularity (verified in `options_chain_snapshots`), FINER than 3-min. Any interval (incl. 3-min) is a read-time rollup off the 1-min base — the endpoint already serves all of them via the `interval` param. Fidelity goal exceeded; nothing to build. |

## Phase 4 — React migration (§10/§11) — IN PROGRESS (substantially built)

Cockpit + React cutover + oipulse Waves W1/W2/W3 merged (see the merge-state note above). Wave-3 oipulse
pages added: **OI Change Heatmap** (#146), **OI Expiry Strategy** (#150), **Open & High
Strategy** (#153). The **Scalping Cockpit** (#147) is now a paper-trading console — **take-paper + live
book/risk panel** (#151) + **scalp-signal alerts** to ntfy/telegram (#152).

**2026-06-25 frontend pass — all MERGED + DEPLOYED:** the **look/UX revamp** (design tokens + self-hosted
Newsreader/Inter/JetBrains-Mono fonts + shadcn bridge + DataTable + signature `PageHeader`/`QueryState`/
motion, #158–#163) rolled out to **64/65 pages** (#166–#173); two new Upstox-backed oipulse pages —
**World Indices** (#174, fix #176) + **Pre-Open Market** (#175); and the nav restructure — the **"All Menu"
mega-dropdown split into a per-section menu bar** (#177). Authority for the revamp is now archived at
`superpowers/plans/archive/2026-06-24-frontend-revamp-foundation-and-hero-pages.md`. Remaining Phase-4:

| Item | Status | Target | Reason |
|---|---|---|---|
| **Data-foundation value-verify** — render every OI/data page in History mode on a REAL session + compare value-for-value vs oipulse | ~~GATED~~ **PASSED (2026-07-01)** | — | **PASSED live-vs-live 2026-07-01** — captured OI == oipulse exact share (no PR; a live data-verification action). See the forward ledger `2026-07-02-remaining-items.md` §2 `value-verify-ratify`. Authority: `superpowers/plans/2026-06-21-data-foundation-milestone.md` + [[oipulse-live-qa-method]]. |
| **Data Ops Console deploy** (B1–B6 merged #121) | **DONE — DEPLOYED + live** | — | Deployed once the backfill idled; backend (`coverage-summary`/`upstox-quota-status`/`expired-backfill/status`/`query`/`export`) + the `/data-ops` route both serve 200 (#219 fixed the bare `/data-ops` → `/data-ops/status` redirect). See [[data-ops-console]]. |
| **`/orders` page** + §18.1 order read endpoints (orderbook/positions/tradebook/funds) | **BUILT (dormant, #131)** | live-verify | See the Phase-4 table — scaffolded + WireMock-tested; live-broker verify deferred. |
| **Manual-verification checklist UI** (verify + confirm panel) | **DONE** (#125) | — | Built (see the Phase-4 table); the `2026-06-20-scalper-manual-verification-checklist.md` contract is fulfilled. |
| **OiPulse ≥90% AI badge** (#2) + any tail oipulse-parity polish | DEFERRED | post value-verify | Proprietary oipulse model; our faithful Table-1/2 HIGH tier is the equivalent. |

## Phases ahead (NOT STARTED — the roadmap, for context)

| Phase | Status | Needs | Notes |
|---|---|---|---|
| 5 — Minervini Track-1 screener (§13) | ~~**NOT STARTED — UNBLOCKED**~~ **SHIPPED + LIVE (#524–#553)** | — | **SHIPPED 2026-07-04/05:** Track A screener (#524/#525/#526 + #527) then the full Phase-5→Phase-9 swing workflow (geometry/setups/regime/backtest/exit doctrine/live daily engine, #528–#553). Remaining = supervised forward-paper watch + §0.5 #12 reliability sign-off (owner-gated, not code). |
| 6 — Backtest + forward wiring (§14) | **PARTIAL** | Phases 3 + 5 (+ the §5 OI data now loading) | **Part 2 premium-as-primary replay LANDED** (#114–#119): an options backtest now trades the option's own 1m premium series (`CANDLE_1M`), not the index close — golden-pinned. The v1 simplifications are now CLOSED (#123): per-bar mark-to-market, FillSimulator slippage+costs on the premium leg, and a 422 DATA_GAP coverage pre-flight. **2026-06-25/26:** session/square-off/expiry enforced in replay (#206), an opt-in **OI-confluence entry gate** drops legs entering against the historical Connecting-Dots trend (#208/#209), a sweepable optimize block (#207), and a post-hoc **OI-attribution** surface (#201) all landed — all parity-safe. Remaining: the value-verify on real backfilled premium (gated on the backfill), forward-test wiring. Scalp historical-backtest fidelity is directional, not P&L-exact (R4) — **and OI-led strategies read MUTED on backtests because derived history forces Dow+IV NEUTRAL; judge them on FORWARD paper with real captured OI, not a weak historical backtest** (#214 lesson). raptorbt cross-check oracle DEFERRED. **Backtest-fidelity forks closed 2026-07-12** (swing lineage / screener-CA / BTST + the P1 execution-realism residue) — see remaining-items §0 group B. |

## Data Ops Console — parked decisions (from #121, B1–B6)

| Item | Status | Target | Reason |
|---|---|---|---|
| `backfill_jobs` audit table (run history surviving a restart) | **DONE (#517)** | — | V030 `backfill_jobs` run-audit table (kind/params/status/rows_written/error/timings) + `GET /api/v1/market/admin/backfill-jobs` (typed) + Status-page history — survives restart. |
| ~~B6 per-expiry bulk export + ZIP/Parquet (async streaming)~~ | **DONE (#584)** | — | **SHIPPED 2026-07-06 (#584) — per-expiry BULK export zips the whole option chain in one download (§6).** (Was: v1 per-contract CSV/JSON ≤100k rows sync; B5 query console covered arbitrary slices meanwhile.) |
| Contract-type selector in the collection wizard | **DONE (#517)** | — | BOTH/OPTIONS/FUTURES selector on the Collection wizard (default BOTH → byte-identical to the old options+futures pull). |
| B1 live updates via STOMP (vs the 2s poll) | DEFERRED | consistency polish | A small poll is simpler; the jobs WS topic is backtest-scoped. |

## Cross-cutting / legacy parking (lower-priority hardening, from Stage A–G)

| Item | Status | Target | Reason |
|---|---|---|---|
| B-9 binary-frame guard production wiring | DEFERRED | when Kite changes its wire format / a first-party WS client | javakiteconnect exposes no raw-frame hook; today's coverage = the daily contract canary + fixture-pinned envelope tests. |
| `instruments.exchange_token` population | **DONE (#387)** | — | `exchange_token` now threaded wire→domain→DB (`InstrumentRecord` field + Live/Mock dump parsers + `setLong(4, …)`); nullable BIGINT, no migration. |
| `candles_1h` IST alignment (buckets to UTC = :30 IST) | **DONE (#513, V029)** | — | Dropped + recreated `candles_1h` with `time_bucket('1 hour', bucket, 'Asia/Kolkata')` (matches the 1d/1w IST siblings), `WITH NO DATA` + refresh policy so the live DB does no heavy one-shot materialization. |
| Options fidelity live walk (SNAPSHOT / SYNTHETIC_B76) | DEFERRED | first live-mode options session | IT-green; needs a real options archive + a multi-month window the mock can't supply. |
| Walk-forward folds + fold-fed MedianPruner live walk | DEFERRED | a real multi-month dataset | Can't be shown on the ~3-day rolling mock window. |
| ~~`requirements.txt` hash-pinning (optimizer)~~ | **DONE** | — | `requirements.lock`/`requirements-dev.lock` via `uv pip compile --generate-hashes`; Dockerfile + `ci-optimizer.yml` both `--require-hashes` (2026-07-04 audit — stale row, do not re-flag). |
| Recorded Kite binary-frame capture | DEFERRED | first live session | The mixed-frame fixture is synthesized from the documented envelope; commit one real capture. |
| ~~Upstox `/pcr` … `source.pcr` switch~~ **FLAG ALREADY BUILT** — only a LIVE verify remains | DEFERRED (live only) | next market-hours session | The dormant switch already exists as `artha.marketdata.source.optionanalytics=upstox\|native` (routes `/pcr-series` to Upstox `/pcr` vs the native fold; mapping covered by `UpstoxOptionAnalyticsSourceTest` + `PcrHistoryServiceIntegrationTest`). Nothing left to build offline. **Remaining is a LIVE market-hours check only:** confirm `/pcr` returns FRESH buckets mid-session (`date=<today>&bucket_interval=1`, last bucket ≈ now, value moves on re-poll) → if fresh, flip `source.optionanalytics=upstox` live+backtest (native stays the cross-check). PCR feeds NO strategy yet (display-only), so no urgency. |

---

## NOT pending (resolved this cycle, recorded so they aren't re-listed)
- Branch protection on `main` — configured (the 7 CI checks are required; `enforce_admins` deliberately
  OFF so the solo owner can admin-merge — was a solo-owner deadlock).
- Accepted documented DEVIATIONS (not deferred work): Monaco editor-route chunk ~562 KB gz; the canary
  Redis key `kite:contract:check`; the ~1.1k-row mock dump fixture; Caffeine (not Redis) indicator cache.
  See the `PHASE_GATES.md` stage parking lists for the full rationale.
- **Optimizer `/best` guard columns** — RESOLVED (#129): per-regime OOS Sharpe (min/mean/max), regimes-covered,
  folds-excluded, dataHash now surface as a compact `guardMetrics` on `/best` + the React sweep leaderboard
  (read from the persisted fold/results data, no recompute; legacy full-window trials show "no fold guards").
- **e2e coverage** for the Data Ops console + scalper checklist — ADDED (#128, Playwright + axe, desktop + 480px).
- **Upstox login-free live migration** (W-U1…U4) — BUILT flag-gated default-Kite (#137/#139/#141/#145/#149).
  ~~REMAINING = deploy off-hours + the live latency A/B + flip the `source.*` flags~~ **W-U4 CUTOVER = a settled
  owner NO (struck 2026-07-12; per `PHASE_GATES.md`: "settled owner NO — stay Kite, split-by-capability") — the
  W-U1..U3 capabilities stay BUILT flag-gated as hot-standby, no full flip.** (see W-U4 row + the runbook
  `docs/manual-tests/wave-u4-upstox-cutover.md`). **Target end-state routing (recommendation, 2026-06-26):**
  keep BOTH brokers, split by strength — Kite = live WS ticker only (+ auth/instruments) + hot-standby; Upstox
  = all REST/analytics/history (quotes/candles/optionchain/fiidii/analytics); OpenAlgo = orders-only (NOT a
  data proxy — it adds a broker hop). Minimizes Kite REST pressure, concentrates polling on the high-headroom
  provider, keeps redundancy. Full rationale + the per-flip gate table: `superpowers/plans/2026-06-24-upstox-live-migration.md`
  → "Target end-state config".
- **Scalper registry 12/12** — #3/#8 (#148) + #11 long-straddle via a two-leg/neutral primitive (#155) seeded
  as paper drafts; SHORT-premium SELL legs of #8/#11 remain the only strategy gap (SPAN + live orders).
- **`OpenAlgoOrderGateway`** (#154) + **higher-order greeks** (#156) + **SPAN `.spn` harness** (#144) — DONE
  (order path + SPAN parity stay owner-gated to enable).
- **`nse↔upstox` Modulith cycle** (#138) + **backfill transient-resilience** (#140) — fixed. ~~#140 is
  merged-not-deployed (the running market-data image still aborts on a transient blip; the bash watchdog
  re-triggers it meanwhile).~~ **DEPLOYED SINCE (stale caveat, struck 2026-07-12):** market-data has been
  rebuilt + deployed live many times after #140 (e.g. #510/#514/#524/#525/#686/#699), so #140's per-expiry
  transient-error isolation ships in every current image.
