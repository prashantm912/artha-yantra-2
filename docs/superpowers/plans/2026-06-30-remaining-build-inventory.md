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

## 1. CONFIRMED PENDING — genuinely unbuilt (code-proven) — 9 (was 15; 6 shipped — §1a COMPLETE + §1b 2/8)

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

### 1a. Scalper signal-side (✅ ALL 4 SHIPPED — COMPLETE) — small, in-service, parity-safe tag-gate pattern
| id | item | doc | code-evidence of absence |
|---|---|---|---|
| ~~`FU1-manual-checks-9`~~ | **✅ DONE #371** — 9 manual-only checks added (`ScalperManualChecks.CHECKS` 7→16) | `followup1-expand-manual-checks.md` §3 | shipped 2026-06-30 |
| ~~`E5-rsi-band-per-strategy`~~ | **✅ DONE #372** — configurable `rsi_band` block + `rsiBandCustom` (on `ScalperParams`) | `rsi-multi-timeframe.md` §3.5 | shipped 2026-06-30 |
| ~~`E5-rsi-recovery-postvertical`~~ | **✅ DONE #373** — `rsiRecovery` trough→recovery sequencer, armed on trend-change ×3 (RATIFICATION-PACK row 51 = KEEP) | `rsi-multi-timeframe.md` §3.7 | shipped 2026-06-30 |
| ~~`E3-fii-participant-classifier`~~ | **✅ DONE #374** — `ParticipantBiasService` LB/SC/LU/SB classifier + `/fii-dii/bias` + `fii-dii-gate` (default-OFF) | `macro-vix-global-fii.md` §3.3 | shipped 2026-06-30 |

### 1b. Frontend / oipulse replication (6 pending; 2 shipped) — the bulk of remaining UI work
| id | item | backend? | code-evidence of absence |
|---|---|---|---|
| ~~`feat-risk-calculator`~~ | **✅ DONE #375** — Risk Calculator (`/features/risk-calculator`; pure `core/riskCalculator` + page + nav) | **pure-frontend** | shipped 2026-06-30 |
| ~~`feat-multiple-window`~~ | **✅ DONE #376** — Multiple Window (`/features/multiple-window`; `core/multipleWindow` 1/2/2×2 + localStorage + page + nav) | **pure-frontend** | shipped 2026-06-30 |
| `feat-event-days` | **Event Days** (econ/expiry-event calendar page) | needs events feed | no `EventDays*`/route/controller; distinct from the E12 backend lockout |
| `fut-pre-open-market` | **Futures Pre-Open Market** (09:00-09:08 F&O-stock A/D + prev-H/L break scan) | needs futures pre-open endpoint | distinct from the built *equity* `/equity/pre-open-market` (index banner only); `prevDayBreak`/`getpreopenmarketdata` grep ZERO |
| `equity-announcement` | **Announcement** (NSE corporate-filings feed, date-range searchable) | needs NEW external source | `/equity/news` (Upstox 7-day headlines) is a different surface; master-plan §20.5 = defer/skip (single-owner) |
| `strat-calendar-spread` | **Calendar Spread** chart (spread-premium candles + socket Add-Position legs) | needs leg-premium series + WS | no `CalendarSpread*` page/route; master-plan §18.7 = "studied, never built, future work" |
| `w4-advance-chart` | **Advance Chart** (openalgo-chart / TradingView-class, drawing + builder tools, OI overlay, study templates) | needs chart substrate decision | only `/charts` cockpit (lightweight-charts, overlays deferred); master-plan PR-W4 |
| `w4-multiframe-chart` | **Multiframe Chart** (multi-timeframe grid of Advance-Chart instances) | depends on Advance-Chart | no `multiframe`/grid route; depends on the unbuilt Advance-Chart |

### 1c. Data / infra — Phase-5 equity-screener chain (2) — sequential
| id | item | doc | code-evidence of absence |
|---|---|---|---|
| `phase5-200day-equity-daily-backfill` | §15 200-day daily-OHLCV equity-universe backfill (Upstox v3 `historical-candle/days/1` → `candles@1d`) — Phase-5 prerequisite | `DEFERRED_BACKLOG.md` L102; master-plan §15 | no `UpstoxDailyHistoryClient`/`openchart`/200d backfill; existing 1d populators cap at 30-90d forward-shallow |
| `phase5-minervini-trend-template` | Phase-5 Minervini Track-1 screener — daily 8-gate Trend Template + RS-rank (§13) | `DEFERRED_BACKLOG.md` L167 | `ScreenerService` presets = `{momentum,long_term,oi_buildup,rs_rank}`; no `TrendTemplate`/`Minervini`; gated on the 200-day backfill above |

*(Low-value aside: `E12-ideal-window-gate` (09:15-10:00 fresh-entry hard-skip) is technically unbuilt but the
`e8-e12-numbers` authority already judged it low-value/owner-deferred — treat as owner-deprioritized, §2.)*

---

## 2. OWNER-GATED / PARKED — mechanism built, waiting on a number / decision / deploy / manual step — 12
Not build work. Each needs an owner input, not code.

| id | what's built | what's owner-gated |
|---|---|---|
| `FU2-unarmed` | 4 soft-dots (indicator-align / futures-OI / breadth / basis) scored + hard-gate versions wired default-OFF | arming decision after forward paper |
| `E3-dow-confluence-unarmed` | dot + seam toggle + `Macro.dowUp` + producer + tests | left un-armed (no automated Dow feed; manual checklist covers it; superseded by directional-vix on trend-change) |
| `E8-atr-stop-arming-roster` | ATR(14)×2 structural stop, tested, tripwire-guarded | **which families arm it** (owner roster) |
| `E9-target-trail-band-value` | premium-% take-profit + trail mechanism, armed (#351) | the VALUE (35% TP / ST-line band are placeholders) — live validation |
| `E11-straddle-combined-prem-exit` | combined-VWAP SL **level** surfaced (#324) | operator-manual by design; no engine-managed 2-leg exit |
| `E11-pe-mirror-seeding` | engine exit-direction support (`scalperPositionDirection`) | **PARKED #364** — one open design choice (signed-composite vs STEP) |
| `E12-oh-freshness-1030` | — | owner-deprioritized (low value, E12 time-window bucket) |
| `E12-ideal-window-gate` | — | owner-deprioritized (low value) |
| `wu4-upstox-cutover` | W-U1/U2/U3 merged, flag-gated default-Kite | off-hours deploy + live tick-latency/OI A/B + flip |
| `data-foundation-value-verify` | every OI/data page renders in History mode; tested | owner oipulse sign-in for the cell-for-cell §20.8 compare |
| `span-real-spn-broker-parity` | loader + adapter + parity harness + fetcher, CI-green on synthetic fixture | a real `nsccl.<date>.s.spn` + owner sign-off (distinct from SPAN sell-legs, which are EXCLUDED) |
| `orders-page-live-broker-verify` | `/orders` page + §18.1 read endpoints (orderbook/positions/tradebook/funds), default-OFF | live-broker arm + verify |
| `instruments-exchange-token-null` | wire value captured in `InstrumentRecord` | populate (one-liner `ps.setObject(4, r.exchangeToken())`) when a consumer needs it |

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

## Net
The **scalper signal side (§1a) is COMPLETE** — all 4 packages shipped (FU1 #371 + rsi-band #372 +
rsi-recovery #373 + fii-classifier #374). Only the §2 owner-numbers remain on the strategy side; the
remaining *net-new* code is **frontend oipulse (§1b, 8 pages)** + the **Phase-5 equity chain (§1c, 2)**. The remaining *bulk of net-new work* is **frontend oipulse (§1b, 8 pages)** and the **Phase-5 equity
screener chain (§1c, 2, sequential)**. Everything else is owner-gated, not code-pending.
