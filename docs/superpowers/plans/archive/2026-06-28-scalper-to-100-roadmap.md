> **ARCHIVED 2026-07-02 — BUILD COMPLETE.** Every AUTOMATE_PKG package is built (default-OFF tag-gate,
> PRs #274–#404 arc), owner-descoped, or owner-declined (inventory §5/§6). What remains is owner-NUMBERS
> only (E9 band, keep/cut/tune) via the live forward-paper analysis runbook — see [`2026-07-02-remaining-items.md`](../2026-07-02-remaining-items.md).

# Scalper → 100% — consolidated implementation roadmap

**Status:** ACTIVE — the single forward authority for finishing the options-scalper engine.
**Owner:** single-owner. **Date:** 2026-06-28. **Anchor (source of truth):**
[`strategy-documents/options-scalper-siva-operative/Options_Scalper_Siva_Operative_Strategy.md`](../../../strategy-documents/options-scalper-siva-operative/Options_Scalper_Siva_Operative_Strategy.md)
— the **debloated** Session-24 operative doc.

> **What this supersedes.** This doc replaces the bloated-audit framing. The strategy-audit chain
> (`docs/strategy-audit/`, now CLOSED) audited the OLD *consolidated* doc and produced a 424-gap /
> 246-`AUTOMATE_PKG` backlog. The debloat (S24 ratification) **dropped 72 rules** from the doc but
> only **~5 backlog packages** ([S24-PRUNE](2026-06-27-backlog/S24-PRUNE.md)). So "100%" = every
> deterministic rule the **debloated** doc demands, enforced in the engine. This roadmap is the
> **sequencing authority**; the 12 stream files under [`2026-06-27-backlog/`](2026-06-27-backlog/)
> remain the **per-package design** (file:line, schema, tests, parity argument).

## 0. Definition of "100% — nothing deferred"

Every deterministic rule in the operative doc resolves to exactly one of:
**BUILT** (live in the engine) · **EPIC-n** (a remaining work-package below) · **DESCOPED**
(an S24-dropped rule — never build) · **OWNER-DECISION** (a number/feed choice, no code) ·
**MANUAL** (`ScalperManualChecks` / discretionary, automatable=false) · **SPLIT-PLAN** (a large
sub-epic that gets its own grilled plan). §6 is the completeness ledger that proves nothing is orphaned.

All engine work follows the **parity-safe default-OFF tag** recipe (WORLD-2 scalper gate, absent on
all 36 shipped YAMLs ⇒ `GoldenDeterminismTest` + `BacktestParityTest` byte-identical). **Arming a tag
on a real strategy is the owner's forward-paper step — never a backtest** (derived-history mutes
OI/Dow/IV; backtests overfit). That arming step is the only thing between "built" and "live".

## 1. BUILT baseline — already in the engine (do NOT rebuild)

Verified against HEAD `ef2650b` (2026-06-28):

- **Core gates:** entry-above-VWAP; VWAP-as-stop / break-on-volume exit; structural 1st-candle / gap
  stops; A/D breadth >32; ST(7,3) higher-TF bias gate; ≥50% OI imbalance / `oi-cross-filter`;
  monthly-expiry ignore-OI; Dow=US30 factor (live LTP).
- **Exit grammar:** the 5 exit types `stop_loss / trailing_stop / take_profit / time_stop / signal_exit`
  (engine + schema; `premium_pct` trailing/TP primitives exist — variants not yet wired).
- **Indicator primitives:** PSAR params pinned, Volume-20 MA declarable, two-candle pattern arming,
  per-index volume floor (BN ≥50K / N ≥125K).
- **S24 drift tags (W3, #251–256, default-OFF):** `rsi-s24-bands` (50-75/40-50/40-25) ·
  `delta-s24-floor` (≥0.7) · `premium-s24-band` (N 150-350 / BN 250-550) · `index-point-sl`
  (BN~100 / N~50-60 / Sensex~200-250) · `open-high-oi-veto` · OIP-AI tier **LOW/MILD/HIGH**.
- **S24 gates (W4, #258–262, default-OFF):** `indicator-distance-veto` · `divergence-vol-gate` ·
  `herozero-side-oi` · `overbought-defer` · `directional-change-gate` · `s24-trade-window` ·
  `gap-size-side-gate` · 6c OIP-AI surfacing (signal/alert/cockpit).
- **2b infra (#220–230, live):** NIFTY/SENSEX-FUT-CONT continuous signal, signal/strike/option
  three-way decoupling (ADR-0003), 36 instrument-agnostic variants seeded + 36/36 functional backtests.
- **Operator surface:** `ScalperManualChecks` 7-item card.

These were ratified in [RATIFICATION-PACK](../../strategy-audit/RATIFICATION-PACK.md) and built per the
(now archived) W3/oip-ai plans. **Everything below is what remains.**

## 1b. Built + armed this session (2026-06-28, owner directive: arm-on-where-they-belong)

The build started under the owner's D1-override (**gates ARMED on the strategies they belong to**, not
left inert — strategies aren't live yet; the seeder mints a new draft version on tag-change at the next
deploy). Default-OFF in code (`absent = off` stays clean); NOT live-deployed. Merged to `main`:

- **W0 — `SUPERTREND_LINE` indicator** (#274): the P1 ST price-level primitive (ta4j Supertrend LINE,
  the level the direction-only `SUPERTREND` dot could not expose). Registered, golden-safe; unblocks the
  E6 ST-distance + E9 ST-level-stop rules. *(Consumers not yet wired — that is E6/E9.)*
- **E2 M1 `oi-cross-required` + M2 `oi-slope-agree`** (#275): the Trending-OI #5 defining edge promoted
  from soft dots to HARD pre-gates (completed fresh cross; sentiment level+slope conjunction; both
  fail-closed). **Armed on `scalp-trending-oi-{nifty,sensex-niftyoi,sensex-sensexoi}`.**
- **E2 M4 `flat-oi-stand-aside`** (#276): the flat-OI trap (null/flat imbalance → stand aside; inverse of
  #5's fail-open). **Armed on `scalp-connect-the-dots-*`** (no `oi-cross-filter` there → mutually exclusive).
- **E2 M6 `max-oi-sr-gate`** (#277): the OI-wall S/R gate (don't trade into the max-standing-OI strike;
  fail-open). **Armed on `scalp-connect-the-dots-*`.**

Parity held throughout: the 5 engine/backtest goldens carry no scalper → byte-identical, no regen
(BacktestParityTest green each PR). **Build recipe proven** for the rest: a pure `ScalperGates` fn → a
`cfg.has(tag)` early-return in `ScalperConfluenceGate` (NO `ScalperConfig` record change — the lighter
W4 pattern, not the stream-files' `requireXxx` field) → `ScalperGatesTest` unit + `ScalperConfluenceGateTest`
seam triple → arm the tag on the applicable YAMLs → a `ScalperStrategyLoadTest` *armed-iff-family* tripwire.
**Caveat (expected):** armed strategies show ~ZERO trades on HISTORICAL backtests (derived history mutes OI →
fail-closed gates) — a data artifact, NOT a regression; judge on FORWARD paper.

**E2 remainder:** M3 `oi-divergence-magnitude` (needs an `oiDivergencePct` producer field), M7
`oi-interval-and-60m-trend` (2nd `/options/trending` fetch); M5 is DESCOPED. See [oi-fidelity-gates.md](2026-06-27-backlog/oi-fidelity-gates.md).

## 2. The remaining work — 12 epics (the AUTOMATE_PKG remainder, debloated)

~95 work-packages across 12 streams. Each epic links its design file. Effort S/M/L; `[P]` =
parity-sensitive (gate behind a new default-OFF tag), `[S]` = self-contained / backtest-only, `safe`
= advisory/account-side. P-counts exclude DESCOPED packages.

| Epic | Stream design | Remaining pkgs | Effort | Serves (operative scope) |
|---|---|---:|:--:|---|
| **E1 Stock-universe + Market-Movers** *(foundation)* | [stock-universe-market-movers.md](2026-06-27-backlog/stock-universe-market-movers.md) | 13 | **L** | Market-Movers (futures-only, 8-day breakout, OH/OL, per-stock OI/RSI/IV/liquidity), every per-stock gate |
| **E2 OI fidelity** | [oi-fidelity-gates.md](2026-06-27-backlog/oi-fidelity-gates.md) | 15 | M | Trending-OI cross/slope/divergence/flat/quadrant, 2nd/positional OI window, max-OI S/R |
| **E3 Macro confluence** | [macro-vix-global-fii.md](2026-06-27-backlog/macro-vix-global-fii.md) | 5 | M | directional VIX gate, Dow dot, FII/DII L/S bias, constituent contribution, volume-pump |
| **E4 IV fidelity** | [iv-fidelity.md](2026-06-27-backlog/iv-fidelity.md) | 6 | M | per-strike IV slope, absolute 10-12 band, 7-10 pair, IV>40 cap, both-flat skip, LOW-IV straddle |
| **E5 RSI multi-timeframe** | [rsi-multi-timeframe.md](2026-06-27-backlog/rsi-multi-timeframe.md) | 7 | M | daily-RSI caps (>70/80/25-30), per-strategy bands, cool-off / post-vertical recovery |
| **E6 Indicators** | [indicators-supertrend-volume.md](2026-06-27-backlog/indicators-supertrend-volume.md) | 6 | M | 15m SuperTrend confirm, **ST price-level**, trendline-break, rising-volume confirm, two-candle substitution |
| **E7 Strike / premium** | [strike-premium-selection.md](2026-06-27-backlog/strike-premium-selection.md) | 2 | M | band-aware backtest selector, per-side premium-skew warning |
| **E8 VWAP-distance + sizing** | [vwap-and-sizing.md](2026-06-27-backlog/vwap-and-sizing.md) | 6 | M | VWAP-distance skip, **prior-day VWAP series**, probability-graded `suggested_qty`, time-pref |
| **E9 Trade management / exits** | [trade-management-exits.md](2026-06-27-backlog/trade-management-exits.md) | 8 | L | target/trailing variants, volume-qualified VWAP-break exit, structural/points trails, **ST-level stop**, gap-fill deadline, profit-slice sizing |
| **E10 Risk governance** | [risk-governance.md](2026-06-27-backlog/risk-governance.md) | ~8 | M-L | daily profit/loss/deploy caps + seeds, 5-account ledgers, auto-journal |
| **E11 Per-strategy controls + seeding** | [per-strategy-controls-seeding.md](2026-06-27-backlog/per-strategy-controls-seeding.md) | 17 | M-L | **BTST routing through the gate**, `direction:both`→side, PE-mirror seeding, Sensex 3× scaling, per-strategy knobs |
| **E12 Event/time + backtest rails + SPAN** | [event-time-backtest-span.md](2026-06-27-backlog/event-time-backtest-span.md) | 7 | M | economic-event lockout, ideal-window / avoid-Friday / expiry-timing gates, backtest `gate.all` rails, **SPAN sell legs** |

**The S24-priority slice (P1–P6).** The 13 S24-specific DEFER rules (from the now-archived W4
disposition) are the highest-visibility cut across these epics — build these first if optimising for the
S24 thread:

| S24 primitive | Lands in | Operative rules unlocked |
|---|---|---|
| **P1** daily-RSI + ST price-level + prior-day VWAP | E5 + E6 + E9(ST-stop) + E8(prior-VWAP) | #2 daily-RSI caps, #6 ST↔VWAP no-trade, #11 VWAP-anchor 10:30, Morning-SL (D25), Gap defence ladder |
| **P2** confluence-aware EXIT seam | E9 + E2 | #12 OI-gap-exit, #8 fake-crossover exit, profit-protection, trailing-prev-candle |
| **P3** cross-bar session state | E2 + E11 | #8 cross-count, Day-20 quadrant-transition, recovery-candle-count |
| **P4** route `style:btst` through the gate | E11 | #4 BTST carry-validity |
| **P5** 2nd/positional OI window + crore | E2 | #7 OI-dual-timeframe |
| **P6** combined-premium series + VWAP | E11(straddle) + E4 | #9 straddle combined-premium-VWAP |

## 3. Dependency-aware build order

The spine: **equity universe → per-stock gates** · **VIX/FII/IV feeds → their gates → the sizing
multiplier** · **default-OFF tag-gating → every `[P]` epic** · **BTST routing → BTST sub-packages** ·
**SPAN → every sell leg**.

### Wave 0 — foundations (no upstream dep; unblock the rest)
1. **E1 equity universe + Market-Movers** — *every per-stock package gates on it*; carries an
   **≥8-session warm-up**, so start the capture **first of all**. `[S]` (brand-new path, no golden).
2. **Wave-0 feeds** — E3 VIX/Dow/FII/constituent + E4 per-strike IV reads, **before** their gates
   (they also light the sizing multiplier's factors in E8).
3. **P1 indicator primitives** — ST price-level accessor (E6), daily-RSI series (E5), prior-day VWAP
   cache (E8). No new feed, no ADR; unblock #2/#6/#11 + Morning-SL + E9 ST-stop.
4. **SPAN #47 live-verify** — gates every sell leg (E11/E12); start the `.spn`/NSE-URL verify early so
   it is ready when the sell path is reached.

### Wave 1 — the FU2 tag-gating pattern, applied broadly
5. **E2 OI fidelity** — no-feed cross/slope/divergence/flat/quadrant gates first; then the producer
   derivations (P5 2nd OI window). Highest-value (the defining Trending-OI edge).
6. **E5 RSI + E6 Indicators** — every datum already on the engine series; ship the free `[S]` wins first.
7. **E3 macro gates + E4 IV gates** — once Wave-0 feeds land.
8. **E8 VWAP-distance + sizing** — distance-skip has no dep; the multiplier's VIX factor lights for free.
9. **E9 trade-management** — YAML target/trail variants (primitives already BUILT) first, then the new
   exit types + the **P2 confluence-exit seam** (needs an ADR: live-only exit vs replay determinism).

### Wave 2 — dependent / heavier
10. **E11 per-strategy controls** — **`btst-route-through-gate` first** (P4; unblocks the 3 BTST
    sub-packages + the `both`→side resolver); then PE-mirror seeding (parity-safe new slugs), Sensex
    3× scaling (rides 2b decoupling), per-strategy knobs, **P6 straddle combined-premium**.
11. **E7 strike/premium** — band-aware backtest selector (`[S]`, own golden) any time; per-side skew dot.
12. **E10 risk governance** — account-side `[S]`/safe rails; parallel with anything.
13. **E12 event/time + SPAN** — time/calendar gates independent; **SPAN sell legs land LAST**
    (gated on Wave-0 SPAN verify + the E11 BTST routing).

## 4. DESCOPE — do NOT build (S24 dropped these)

Per [S24-PRUNE](2026-06-27-backlog/S24-PRUNE.md) (markers in-place in the stream files):
- `oi-direction-change-arrows` (E2 P5) — dropped tool-UI primitive (#26/#63).
- `trending-oi-strike-window` ATM±7 recenter (E2 P13) — dropped strike-housekeeping (#25/#62/#74).
- `dynamic-strike-recenter` ATM±7 (E7) — same dropped recenter.
- `pct-price-move-gate` duplicate (E1) — built once in E6, not re-keyed per-stock.
- **KEEP-PARTIAL (build the kept half only):** S22 premium-band *numerals* (keep the premium-FLOOR),
  New-High/Low panel *fields* (keep the screener), 60m-ST *equivalence* (keep 15m ST).

**SPLIT to own grilled plan** (large, gates ≥5 strategies — not in the 12 epics' critical path):
- **True S/R-zone engine** (1d+15m pivot/zone + spot-OI-bar S/R; E9 ships the points-basis stop now,
  stubs the S/R consumer behind an inert tag).
- **Scale-in / averaging ladder** (geometric 1/2/4/8→16; deliberately deferred, manual today).

## 5. No-code owner decisions (close these to unblock arming)

| Item | Decision needed | Where |
|---|---|---|
| #1 / D4 daily-loss cap | the 10-12% number → `daily_loss_limit` DB row `{enabled, mode:pct, value}` | E10 / RiskService |
| D21 BTST overnight SL | 50%-premium vs none | E11/E12 |
| D10 / D13 window anchors | Gap 9:45 vs 9:40; Trending-OI end 1:30-2 vs 2:30 | E12 (`s24-trade-window` already 09:45-14:30) |
| FII feed scalar | which feed drives `Macro.fiiLongPct` | E3 |
| 15-strike chain-read width | market-data sampling param (if ever wanted) | E2 producer |
| 2 UNCERTAIN descopes | keep (default) or drop: per-side IV-skew/`iv_slope`, post-vertical-RSI ~40 | E4 / E5 |

## 5b. Follow-ups (small, parity-safe, ride the above)

- **FU1** — [expand-manual-checks](2026-06-27-followup1-expand-manual-checks.md): add 9 `ScalperManualChecks`
  items (constituent-weight, FII L/S, pre-open A/D, Sensex participation, intraday-vs-positional OI,
  expiry IV-crush, straddle-VWAP, time-of-day VWAP). Mechanical; **unbuilt**.
- **FU2** — [soft-dots-to-hard-gates](2026-06-27-followup2-soft-dots-to-hard-gates.md): 4 hard-gate
  promotions (indicator-alignment, futures-OI, breadth, basis), default-OFF. **Unbuilt**; rides the W4
  framework.

## 6. Completeness ledger — every operative scope accounted for

| Operative scope | Disposition |
|---|---|
| VWAP/SuperTrend (entry, defence ladder), Volume floors, Breadth >32, ST(7,3) bias, ≥50% OI gate, monthly-expiry, Dow factor, structural stops, 5 exit types | **BUILT** (§1) |
| RSI bands, delta floor, premium band, point-SLs, OIP-AI tier, the 7 W4 gates | **BUILT** (W3/W4 tags, §1) — arm on forward paper |
| Market-Movers (futures-only, 8-day, per-stock OH/OL/OI/RSI/IV/liquidity) | **E1** |
| Trending-OI cross/slope/divergence/flat/quadrant, 2nd OI window, max-OI S/R | **E2** |
| VIX directional gate, Dow dot, FII/DII bias, constituent contribution | **E3** |
| per-strike IV slope/band/cap, both-flat, LOW-IV straddle | **E4** |
| daily-RSI caps, per-strategy RSI bands, cool-off | **E5** |
| 15m ST confirm, ST price-level, trendline-break, rising-volume, two-candle substitution | **E6** |
| backtest premium-band selector, per-side premium-skew | **E7** |
| VWAP-distance skip, prior-day VWAP, probability-graded sizing | **E8** |
| targets/trailing variants, volume-qualified VWAP exit, structural/points/ST trails, gap-fill deadline, profit-slice | **E9** |
| daily caps, 5-account ledgers, auto-journal | **E10** |
| BTST routing+side+quadrant+window, Sensex scaling, PE-mirror seeding, per-strategy knobs, straddle combined-premium | **E11** |
| economic-event lockout | **MANUAL — PERMANENT** (owner-ratified 2026-06-29): signals always fire on event days, the owner decides via the `news_clear` ScalperManualChecks item; NO scheduled-event feed, never auto-build (operative §2.9) |
| avoid-Friday gate, time/expiry/window gates, backtest rails, SPAN sell legs | **E12** (avoid-Friday BUILT #333; SPAN sell legs SPAN-deferred) |
| ATM±7 recenter, OI direction-arrows, 200/300% spurt, scale-in ladder, S/R-zone engine | **DESCOPED / SPLIT-PLAN** (§4) |
| daily-loss number, overnight-SL, window anchors, FII feed, 15-strike width, 2 UNCERTAIN | **OWNER-DECISION** (§5) |
| pre-market prep, capital governance, news, S/R eyeballing, psychology (~106 audit rows) | **MANUAL** (`ScalperManualChecks` + FU1) |

When E1–E12 ship and the tags are armed on forward paper, **every row above is BUILT, DESCOPED, an
OWNER number, or MANUAL — nothing remains deferred.**

## 7. References
- **Authority:** the operative doc (anchor, top).
- **Design:** the 12 stream files in [`2026-06-27-backlog/`](2026-06-27-backlog/) + FU1/FU2.
- **History (CLOSED):** [`docs/strategy-audit/`](../../strategy-audit/) (the bloated-consolidated audit),
  and `plans/archive/` (2b scalper-tunable-infra, w3-engine-drift-impl, oip-ai-probability-spec).
- **Decisions:** [RATIFICATION-PACK](../../strategy-audit/RATIFICATION-PACK.md) (Part 1 drops, Part 2/3
  drift rulings), [ADR-0003](../../adr/0003-scalper-signal-strike-option-decoupling.md).
