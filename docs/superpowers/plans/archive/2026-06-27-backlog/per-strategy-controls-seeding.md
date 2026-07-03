# Per-strategy controls + bearish/PE seeding + BTST routing + Sensex scaling

> **ARCHIVED (2026-07-03 doc sweep):** historical planning doc — the work here is delivered, superseded, or consciously parked. Anything still open lives in `../2026-07-02-remaining-items.md` (ledger) or `../2026-07-03-10x-value-roadmap.md`. Do not mine this file for TODOs.


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
>
> **[Audit-4 — `ScalperConfig` constructor-arity fan-out, applies to EVERY new record field].** `ScalperConfig`
> is a `record` (`ScalperConfig.java:36-52`, currently **16** components) with exactly ONE canonical
> `new ScalperConfig(...)` in the factory (`:154-156`) PLUS **8** `new ScalperConfig(...)` literals in
> `ScalperConfluenceGateTest` (`:44,49,56,62,68,74,81,87`). EVERY new field this stream adds —
> `btst-confluence` flag (§3.1), `pointScale` (§3.5), `gapHighLow` (§3.9), `straddle_offset` (§3.11), any
> two-candle-event/morning/straddle/breakeven flag — forces updating the record header + the canonical
> constructor + **all 8** test literals (append the new default positionally). The plan's §3.x sub-sections
> say "add a `ScalperConfig` field" without flagging this fan-out; it is a required, mechanical, per-field
> step. (Same shape applies to `ScalperOiProps.java:18-29` for §3.10's threshold knobs: its compact
> constructor + the `defaults()` 11-null literal at `:77` must grow too.)

### 3.1 `btst-route-through-gate` [P] — the load-bearing fix (7 gaps)

**File:** `SignalEngine.java` (`preCloseEvaluate` `:532-571`).

**Change.** Route the BTST pre-close entry through `scalperGate.evaluate(...)` exactly like the intraday
`scalperEntry` path, instead of calling `emitEntry(..., null)`. Add a `preCloseScalperEntry` helper that
mirrors `scalperEntry` (`SignalEngine.java:445-468`) but feeds the **pre-close 1m day-bar series + index**
(the BTST evaluation already builds `dayBars` and a synthetic `preCloseBar`):

```java
// preCloseEvaluate, replacing the emitEntry(..., null) at :567-569
// NOTE: `strategy.scalper() != null` is TRUE for the 3 seeded btst YAMLs ALREADY (they are scalpers),
// so the routing MUST also gate on a NEW cfg.requireBtstConfluence() flag (the `btst-confluence` tag) —
// else PR-1 silently re-routes the 3 live btst strategies. And see Audit-1: do NOT pass the `daily`
// series to the seam; build a 3m intraday series from `dayBars` so the volume floor / VWAP / pattern
// reads are on the bar size they were calibrated for.
if (evaluation.isPresent() && evaluation.get().entry()) {
  EngineCandle lastOneMinute = dayBars.get(dayBars.size() - 1);
  if (strategy.scalper() != null && strategy.scalper().requireBtstConfluence()) {
    preCloseScalperEntry(strategy, instrument, evaluation.get(), dayBars, today); // routes through the seam
  } else {
    emitEntry(strategy, instrument.exchange(), instrument.tradingsymbol(), "1d",
        lastOneMinute, evaluation.get(), null);   // non-scalper btst + unarmed-scalper btst unchanged
  }
}
```

`preCloseScalperEntry` calls `scalperGate.evaluate(cfg, bank, future, index, barInstant, istTime,
eodDate)` with the BTST clock's IST time (`preCloseAt`, e.g. 15:20). **One seam adjustment is required:**
the default `ScalperGates.timeWindow` (`:40-42`) blocks any fresh entry after 15:30 — but 15:20 passes, so
BTST clears it. (If a strategy sets `preCloseAt` after 15:30 the gate correctly blocks; record as an Open
Point.) Once routed, `decision != null`, so:

> **[Audit-1 — bar-timeframe mismatch, must resolve before coding].** The intraday `scalperEntry` path
> builds the `IndicatorBank` + `future` series on the strategy's **3m primary** and evaluates the gate at the
> 3m bar index; `ScalperGates.volume` (NIFTY 125k / index 50k floor), the §0B VWAP-decisive side, the
> `rsiBand`, and `structuralStop`/`TwoCandleGate`/`MarketStructure` reads are all calibrated for the 3m
> intraday bar. But `preCloseEvaluate` builds `bank` from the **1d** daily view and evaluates at
> `index = daily.size()-1` (`SignalEngine.java:561-562`), with the synthetic pre-close DAILY bar appended.
> Passing `daily` as the gate's `future` + the 1d-bank `index` would feed the volume floor a daily volume
> (always ≫125k → the floor is meaningless), read VWAP/RSI off the daily series, and run the structural-stop
> pattern detectors over daily bars (wrong geometry). **The plan must specify which series the routed BTST
> gate reads:** the faithful choice is to build a 3m (or 1m) `bank`+`future` for the day's session and
> evaluate the gate at the LAST intraday bar (the 15:20 bar), NOT the daily view — i.e. `preCloseScalperEntry`
> assembles its own 3m series from `dayBars`, not the `daily` series the chart `EntryEvaluator` ran on. This
> is the single biggest soundness gap in PR-1; it is NOT a one-line reroute. (The original sketch below
> passing `daily` is therefore wrong — it is kept only to show the call shape.)
>
> **[Audit-10 — pass-2 refinement: the 3m series likely already exists, no manual rebuild needed].** The
> intraday path does NOT hand-assemble its 3m series — it calls `bank.primarySeries()` (the 3m primary
> the live engine already subscribes + refreshes) and `index = primary.size()-1`
> (`evaluateAtBarClose` `:395-396`). `preCloseScalperEntry` can do the SAME: `IndicatorBank.build(...)`
> already runs in `preCloseEvaluate` `:561`, and `bank.primarySeries()` returns the 3m primary (not the
> daily view — the daily series is fetched SEPARATELY at `:550` and is not what the bank's primary points
> at). So the faithful fix is to evaluate the gate on `bank.primarySeries()` + its last index at 15:20,
> reusing the existing 3m series — NOT to manually fold `dayBars` (1m) into 3m buckets. Either works, but
> "reuse `bank.primarySeries()`" is the smaller, lower-risk change and matches the intraday call shape
> exactly. (Caveat the dev must confirm at code time: that the 3m primary's LAST bar at the 15:20 clock is
> the 15:18–15:21 session bar, i.e. the seriesStore was refreshed for the pre-close minute — the intraday
> `evaluateAtBarClose` refreshes on the bucket boundary `:479`; `preCloseScalperEntry` must `refreshFromRest`
> the 3m primary key before reading it, mirroring `:479`.)

Once routed, `decision != null`, so:
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

**PARITY:** **[P]**. This changes live BTST signal emission (a confluence-gated BTST may now BLOCK where the
chart-only path fired, and stamps an option leg). **The change is to `SignalEngine.preCloseEvaluate` — the
LIVE engine — which `GoldenDeterminismTest` NEVER exercises** (the golden runs `TickwiseGoldenRunner`, a
self-contained harness with its OWN btst pre-close path at `TickwiseGoldenRunner.java:193-210` and NO
`ScalperConfluenceGate`/`MarketOiClient`). So the existing `btst-preclose` golden
(`GoldenDeterminismTest.java:33-36`) stays byte-identical **regardless of the tag** — not because
"`strategy.scalper()` is false for it" (a confusion between the two code paths), but because the golden
harness has no seam to route through at all. (The `btst-preclose` fixture is in fact `mode: explicit`,
`direction: long`, untagged — verified `golden/strategies/btst-preclose.yaml` — so it is doubly safe.) Arm
via a **new tag** `btst-confluence` so the LIVE routing is opt-in per BTST YAML; an unarmed btst YAML keeps
the legacy chart-only emit. **Note the 3 seeded btst YAMLs ALREADY load as scalpers** (they carry
`tags:[scalper,...]` + `mode: options_of_underlying`, so `strategy.scalper() != null` is true for them
today) — therefore the `strategy.scalper() != null` branch alone is NOT a sufficient guard; the routing
MUST additionally check the new `btst-confluence` flag (a `ScalperConfig` boolean), else arming the routing
silently changes the 3 live btst strategies the moment PR-1 ships. New golden variant
`btst-confluence-preclose` is added to a **new** scalper-aware golden harness (see §5), which must
stub/inject the `MarketOiClient`+chain — NOT to the pure `GoldenDeterminismTest`/`TickwiseGoldenRunner`
(which have no OI/chain client and cannot reach the seam).

### 3.2 `btst-side-resolver` [P] — resolve `both`→side across emit + golden + replay (1 gap)

**Files:** `SignalEngine.java:591-592`, `TickwiseGoldenRunner.java:268-269` (in `libs/strategy-engine`),
`OptionsPremiumReplay.java` `pairLegs` `:119-146` (in **`services/backtest-service`**, NOT strategy-engine —
the disposition's `OptionsPremiumReplay.java:41-42` cite is STALE; the live side-keying is `pairLegs`'s
`"SHORT".equals(open.direction())` at `:132` + `:143`).

**Change.** For a routed BTST scalper, the side is already resolved by the confluence seam (the VWAP-vs-
close + close-location read). The emit must honour that instead of collapsing `BOTH`→BUY:

```java
// SignalEngine.emitEntry — for a scalper decision the persisted `side` stays "BUY" (long premium —
// BOTH the CE and PE legs are BUYS); the CE-vs-PE distinction is the LEG, carried in scalper_detail.
// So for a routed scalper this line is effectively a no-op (already "BUY") — the actual fix is NOT
// the emit `side` string but emitting the resolved LEG (below). The `BOTH`→BUY collapse is harmless
// for the emit side precisely because long-premium is always BUY; it is harmful only in the
// golden/replay DIRECTION string, which selects CE vs PE.
String side =
    decision != null
        ? "BUY"   // long-premium scalper: always BUY; leg side rides scalper_detail
        : strategy.definition().direction() == StrategyDefinition.Direction.SHORT ? "SELL" : "BUY";
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

**[Audit-2 — the `entryEvent` sketch is unsound as written].** `TickwiseGoldenRunner` has **no** concept of
a resolved side: no `OptionType` import, no `ScalperConfig`, no `ScalperConfluenceGate`, no `resolvedSide`
variable. Editing its `entryEvent` (`:266-273`) to read `resolvedSide` would NOT compile, and would also
**contradict** the very next claim that "the pure `TickwiseGoldenRunner` path is unchanged." The two cannot
both be true. Resolve one of two ways, and state which in PR-1:
> (a) **Preferred — separate harness.** The new scalper-aware golden harness (§5) is its OWN class (it has
> the stubbed `MarketOiClient`/chain the seam needs); it computes the resolved CE/PE side from the seam's
> `Decision` and emits `SHORT`/`LONG` itself. `TickwiseGoldenRunner.entryEvent` is left **byte-identical**
> (it still maps `BOTH`→`LONG`, but no PURE golden uses `direction: both`, so nothing moves). The
> `GoldenSignalsJson.SignalEvent` record is shared/frozen, so the new harness writes the SAME shape.
> (b) **Overload, not rewrite.** Add an `entryEvent(..., OptionType resolvedSide)` overload used only by the
> new harness; the existing 2-arg `entryEvent` is untouched. The single-line rewrite shown above is wrong —
> it mutates the frozen path.

Either way the **pure** `TickwiseGoldenRunner` byte-output is unchanged (`BOTH` still maps to LONG there;
no pure golden uses `direction: both`), and `OptionsPremiumReplay.pairLegs` continues to key off the emitted
`direction` string — so the NEW harness must emit `SHORT` for a resolved PE leg for the replay to buy the PE.

**Data flow:** pre-close bar close vs day-high/low → resolved CE/PE leg → emit side / golden direction →
`OptionsPremiumReplay` buys the matching ATM option.

**PARITY:** **[P]**. New side-resolution path. Gated behind the same `btst-confluence` tag (a non-routed
BTST is unaffected). The `btst-preclose` pure golden stays byte-identical (no scalper, no `both`-side
resolution). New golden variant `btst-confluence-preclose` covers a PE/STBT resolution case.

> **[Audit-3 — CRITICAL: "add a confirming dot" is NOT free; it perturbs EVERY scalper unless conditional].**
> `ConnectTheDotsScorer.score(...)` is the **single shared scorer** for ALL scalper strategies (the only prod
> call site is `ScalperConfluenceGate.java:251`). It computes `aggregate = num/den` where `den` sums EVERY
> dot's weight (`ConnectTheDotsScorer.java:100-109`) and `valid = aggregate >= threshold` (`:115`). **Adding a
> dot via an unconditional `add(dots, ...)` increases `den` for every strategy** — connect-the-dots,
> trending-oi, golden-crossover, two-candle, etc. — shifting their aggregate ratio and **moving live emissions
> + every existing scalper golden** (this is exactly what the sibling `vwap-and-sizing.md:186/:814` and
> `strike-premium-band-backtest` audits call out: the scorer's `den` is load-bearing). Therefore EVERY "soft
> confirming dot" in §3.3 / §3.4 / §3.5 (participation) / §3.6 (GC combo) MUST be added **conditionally** —
> appended to `dots` ONLY when its arming flag (`cfg.requireXxx()` / the `btst-confluence` flag) is set, OR the
> scorer must take a flag and skip it — so an untagged scalper sees an IDENTICAL `dots`/`den` and stays
> byte-identical. The plan's blanket "gated by `btst-confluence`" is only true if the dot is literally not
> appended for untagged strategies. **This is a parity prerequisite for §3.3/§3.4/§3.5/§3.6, not optional.**
> It also means `ConnectTheDotsScorer.score(...)`'s signature likely grows a flag (touch the one prod call
> site + the **25** `ConnectTheDotsScorer.score(...)` call literals in `ConnectTheDotsScorerTest`), OR the dot is
> appended in `ScalperConfluenceGate` after the score (changing how `valid` is recomputed) — pick one and
> spell it out per package.

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
  **out of scope here** (other streams own them). **[Audit-7] Traceability caveat:** `gap-theory-controls`
  exists in the master backlog (`GAP-DISPOSITION.md:168`, single-gap [P]) but has NO literal source row in
  `disposition/gap-theory.md` (its 10 AUTOMATE_PKG rows map to gap-highlow / supertrend-level-stop /
  gap-fill-deadline-switch / sr-levels / event-calendar-lockout / backtest-fidelity-rails / vwap-distance /
  trade-management — none is `gap-theory-controls`). It is a SYNTHESIZED residual; the scope above is the
  auditor's reasonable interpretation, but the owner should confirm WHAT the one residual gap actually is
  before coding (the `MIN_POINTS` knob is real; the SL-anchor half overlaps `supertrend-level-stop`). Note
  `GapState.detect(future, index)` takes no `side` arg, so `detectHighLow` either needs a `side` param or
  must compute both directions (see §3.9 signature note).

**PARITY:** **[P]**. New tags `gap-highlow` + `gap-controls`; the seeded gap-theory YAMLs do NOT carry
them (the `gap-theory` arming tag itself is unchanged). New golden variant per variant.

### 3.10 `trend-change-controls` [P] (1 gap)

**Files:** `TrendChangeGate.java` (`MIN_SHIFT_PCT` `:47`, `DOWN_REVERSAL_CAP` `:49`), `ScalperOiProps.java`
(or a new props block).

**Change.** Surface the two fixed thresholds as **tunable knobs** (move to `ScalperOiProps` or a new
`ScalperTrendChangeProps`): the ≥50% Trending-OI shift floor and the ~14:30 down-reversal cap. Pass them
into `TrendChangeGate.evaluate(...)` — which currently takes `(future, index, side, underlying, oi, istTime)`
and reads the thresholds as private static finals (`:47, :49`); adding params changes the gate signature +
the one call site (`ScalperConfluenceGate.java:206`). **[Audit-8] Traceability caveat:** like
`gap-theory-controls`, `trend-change-controls` is in the master backlog (`GAP-DISPOSITION.md:168`) but is NOT
one of the named themes in `disposition/trend-change.md`'s roll-up (lines 45-55: trendline-break-detector /
rising-volume-confirm / vwap-break-volume-qualified / oi-confirmed-sl-leeway / trade-management /
directional-vix / constituent-contribution / max-oi-sr / oi-both-sides / post-vertical-rsi / per-side-premium
— none is `trend-change-controls`). It is a synthesized residual; confirm the exact knob set with the owner.
This is the per-strategy residual; the **scope** decision (whether
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

**[Audit-6 — traceability + required co-edits].** In the source disposition there is exactly ONE PE-seeding
row (`disposition/two-candle.md:39`, `seed-pe-variants`); `bearish-side-seeding` (`GAP-DISPOSITION.md:154`,
[P]·M) and `bearish-pe-mirror-yaml` (`:160`) are the **master backlog's** decomposition of that one job into
an engine-confirmation facet + the YAML/seeder facet — they are NOT three independent source rows. The plan's
§1 framing ("facets of one job") is correct; treat them as one PR-3 deliverable. Required co-edits for each
new PE slug: (1) add to `ScalperStrategySeeder.STRATEGIES` (`:36-73`); (2) add to BOTH lockstep maps in
`ScalperStrategyLoadTest` (Audit-5); (3) the PE YAML inherits a bounding exit from the mirror's
`exit_rules` (the §0B `hasBoundingExit` load guard, `SignalEngine.java:199`) — verify the mirror keeps a
`stop_loss`/`time_stop`. Also note the bearish two-candle path: the source's bearish #3 rule (skip when
RSI < ~20) is `ACCEPT_BY_DESIGN`, already covered by the PE `>20` band floor (`ScalperGates.java:81`) — do
NOT add a new gate for it. The RSI-band conflict (Open Point 8) does not block registering the slug.

**[Audit-11 — pass-2: each new PE/btst slug must satisfy the load-test's HARD per-slug assertions, not
just appear in the two maps].** Beyond the `UNDERLYING`/`EXPECTED_TAG` map entries (Audit-5),
`ScalperStrategyLoadTest` asserts for EVERY slug it enumerates: `mode == options_of_underlying` (`:114`),
`primaryTimeframe == "3m"` (`:128`), `signalIndex == "NIFTY 50"` (`:137`), `deltaLo == 0.6` + `threshold
== 0.6` (`:132-133`), the OI-index derivation by `-sensex-niftyoi`/`-sensex-sensexoi` suffix (`:138-142`),
and all four seam aliases `{vwma20, psar, rsi14, supertrend}` declared (`:146`). So a PE-mirror YAML is NOT
a free `direction`/`option_types` flip of the CE source — it MUST keep `signal_underlying:
NFO/NIFTY-FUT-CONT` (so `signalIndex` resolves to `"NIFTY 50"`), the 3m primary, the four indicator
aliases, and the 0.6 delta/threshold defaults, or the load test fails. The mirror naming must also follow
the `-sensex-niftyoi`/`-sensex-sensexoi` suffix grammar if SENSEX PE variants are seeded (the OI-index
assert keys off the suffix). Confirm the PE slugs are NIFTY-only or follow the suffix convention before
PR-3.

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
- **New scalper-aware golden harness** (new): a `ScalperGoldenDeterminismTest` with a stubbed
  `MarketOiClient`/chain so the confluence seam is deterministic; add frozen fixtures
  `btst-confluence-preclose`, one PE-mirror variant, one straddle-offset variant. Twice-run
  byte-match + frozen-fixture byte-match, regenerate-once via `-Dgolden.generate=true`
  (mirrors `GoldenDeterminismTest.java:61-71`).
  **[Audit-9 — module placement: this harness CANNOT extend `OptionsPremiumGoldenTest`].** The original
  "(or extend the premium harness)" wording is removed: `OptionsPremiumGoldenTest` lives in
  **`services/backtest-service`**, which does NOT depend on `strategy-signal-service`
  (verified `backtest-service/pom.xml` — no `strategy-signal` dependency) and therefore cannot reference
  `ScalperConfluenceGate`/`MarketOiClient` (both in `strategy-signal-service`). The pure golden harness
  (`GoldenDeterminismTest`/`TickwiseGoldenRunner`) lives in **`libs/strategy-engine`**, which likewise
  cannot depend on the service. So the new harness MUST be a fresh test class in **`strategy-signal-service`**
  (which does depend on `strategy-engine` `:57`, so it can reuse the frozen `GoldenSignalsJson` shape) — it
  is genuinely new test infrastructure, NOT an extension of either existing harness. **There is currently
  NO deterministic golden for ANY scalper strategy** (the 5 frozen FEATURES are all non-scalper; verified —
  every existing scalper routes through the live, non-deterministic OI seam), so the stubbed-OI harness is
  built from zero. This raises PR-1's effort from "M" to the upper end of M — it is the gating
  infrastructure for EVERY [P] golden in this stream (btst-confluence, PE-mirror, straddle-offset all need
  it; none can ride the pure `TickwiseGoldenRunner`, which has no seam).
- `ScalperStrategyLoadTest` (extend): every new PE/btst-confluence YAML compiles, loads, and has a
  bounding exit (the `ScalperRisk.hasBoundingExit` §0B rule, `SignalEngine.java:199`). **[Audit-5] This test
  is a HARD lockstep fixture** (`ScalperStrategyLoadTest.java:31-91`): it enumerates ALL seeded slugs in a
  `UNDERLYING` map + a per-gate `EXPECTED_TAG` map and asserts each is `options_of_underlying`, primary `3m`
  (`:128`), `signalIndex == "NIFTY 50"` (`:137`), declares the 4 seam aliases (`:146`), and that
  tag→`requireXxx` mirrors. So each new PE/btst YAML added to `ScalperStrategySeeder.STRATEGIES` (`:36-73`)
  MUST also be added to **both** maps here (the new PE slugs map to their NIFTY-50/SENSEX underlying; a btst
  YAML armed with `btst-confluence` needs an `EXPECTED_TAG` entry or the assert relaxed). The class javadoc
  ("MUST stay in lockstep with ScalperStrategySeeder") makes this non-optional — a slug added to the seeder
  but not these maps leaves the new strategy un-asserted; a map entry without a seeder entry fails.

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

9. **[Audit] BTST-gate bar timeframe (the largest PR-1 unknown).** `preCloseEvaluate` runs on the **1d**
   view; the confluence gate's volume floor / VWAP / RSI band / structural-stop detectors are calibrated for
   the **3m** intraday bar (Audit-1). `preCloseScalperEntry` must build a 3m (or 1m) intraday series from
   `dayBars` and evaluate the gate at the 15:20 bar, NOT pass the `daily` series. **Owner/dev to confirm**
   which interval the BTST confluence reads (the 3m intraday view is the auditor's recommendation) before
   PR-1 — it changes which bars the volume/VWAP/pattern gates see and is not a one-line reroute.

10. **[Audit] The new `MarketOiClient` reads (§3.4 window, §3.5 participation) need market-data support.**
    `intradayOiWindow(...)` (2:30-3:00pm dominant quadrant) and `indexParticipation(...)` are NOT existing
    `MarketOiClient` methods — each is a NEW client method that needs a corresponding market-data reduction/
    endpoint. The plan claims "no new capture, no migration" (true — they reduce over existing captured OI
    series) but a NEW read PATH (client method + market-data query/endpoint + the DTO + the contract snapshot
    if it adds a query param/mapping) IS new code. Confirm the market-data side is in scope for PR-2/PR-4 or
    split it out. Also: the BTST/OI/participation dots **degrade to NEUTRAL on derived history** (the muted-OI
    artifact, CLAUDE.md) — so PR-2/PR-4's value is FORWARD-paper only; the new scalper goldens will pin
    determinism with STUBBED OI, not validate the dot's edge.

11. **[Audit] Scorer-denominator parity (Audit-3) is a hard prerequisite, not a footnote.** Every "soft dot"
    in §3.3/§3.4/§3.5/§3.6 must be appended to `ConnectTheDotsScorer` ONLY when its arming flag is set (or the
    scorer skips it behind a flag), or it shifts `den` for every scalper and breaks every existing scalper
    golden + moves live emissions. Decide per package: (a) conditional `add(...)` inside the scorer (scorer
    grows a flag arg → update the 25 `ConnectTheDotsScorer.score(...)` test literals), or (b) append the dot
    in `ScalperConfluenceGate` after `score(...)` and recompute `valid`. Spell out the chosen mechanism in
    PR-2/PR-4 before coding.

12. **[Audit] `gap-theory-controls` / `trend-change-controls` are synthesized residuals** with no literal
    source row in their disposition files (Audit-7/Audit-8); the master backlog lists them but the per-gap
    scope is the auditor's interpretation. Owner to confirm the exact knob set (the `MIN_POINTS` floor and the
    two TrendChangeGate thresholds are concrete; the SL-anchor halves overlap the separate-stream
    `supertrend-level-stop`) before PR-6.

---

## Audit pass 1 findings

**Verdict: sound-with-open-points.** The plan's package inventory is faithful to source: all **17 packages
+ 27 gap counts + P/S classifications match `GAP-DISPOSITION.md §3` exactly**, and the per-dimension
disposition cites (btst-stbt, two-candle, morning-trade, gap-theory, trend-change, straddle) check out
row-for-row. The structural diagnosis (BTST `decision=null` bypass at `SignalEngine.java:567-569`; the
`both`→BUY/LONG collapse) is correct. But several **parity and soundness gaps** must be closed before a dev
picks this up; all are corrected inline above (search `[Audit-N]`) and captured as Open Points 9–12.

**Citations (all opened + verified).**
- ✓ `SignalEngine.java` — `preCloseEvaluate` `:532-571`, `emitEntry(...,null)` `:567-569`, `decision==null`
  skips `stampScalperDetail` `:618-622`, intraday `scalperEntry` `:430-431/:445-468`, emit-side `:591-592`,
  daily refresh `:547-550`, 1m subscribe `:212`, `hasBoundingExit` guard `:199`, `levelFromRules` `:583/590/809`.
- ✓ `ScalperConfluenceGate.java:100-280` — every gate-arming line (`:132,148-152,153-163,167,173,204,218,236`),
  StrikePicker `:271-276`, straddle `:132-147`, scorer call `:251`. All exact.
- ✓ `ScalperConfig.java` — `from(...)` `:101-157`, all 8 tag→flag lines (`:119,121,123,125,128,131,135,153`),
  opening window `:72-73`, canonical ctor `:154-156`, **16-component record `:36-52`**. All exact.
- ✓ `ScalperGates.java` — PE band `:81`, midday block `:23-24/:37-39`, `NO_FRESH_ENTRY_AFTER` `:40-42`,
  `timeWindow` `:33-44`. `GapState.detect` `:43-67` + `MIN_POINTS` `:34` + high/low note `:11-14`.
  `GapTheoryGate.evaluate` `:47-66`. `TrendChangeGate` `MIN_SHIFT_PCT :47` / `DOWN_REVERSAL_CAP :49` /
  `evaluate :61-91`. `StraddleLegPicker.pick :52-93`. `ScalperGateContext.Oi :39-52`. All exact.
- ✓ `TickwiseGoldenRunner.java:268-269` (BOTH→LONG), `OptionsPremiumReplay.java` `pairLegs :119-146` (side
  `:132/:143`), `StrategyDefinition.Direction` BOTH `:27-31`, `StrategyCompiler :43-45`. `GoldenDeterminismTest`
  FEATURES `:33-36` + regen `:61-71`. `btst-preclose.yaml` (explicit/long/untagged). YAML cites
  (`scalp-btst-stbt-nifty.yaml:88` both / `:72` [CE,PE]; `scalp-two-candle-nifty.yaml:22` [CE] / `:38` long).
  Seeder `STRATEGIES :36-73`. All exact.

**One stale cite found and noted (not in the plan — in its SOURCE):** `disposition/btst-stbt.md:21` cites
`OptionsPremiumReplay.java:41-42` for the side-keying; that is wrong (line 41-42 is unrelated). The plan's
own cite (`pairLegs :119-146`, side at `:132/:143`) is the correct one — i.e. the plan is MORE accurate than
its source. Annotated in §3.2.

**Soundness / parity issues corrected inline:**
- **[Audit-1] BTST bar-timeframe mismatch (§3.1, the biggest gap).** `preCloseEvaluate` builds the
  `IndicatorBank` on the **1d** view; the confluence gate's volume floor (125k/50k), VWAP, RSI band, and
  `TwoCandleGate`/`MarketStructure` structural-stop detectors are calibrated for the **3m** intraday bar.
  Passing `daily` to the seam makes the volume floor meaningless and the pattern reads wrong-geometry. The
  plan's "feeds the 1m series" text contradicted its own `daily`-passing sketch. Corrected: build a 3m
  intraday series from `dayBars`; this is NOT a one-line reroute. → Open Point 9.
- **[Audit / §3.1] The 3 seeded btst YAMLs are ALREADY scalpers** (`tags:[scalper,...]` + `options_of_underlying`),
  so `strategy.scalper() != null` alone does NOT isolate the change — the routing MUST also gate on a new
  `requireBtstConfluence()` flag, else PR-1 silently re-routes 3 live strategies. Plan's parity argument
  ("scalper() is false for btst-preclose") also conflated the LIVE `SignalEngine` with the golden
  `TickwiseGoldenRunner` (the golden never reaches the seam at all). Both corrected in §3.1.
- **[Audit-2] §3.2 golden-runner edit is unsound.** `TickwiseGoldenRunner.entryEvent` has no `resolvedSide`/
  `OptionType`/scalper concept; the single-line rewrite would not compile and contradicts "the pure path is
  unchanged." Corrected to a separate harness OR an overload; the frozen path stays byte-identical.
- **[Audit-3] CRITICAL scorer-denominator parity.** `ConnectTheDotsScorer.score(...)` is the SINGLE shared
  scorer; `den` sums every dot (`:100-109`). Adding an unconditional "soft confirming dot" (§3.3/§3.4/§3.5/§3.6)
  shifts `den` for EVERY scalper and breaks every existing scalper golden + moves live emissions. The plan's
  blanket "gated by `btst-confluence`/muted on history" is insufficient — the dot must be conditionally
  appended only when armed. Corrected with an explicit callout. → Open Point 11.
- **[Audit-4] `ScalperConfig` (16-component record) + `ScalperOiProps` constructor-arity fan-out.** Every new
  field (`btst-confluence` flag, `pointScale`, `gapHighLow`, `straddle_offset`, …) forces editing the record
  header + canonical ctor + **all 8** `new ScalperConfig(...)` literals in `ScalperConfluenceGateTest`
  (`:44,49,56,62,68,74,81,87`). The plan never flagged this; added.
- **[Audit-5] `ScalperStrategyLoadTest` is a hard lockstep fixture** (`:31-91`): two maps (`UNDERLYING` +
  `EXPECTED_TAG`) enumerate all 36 slugs and assert `3m`/`signalIndex==NIFTY 50`/seam-aliases. Each new PE/btst
  slug added to the seeder MUST be added to BOTH maps. The plan's §5 only said "extend"; spelled out.
- **[Audit-6] PE-seeding traceability.** `bearish-side-seeding`/`bearish-pe-mirror-yaml` are the master
  backlog's decomposition of the ONE `seed-pe-variants` disposition row (`two-candle.md:39`), not 3 source
  rows — treat as one PR-3 deliverable; required seeder + load-test + bounding-exit co-edits listed.
- **[Audit-7/8] `gap-theory-controls` / `trend-change-controls` are synthesized residuals** with no literal
  disposition-file row; their scope is the auditor's interpretation. Flagged for owner confirmation. → Open
  Point 12. Also noted `GapState.detect` takes no `side` arg (the `detectHighLow` signature needs one) and
  `TrendChangeGate.evaluate` reads thresholds as static finals (making them params changes the signature +
  the `:206` call site).
- **[Audit] New `MarketOiClient` reads (§3.4 window / §3.5 participation)** are NEW client methods needing
  market-data support (query/endpoint/DTO/contract). "No migration" is true but it is still new cross-service
  code; the value is forward-paper only (OI muted on derived history). → Open Point 10.

**Parity firewall verdict.** With Audit-1/2/3/4 applied, `GoldenDeterminismTest` (5 pure goldens) +
`BacktestParityTest` + `OptionsPremiumGoldenTest` stay byte-identical: the live `SignalEngine.preCloseEvaluate`
change is not on the golden path; the `TickwiseGoldenRunner`/scorer edits are confined to a NEW scalper-aware
harness + conditional (armed-only) dots; no shipped YAML carries a new tag. As written BEFORE the corrections,
the "soft dot" packages (§3.3-§3.6) WOULD have broken every scalper golden via the shared `den` — that was the
single most important catch. Dependency sequencing (routing→side-resolver→OI dots; equity-universe and SPAN
correctly scoped OUT; opening-tick path exists before morning controls) is sound.

*Pass 1 — auditor's note: a pass-2 should re-open `ScalperConfluenceGateTest` + `ConnectTheDotsScorerTest`
to confirm the exact literal counts after the new fields land, and verify the new scalper-golden harness
design against `OptionsPremiumGoldenTest`'s existing stubbing pattern.*

---

## Audit pass 2 findings

**Verdict: sound-with-open-points.** Independent re-verification confirms the plan (with pass-1's
corrections applied) is faithful to source and structurally sound. Every load-bearing citation I re-opened
matches to the line; pass-1's corrections (Audit-1 through Audit-8) are all correct and introduced no new
error. I found three additional gaps — one concrete (Audit-9, harness module placement), one refinement
(Audit-10, the 3m series already exists), one a hard-test constraint on PR-3 (Audit-11) — all corrected
inline. None changes the verdict; PR-1 remains the load-bearing, highest-risk deliverable and its golden
infrastructure is bigger than the original "M" implied.

**Citations independently re-verified (sample, all opened this pass — exact unless noted):**
- ✓ `SignalEngine.preCloseEvaluate` — `emitEntry(..., null)` at **`:567-569`** (plan's `:563-570` window is
  right; the `null` is `:569`), `bank`+`index = daily.size()-1` built off the **1d** daily view (`:550,561,562`),
  emit-side `BOTH→BUY` `:591-592`, `stampScalperDetail` skipped when `decision==null` `:618-622`, daily
  refresh `:549-550`, `scalperGate` injected field `:104`. Intraday `scalperEntry` `:445-468` passes
  `future = primary = bank.primarySeries()` + `index = primary.size()-1` (`evaluateAtBarClose :395-396`) —
  **confirming Audit-1's core: the intraday gate reads the 3m primary, `preCloseEvaluate` would feed 1d.**
- ✓ `ScalperConfig` — **16-component record** `:36-52` exact; all 8 tag→flag lines `:119,121,123,125,128,131,135,153`
  exact; canonical ctor `:154-156` exact; opening window `:72-73`.
- ✓ `ScalperConfluenceGate` — straddle `:132-147`, VWAP side `:148-152`, volume/RSI `:153-163`, two-candle `:167`,
  gapFill `:173`, callPutDelta `:196`, trendChange `:204` calling `TrendChangeGate.evaluate(future,index,side,
  signalIndex,oi,istTime)` at `:206`, openHighLow `:218`, heroZero `:236`, scorer call `:251`. All exact.
- ✓ `ConnectTheDotsScorer` — `den` sums EVERY dot weight `:100-109`, `valid = aggregate >= threshold` `:114-115`,
  dots built unconditionally via `add(dots,...)` `:90-98`. **Audit-3's denominator-parity catch is sound and is
  the single most important finding in the stream.** Test-literal counts re-counted: **8** `new ScalperConfig(...)`
  in `ScalperConfluenceGateTest` (`:44,49,56,62,68,74,81,87` — exact) and **25** `.score(` in
  `ConnectTheDotsScorerTest` — both match Audit-3/Audit-4 exactly.
- ✓ `TickwiseGoldenRunner.entryEvent :266-273` (`BOTH→LONG` `:268-269`) — and grep confirms **NO**
  `OptionType`/`ScalperConfig`/`ScalperConfluenceGate`/`MarketOiClient` import in the file, so Audit-2's "the
  `resolvedSide` rewrite would not compile" is correct.
- ✓ `OptionsPremiumReplay.pairLegs :119-146`, side-key `"SHORT".equals(open.direction())` at `:132` and `:143`.
  The disposition's `:41-42` cite is indeed stale; the plan's cite is the accurate one.
- ✓ `GoldenDeterminismTest.FEATURES :33-36` = {ema-crossover, optional-indicator-activation, btst-preclose,
  exit-intrabar, context-series} — **none is a scalper**; regen `:61-71`. `btst-preclose.yaml` is
  `mode: explicit`, `direction: long`, `primary: 1d`, untagged — doubly parity-safe.
- ✓ `scalp-btst-stbt-nifty.yaml` — `direction: both` `:88`, `option_types:[CE,PE]` `:72`, `primary: 3m` `:75`,
  `tags:[scalper,options,btst,nifty]` `:63` (**so `strategy.scalper()!=null` IS true for it today** — confirming
  the plan's correction that the new `btst-confluence` flag, not the scalper-null check, is the required guard).
- ✓ `ScalperStrategyLoadTest` — `UNDERLYING` (36 slugs `:31-68`) + `EXPECTED_TAG` (`:72-91`) lockstep maps;
  per-slug asserts `options_of_underlying :114`, `3m :128`, `signalIndex=="NIFTY 50" :137`, seam aliases `:146`.
  Audit-5 exact. (btst slugs `:66-68` carry NO `EXPECTED_TAG` entry today → the `btst-confluence` arming needs
  one added.)
- ✓ `TrendChangeGate` `MIN_SHIFT_PCT=50 :47`, `DOWN_REVERSAL_CAP=14:30 :49`, `evaluate :61` (reads both as
  static finals — making them params changes the signature + the single `:206` call site, as pass-1 noted).
- ✓ Source disposition: `btst-stbt.md` dimension note `:6-13`, gap rows `:17-34` (incl. the stale
  `OptionsPremiumReplay.java:41-42` cite at `:21`, and the 2:30-3:00pm window at `:26`); `two-candle.md:37`
  (sensex-point-scaling) `:39` (seed-pe-variants). `GAP-DISPOSITION` `btst-route-through-gate`=7 `:123`,
  `sensex-point-scaling`=3 `:137`, PE-triad `:154/156/160`, RSI-band gating `:249`, the synthesized residuals
  `gap-theory-controls`/`trend-change-controls` at `:168` with **0 occurrences** in their disposition files
  (Audit-7/8 confirmed). Package count: 4 multi-gap (7+3+2+2) + 13 single-gap = **17 packages / 27 gaps** —
  arithmetic checks out.

**New findings this pass (corrected inline):**
- **[Audit-9 — concrete, harness module placement].** §5.2's "(or extend the premium harness)" is unsound:
  `OptionsPremiumGoldenTest` is in `backtest-service` (no `strategy-signal-service` dep → cannot reach
  `ScalperConfluenceGate`); `GoldenDeterminismTest`/`TickwiseGoldenRunner` are in `libs/strategy-engine`
  (cannot depend on the service). The new stubbed-OI scalper harness MUST be a fresh class in
  `strategy-signal-service`. **No scalper has ANY golden today** (the 5 FEATURES are all non-scalper), so this
  is greenfield test infrastructure that gates every [P] golden in the stream — PR-1's true effort is the
  upper end of M. Corrected in §5.2; the misleading "or extend" wording removed.
- **[Audit-10 — refinement to Audit-1].** Audit-1 prescribes hand-assembling a 3m series from `dayBars`; but
  the intraday path already uses `bank.primarySeries()` (the live 3m primary), and `preCloseEvaluate` already
  builds the same `bank` at `:561`. Reusing `bank.primarySeries()` + `refreshFromRest(primaryKey)` at the
  15:20 clock (mirroring the intraday `:479`) is the smaller, lower-risk fix that matches the intraday call
  shape — no manual 1m→3m folding needed. Added to §3.1. (Audit-1's core "do not pass `daily`" stands.)
- **[Audit-11 — PR-3 hard-test constraint].** `ScalperStrategyLoadTest` asserts per-slug invariants
  (`signalIndex=="NIFTY 50"`, 3m, four seam aliases, 0.6 delta/threshold, suffix-keyed OI index) on EVERY
  enumerated slug — so a PE mirror is NOT a free `direction`/`option_types` flip: it must keep
  `signal_underlying: NFO/NIFTY-FUT-CONT`, the 3m primary, the four indicator aliases, and the
  `-sensex-niftyoi`/`-sensex-sensexoi` suffix grammar. Added to §3.14.

**Parity-safety end-to-end — re-confirmed.** Walking every signal-affecting change against the firewall:
- §3.1 BTST routing — gated on the new `btst-confluence` tag AND only touches live `SignalEngine.preCloseEvaluate`
  (off the golden path); `btst-preclose` pure golden is `explicit/long/1d/untagged` → byte-identical. ✓
- §3.2 side-resolver — confined to the NEW scalper harness (Audit-2 option (a)/(b)); `TickwiseGoldenRunner`
  byte-frozen (`BOTH→LONG` unchanged, no pure golden uses `both`). ✓
- §3.3/§3.4/§3.5(participation)/§3.6 soft dots — **conditionally appended only when armed** (Audit-3 / Open
  Point 11); an untagged scalper sees an identical `dots`/`den` → existing scalper goldens (once they exist)
  stay byte-identical, and **the 5 pure goldens never reach the scorer at all**. This is the load-bearing
  parity prerequisite and it is correctly flagged as a hard precondition, not a footnote. ✓
- §3.5 point-scale, §3.9 gap-highlow, §3.11 straddle-offset, §3.12/§3.13 straddle — each a new tag/knob,
  default-OFF/0 ⇒ seam path byte-identical until armed. ✓
- §3.10 trend-change knobs — defaults == today's constants ⇒ arming is byte-identical; only a tuned override
  (riding DB rows) moves a golden. Correctly classed parity-neutral. ✓
- §3.14 PE seeding — brand-new slugs, no existing golden to perturb; CE YAMLs untouched. ✓
  **Conclusion: EVERY signal-affecting change is tag/knob-gated default-OFF with a NEW golden, and no existing
  golden/parity fixture is regenerated.** The one residual risk is purely a coding-discipline one (the
  conditional-dot-append of Audit-3 must actually be implemented conditionally) — correctly elevated to a
  hard prerequisite.

**Residual open items the dev must still resolve before each PR (not plan defects — genuine unknowns):**
1. Open Points 9 (BTST gate interval), 10 (new `MarketOiClient` reads = real cross-service code + contract
   snapshot if a query param/mapping is added), 11 (per-package conditional-dot mechanism), 12 (the two
   synthesized-residual knob sets) — all correctly surfaced and owner/dev-gated.
2. Audit-9's greenfield scalper-golden harness is the critical-path infrastructure for PR-1 and should be
   scoped/built first within PR-1 (every later [P] golden depends on it).
3. The `btst-stbt` slugs need an `EXPECTED_TAG` entry (or a relaxed assert) the moment `btst-confluence` arms.

**Dependency order — re-confirmed sound.** Routing (3.1) → side-resolver (3.2, must accompany) → the two OI
dots (3.3/3.4); SENSEX decoupling done so 3.5 is unblocked; opening-tick path exists before 3.7/3.8; SPAN
correctly scoped OUT (this stream seeds only long-premium buy legs + long straddle). No cross-service feed is
blocked beyond the new `MarketOiClient` reductions (Open Point 10). The `[S]` straddle/morning packages are
correctly classed (no frozen golden on those paths today).

**Readiness verdict: READY to execute, PR-by-PR, with Open Points 9–12 + Audit-9/11 resolved at the start of
their owning PR.** PR-1 (routing + side-resolver + the new stubbed-OI golden harness) is the gating, highest-
risk deliverable; the rest are mechanically additive behind default-OFF tags/knobs. The parity firewall holds
end-to-end **provided** the Audit-3 conditional-dot-append discipline is followed literally. No blocker found
that should stop the stream from starting.

*Pass 2 — auditor's note: the only thing I could not fully close from docs alone is whether the live 3m
primary's last bar is reliably present at the 15:20 pre-close clock (Audit-10's caveat) — a dev should
confirm the `refreshFromRest` timing at code time. Everything else is verified against source.*
