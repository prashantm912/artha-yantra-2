# Remaining-build inventory — code-verified, adversarially refuted (2026-06-30)

**Status:** ACTIVE — the single forward inventory of *everything yet to build* across the whole platform
(scalper engine, frontend/oipulse, data/infra/Phase-5). This is a **status ledger**, not a design doc:
per-item design lives in the cited authority. Supersedes ad-hoc "what's left" lists.

## How this was produced (so it can be trusted)
A two-phase agent workflow over the **latest** authorities (NOT the bloated/closed `strategy-audit/`):
1. **Discover** — four domain readers (scalper-signal / scalper-mgmt / frontend-oipulse / data-infra) read
   the live authority docs + **grepped the code** for each package's identifiers, returning a candidate
   unbuilt list with empty-grep evidence.
2. **Refute** — every candidate was handed to an *adversarial* verifier whose job was to **find the code that
   builds it** and reject the "unbuilt" claim. Only items where absence was *proven in code* survive as
   pending. This pass caught **4 false-flags** (see §4) — the exact failure mode (claiming pending-when-built)
   that earlier passes hit.

Tally: **31 candidates → 15 confirmed-pending · 12 owner-gated/parked · 4 false-flags rejected.**

Authorities used: `2026-06-28-scalper-to-100-roadmap.md` (+ the 12 `2026-06-27-backlog/` streams, FU1/FU2,
`2026-06-29-e8-e12-numbers.md`), `2026-06-19-openalgo-react-integration-master-plan.md` (§17/§18 override),
`DEFERRED_BACKLOG.md` (reconciled 2026-06-30), `PHASE_GATES.md`, `docs/oipulse-study/`.

---

## 1. CONFIRMED PENDING — genuinely unbuilt (code-proven) — 2 (was 15; 13 shipped — §1a + §1b COMPLETE; §1c 200-day backfill DONE #389 + calendar-spread DONE #390; only the **Phase-5 Minervini screener** + the low-value E12-ideal-window remain)

> **Progress log** (mark each item DONE here the moment it merges):
> - ✅ `FU1-manual-checks-9` — **DONE 2026-06-30 (PR #371, e49c764).** `ScalperManualChecks.CHECKS` 7→16
>   (the 9 S21–S24 manual-only gaps); parity-safe side-channel, no FE/schema/contract change; tests green.
> - ✅ `E5-rsi-band-per-strategy` — **DONE 2026-06-30 (PR #372).** Configurable `scalper.params.rsi_band`
>   block + `ScalperGates.rsiBandCustom`; rides `ScalperParams` (no `ScalperConfig` arity change);
>   absent⇒null⇒byte-identical; seam precedence open-high-low → rsi_band → rsi-s24-bands → shared band.
> - ✅ `E5-rsi-recovery-postvertical` — **DONE 2026-06-30 (PR #373).** `ScalperGates.rsiRecovery` (oversold
>   trough→recovery≥40 sequencer) + 3 `ScalperOiProps` knobs + seam window-scan; armed on trend-change ×3.
>   **RATIFICATION-PACK row 51 = KEEP/AUTOMATE_PKG (NOT the inventory's "uncertain").** Also bumped
>   strategy-schema tags `maxItems` 16→24 (trend-change hit 17 tags; backward-compatible relaxation).
> - ✅ `E3-fii-participant-classifier` — **DONE 2026-06-30 (PR #374).** New market-data `ParticipantBiasService`
>   (LB/SC/LU/SB 2-day FII OI-delta classifier + option-leg seller read) + `GET /fii-dii/bias`; strategy-signal
>   `Macro.fiiBiasSign` + `ScalperGates.fii` + `fii-dii-gate` seam (default-OFF). Real EOD data ⇒ backtest-usable.
>   Contract recaptured (additive path) + TS regen. **§1a COMPLETE — the scalper signal side is fully built.**
> - ✅ `feat-risk-calculator` (§1b) — **DONE 2026-06-30 (PR #375).** Pure-client position-sizing utility:
>   `core/riskCalculator` (computeRisk + spec) + `RiskCalculatorPage` (`/features/risk-calculator`) + nav;
>   sizes off capital/risk%/entry/stop (lot-floored), derives R-target + 1%-lock + deployment. No backend.
> - ✅ `feat-multiple-window` (§1b) — **DONE 2026-06-30 (PR #376).** Composable 1/2/2×2 monitoring workspace:
>   `core/multipleWindow` (layout + panes + localStorage persist + spec) + `MultipleWindowPage` (per-pane
>   widget dropdown over a registry of existing pages, lazy + per-pane ErrorBoundary) + nav. No backend.
> - ✅ `fut-pre-open-market` (§1b) — **DONE 2026-06-30 (PR #377).** Futures Pre-Open scanner: market-data
>   `FuturesPreOpenScan` (pure A/D + H/L-break, 9 tests) + `FuturesPreOpenService` (NSE phase + index rows
>   via `UpstoxMarketStatusClient.preOpen()`, radar stock-future rows via ONE batched `/market-quote/quotes`
>   + captured-EOD prev-day H/L) + `GET /market/futures/pre-open` (Map-envelope, contract recaptured) +
>   `FuturesPreOpenMarketPage` (2×2 grid + counters + break badge) + nav. Parity-safe read-only;
>   Modulith-safe (wire `Tick` stays internal, exposed domain `Quote`). v1 radar = captured NIFTY-Bank
>   (v2 = full N50 Upstox prev-H/L). **Live-data render verifies at next pre-open (09:00 IST).**
>
> - ✅ `w4-advance-chart` + `w4-multiframe-chart` (§1b) — **DONE 2026-06-30 (PR #379).** LWC pro chart:
>   `core/indicators` (session VWAP / VWMA20 / SuperTrend10,2 / RSI14+SMA / volumeMA — 11 specs) +
>   `components/charts/AdvanceChart` (candles + volume + the 5 overlays, RSI in a 2nd LWC pane) +
>   `AdvanceChartPage` (`/advance-chart`; symbol typeahead + interval + lazy-older) + `MultiframeChartPage`
>   (`/multiframe-chart`; 2×2 multi-TF grid of AdvanceCharts) + nav. Pure-frontend (binds `/market/candles`).
>   TV-binary extras (drawing tools / study-template save-load / OI-bar / trade-history / audio alerts)
>   deferred per the study's replication note. **Visually verify the chart render after deploy.**
> - ✅ `equity-announcement` (§1b) — **DONE 2026-06-30 (PR #378).** NSE corporate-filings feed: `nse`
>   `NseAnnouncementFetcher` (iface + Live `@Profile(live)` via `NseHttpClient.getWithCookieSeed` + Mock)
>   + `AnnouncementService`/`Controller` (`GET /market/equity/announcements?from&to&symbol`, Map-envelope,
>   contract recaptured) + `AnnouncementPage` (`/equity/announcement`; date-range + symbol filter, Detail
>   col, PDF "Open File" link) + nav. Mirrors the existing NSE corporate-action scraper; row-mapping
>   unit-tested (5). Degrades to empty when not-live. **Live-verify the NSE field mapping after deploy.**
>
> **Owner §1b dispositions (2026-06-30):** `feat-event-days` = **SKIP** (it's a static proprietary
> Union-Budget slideshow, NOT an event calendar — `docs/oipulse-study/features/event-days.md`; inventory
> mischaracterized it); `strat-calendar-spread` = **✅ BUILT #390** (owner un-deferred); `equity-announcement` = **✅ BUILT
> #378** (owner overrode the master-plan §20.5 defer); `w4-advance-chart` + `w4-multiframe-chart` = **BUILD**.

### 1a. Scalper signal-side (✅ ALL 4 SHIPPED — COMPLETE) — small, in-service, parity-safe tag-gate pattern
| id | item | doc | code-evidence of absence |
|---|---|---|---|
| ~~`FU1-manual-checks-9`~~ | **✅ DONE #371** — 9 manual-only checks added (`ScalperManualChecks.CHECKS` 7→16) | `followup1-expand-manual-checks.md` §3 | shipped 2026-06-30 |
| ~~`E5-rsi-band-per-strategy`~~ | **✅ DONE #372** — configurable `rsi_band` block + `rsiBandCustom` (on `ScalperParams`) | `rsi-multi-timeframe.md` §3.5 | shipped 2026-06-30 |
| ~~`E5-rsi-recovery-postvertical`~~ | **✅ DONE #373** — `rsiRecovery` trough→recovery sequencer, armed on trend-change ×3 (RATIFICATION-PACK row 51 = KEEP) | `rsi-multi-timeframe.md` §3.7 | shipped 2026-06-30 |
| ~~`E3-fii-participant-classifier`~~ | **✅ DONE #374** — `ParticipantBiasService` LB/SC/LU/SB classifier + `/fii-dii/bias` + `fii-dii-gate` (default-OFF) | `macro-vix-global-fii.md` §3.3 | shipped 2026-06-30 |

### 1b. Frontend / oipulse replication (COMPLETE — 7 built incl. Calendar Spread #390; Event Days SKIP per owner) — no UI work remains
| id | item | backend? | code-evidence of absence |
|---|---|---|---|
| ~~`feat-risk-calculator`~~ | **✅ DONE #375** — Risk Calculator (`/features/risk-calculator`; pure `core/riskCalculator` + page + nav) | **pure-frontend** | shipped 2026-06-30 |
| ~~`feat-multiple-window`~~ | **✅ DONE #376** — Multiple Window (`/features/multiple-window`; `core/multipleWindow` 1/2/2×2 + localStorage + page + nav) | **pure-frontend** | shipped 2026-06-30 |
| ~~`fut-pre-open-market`~~ | **✅ DONE #377** — Futures Pre-Open scanner (`/futures/pre-open-market`; `FuturesPreOpenScan/Service` + `/market/futures/pre-open` + page; live-verify next pre-open) | futures pre-open endpoint (built) | shipped 2026-06-30 |
| ~~`feat-event-days`~~ | **⛔ SKIP (owner)** — NOT an event calendar: a static proprietary Union-Budget slideshow (`docs/oipulse-study/features/event-days.md`, no API). Inventory mischaracterized it. | — | n/a (descoped) |
| ~~`equity-announcement`~~ | **✅ DONE #378** — Announcement (`/equity/announcement`; `NseAnnouncementFetcher` + `/market/equity/announcements` + page; live-verify NSE field mapping after deploy) | NSE source (via `NseHttpClient`) | shipped 2026-06-30 |
| ~~`strat-calendar-spread`~~ | **✅ DONE #390** — Calendar Spread chart (`/options/calendar-spread`; `CalendarSpreadChartService` near−far spread candles + `/market/options/calendar-spread` + page + nav; live-verified — resolves both legs + header quote, items populate in market hours) | leg-premium series (built, read-time) | shipped 2026-06-30 (owner un-deferred) |
| ~~`w4-advance-chart`~~ | **✅ DONE #379** — Advance Chart (`/advance-chart`; LWC + VWAP/VWMA/SuperTrend/RSI/volMA via tested `core/indicators`). TV-binary extras (drawing/study-templates/OI-bar/trade-history) deferred. | LWC (in stack) | shipped 2026-06-30 |
| ~~`w4-multiframe-chart`~~ | **✅ DONE #379** — Multiframe Chart (`/multiframe-chart`; 2×2 multi-TF grid of AdvanceCharts) | depends on Advance-Chart | shipped 2026-06-30 |

### 1c. Data / infra — Phase-5 equity-screener chain (200-day backfill DONE #389; **Minervini screener** is the lone remaining net-new build) — sequential
| id | item | doc | code-evidence of absence |
|---|---|---|---|
| ~~`phase5-200day-equity-daily-backfill`~~ | **✅ DONE #389** — `UpstoxEquityMasterClient` (NSE_EQ key resolver) + `EquityDailyBackfillService` (async, `POST /market/admin/equity-daily-backfill`) → `candles`@1d `source=BACKFILL`. **Live-verified: RELIANCE/TCS/INFY each return 222 daily candles** via `/candles?interval=1d` (M1b met, MAs compute without DATA_GAP). | `DEFERRED_BACKLOG.md` L102; master-plan §15 | shipped 2026-06-30 (Upstox cash-equity daily, not the openchart sidecar) |
| `phase5-minervini-trend-template` | Phase-5 Minervini Track-1 screener — daily 8-gate Trend Template + RS-rank (§13) | `DEFERRED_BACKLOG.md` L167 | `ScreenerService` presets = `{momentum,long_term,oi_buildup,rs_rank}`; no `TrendTemplate`/`Minervini`; gated on the 200-day backfill above |

*(Low-value aside: `E12-ideal-window-gate` (09:15-10:00 fresh-entry hard-skip) is technically unbuilt but the
`e8-e12-numbers` authority already judged it low-value/owner-deferred — treat as owner-deprioritized, §2.)*

---

## 2. OWNER-GATED / PARKED — mechanism built, waiting on a number / decision / deploy / manual step — 12
Not build work. Each needs an owner input, not code.

> **§2 OWNER DECISIONS (2026-06-30 walk-through) — 9 open items resolved:**
> 1. **E8 ATR-stop = OFF** — keep all families on existing point/premium stops (no arming).
> 2. **E11 PE-mirror = ✅ DONE (#381 template + #382 replicate).** STEP = additive PE YAMLs (`option_types:[PE]`
>    + directional gates/exit flipped to the PE side); the CE-only restriction was just `option_types:[CE]`,
>    the engine already resolves PE by price-vs-VWAP + scores the bearish side symmetrically. 27 PE drafts
>    (9 directional families × 3); the **9 `-nifty-pe` PUBLISHED live-paper**, the 18 SENSEX PE stay drafts (owner).
> 3. **E11 straddle combined-prem exit = ✅ DONE (#383 part 1 + #384 part 2).** Found the prerequisite gap
>    (straddle paper-opened only the CE leg). #383 = 2-leg paper-open (both ATM legs, combined-prem sized).
>    #384 = the live-only `@Scheduled` `StraddleExitMonitor` that closes both legs when the combined premium
>    ≤ the straddle-chart `slLevel` (combinedVwap − buffer). Parity-safe (never in replay; acts only on a
>    paper straddle). Inert until the owner publishes a straddle.
> 4. **E12 ideal-window + OH-freshness = SKIP** (low-value; existing time rails cover the session).
> 5. **FU2 4 soft dots = leave ADVISORY** (arm as hard gates only after live data).
> 6. **E3 Dow dot = leave UN-ARMED** (directional-VIX + manual checklist cover macro).
> 7. **wu4 Upstox cutover = STAY KITE / defer** (no urgency; revisit with a live-market A/B).
> 8. **SPAN / short-premium = KEEP LONG-ONLY** — verified all 36 scalper YAMLs are buy/long (`direction: long|both`, zero `short`); selling legs are SPAN-deferred future work. SPAN stays dormant.
> 9. **orders live-broker = KEEP PAPER / read-only** — validate on live paper first; real-money only after proof + supervision. (Per constraints, I never auto-execute trades.)
>
> → 2 builds queued (#2 PE-mirror STEP, #3 straddle auto-exit); 7 resolved as keep-as-is.
>
> **§2 follow-on builds (2026-06-30, second pass):**
> - **E9-target-trail-band = ✅ DONE (#386).** The fixed premium-% TP (#351, `value:35`) on its 2 armed
>   homes (gap-theory + market-movers, 12 variants) was a hardcoded placeholder pending live validation;
>   the owner design calls for it to be optimizer-tunable, but it sat in no sweep. Added
>   `exit_rules[type=take_profit].params.value` (range [20,55] step 5) to each optimize block — default 35
>   unchanged (live byte-identical), optimize block is backtest-only (parity-safe). The ST-line trail keeps
>   the canonical SuperTrend(10,2.0), pinned to the scored ST by design (no independent sweep → no divergence).
> - **instruments-exchange-token-null = ✅ DONE (#387).** Threaded `exchange_token` wire→domain→DB
>   (the ledger's suggested one-liner was inaccurate — the domain `InstrumentRecord` had no field). +1 record
>   field + 3 ctor sites + `setLong(4, …)`; nullable BIGINT column so no migration.

| id | what's built | what's owner-gated |
|---|---|---|
| `FU2-unarmed` | 4 soft-dots (indicator-align / futures-OI / breadth / basis) scored + hard-gate versions wired default-OFF | arming decision after forward paper |
| `E3-dow-confluence-unarmed` | dot + seam toggle + `Macro.dowUp` + producer + tests | left un-armed (no automated Dow feed; manual checklist covers it; superseded by directional-vix on trend-change) |
| `E8-atr-stop-arming-roster` | ATR(14)×2 structural stop, tested, tripwire-guarded | **which families arm it** (owner roster) |
| ~~`E9-target-trail-band-value`~~ | **✅ DONE #386** — TP `value` now optimizer-tunable on gap-theory + market-movers (default 35, parity-safe) | the live-validated NUMBER stays an optimizer/live output, not a code gap |
| ~~`E11-straddle-combined-prem-exit`~~ | **✅ DONE #383+#384** — 2-leg paper-open + the live `StraddleExitMonitor` (combined-prem ≤ slLevel → close both legs) | engine-managed; was operator-manual |
| ~~`E11-pe-mirror-seeding`~~ | **✅ DONE #381+#382** — 27 PE drafts (STEP, additive `option_types:[PE]`); 9 NIFTY published | owner chose STEP; replicated + NIFTY-published |
| `E12-oh-freshness-1030` | — | owner-deprioritized (low value, E12 time-window bucket) |
| `E12-ideal-window-gate` | — | owner-deprioritized (low value) |
| `wu4-upstox-cutover` | W-U1/U2/U3 merged, flag-gated default-Kite | off-hours deploy + live tick-latency/OI A/B + flip |
| ~~`data-foundation-value-verify`~~ | **✓ PASS 2026-07-01 (live-vs-live §20.8)** — owner's oipulse was signed-in + market open, so ran a live-vs-live compare. **OI Analysis: our captured OI == oipulse to the EXACT share** (2,882,220 / 3,464,080, SENSEX 77000, 2 buckets); Straddle premium + underlying + VWAP match; Connecting-Dots + OI-page STRUCTURE match. Our History-mode side re-confirmed on the full 2026-06-30 captured session (12 endpoints, real rows). Data foundation **value-verified**. Runbook: `docs/manual-tests/phase-4-wave1-value-verify-runbook.md` (Part A results). | residual (not build-gating): **F1** — `chain-table` ignores History-mode date (Options Chain page shows live data in History) → candidate BE fix; + low nits (heatmap UTC labels, strike-series ΔOI-method F5). Owner to ratify the close. |
| `span-real-spn-broker-parity` | loader + adapter + parity harness + fetcher, CI-green on synthetic fixture | a real `nsccl.<date>.s.spn` + owner sign-off (distinct from SPAN sell-legs, which are EXCLUDED) |
| `orders-page-live-broker-verify` | `/orders` page + §18.1 read endpoints (orderbook/positions/tradebook/funds), default-OFF | live-broker arm + verify |
| ~~`instruments-exchange-token-null`~~ | **✅ DONE #387** — `exchange_token` threaded wire→domain→DB (was hardcoded NULL) | none (closed) |

*(13 rows because `E12-ideal-window` is carried here as owner-deprioritized rather than in §1.)*

---

## 3. Recommended build order (when un-gated)
- **Wave A — finish the scalper signal-side (§1a, 4 items).** Small, in-`strategy-signal-service`, the proven
  `cfg.has("tag")` default-OFF + `ScalperStrategyLoadTest` tripwire pattern, parity-safe. Highest leverage.
- **Wave B — owner-numbers (§2).** Pure owner input → I arm. No code design needed; just the rosters/values.
- **Wave C — frontend oipulse (§1b).** Order: pure-frontend first (Risk Calculator, Multiple Window) → then
  endpoint-gated (Event Days, Futures Pre-Open, Announcement, Calendar Spread) → then the big PR-W4 charts
  (Advance Chart, Multiframe) which need a substrate decision (openalgo-chart MIT vs TradingView binary).
- **Wave D — Phase-5 equity chain (§1c).** Strictly sequential: 200-day backfill → Minervini screener. This is
  the home of the E1 per-stock equity residual (task #98).

---

## 4. FALSE-FLAGS the refutation rejected — DO NOT re-list these as pending
Recorded so a future pass doesn't re-flag them (each was caught claiming pending-when-actually-built/descoped):

| flagged as | truth | proof |
|---|---|---|
| E6 volume-qualified VWAP exit | **BUILT** (#301) | ships as the `min_volume` param on `signal_exit` (`ExitEvaluator:377-395`), armed on **30/36 YAMLs**, golden `signal-exit-volume.yaml` + `ExitEvaluatorTest`. Discovery grepped the wrong name (`vwap-vol-exit`) |
| PCR Upstox source-switch | **BUILT** (#75/#76) | ships as `source.optionanalytics` (PCR+max-pain paired), `UpstoxOptionAnalyticsSource` `@ConditionalOnProperty`, FE source-aware. Not `source.pcr` |
| OSPL Signal / Community-Pine pages | **DESCOPED** | future-work, closed-source Pine; parented under the deferred Advance-Chart cluster (master-plan §18.7) |
| Multi-Leg-Price strategy chart | **DESCOPED** | #36 explicitly cut option-strategies from oipulse parity (only read-only straddle/strangle `/premium` kept) |

---

## 5. Already-descoped / excluded — DO NOT add back
From the S24 prune + owner decisions (reference, so nothing here gets re-proposed as pending):
- `oi-direction-change-arrows` (E2 M5), `trending-oi ATM±7 recenter` (E2 P13), `dynamic-strike-recenter` (E7),
  `pct-price-move-gate` (E1) — S24-PRUNE descopes.
- E12 **economic-event lockout** = PERMANENT MANUAL check (#339, never auto-built by design).
- E12 **event-window anchors** = owner DECLINED.
- **SPAN short/sell legs** = deferred on the #47 margin appliance — EXCLUDED from scalper-100.
- **E1 equity-screener OOM path** = EXCLUDED (replaced by Upstox on-demand + captured bank-radar).

---

## 6. DECIDED — WILL NOT BE DONE (owner NO; do NOT re-flag as pending / false-flag)
These are **closed by an explicit owner decision**, not unbuilt work. A future "what's left" pass must
**not** resurface them as pending — list them here only as the record of *why* they are out. (Distinct from
§2, which is "mechanism built, waiting on an owner NUMBER" — those stay open; these are settled NOs.)

| item | decision | why |
|---|---|---|
| E8 ATR-stop arming | **WON'T arm** | all families keep their existing point/premium stops; no doc home for ATR (#322 confirmed un-armed) |
| FU2 4 soft dots (indicator-align / futures-OI / breadth / basis) | **WON'T hard-gate** | stay ADVISORY; only arm after live data proves them — not a build |
| E3 Dow-confluence dot | **WON'T arm** | no automated Dow feed; directional-VIX + the manual checklist cover macro |
| wu4 Upstox live cutover | **WON'T cut over (stay Kite)** | no urgency; Kite is the always-on fallback; revisit only with a live A/B |
| SPAN short / sell-leg premium | **WON'T build (long-only)** | all 36 scalpers are buy/long (verified); selling legs are permanently out of scalper-100 |
| orders live-broker arm | **WON'T arm (keep paper)** | paper/read-only only; real-money is out of scope (I never auto-execute trades) |
| E12 ideal-window + OH-freshness-1030 | **WON'T build** | low-value; existing time rails (09:45 floor / 15:12 square-off) cover the session |
| E12 economic-event lockout / event-window anchors | **WON'T build** | permanent-MANUAL (#339) / owner-DECLINED |
| Event Days page | **WON'T build** | a static proprietary Union-Budget slideshow, not an event calendar (no API) |
| SENSEX point-scale constant | **WON'T build** | dead code — all 2b scalpers signal on NIFTY-FUT-CONT, stops already in NIFTY-signal points |
| E1 equity-screener OOM path | **WON'T build** | replaced by Upstox on-demand + the captured bank-radar (#345-347) |

**Still genuinely open (NOT closed):** the §1c Phase-5 **Minervini screener** is the only net-new code (its
200-day MA history is now seeded #389; `strat-calendar-spread` shipped #390); the §2 rows below the
E9/instruments lines (`E9-target-trail` live number, `span-real-spn-broker-parity`) are owner-NUMBER/sign-off
gated, not build gaps. **`data-foundation-value-verify` PASSED 2026-07-01** (live-vs-live §20.8; captured OI ==
oipulse to the exact share) — the only residual is optional finding **F1** (`chain-table` doesn't honour
History-mode date; live mode is correct), a small BE fix, not a value-verify blocker.

---

## Net
Both **§1a (scalper signal side)** and **§1b (frontend / oipulse)** are now **COMPLETE**. §1a = 4
packages (#371–374); §1b = 6 pages built (Risk Calculator #375, Multiple Window #376, Futures Pre-Open
#377, Announcement #378, Advance Chart + Multiframe #379) with Event Days + Calendar Spread owner-skipped.
The §1c **200-day equity daily backfill is DONE (#389, live-verified — 222 daily candles/symbol)** and
**calendar-spread is DONE (#390)**, so the **only net-new code left is the Phase-5 Minervini Trend-Template
screener** (it now has its 150/200-day MA history). The §2 second pass then closed **E9-target-trail (#386)** +
**instruments-exchange-token (#387)**; the remaining §2 rows are owner-NUMBER/sign-off gated, not code.
Everything the owner has decided NOT to do is consolidated in **§6 (WILL NOT BE DONE)** so it is not
re-flagged as pending. Deploy-verify pending on #377 (pre-open render at next 09:00), #378 (NSE field
mapping), #379 (chart render).
