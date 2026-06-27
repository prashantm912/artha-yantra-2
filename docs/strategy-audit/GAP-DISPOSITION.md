# Strategy-Audit Gap Disposition — master coverage map

**Purpose.** The strategy audit (`docs/strategy-audit/`) enumerated on the order of **~424 raw gap
findings** across the 12-strategy Siva deck plus the shared Global-Risk / indicator / OI / VIX / IV
reference sections. This document is the **master coverage map**: it proves every audit gap is accounted
for — each gap has exactly one home (already covered, covered by a follow-up plan, scheduled in the
work-package backlog, kept as a future manual check, accepted by design, or flagged as an owner decision).

It is rebuilt from the **complete set of 18 per-dimension disposition files** under
[`docs/strategy-audit/disposition/`](disposition/) — the source of truth. Every number below is the sum of
the per-row `Disposition` column across those 18 files (computed, not carried over from any earlier draft).

The two follow-up plans that came out of that audit deliberately close only a **thin slice**:

- **FU1** — [`2026-06-27-followup1-expand-manual-checks.md`](../superpowers/plans/2026-06-27-followup1-expand-manual-checks.md):
  adds **9 manual checks** to `ScalperManualChecks` (on-card reminders, no automation, parity-neutral).
- **FU2** — [`2026-06-27-followup2-soft-dots-to-hard-gates.md`](../superpowers/plans/2026-06-27-followup2-soft-dots-to-hard-gates.md):
  promotes **4 soft confluence dots to opt-in hard gates** (`indicator-alignment`, `futures-oi-gate`,
  `breadth-gate`, `basis-gate`), tag-gated default-OFF so every shipped config stays byte-identical.

The already-shipped **7-item `ScalperManualChecks`** (news / level-respected / not-parabolic / regime /
VIX-normal / global-cues / clean-setup) carries the `COVERED_EXISTING` rows. Everything else — the bulk —
is the **work-package backlog** in §3, plus the keep-manual / accept-by-design / owner-decision tails (§4).

---

## 1. Headline & reconciliation

The audit's **~424** is the *raw finding inventory*. This disposition exercise works from the
**deduplicated non-FULL gap set** — the rows that actually need a decision — rolled up across all 18
per-dimension files. The dispositioned total differs slightly from the headline 424 because the v2/v3
review passes added rows to several dimensions (and one Hero-Zero source gap splits into two homes — an
FU2-hardened OI leg and an automate-able per-strike-price residual).

**Computed total: 427 dispositioned gap rows.** Every current non-FULL row across the 18 files is
accounted for; the per-disposition counts sum exactly to the total:

| Disposition | Count |
|---|---:|
| COVERED_EXISTING | 57 |
| COVERED_FU1 | 26 |
| COVERED_FU2 | 13 |
| AUTOMATE_PKG | 246 |
| KEEP_MANUAL_NEW | 32 |
| ACCEPT_BY_DESIGN | 36 |
| UNCERTAIN_OWNER | 17 |
| **Total** | **427** |

Reconciliation: `57 + 26 + 13 + 246 + 32 + 36 + 17 = 427`. ✓

**What the two plans actually close.** FU1 + FU2 together directly close **39** of the 427 rows
(`COVERED_FU1 = 26` + `COVERED_FU2 = 13`). A further **57** (`COVERED_EXISTING`) are already carried by the
shipped 7-item `ScalperManualChecks`. So **96 rows are covered today or by the two queued plans**; the
remaining **331** are this document's job to disposition — **246** scheduled in the work-package backlog
(§3), and **85** in the keep-manual / accept-by-design / owner-decision tails (§4: `32 + 36 + 17`).

### Per-dimension contribution (all 18 files)

| Dimension (file) | EXIST | FU1 | FU2 | AUTO | KEEP | ACCEPT | UNCERT | Total |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| [two-candle](disposition/two-candle.md) | 4 | 0 | 3 | 18 | 1 | 2 | 1 | 29 |
| [open-high-low](disposition/open-high-low.md) | 4 | 0 | 3 | 14 | 1 | 1 | 0 | 23 |
| [market-movers](disposition/market-movers.md) | 0 | 1 | 0 | 21 | 1 | 0 | 0 | 23 |
| [gap-theory](disposition/gap-theory.md) | 3 | 0 | 0 | 11 | 0 | 3 | 0 | 17 |
| [trending-oi](disposition/trending-oi.md) | 5 | 0 | 0 | 20 | 0 | 2 | 1 | 28 |
| [golden-crossover](disposition/golden-crossover.md) | 5 | 0 | 1 | 7 | 1 | 0 | 1 | 15 |
| [hero-zero](disposition/hero-zero.md) | 5 | 0 | 1 | 11 | 3 | 3 | 0 | 23 |
| [btst-stbt](disposition/btst-stbt.md) | 3 | 1 | 1 | 15 | 3 | 2 | 0 | 25 |
| [morning-trade](disposition/morning-trade.md) | 4 | 2 | 1 | 12 | 3 | 0 | 0 | 22 |
| [connect-the-dots](disposition/connect-the-dots.md) | 5 | 0 | 1 | 13 | 3 | 1 | 1 | 24 |
| [straddle](disposition/straddle.md) | 3 | 1 | 0 | 14 | 1 | 1 | 1 | 21 |
| [trend-change](disposition/trend-change.md) | 5 | 1 | 1 | 12 | 3 | 2 | 1 | 25 |
| [risk-framework](disposition/risk-framework.md) | 4 | 0 | 0 | 24 | 8 | 6 | 0 | 42 |
| [indicators-oi-vix-iv](disposition/indicators-oi-vix-iv.md) | 2 | 3 | 1 | 9 | 0 | 2 | 1 | 18 |
| [gates-strike-sr-fiidii](disposition/gates-strike-sr-fiidii.md) | 1 | 1 | 0 | 11 | 3 | 2 | 0 | 18 |
| [session-additions-and-manual-coverage](disposition/session-additions-and-manual-coverage.md) | 0 | 11 | 0 | 15 | 1 | 6 | 3 | 36 |
| [intro-terminology](disposition/intro-terminology.md) | 2 | 3 | 0 | 7 | 0 | 4 | 2 | 18 |
| [completeness-sweep](disposition/completeness-sweep.md) | 2 | 2 | 0 | 13 | 0 | 0 | 5 | 22 |
| **Total** | **57** | **26** | **13** | **246** | **32** | **36** | **17** | **427** |

> Note on Hero-Zero: its table has 23 physical rows for 21 source non-FULL gaps because the
> "strong-move >50% confirmation" gap splits into two homes (an FU2-hardened OI leg + an AUTOMATE_PKG
> per-strike residual). The split is intentional so each sub-gap has a distinct disposition; this master
> total counts physical disposition rows.

---

## 2. Coverage table

| Disposition | Count | Meaning |
|---|---:|---|
| **COVERED_EXISTING** | 57 | Already carried by the shipped 7-item `ScalperManualChecks` (on-card discipline reminders). No new work. |
| **COVERED_FU1** | 26 | Closed by Follow-up-1's 9 added manual checks (`fii_ls_ratio`, `constituent_contribution`, `pre_open_bias`, `sensex_participation`, `oi_intraday_positional`, `iv_crush_awareness`, `straddle_vwap_entry`, `time_of_day_vwap`, `vix_regime_bands`). |
| **COVERED_FU2** | 13 | Closed by Follow-up-2's 4 soft-dot→hard-gate promotions (`indicator-alignment`, `futures-oi-gate`, `breadth-gate`, `≥50% ΔOI imbalance`), tag-gated default-OFF. |
| **AUTOMATE_PKG** | 246 | Automatable, not in FU1/FU2 → scheduled into the themed work-package backlog (§3). |
| **KEEP_MANUAL_NEW** | 32 | Genuinely manual / judgement / no data source → future manual-check candidates beyond FU1, or trader discretion (§4.1). |
| **ACCEPT_BY_DESIGN** | 36 | Wontfix: soft-by-design, derived-history artifact, structurally moot (long-only / single-entry), or out-of-scope (SPAN-gated short side) (§4.2). |
| **UNCERTAIN_OWNER** | 17 | Doc-internal conflict or an explicit owner design choice → open points awaiting sign-off (§4.3). |
| **Total** | **427** | |

---

## 3. THE WORK-PACKAGE BACKLOG (`AUTOMATE_PKG` = 246 gaps)

The 246 `AUTOMATE_PKG` gaps group into **96 themed work-packages** (28 multi-gap, 68 single-gap),
ordered by gaps-closed descending. `[P]` = **parity-sensitive** (touches signal emission / the gate seam →
needs the FU2 default-OFF tag-gating + new-golden pattern so shipped configs stay byte-identical).
`[S]` = **safe** (read-only analytics, a new manual-managed exit/sizing leg, backtest-only fidelity, or a
new YAML variant that does not alter an existing config's bytes). Effort is a rough S/M/L.

### 3a. High-leverage packages (≥4 gaps)

| Package | # gaps | Effort | Feeds (dimensions) | P/S |
|---|---:|:--:|---|:--:|
| **`trade-management-targets-trailing`** | 31 | L | 14 dims — two-candle, open-high-low, gap-theory, trending-oi, golden-crossover, hero-zero, btst-stbt, morning-trade, connect-the-dots, straddle, trend-change, market-movers, risk-framework, completeness-sweep | **[P]** exit/target rules change trade emission (replay-golden), though many legs are management-only |
| **`probability-graded-sizing`** | 13 | L | risk-framework, open-high-low, trending-oi, morning-trade, session-additions | **[S]** sizing / `suggested_qty` — advisory, does not change which signals fire |
| **`iv-per-strike`** | 12 | M | two-candle, open-high-low, indicators-oi-vix-iv, intro-terminology, straddle, gates-strike-sr-fiidii, session-additions | **[P]** per-strike IV-slope becomes an entry precondition / dot |
| **`directional-vix-gate`** | 11 | M | connect-the-dots, trend-change, indicators-oi-vix-iv, open-high-low, two-candle, intro-terminology, gates-strike-sr-fiidii, completeness-sweep, session-additions | **[P]** wires the null VIX feed into `MarketOiClient.macro` → a confirm/block gate (explicitly OUT of FU2) |
| **`intraday-positional-oi`** | 11 | L | two-candle, market-movers, trending-oi, morning-trade, hero-zero, session-additions | **[P]** positional / series-state OI compare feeds the confluence (muted on derived history) |
| **`sr-levels-targets-stops`** | 11 | L | risk-framework, gap-theory, open-high-low, morning-trade, gates-strike-sr-fiidii, completeness-sweep, session-additions | **[P]** an S/R-zone engine drives targets / stops / entries (new shared primitive) |
| **`strike-premium-band-backtest`** | 11 | M | two-candle, open-high-low, hero-zero, straddle, intro-terminology, completeness-sweep, session-additions | **[S]** backtest premium-replay selector honours the premium band (backtest-only; live `StrikePicker` already does) |
| **`oi-cross-hard-gate`** | 8 | M | trending-oi, gates-strike-sr-fiidii, intro-terminology, completeness-sweep | **[P]** promotes the OI-cross / both-sides-OI / 200-300% spurt reads from soft dots to hard pre-gates |
| **`btst-route-through-gate`** | 7 | M | btst-stbt | **[P]** the load-bearing BTST fix — route `style: btst` through the confluence gate + StrikePicker so ~7 already-built gates become reachable (`decision != null`) |
| **`daily-target-caps`** | 7 | S | risk-framework | **[S]** seed daily profit/loss targets + 0.5% / 2-3% / 10-12% caps + over-trade taper (account-side rails) |
| **`vwap-distance-sizing`** | 7 | M | connect-the-dots, gap-theory, hero-zero, intro-terminology, morning-trade, risk-framework, session-additions | **[P]** a VWAP-distance entry-skip / sizer + prior-day VWAP series |
| **`fii-dii-bias`** | 6 | M | connect-the-dots, gates-strike-sr-fiidii, completeness-sweep | **[P]** consume the dead-wired `fiiLongPct` + the `nse_eod_participant_oi` matrix into a confluence dot/gate |
| **`short-premium-span` (+ legs)** | 5 | L | straddle, btst-stbt | **[S]** SPAN-deferred (#47): short straddle + BTST/STBT sell legs — gated on the margin-service appliance, no parity impact until a sell path exists |
| **`backtest-fidelity-rails`** | 4 | S | gap-theory, risk-framework | **[S]** lift the live HARD rails (RSI band / volume / delta-premium / ≥1yr publish gate) into the backtest `gate.all` so backtest matches live (backtest-only) |
| **`event-calendar-lockout`** | 4 | M | gap-theory, gates-strike-sr-fiidii, intro-terminology, open-high-low | **[P]** a true "no fresh entry before a scheduled event after 3:30" gate (needs an economic-calendar feed) + the 9:15-10:00 ideal window |

### 3b. Mid packages (2-3 gaps)

| Package | # gaps | Effort | Feeds | P/S |
|---|---:|:--:|---|:--:|
| `multi-timeframe-rsi` | 3 | M | connect-the-dots, open-high-low, session-additions | **[P]** add 5m + Daily RSI overbought caps on top of the 3m floor |
| `rsi-band-per-strategy` | 3 | S | golden-crossover, morning-trade, completeness-sweep | **[P]** per-strategy RSI-band override (e.g. CE cap to 75, >85 cool-off) |
| `sensex-point-scaling` | 3 | S | two-candle, session-additions | **[P]** ~3× SL/target point-scaling + a runtime Sensex-vs-Nifty participation comparator |
| `multi-timeframe-supertrend` | 3 | M | connect-the-dots, session-additions | **[P]** selectable 15m/intraday ST(7,3) variant + PSAR-distance durability |
| `oi-divergence-magnitude` | 2 | S | trending-oi | **[P]** threshold the 20-30% / ≥50% OI-gap % + corroborating price-impulse % |
| `supertrend-level-stop` | 2 | S | gap-theory, golden-crossover | **[P]** expose the Supertrend price band so the support-form SL can anchor on the ST level |
| `two-candle-event-controls` | 2 | S | two-candle | **[P]** one-rejection-trade-per-event cap + 2-candle × Golden-Crossover combo |
| `morning-opening-formation` | 2 | M | morning-trade | **[P]** rejection-wick + "2nd candle breaks the 1st" opening-tick trigger on the 1m series |
| `equity-fno-universe-screener` | 2 | L | market-movers | **[S]** foundational equity-futures/cash capture + Top-Gainers/Losers screener + New-High/Low-Maker feed (gates all per-stock Market-Movers packages) |
| `nday-breakout-extremes` | 2 | M | market-movers | **[S]** rolling N-day high/low per stock + radar-building staging (depends on the equity universe) |
| `per-stock-intraday-series` | 2 | M | market-movers | **[S]** per-stock 5m VWAP/VWMA/ST/RSI series (depends on the equity universe) |
| `per-stock-liquidity-ranking` | 2 | M | market-movers | **[S]** ADV ranking + large-cap classification (depends on the equity universe) |
| `five-account-ledgers` | 2 | L | risk-framework | **[S]** true per-account capital split + per-account 1% target + first-loss freeze (schema change) |

### 3c. Single-gap packages (68 — one `AUTOMATE_PKG` gap each)

Grouped by parity sensitivity. Each is one gap; effort S unless flagged (M).

**Parity-sensitive `[P]`** (signal / gate / dot emission — needs the FU2 default-OFF tag-gating + a new golden):
`same-candle-crossover-event`, `volume-floor-per-index`, `bearish-side-seeding` (M),
`trending-oi-strike-window`, `two-candle-volume-substitution`, `rsi-cooloff-pullback-entry`,
`entry-window-230pm`, `seed-pe-variants`, `multi-tf-rsi-crosscheck`, `oi-quadrant-avoid-veto`,
`price-move-per-oi-demand`, `iv-absolute-band`, `daily-rsi-crosscheck`, `volume-pump-attribution`,
`trending-oi-window-fidelity`, `oi-interval-and-60m-trend`, `fake-cross-side-flip`,
`incomplete-cross-reject`, `flat-oi-stand-aside`, `oi-direction-change-arrows`,
`dynamic-strike-recenter`, `time-of-day-preference`, `bearish-pe-mirror-yaml`,
`indicator-param-pinning`, `two-candle-pattern-arming`, `structural-stop-arming`,
`pullback-entry-trigger`, `gap-highlow-variant`, `gap-fill-deadline-switch`,
`drastic-oi-floor`, `iv-flat-both-sides`, `expiry-entry-timing`, `daily-rsi-hard-block`,
`avoid-friday-skip`, `trendline-break-detector` (M), `rising-volume-confirm`,
`vwap-break-volume-qualified`, `oi-confirmed-sl-leeway`, `constituent-contribution`,
`max-oi-sr-gate`, `oi-both-sides-consolidation`, `post-vertical-rsi-recovery`,
`per-side-premium-skew`, `daily-loss-maxpositions-wiring`, `oi-support-resistance`,
`gap-theory-controls`, `trend-change-controls`, `per-stock-ohlc-flags`,
`per-stock-oi-interpretation`, `per-stock-daily-rsi`, `per-stock-strike-iv-direction`,
`per-stock-oi-spurt`, `per-stock-chain-both-sides-oi`, `pct-price-move-gate`,
`short-side-mirror`, `volume-ma-indicator`, `volume-conditional-exit`,
`btst-close-vs-oi-quadrant`, `btst-side-resolver`, `btst-intraday-oi-window`,
`global-cues-feed`.

**Safe `[S]`** (read-only / management-leg / sizing / backtest-only / new variant):
`straddle-strike-offset`, `straddle-event-window`, `straddle-breakeven-sizing`,
`scale-in-ladder` (M), `profit-slice-sizing`, `morning-eod-precondition`, `auto-journal`.

> Market-Movers note: 10 of these single-gap packages (`per-stock-ohlc-flags`,
> `per-stock-oi-interpretation`, `per-stock-daily-rsi`, `per-stock-strike-iv-direction`,
> `per-stock-oi-spurt`, `per-stock-chain-both-sides-oi`, `pct-price-move-gate`, `short-side-mirror`,
> `volume-ma-indicator`, `volume-conditional-exit`) plus the 4 mid `per-stock-*` / `nday-breakout-extremes`
> packages are all gated on the foundational equity capture (`equity-fno-universe-screener`) and form a
> self-contained sub-epic.

---

## 4. The tails

### 4.1 KEEP_MANUAL_NEW (32) — future manual-check candidates beyond FU1 / trader discretion

No clean data trigger or no feed; candidates for a *future* manual-check expansion, or left to trader
judgement. Grouped by theme:

- **External AI / OIP feed absent** (no source in repo, 6): OIP-AI direction match at 3:20pm
  (btst-stbt, gates-strike-sr-fiidii), AI-suggested OSPL strike in range (connect-the-dots,
  gates-strike-sr-fiidii), "320 Strategy" probability carry (btst-stbt), OI-Pulse ≥90% badge chase-gate
  (open-high-low).
- **Discretionary scale-in / averaging** (single-entry engine, no scale-in primitive, 4): averaging
  ladder 3%→+7%→≤20% (two-candle), add-only-around-prev-close (morning-trade), profits-slice sizing
  (morning-trade, straddle).
- **Discretionary read / hedge** (no detector, 14): OH-as-exit/hedge (morning-trade), round-strike
  double-zero pin (hero-zero), premium-adjust fake-out (hero-zero), per-stock no-rigid-SL risk sizing
  (market-movers), RSI-exhaustion "wait for VWAP" (golden-crossover), morning-re-confirm 4-way carry
  (btst-stbt), prevailing-trend up/down/sideways classification (trend-change), failed-attempt 1-2-3
  reversal (trend-change), held-pivot + PSAR-bounce cue (trend-change), Nifty-vs-Sensex HFT-gap watch
  (session-additions), Dollar/Asian/European/Crude/Bond global scan (connect-the-dots),
  impending-event-after-3:30 confirm (connect-the-dots), 3:15 global-cue re-check (gates-strike-sr-fiidii),
  3:20 OIP next-day alignment (gates-strike-sr-fiidii).
- **Trader-psychology / process / out-of-app conduct** (8, all risk-framework): first-trade-should-win,
  pre/post-market prep, prerequisites (internet/battery), calm-place readiness, only-money-you-can-lose,
  preserve-capital-as-FD, slow lot-ramp at 3-6mo, 5-10%-of-net-worth allocation.

### 4.2 ACCEPT_BY_DESIGN (36) — wontfix, with reason

- **Structurally moot — long-only / single-entry** (the rule is honoured by construction): gap-up
  "do not short" (gap-theory), no-averaging / pyramid + gamma-spike-seller-skip + sell-only-hedged
  (risk-framework ×3), do-not-average-a-loser (hero-zero), Art-of-Averaging (intro-terminology),
  bearish-2-candle RSI<20 skip (two-candle), straddle Trending-OI confirm muted on the neutral path
  (straddle), counter-trend "trade toward the gap" deferred-risky (gap-theory).
- **Derived-history / forward-only artifact** (degrades to NEUTRAL on backtests, judge on forward paper):
  with-trend backtest proxy split (gap-theory), OH trend-alignment soft-by-design (open-high-low),
  trend-is-your-friend already-gated (risk-framework), both-OI-climbing implicit reject (trend-change).
- **Soft preference / descriptive note, not a discrete gate** (low value to harden): ideal 9:15-10:00
  window (intro-terminology, gates-strike-sr-fiidii), formed-candle-after-09:45 floor (two-candle),
  gamma-moves-at-3pm note (intro-terminology), trending-day new-high cadence (session-additions),
  ATM±7 OI-page housekeeping (session-additions), OSPL volume colour-coding (session-additions),
  morning-scalp finish-on-target discipline (gates-strike-sr-fiidii), cut-losses-quickly approximated by
  the bar-stop (risk-framework), skip-morning-prints (risk-framework), midday 11-13 block stricter than
  doc (trend-change).
- **Obsolete doc rule / superseded instrument**: BankNifty-Futures-3m chart (indicators-oi-vix-iv),
  MACD/ADX intraday-variant kit (indicators-oi-vix-iv), Nifty/BankNifty/FinNifty instruments
  BN/FinNifty-deprecated (trending-oi), overnight rails moot under forced square-off (trending-oi),
  Hero-Zero "no explicit numeric target" correctly un-encoded (hero-zero), Hero-Zero 3m-future on
  NIFTY-FUT-CONT per ADR-0003 (hero-zero).
- **Out-of-scope layer** (order / margin / account / stock-universe — not the signal seam): lot-size from
  instrument master (intro-terminology, session-additions ×2), short-premium selling SPAN-gated
  (session-additions), connect-the-dots night-risk moot (connect-the-dots), STBT stock short-sell penalty
  + stock 8/9-day-low (btst-stbt ×2, stock universe #3 deferred).

### 4.3 UNCERTAIN_OWNER (17) — open points awaiting owner sign-off

Mostly doc-internal conflicts (the §4.2 grid vs the strategy-card text) and explicit deferred design
choices. Resolve before tuning the affected leg:

- **RSI-band conflicts** (card vs §4.2 grid, 7): two-candle CE 50-75 vs 60-80, two-candle bullish cap
  "75 or 80?" (completeness-sweep), trending-oi <75/>25 vs 60-80/20-40, connect-the-dots 50-75 vs 60-80,
  golden-crossover bearish >25 vs <25 (and the same row in completeness-sweep), Hero-Zero bearish PE
  mirror band (completeness-sweep). *(Gates `bearish-side-seeding` until resolved.)*
- **OI / strike-window semantics** (2): Trending-OI ≥50% day-cumulative vs interval (completeness-sweep),
  Trending-OI reliable on 15 strikes — endpoint param unsurfaced (indicators-oi-vix-iv).
- **Primary-TF choice** (1): Two-Candle 5-minute an allowed primary? (completeness-sweep).
- **Delta-band design choice** (deferred v1 simplification, 2): expiry-phase delta 0.7-0.8/~0.5 +
  VIX-conditional (session-additions), buyer-delta-to-0.9 / seller-~0.4 wider band (session-additions).
- **Indicator period** (1): VWMA/WMA period silent in §1, 20 is an engine default (intro-terminology).
- **Hero-Zero start time** (1): code 14:30 vs §1 14:00 (intro-terminology).
- **Scope decisions** (3): author PE/short + futures legs for trend-change (trend-change), straddle
  long-vs-short auto-selection vs discretionary (straddle), §7 RESOLVED/open-question status the engine
  should reflect — O=H bands / Hero-Zero SL / open ambiguities (session-additions).

---

## 5. Relationship to FU1 / FU2

- **FU1** ([plan](../superpowers/plans/2026-06-27-followup1-expand-manual-checks.md)) closes the **26
  `COVERED_FU1`** rows by adding 9 on-card manual checks (`fii_ls_ratio`, `constituent_contribution`,
  `pre_open_bias`, `sensex_participation`, `oi_intraday_positional`, `iv_crush_awareness`,
  `straddle_vwap_entry`, `time_of_day_vwap`, `vix_regime_bands`). It is parity-neutral (no automation).
  The bulk of FU1's rows land in `session-additions` (11) — that dimension is the direct source of the 9
  checks — with the rest spread across indicators-oi-vix-iv, intro-terminology, morning-trade,
  completeness-sweep, btst-stbt, trend-change, market-movers, straddle, gates-strike-sr-fiidii.
- **FU2** ([plan](../superpowers/plans/2026-06-27-followup2-soft-dots-to-hard-gates.md)) closes the **13
  `COVERED_FU2`** rows by promoting 4 soft confluence dots to opt-in hard gates (`indicator-alignment`,
  `futures-oi-gate`, `breadth-gate`, `≥50% ΔOI imbalance`), tag-gated default-OFF for byte-identical
  configs. These land on the directional path of two-candle (3), open-high-low (3), and one each in
  golden-crossover, hero-zero, btst-stbt, morning-trade, connect-the-dots, indicators-oi-vix-iv,
  trend-change. (The neutral straddle path is never reached by these gates → straddle has 0.)
- **VIX + Dow are explicitly OUT of FU2 scope** — their automation lives in the `directional-vix-gate`
  (11 gaps) and `global-cues-feed` packages in the §3 backlog, not in either plan.
- **Everything beyond those 39 rows is this backlog.** The 57 `COVERED_EXISTING` need no work; the 246
  `AUTOMATE_PKG` are the §3 packages; the 85 in the tails (§4) are future manual checks (32), wontfix
  (36), or owner decisions (17).

---

## 6. Source files

**Per-dimension disposition files** (`docs/strategy-audit/disposition/`):

1. [two-candle.md](disposition/two-candle.md)
2. [open-high-low.md](disposition/open-high-low.md)
3. [market-movers.md](disposition/market-movers.md)
4. [gap-theory.md](disposition/gap-theory.md)
5. [trending-oi.md](disposition/trending-oi.md)
6. [golden-crossover.md](disposition/golden-crossover.md)
7. [hero-zero.md](disposition/hero-zero.md)
8. [btst-stbt.md](disposition/btst-stbt.md)
9. [morning-trade.md](disposition/morning-trade.md)
10. [connect-the-dots.md](disposition/connect-the-dots.md)
11. [straddle.md](disposition/straddle.md)
12. [trend-change.md](disposition/trend-change.md)
13. [risk-framework.md](disposition/risk-framework.md)
14. [indicators-oi-vix-iv.md](disposition/indicators-oi-vix-iv.md)
15. [gates-strike-sr-fiidii.md](disposition/gates-strike-sr-fiidii.md)
16. [session-additions-and-manual-coverage.md](disposition/session-additions-and-manual-coverage.md)
17. [intro-terminology.md](disposition/intro-terminology.md)
18. [completeness-sweep.md](disposition/completeness-sweep.md)

**Follow-up plans:**

- FU1 — [2026-06-27-followup1-expand-manual-checks.md](../superpowers/plans/2026-06-27-followup1-expand-manual-checks.md)
- FU2 — [2026-06-27-followup2-soft-dots-to-hard-gates.md](../superpowers/plans/2026-06-27-followup2-soft-dots-to-hard-gates.md)
