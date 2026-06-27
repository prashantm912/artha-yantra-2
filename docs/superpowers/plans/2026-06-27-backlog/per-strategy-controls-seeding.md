# Per-strategy controls + bearish/PE seeding + BTST routing + Sensex scaling

**Stream slug:** `per-strategy-controls-seeding`
**Source map:** `docs/strategy-audit/GAP-DISPOSITION.md` §3 (work-package backlog) + the per-dimension
disposition files under `docs/strategy-audit/disposition/` (btst-stbt, two-candle, morning-trade,
gap-theory, trend-change, straddle).
**Convention authority:** FU2 plan `docs/superpowers/plans/2026-06-27-followup2-soft-dots-to-hard-gates.md`
(the parity-safe default-OFF tag-gating pattern) + `CLAUDE.md` "parity-safe-additive" rules.

This stream is the **per-strategy behaviour-control + side-seeding** epic: it (a) routes the BTST/STBT
overnight carry through the confluence gate that the intraday path already uses, (b) seeds the bearish
**PE** mirror legs that the code already supports but no YAML registers, (c) adds the small per-strategy
control knobs (two-candle event cap, morning opening-formation + EOD precondition, gap-theory controls,
trend-change controls, straddle offset/event-window/breakeven), and (d) wires Sensex ~3× point-scaling.
It is **adjacent to but distinct from** the cross-cutting packages (`trade-management-targets-trailing`,
`iv-per-strike`, `directional-vix-gate`, `intraday-positional-oi`, `sr-levels-targets-stops`) which other
streams own — those are explicitly **out of scope here** and are noted as dependencies where relevant.

---

## 1. Goal & the packages/gaps this stream closes

| Package | # gaps | Doc § | Dimension(s) | P/S |
|---|---:|---|---|:--:|
| `btst-route-through-gate` | 7 | §3.8 (btst-stbt) | btst-stbt | **[P]** |
| `sensex-point-scaling` | 3 | §3.1 S23/§4.16 (two-candle) + session-additions | two-candle, session-additions | **[P]** |
| `two-candle-event-controls` | 2 | §3.1 S21(c)/S23, S21(d) | two-candle | **[P]** |
| `morning-opening-formation` | 2 | §3.9 entry 2-3 | morning-trade | **[P]** |
| `morning-eod-precondition` | 1 | §3.9 setup 4 | morning-trade | **[S]** |
| `gap-theory-controls` | 1 | §3.4 (single-gap pkg) | gap-theory | **[P]** |
| `gap-highlow-variant` | 1 | §3.4 L595 | gap-theory | **[P]** |
| `trend-change-controls` | 1 | §3.12 (single-gap pkg) | trend-change | **[P]** |
| `straddle-strike-offset` | 1 | §3.11 setup #4 | straddle | **[S]** |
| `straddle-event-window` | 1 | §3.11 (event-long ~12:30) | straddle | **[S]** |
| `straddle-breakeven-sizing` | 1 | §3.11 setup/risk | straddle | **[S]** |
| `btst-close-vs-oi-quadrant` | 1 | §3.8 entry; §6.8 | btst-stbt | **[P]** |
| `btst-side-resolver` | 1 | §3.8 entry; §6.8 | btst-stbt | **[P]** |
| `btst-intraday-oi-window` | 1 | §3.8 setup 4 | btst-stbt | **[P]** |
| `bearish-side-seeding` | 1 | §3.x bull/bear (M) | two-candle + family | **[P]** |
| `bearish-pe-mirror-yaml` | 1 | §3.x bear | golden-crossover/family | **[P]** |
| `seed-pe-variants` | 1 | §3.1 bull/bear | two-candle | **[P]** |

**Total: 27 dispositioned gaps** across 17 packages (the 7-gap `btst-route-through-gate` dominates).

The three BTST single-gap packages (`btst-close-vs-oi-quadrant`, `btst-side-resolver`,
`btst-intraday-oi-window`) sit **downstream of** `btst-route-through-gate` — they are unreachable until the
routing fix lands. The three PE-seeding packages (`bearish-side-seeding`, `bearish-pe-mirror-yaml`,
`seed-pe-variants`) are facets of one job: register the bearish PE leg the code already supports.

---

## 2. Current state (verified by opening the cited code)

### 2.1 The BTST bypass (the load-bearing finding)

`SignalEngine.preCloseEvaluate(...)` — the A9 pre-close BTST clock — evaluates the chart `EntryEvaluator`
then calls `emitEntry(...)` **with `decision = null`**, bypassing the entire scalper confluence seam:

`services/strategy-signal-service/.../signals/SignalEngine.java:563-570`
```java
Optional<EntryEvaluator.Evaluation> evaluation =
    EntryEvaluator.evaluate(strategy.definition(), bank, index);
if (evaluation.isPresent() && evaluation.get().entry()) {
  EngineCandle lastOneMinute = dayBars.get(dayBars.size() - 1);
  emitEntry(
      strategy, instrument.exchange(), instrument.tradingsymbol(), "1d", lastOneMinute,
      evaluation.get(), null);                 // <-- decision == null: NO confluence, NO StrikePicker
}
```

Contrast the **intraday** path, which routes through `scalperEntry` → `scalperGate.evaluate(...)` →
`emitEntry(..., decision.get())` (`SignalEngine.java:430-431, 445-467`). So for `style: btst`, every
already-built gate (`ScalperGates.rsiBand`/`volume`/`breadth`, `ConnectTheDotsScorer` futures-OI /
sentiment / trending dots, and the `StrikePicker` delta/premium band) is **unreachable** — `decision`
is null, so `emitEntry` skips `stampScalperDetail` (`SignalEngine.java:618-622`) and the option is never
picked. The disposition records this as the single highest-leverage automation in btst-stbt
(`disposition/btst-stbt.md:8-13`).

### 2.2 The `both`→BUY/LONG collapse (the second, independent BTST bug)

`scalp-btst-stbt-nifty.yaml:88` declares `direction: both`. But:

- **Emit side** — `SignalEngine.java:591-592`:
  ```java
  String side = strategy.definition().direction() == StrategyDefinition.Direction.SHORT ? "SELL" : "BUY";
  ```
  `BOTH` is neither `SHORT` → resolves to **BUY** always.
- **Golden runner** — `TickwiseGoldenRunner.java:268-269`:
  ```java
  String direction = definition.direction() == StrategyDefinition.Direction.SHORT ? "SHORT" : "LONG";
  ```
  `BOTH` → **LONG** always.
- **Premium replay** — `OptionsPremiumReplay.pairLegs` keys side off `"SHORT".equals(open.direction())`
  (`OptionsPremiumReplay.java:132, 143`) → a `LONG` signal always buys the CE.

`StrategyDefinition.Direction` **does** have a `BOTH` value (`StrategyDefinition.java:27-30`), and
`StrategyCompiler.java:43-45` parses it — so `both` compiles, but nothing downstream resolves it to a
**per-bar** side. STBT/PE therefore never executes. This is `btst-side-resolver`
(`disposition/btst-stbt.md:21`).

### 2.3 The confluence gate seam (where new per-strategy gates plug in)

`ScalperConfluenceGate.evaluate(...)` (`ScalperConfluenceGate.java:100-280`) is the single seam. It:
- resolves a **per-bar** side from VWAP (`:148-152` — CE if `close ≥ vwap`, else PE);
- applies hard rails (time window `:112-118`, volume + RSI `:153-163`);
- runs the per-strategy hard pre-gates, **each armed by a `cfg.requireXxx()` flag** — `requireTwoCandle`
  `:167`, `requireGapFill` `:173`, `requireTrendChange` `:204`, `requireOpenHighLow` `:218`,
  `requireHeroZero` `:236`, `requireStraddle` `:132`;
- scores Connect-the-Dots and picks the option (`StrikePicker` `:271-276`).

`ScalperConfig.from(JsonNode, List<String> tags)` (`ScalperConfig.java:101-157`) maps each YAML tag to a
flag (`two-candle-pattern` `:119`, `gap-theory` `:121`, `trend-change` `:123`, `open-high-low` `:125`,
`opening-tick` `:128`, `hero-zero` `:131`, `straddle` `:135`, `oi-cross-filter` `:153`). **This is exactly
the FU2 extension hook** — a new behaviour = (a) a record field, (b) a `tags.contains("<tag>")` parse, (c)
an early-return hard-gate in the seam, default-OFF when the tag is absent.

### 2.4 The straddle path (where the straddle packages plug in)

`ScalperConfluenceGate.java:132-147` branches on `cfg.requireStraddle()` BEFORE the directional split,
runs the volume floor (`:133`), and calls `StraddleLegPicker.pick(...)` which selects the **exact ATM**
strike (nearest-forward with both legs) — `StraddleLegPicker.java:52-93`. There is **no offset knob** (no
OTM-safer-bet variant), **no event-window override** (the `11:00-13:00` midday block applies via
`ScalperGates.timeWindow` `:33-44`), and **no breakeven/expected-move sizing** (sizing is a flat
`premium_budget` in YAML). These are the three `[S]` straddle packages.

### 2.5 Gap-theory + trend-change controls (where the two control packages plug in)

- `GapState.detect` (`GapState.java:43-67`) detects a gap from the **prior close → open** only; the doc's
  stricter **prior-high/low → open** "high-probability variant" (§3.4 L595) is documented as a non-v1
  superset (`GapState.java:11-14`). `gap-highlow-variant` adds it as an opt-in superset.
- `GapTheoryGate` (`GapTheoryGate.java:47-66`) has no fill-deadline / no event-window controls; the
  `gap-theory-controls` single-gap residual (the per-strategy knobs not in the cross-cutting
  `event-calendar-lockout` / `gap-fill-deadline-switch` themes) is the per-strategy arming + the SL-anchor
  selection knob.
- `TrendChangeGate` (`TrendChangeGate.java:61-91`) has the four-leg reversal logic with **fixed**
  thresholds (`MIN_SHIFT_PCT = 50` `:47`, `DOWN_REVERSAL_CAP = 14:30` `:49`). `trend-change-controls` is
  the per-strategy residual: surface these as tunable knobs + the per-strategy arming control.

### 2.6 PE seeding state

`ScalperConfig` / `ScalperConfluenceGate` already resolve a **per-bar** side (CE *or* PE) from VWAP
(`:148-152`) and `ScalperGates.rsiBand` already has the PE band (20-40, `ScalperGates.java:81`). But every
seeded YAML is **CE-only**: `scalp-two-candle-nifty.yaml:22` `option_types: [CE]`, `direction: long`
(`:38`); the seeder list (`ScalperStrategySeeder.java:36-73`) registers no `*-pe` variant. The engine
**supports** the bearish leg; no YAML registers it (`disposition/two-candle.md:39`). This is the
`seed-pe-variants` / `bearish-pe-mirror-yaml` / `bearish-side-seeding` triad.

### 2.7 Sensex point-scaling state

The three-way instrument decoupling is done (signal/strike-reference/underlying — `ScalperConfig.java`
+ the sensex YAMLs e.g. `scalp-two-candle-sensex-niftyoi.yaml:15-19`). What is **NOT** done is the ~3×
SL/target **point-scaling**: the SL anchors (`structuralStop`) and any point-based target are computed on
the **signal** future (NIFTY) and applied to the **SENSEX** option leg unscaled. The disposition flags the
point-scaling and a runtime Sensex-vs-Nifty participation comparator as unbuilt
(`disposition/two-candle.md:37`, `sensex-point-scaling` row in GAP-DISPOSITION §3b).

---

## 3. Design — per package

> **Pattern reused everywhere below:** a new per-strategy behaviour = `ScalperConfig` record field +
> `tags.contains("<tag>")` parse + a `cfg.requireXxx()`/`cfg.xxx()` read in `ScalperConfluenceGate`,
> default-OFF (tag absent ⇒ field false/empty ⇒ seam path byte-identical). This is the FU2 firewall
> (`followup2…md:34-47`): no golden/parity YAML carries the tag, so the 5 pure-engine goldens cannot move.

### 3.1 `btst-route-through-gate` [P] — the load-bearing fix (7 gaps)

**File:** `SignalEngine.java` (`preCloseEvaluate` `:532-571`).

**Change.** Route the BTST pre-close entry through `scalperGate.evaluate(...)` exactly like the intraday
`scalperEntry` path, instead of calling `emitEntry(..., null)`. Add a `preCloseScalperEntry` helper that
mirrors `scalperEntry` (`SignalEngine.java:445-468`) but feeds the **pre-close 1m day-bar series + index**
(the BTST evaluation already builds `dayBars` and a synthetic `preCloseBar`):

```java
// preCloseEvaluate, replacing the emitEntry(..., null) at :567-569
if (evaluation.isPresent() && evaluation.get().entry()) {
  EngineCandle lastOneMinute = dayBars.get(dayBars.size() - 1);
  if (strategy.scalper() != null) {
    preCloseScalperEntry(strategy, instrument, "1d", lastOneMinute, evaluation.get(),
        bank, daily, index, today);          // routes through the confluence seam
  } else {
    emitEntry(strategy, instrument.exchange(), instrument.tradingsymbol(), "1d",
        lastOneMinute, evaluation.get(), null);   // non-scalper btst unchanged
  }
}
```

`preCloseScalperEntry` calls `scalperGate.evaluate(cfg, bank, future, index, barInstant, istTime,
eodDate)` with the BTST clock's IST time (`preCloseAt`, e.g. 15:20) and the **1m future series** for the
structural-stop/pattern reads. **One seam adjustment is required:** the default `ScalperGates.timeWindow`
(`:40-42`) blocks any fresh entry after 15:30 — but 15:20 passes, so BTST clears it. (If a strategy sets
`preCloseAt` after 15:30 the gate correctly blocks; record as an Open Point.) Once routed, `decision !=
null`, so:
- StrikePicker runs (ATM±3, delta 0.6-0.7, premium band) — closes gaps "Strikes within ATM±3",
  "Delta 0.6-0.7", "Premium band" (`disposition/btst-stbt.md:17-19`);
- `ScalperGates.rsiBand` / `volume` / `breadth` become reachable — closes "RSI not overbought",
  "Volume high last 30m", "Advance/decline match" (`:30, 32, 33`);
- the `futures_oi` + `trending_cross` + `sentiment` dots score — closes "3:15pm Futures-OI" +
  "3:15pm Option-OI" (`:23, 24`).

**Data flow:** A9 pre-close clock → `preCloseEvaluate` builds the day's 1m bars + the daily pre-close bar →
chart `EntryEvaluator` passes → **NEW** `scalperGate.evaluate` (live OI/chain snapshot at 15:20) →
`emitEntry(..., decision)` stamps `scalper_detail` with the picked option. (Live-only — the confluence
reads current snapshots; the picked leg is persisted so a replay reads it back, per §12.9.)

**PARITY:** **[P]**. This changes BTST signal emission (a confluence-gated BTST may now BLOCK where the
chart-only path fired, and stamps an option leg). The existing `btst-preclose` golden in
`GoldenDeterminismTest.FEATURES` (`GoldenDeterminismTest.java:33-36`) is a **pure-engine** strategy with
**no scalper tag** → it does **not** route through the seam (the `strategy.scalper() != null` branch is
false for it), so it stays byte-identical. Arm via a **new tag** `btst-confluence` so the routing is
opt-in per BTST YAML; an unarmed btst YAML keeps the legacy chart-only path. New golden variant:
`btst-confluence-preclose` (a scalper-tagged BTST fixture) added to a **new** scalper-aware golden harness
(see §5) — NOT to the pure `GoldenDeterminismTest` (which has no OI/chain client).

### 3.2 `btst-side-resolver` [P] — resolve `both`→side across emit + golden + replay (1 gap)

**Files:** `SignalEngine.java:591-592`, `TickwiseGoldenRunner.java:268-269`, `OptionsPremiumReplay.java`
(`pairLegs` `:119-146`).

**Change.** For a routed BTST scalper, the side is already resolved by the confluence seam (the VWAP-vs-
close + close-location read). The emit must honour that instead of collapsing `BOTH`→BUY:

```java
// SignalEngine.emitEntry — replace the SHORT?SELL:BUY line for a scalper decision
String side;
if (decision != null && decision.side() != null) {
  side = decision.side() == OptionType.CE ? "BUY" : "BUY";   // both legs BUY premium; CE vs PE is the LEG
} else {
  side = strategy.definition().direction() == StrategyDefinition.Direction.SHORT ? "SELL" : "BUY";
}
```

The **real** STBT distinction is the **leg** (PE vs CE), already carried in `decision.pick()` /
`scalper_detail`. The §3.8 split is "close toward HIGH ⇒ CE/BTST, toward LOW ⇒ PE/STBT" — implement as a
**close-vs-day-high/low** input to the side resolution inside `ScalperConfluenceGate` for the BTST path
(a new `cfg.closeLocationSide()` branch that overrides the plain VWAP side when the bar is the day's
pre-close bar). For the **golden/replay** path, `entryEvent` must emit the resolved direction
(`LONG` for CE-leg / `SHORT` for PE-leg) so `OptionsPremiumReplay.pairLegs` buys the correct option:

```java
// TickwiseGoldenRunner.entryEvent — derive from the resolved leg, not definition.direction()
String direction = resolvedSide == OptionType.PE ? "SHORT" : "LONG";
```

This requires the golden runner to know the per-bar resolved side for a BTST scalper — the new
scalper-aware golden harness (§5) supplies it; the **pure** `TickwiseGoldenRunner` path (non-scalper) is
unchanged (`BOTH` still maps to LONG there, but no pure golden uses `direction: both`).

**Data flow:** pre-close bar close vs day-high/low → resolved CE/PE leg → emit side / golden direction →
`OptionsPremiumReplay` buys the matching ATM option.

**PARITY:** **[P]**. New side-resolution path. Gated behind the same `btst-confluence` tag (a non-routed
BTST is unaffected). The `btst-preclose` pure golden stays byte-identical (no scalper, no `both`-side
resolution). New golden variant `btst-confluence-preclose` covers a PE/STBT resolution case.

### 3.3 `btst-close-vs-oi-quadrant` [P] — the literal SC/LB/SB/LU mapping (1 gap)

**File:** new helper read inside `ScalperConfluenceGate` (BTST path), reading `ctx.oi()`
(`ScalperGateContext.Oi` `:39-52`).

**Change.** The generic `oiQuadrant` (`ScalperGates.oiQuadrant` `:121-125`) is **futures bull/bear**, NOT
the literal close-vs-OI-extreme BTST mapping (BTST: SC=Q3/LB=Q1, STBT: SB=Q2/LU=Q4 — close vs OI day-high/
low). Add a `BtstOiQuadrant.classify(closeLocation, oiLocation)` pure helper that maps (close vs day
high/low) × (OI vs OI day high/low) into the four BTST quadrants, consumed as a **soft confirming dot**
on the routed BTST path (not a hard block — it is muted on derived history per
`disposition/btst-stbt.md:22`). The OI day-high/low input is a new field on the BTST context read
(market-data already exposes per-strike OI series; a min/max over the session is a read-time reduction).

**PARITY:** **[P]** (adds a dot to the BTST confluence). Gated by `btst-confluence`. New golden variant
only (no existing BTST scalper golden). Muted on derived history → forward-paper discriminator.

### 3.4 `btst-intraday-oi-window` [P] — the 2:30-3:00pm SC/SB observation window (1 gap)

**File:** `MarketOiClient` (a new windowed read) + `ScalperConfluenceGate` BTST path.

**Change.** Today the BTST gate evaluates ONCE at 15:20. The §3.8 setup-4 rule observes **short
covering (BTST) / short build-up (STBT) between 2:30-3:00pm around S/R**. Add a windowed OI-state read
(`MarketOiClient.intradayOiWindow(underlying, from=14:30, to=15:00)`) returning the dominant quadrant over
that window, consumed as a confirming dot on the BTST path. The SC/SB classification already exists in
`ScalperGates`/the quadrant enum (`OiQuadrant.bullish()/bearish()`); the **window** is new.

**PARITY:** **[P]**. Gated by `btst-confluence`. New golden variant; muted on derived history.

### 3.5 `sensex-point-scaling` [P] — ~3× SL/target scaling + participation comparator (3 gaps)

**Files:** `ScalperConfig.java` (a scale factor), `ScalperConfluenceGate.structuralStop` /
`SignalEngine.levelFromRules` (apply the scale), a new `MarketOiClient` participation read.

**Change.** SENSEX moves ~3× the NIFTY points for the same % move, so when the **signal** future is NIFTY
but the **option execution** is SENSEX, a point-based SL/target derived on NIFTY must be scaled. Add:
```java
// ScalperConfig — derive from the option-root index vs the signal index
BigDecimal pointScale;   // 1.0 when signalIndex == oi/option index; ~3.0 for NIFTY-signal/SENSEX-option
```
populated from a `POINT_SCALE` map (`Map.of("SENSEX", 3.0)` keyed by the option root, default 1.0). The
SL/target the engine computes off the NIFTY signal future is multiplied by `pointScale` when the option
leg is on the higher-priced index. Plus a **runtime Sensex-vs-Nifty participation comparator**
(`MarketOiClient.indexParticipation`) as a read-only confirming dot (does the leading index confirm the
SENSEX trade?).

**PARITY:** **[P]** (SL/target value change alters emitted protective levels → changes trade outcomes in
replay). Gated by a new `sensex-point-scaling` tag; the existing SENSEX YAMLs do NOT carry it (byte-
identical until armed). New golden variant per the scaled SENSEX path. **Owner sign-off** on the 3.0
factor (see Open Points — it should be derived from live point-ratio, not hard-coded).

### 3.6 `two-candle-event-controls` [P] — per-event cap + GC combo (2 gaps)

**File:** `ScalperConfluenceGate` (two-candle path) + a small per-strategy event-state.

**Change.** §3.1 S21(c)/S23: only **ONE** ST/VWAP-rejection trade per 2-candle event. Add a per-(strategy,
session, event-anchor) counter so a second rejection on the same formation is suppressed. §3.1 S21(d):
2-candle + Golden-Crossover on the same side = a high-conviction combo — add a soft combo dot when the GC
gate also confirms (the GC gate exists). Both armed by a new `two-candle-event-controls` tag.

**PARITY:** **[P]** (suppresses some emissions / adds a dot). Gated, default-OFF. New golden variant on a
two-candle scalper fixture.

### 3.7 `morning-opening-formation` [P] — rejection-wick + "2nd breaks 1st" (2 gaps)

**File:** new `OpeningFormation.java` helper + `ScalperConfluenceGate` opening-tick path.

**Change.** §3.9 entry 2-3: a **rejection-wick** at the prior-day close + a **"2nd candle breaks the
1st"** opening-tick trigger read on the 1m series. The opening-tick path already exists
(`cfg.openingTick()` `ScalperConfig.java:128`, the 09:15-09:30 window `ScalperConfig.java:72-73`); today it
evaluates a single closed bar. Add a pure `OpeningFormation.detect(oneMinute, index, side)` that checks
(a) a rejection wick at the prior-day close level and (b) the 2nd 1m candle breaking the 1st's range in
the side's direction. Consumed as a hard arming condition on the opening-tick path when armed.

**PARITY:** **[P]**. Gated by a new `morning-opening-formation` tag (or folded into `opening-tick` arming —
see Open Points). New golden variant. **Dependency:** needs the 1m series at the opening tick (already
subscribed for scalpers — `SignalEngine.java:212`).

### 3.8 `morning-eod-precondition` [S] — prior-day convincing-close gate (1 gap)

**File:** new pure helper + `ScalperConfluenceGate` opening-tick path.

**Change.** §3.9 setup 4: the prior day must have **closed at/near its HIGH or LOW** (inside/near-open
close ⇒ no trade). Prior-day OHLC is available (the daily series the BTST path already refreshes,
`SignalEngine.java:547-550`). Add `EodPrecondition.convincingClose(prevDayOhlc, side, thresholdPct)` (close
within X% of the day's range extreme). Consumed on the opening-tick path.

**PARITY:** **[S]** — but note: it **does** gate emission, so strictly it is parity-sensitive in the same
sense. It is classed `[S]` in GAP-DISPOSITION because the morning opening-tick path has **no existing
golden** (no morning-trade scalper golden is frozen). Still arm via tag + add a fresh golden variant so the
behaviour is pinned; no existing golden moves.

### 3.9 `gap-theory-controls` [P] + `gap-highlow-variant` [P] (2 gaps)

**Files:** `GapState.java` (`detect`), `GapTheoryGate.java`, `ScalperConfig.java`.

**Change.**
- `gap-highlow-variant`: add an opt-in **stricter** gap detector measuring the gap from the prior candle
  **HIGH→open** (bull) / **LOW→open** (bear) instead of close→open (`GapState.java:11-14` already flags
  this as the documented v1-deferred superset). Add `GapState.detectHighLow(...)` and a
  `cfg.gapHighLow()` switch so the gate uses it when armed.
- `gap-theory-controls`: the per-strategy residual — surface the gap-significance floor
  (`GapState.MIN_POINTS = 3` `:34`) and the SL-anchor selection (pre-gap candle vs the deferred
  Supertrend-level — `supertrend-level-stop` is a separate stream's package) as per-strategy knobs, and
  arm them via tag. The cross-cutting `gap-fill-deadline-switch` and `event-calendar-lockout` themes are
  **out of scope here** (other streams own them).

**PARITY:** **[P]**. New tags `gap-highlow` + `gap-controls`; the seeded gap-theory YAMLs do NOT carry
them (the `gap-theory` arming tag itself is unchanged). New golden variant per variant.

### 3.10 `trend-change-controls` [P] (1 gap)

**Files:** `TrendChangeGate.java` (`MIN_SHIFT_PCT` `:47`, `DOWN_REVERSAL_CAP` `:49`), `ScalperOiProps.java`
(or a new props block).

**Change.** Surface the two fixed thresholds as **tunable knobs** (move to `ScalperOiProps` or a new
`ScalperTrendChangeProps`): the ≥50% Trending-OI shift floor and the ~14:30 down-reversal cap. Pass them
into `TrendChangeGate.evaluate(...)`. This is the per-strategy residual; the **scope** decision (whether
to author a PE/short trend-change variant + futures legs) is a separate Open Point (`disposition/
trend-change.md:41` UNCERTAIN_OWNER). The cross-cutting trend-change packages (`trendline-break-detector`,
`rising-volume-confirm`, `vwap-break-volume-qualified`, `oi-confirmed-sl-leeway`, `max-oi-sr-gate`,
`oi-both-sides-consolidation`, `post-vertical-rsi-recovery`, `per-side-premium-skew`) are **out of scope
here**.

**PARITY:** **[P]** if a non-default threshold changes emission. Default values = today's constants ⇒
arming with defaults is byte-identical; only a tuned override moves a golden, so the knob itself is parity-
neutral and the **tuned** value rides DB rows (no new golden until a value is changed). Document as such.

### 3.11 `straddle-strike-offset` [S] (1 gap)

**File:** `StraddleLegPicker.java:52-93`, `ScalperConfig.java`.

**Change.** Add an `offsetSteps` parameter to `StraddleLegPicker.pick(...)` so the OTM-safer-bet variant
(one leg ITM / one OTM, §3.11 setup #4) is selectable — pick the strike `offsetSteps` away from the ATM
instead of the nearest-forward strike. Default `0` ⇒ the current exact-ATM behaviour. Armed by a YAML knob
(`universe.options.straddle_offset`) parsed in `ScalperConfig`.

**PARITY:** **[S]** — a new straddle **variant** path with no existing frozen straddle golden (the straddle
path is live-only neutral; `offset 0` is byte-identical). A fresh golden variant pins the offset path.

### 3.12 `straddle-event-window` [S] (1 gap)

**File:** `ScalperGates.java:23-24, 37-39` (the midday block) + `ScalperConfluenceGate` straddle path.

**Change.** §3.11 event-long form: after ~12:30 the combined premium closes above both-leg VWAP — but the
engine's `MIDDAY_BLOCK_FROM/TO` (11:00-13:00) blocks it. Add an event-aware window override
(`cfg.straddleEventWindow()`) that, when armed, replaces the midday block with an event-long window for the
straddle path only.

**PARITY:** **[S]** — straddle path, no frozen golden; unarmed ⇒ midday block unchanged. Fresh golden
variant.

### 3.13 `straddle-breakeven-sizing` [S] (1 gap)

**File:** new `StraddleBreakeven.java` helper + `ScalperConfluenceGate` straddle decision.

**Change.** §3.11 setup/risk: the underlying must move **> combined premium** from the strike to break
even ("don't pay ~1000 for a 100-200pt move"). Both leg premiums are known at entry
(`StraddleLegPicker` returns both picks). Compute the combined-premium breakeven distance and an
expected-move check; surface it on the straddle `Decision` side-channel (and optionally **block** the
straddle when the expected move < breakeven, when armed). Sizing stays advisory (no SPAN — long straddle is
defined-risk).

**PARITY:** **[S]** (read-only side-channel by default; an optional arming makes it a block). Fresh golden
variant if the block is armed.

### 3.14 `seed-pe-variants` + `bearish-pe-mirror-yaml` + `bearish-side-seeding` [P] (3 gaps)

**Files:** new `scalp-*-pe.yaml` YAMLs under `services/.../scalper-strategies/`, `ScalperStrategySeeder.java`
(`STRATEGIES` list `:36-73`).

**Change.** The engine already resolves a per-bar PE side (`ScalperConfluenceGate.java:148-152`) and
`ScalperGates.rsiBand` has the PE band. Seed the bearish mirror as **new YAML variants** that change
`direction: long`→`short` (or `both` for the symmetric strategies) and `option_types: [CE]`→`[PE]`. Add a
mirror set for the strategies whose source describes a bearish leg (two-candle, golden-crossover,
trend-change PE down-reversal). The seeder registers them as **drafts** (never auto-emit). `bearish-side-
seeding` is the engine-side confirmation that the PE path resolves correctly end-to-end (it does — verified
at `:148-152` and `ScalperGates.java:81`); `bearish-pe-mirror-yaml` + `seed-pe-variants` are the YAML +
seeder-list additions.

**PARITY:** **[P]** — a **brand-new** strategy (new slug, new option side) with **no existing golden**, so
it is parity-safe by construction (nothing to perturb). Each new PE YAML gets its own golden variant in the
scalper harness. The CE YAMLs are untouched (byte-identical).

---

## 4. PARITY classification summary

| Package | Class | New tag / knob | Golden-variant plan |
|---|:--:|---|---|
| `btst-route-through-gate` | **[P]** | tag `btst-confluence` | `btst-confluence-preclose` in the new scalper golden harness |
| `btst-side-resolver` | **[P]** | (rides `btst-confluence`) | a PE/STBT resolution case in `btst-confluence-preclose` |
| `btst-close-vs-oi-quadrant` | **[P]** | (rides `btst-confluence`) | new variant; muted on derived history |
| `btst-intraday-oi-window` | **[P]** | (rides `btst-confluence`) | new variant; muted on derived history |
| `sensex-point-scaling` | **[P]** | tag `sensex-point-scaling` | scaled-SENSEX golden variant |
| `two-candle-event-controls` | **[P]** | tag `two-candle-event-controls` | two-candle scalper golden variant |
| `morning-opening-formation` | **[P]** | tag `morning-opening-formation` | morning opening-tick golden variant |
| `morning-eod-precondition` | **[S]** | tag `morning-eod-precondition` | fresh variant (no existing morning golden) |
| `gap-highlow-variant` | **[P]** | tag `gap-highlow` | gap-theory golden variant |
| `gap-theory-controls` | **[P]** | tag `gap-controls` (+ knobs, default=today) | variant only if a knob deviates |
| `trend-change-controls` | **[P]** | knobs (default=today's constants) | variant only on a tuned override |
| `straddle-strike-offset` | **[S]** | knob `straddle_offset` (default 0) | straddle golden variant (offset≠0) |
| `straddle-event-window` | **[S]** | tag `straddle-event-window` | straddle golden variant |
| `straddle-breakeven-sizing` | **[S]** | tag `straddle-breakeven` | straddle golden variant (if it blocks) |
| `seed-pe-variants` | **[P]** | new YAMLs | one golden variant per new PE slug |
| `bearish-pe-mirror-yaml` | **[P]** | new YAMLs | per-slug golden variant |
| `bearish-side-seeding` | **[P]** | (engine path, already supported) | covered by the PE-slug variants |

**The parity firewall (all [P] above).** The 5 frozen pure-engine goldens
(`GoldenDeterminismTest.FEATURES = {ema-crossover, optional-indicator-activation, btst-preclose,
exit-intrabar, context-series}`) carry **no scalper tag** and never route through `ScalperConfluenceGate`,
so no tag-gated gate here can perturb them (the FU2 firewall, `followup2…md:148-162`). The BTST routing fix
is the one case that touches the **golden runner + premium replay** code paths directly — but only on the
**scalper** branch (`strategy.scalper() != null`), and `btst-preclose` is a pure non-scalper strategy, so
it stays byte-identical. **Every [P] change adds a NEW golden in a new scalper-aware harness; no existing
golden/parity fixture is regenerated.**

---

## 5. Tests

### 5.1 Unit tests (new + extend)
- `ScalperConfluenceGateTest` (extend): a BTST-tagged config routes through the seam (decision non-null);
  `both` resolves to CE leg when close-toward-high and PE leg when close-toward-low.
- `BtstSideResolverTest` (new): close-location → side mapping; the emit/golden/replay direction agree.
- `BtstOiQuadrantTest` (new): the SC/LB/SB/LU close-vs-OI-extreme mapping (each of the four states).
- `OpeningFormationTest` (new): rejection-wick + "2nd breaks 1st" detection on synthetic 1m bars.
- `EodPreconditionTest` (new): convincing-close-at-high/low vs inside-close.
- `GapStateTest` (extend `GapTheoryGateTest`): `detectHighLow` superset fires where `detect` does not.
- `TrendChangeGateTest` (extend): tunable shift-floor + cap honoured; defaults == today.
- `StraddleLegPickerTest` (extend): `offsetSteps` selects the OTM-safer-bet pair; `0` == current ATM.
- `StraddleBreakevenTest` (new): combined-premium breakeven distance + expected-move comparison.
- `SensexPointScaleTest` (new): the 3× scale applies only on the NIFTY-signal/SENSEX-option leg.
- `ScalperConfigTest` (extend): each new tag/knob parses to the right flag; absent ⇒ default-OFF.

### 5.2 Golden / parity (the firewall checks)
- **Existing must stay green untouched:** `GoldenDeterminismTest` (the 5 pure goldens),
  `BacktestParityTest`, `OptionsPremiumGoldenTest` — run after every change to prove byte-identity.
- **New scalper-aware golden harness** (new): a `ScalperGoldenDeterminismTest` (or extend the premium
  harness) with a stubbed `MarketOiClient`/chain so the confluence seam is deterministic; add frozen
  fixtures `btst-confluence-preclose`, one PE-mirror variant, one straddle-offset variant. Twice-run
  byte-match + frozen-fixture byte-match, regenerate-once via `-Dgolden.generate=true`
  (mirrors `GoldenDeterminismTest.java:61-71`).
- `ScalperStrategyLoadTest` (extend): every new PE/btst-confluence YAML compiles, loads, and has a
  bounding exit (the `ScalperRisk.hasBoundingExit` §0B rule, `SignalEngine.java:199`).

### 5.3 e2e / manual
- e2e (`e2e/`): a published `btst-confluence` strategy stamps a `scalper_detail` option leg on its
  pre-close signal (today it does not). A PE-mirror strategy emits a PE leg on a bearish bar.
- Manual-test doc `docs/manual-tests/` (per the §19 convention): a runbook to publish a btst-confluence
  + a PE variant on the mock stack and confirm the picked option + side.

---

## 6. Dependencies & sequencing

```
btst-route-through-gate (3.1)  ──►  btst-side-resolver (3.2)  ──►  btst-close-vs-oi-quadrant (3.3)
        │  (the routing fix is the prerequisite)                 └►  btst-intraday-oi-window (3.4)
        ▼
seed-pe-variants / bearish-* (3.14)  — independent; needs only the existing PE side resolution
sensex-point-scaling (3.5)           — independent; needs the SENSEX decoupling (DONE, 2b-E2/E2b)
two-candle-event-controls (3.6)      — independent
morning-opening-formation (3.7) ──► morning-eod-precondition (3.8)  (both on the opening-tick path)
gap-highlow-variant (3.9) + gap-theory-controls (3.9)  — independent
trend-change-controls (3.10)         — independent
straddle-strike-offset (3.11) / event-window (3.12) / breakeven-sizing (3.13)  — independent of each other
```

Hard ordering:
1. **`btst-route-through-gate` MUST land first** — the other 3 BTST packages are unreachable until
   `decision != null` on the BTST path.
2. **`btst-side-resolver` must accompany the routing fix** — even routed, `both`→BUY/LONG collapse means
   STBT/PE never executes; the disposition flags this as "distinct from routing"
   (`disposition/btst-stbt.md:21`).
3. **The SENSEX decoupling is a prerequisite for `sensex-point-scaling`** — already DONE (tasks 2b-E2/E2b),
   so this package is unblocked.
4. **SPAN gates the BTST/STBT SELL legs and the short straddle** — those are the separate
   `short-premium-span` package (out of this stream); this stream seeds only the **long-premium** BTST buy
   leg and the **long** straddle, neither of which needs SPAN.
5. **The opening-tick path** (`cfg.openingTick`) must exist before morning-formation/eod-precondition arm
   onto it — it already exists (`ScalperConfig.java:128`).

No cross-service feed is blocked: the BTST OI-window + Sensex-participation reads are new
`MarketOiClient`/market-data reductions over **existing** captured OI series (no new capture, no migration).

---

## 7. Effort + suggested PR breakdown

| PR | Packages | Effort | Notes |
|---|---|:--:|---|
| **PR-1** | `btst-route-through-gate` + `btst-side-resolver` | **M** | The load-bearing fix; tag `btst-confluence`; new scalper golden harness + `btst-confluence-preclose` fixture. Arm onto the 3 btst YAMLs. |
| **PR-2** | `btst-close-vs-oi-quadrant` + `btst-intraday-oi-window` | **M** | Two confirming dots + the windowed `MarketOiClient` read. Rides PR-1's tag. |
| **PR-3** | `seed-pe-variants` + `bearish-pe-mirror-yaml` + `bearish-side-seeding` | **M** | New PE YAMLs + seeder list + per-slug goldens. Parity-safe (new slugs). |
| **PR-4** | `sensex-point-scaling` | **S** | Scale factor + participation comparator. **Owner sign-off on the 3.0** (Open Points). |
| **PR-5** | `two-candle-event-controls` + `morning-opening-formation` + `morning-eod-precondition` | **M** | The two-candle cap/combo + the two morning opening-tick controls. |
| **PR-6** | `gap-highlow-variant` + `gap-theory-controls` + `trend-change-controls` | **S** | Detector superset + tunable knobs (defaults == today ⇒ parity-neutral until tuned). |
| **PR-7** | `straddle-strike-offset` + `straddle-event-window` + `straddle-breakeven-sizing` | **S** | Three `[S]` straddle knobs; new straddle goldens. |

**Overall effort: M-L** (PR-1/PR-2/PR-3/PR-5 are the substance; PR-4/PR-6/PR-7 are small). One commit per
phase, single PR per row, squash-merge (per CLAUDE.md trunk rules). Each PR ends with the full verify trio
(build with `-am`, the golden/parity firewall green, e2e where applicable).

---

## Open Points

1. **BTST routing tag — opt-in vs. always-on.** A new `btst-confluence` tag (opt-in, recommended) keeps
   the change parity-isolated and lets the owner A/B routed-vs-legacy BTST. **Recommended default:** add
   the tag and arm it onto the 3 seeded btst YAMLs in the same PR (so live BTST routes), but keep the
   engine path conditional on the tag so a non-scalper `style: btst` strategy is unaffected.
   *Alternative:* route ALL `style: btst` scalpers unconditionally (simpler, but no A/B and a larger
   blast radius). **Recommend the tag.**

2. **`preCloseAt` after 15:30.** The default `ScalperGates.timeWindow` blocks fresh entry ≥15:30, which
   would block a BTST clock set later than 15:30. **Recommended default:** for the routed BTST path use a
   dedicated pre-close window check (allow up to ~15:25), not the intraday `NO_FRESH_ENTRY_AFTER`.
   Confirm with owner whether any BTST strategy intends a `preCloseAt` after 15:25.

3. **The Sensex 3× point-scale factor.** Hard-coding `3.0` is a v1 placeholder. **Recommended default:**
   derive the factor at entry from the live SENSEX-future / NIFTY-future point ratio (a runtime read),
   falling back to `3.0` when unavailable — so the SL/target scaling tracks the real ratio.
   *Alternative:* a static per-index map (simpler, less faithful). **Owner sign-off required** either way
   because it changes emitted protective levels ([P]).

4. **`btst-side-resolver` — `both` vs separate CE/PE BTST YAMLs.** Resolving `direction: both` per-bar (one
   YAML emits CE or PE depending on the close location) is the faithful §3.8 model. *Alternative:* seed two
   separate YAMLs (`scalp-btst-nifty` CE-only + `scalp-stbt-nifty` PE-only) and drop `both` entirely —
   simpler emit/golden/replay (no `both` resolution), but two strategies to manage and it loses the
   single-decision-clock semantics. **Recommend the per-bar `both` resolver** (matches the source and the
   existing one-clock BTST primitive); fall back to split YAMLs only if the golden/replay `both` plumbing
   proves too invasive.

5. **Morning opening-formation arming.** Fold the rejection-wick + "2nd breaks 1st" into the **existing**
   `opening-tick` arming (no new tag) vs. a separate `morning-opening-formation` tag. **Recommended
   default:** a separate tag — the opening-tick path is already shipped/armed on the morning YAMLs, and
   folding new hard conditions into it would change those armed strategies' emission (parity break on a
   live strategy). A separate default-OFF tag keeps them byte-identical.

6. **`trend-change-controls` PE/short scope.** Whether to author a PE/short trend-change YAML + futures
   legs is an explicit UNCERTAIN_OWNER decision (`disposition/trend-change.md:41`). **Recommended
   default:** seed the PE down-reversal mirror YAML in PR-3 (it is engine-supported), but DEFER the
   futures legs (order-side, no engine path). Owner confirms before tuning.

7. **Straddle long-vs-short auto-selection** (`disposition/straddle.md:28` UNCERTAIN_OWNER). The IV/range
   half is automatable (`iv-per-strike`, a different stream); the "event" half is judgmental. **Recommended
   default:** keep straddle long-only here (this stream); leave long/short auto-selection to the
   `iv-per-strike` stream + the SPAN-gated short path. Out of scope for this stream.

8. **RSI-band conflicts gating PE seeding** (`GAP-DISPOSITION.md:246-249`, UNCERTAIN_OWNER). The bearish PE
   band (card-vs-§4.2 conflict: PE >25 vs <25, CE 50-75 vs 60-80) is flagged as gating `bearish-side-
   seeding` until resolved. **Recommended default:** seed the PE variants using the **already-shipped**
   `ScalperGates.rsiBand` PE band (20-40, `ScalperGates.java:81`) — i.e. do NOT change the band as part of
   seeding; the band conflict is a separate owner decision that retunes an existing constant, not a blocker
   for registering the PE slug. Flag it but do not let it block PR-3.
