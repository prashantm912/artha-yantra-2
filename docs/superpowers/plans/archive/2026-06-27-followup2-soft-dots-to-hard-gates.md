> **ARCHIVED 2026-07-02 — BUILT default-OFF (#258–#262 W4 gates).** Owner decision 2026-06-30: the 4
> soft-dots stay ADVISORY; hard-gate arming only after live forward-paper data (inventory §6).

# Follow-up 2 — promote top soft-dots to hard gates (parity-safe, tag-gated)

Status: PLAN (implementation-ready). Owner: single-owner. Target service:
`services/strategy-signal-service` (scalper confluence seam). Date: 2026-06-27.

> Read order for the executor: this plan is self-contained. The CLAUDE.md
> "parity-safe-additive" convention and the `oi-cross-filter` (#5) gate are the
> load-bearing precedents — every change here copies that exact shape.

---

## 1. Goal & scope

### What changes
Promote the four most-defensible **soft confluence dots** to **opt-in hard gates** on the
scalper Track-2 confluence path, each armed by a NEW per-strategy YAML tag that is **absent
from every existing strategy** (so every shipped config is byte-identical today, default-OFF).
The four promotions, in priority order:

1. **`indicator-alignment`** — the doc's "ALL soldiers on the far side" conjunction
   (PSAR + VWMA + Supertrend + VWAP all on the side). Already coded as
   `ScalperGates.indicatorAlignment` (L102-118) and unit-tested, but **never called** on the
   confluence path. This is the documented, highest-value promotion
   (`docs/strategy-audit/two-candle.md` L64, `trend-change.md` L62, README §4 L538). **PR-1.**
2. **`futures-oi-gate`** — futures-OI quadrant (LB/SC bull, SB/LU bear) as a hard precondition,
   reusing `ScalperGates.oiQuadrant` (L121-125). **PR-2**, with an explicit derived-history
   carve-out (see §4.2 and the open point on fail-open).
3. **`breadth-gate`** — advances/declines > 32 as a hard precondition, reusing
   `ScalperGates.breadth` (L128-133). **PR-2.**
4. **`basis-gate`** — futures premium/discount as a hard precondition, reusing
   `ScalperGates.futuresBasis` (L164-171). **PR-2.**

Each promotion is: (a) one new `requireXxx` boolean on the `ScalperConfig` record, (b) one
`tags.contains("<tag>")` parse line in `ScalperConfig.from`, (c) one early-return hard-gate
block in `ScalperConfluenceGate.evaluate` (the #5 template), (d) the seam-test triple
(pass / block / non-Xxx-unaffected), (e) **NO new YAML carries the tag in this work** — arming
real strategies is a separate, deliberate, owner-driven follow-up (§7 PR-3, default deferred).

### What does NOT change
- **The dots stay soft in the scorer.** `ConnectTheDotsScorer` weights (L32-35), the 19-dot
  list (L74-98), and the aggregate math (L100-109) are **untouched**. When a tag is unarmed the
  aggregate is bit-for-bit identical to today. We do NOT thread a new flag into `score(...)` and
  we do NOT add an AND-term to the scorer's `valid` (L114-115). (See §4.0 "design choice".)
- **No re-weighting, no threshold change.** `THRESHOLD=0.6` (`ScalperConfig` L88) and the dot
  weights are explicitly out of scope (they are DB-param-bound tuning levers per the source
  comments; orthogonal to this work).
- **No golden/parity fixture is regenerated.** The 5 engine goldens
  (`ema-crossover`, `optional-indicator-activation`, `btst-preclose`, `exit-intrabar`,
  `context-series`) carry no scalper strategy and are invisible to this change (§5).
- **No DB migration, no springdoc contract change** (§6).
- **VIX and Dow** (recon candidates 4 and 5) are **explicitly out of scope** — they are
  "wire-the-feed" tasks (`MarketOiClient.macro` passes null VIX/Dow), not gate-promotion tasks,
  and adding a Dow *dot* would change the scorer denominator and break goldens. Recorded as Open
  Points (§8) with a recommended split into a separate feed-wiring plan.
- **The neutral straddle path** (`requireStraddle`, `ScalperConfluenceGate` L132-147) is never
  reached by these gates (it returns before the directional side is decided), so it is unaffected.

---

## 2. Background — current code (verified file:line)

All line numbers below were opened and confirmed against the working tree on 2026-06-27.

### 2.1 The soft-dot scorer — `ConnectTheDotsScorer.java`
- Weights: `W_VWAP=2.5` (L32), `W_OI=1.5` (L33), `W_IV=0.8` (L34), `W=1.0` (L35),
  `IV_RANK_LOW=50` (L36).
- The **18** dots are added L74-98 via `add(dots, name, weight, supports, reason)` (AUDIT pass 1: the
  scorer adds 18 dots, not 19 — vwap, supertrend, vwma, psar, rsi, volume, futures_oi, underlying_oi,
  trending_cross, sentiment, drastic_oi, sentiment_slope, oi_spurt, breadth, vix, basis, iv_rank,
  iv_pair; the §5.2 "over 19 dots" reasoning must use 18). Relevant to this
  plan: `vwap` (L74), `supertrend` (L75), `vwma` (L76), `psar` (L77), `futures_oi` via
  `ScalperGates.oiQuadrant` (L80), `underlying_oi` (L81), `breadth` via `ScalperGates.breadth`
  (L91), `basis` via `ScalperGates.futuresBasis` (L93).
- Aggregate = weighted-supports / total-weight, scale 4 HALF_UP (L100-109). Denominator = the sum of
  all 18 dot weights = `2.5 + 1.5 + 0.8 + 0.8 + 14×1.0 = 19.6` (W_VWAP + W_OI + 2×W_IV + 14 unit
  dots). **AUDIT pass 2: the denominator is 19.6, NOT 20.6 — there are 14 unit-weight (`W=1.0`) dots,
  not 15.** 18 total dots − 4 weighted (vwap @2.5, futures_oi @1.5, iv_rank @0.8, iv_pair @0.8) = 14
  unit dots (supertrend, vwma, psar, rsi, volume, underlying_oi, trending_cross, sentiment, drastic_oi,
  sentiment_slope, oi_spurt, breadth, vix, basis). A passing CFG (`confluenceConfirmsAndPicksTheInBandCe`)
  clears 0.6 = `≥11.76` of supporting weight (0.6 × 19.6); the actual passing `bullContext()` aggregate
  is `14.8 / 19.6 = 0.7551` (audit-verified by enumerating its supporting dots). Dropping one `W=1.0`
  dot subtracts 1.0/19.6 ≈ 0.051 from the aggregate (the §5.2 headroom math).
- `biasAligned` L111; validity rule L114-115:
  `valid = (!vwapHardGate || vwapSide) && biasAligned && !standAside && aggregate >= threshold`.
  The existing hard constraints inside the scorer are exactly: vwap (when `vwapHardGate`),
  60-minute bias, and the T2.8 `standAside`. **This plan adds NO term here.**
- `score(...)` signature L63-65. **This plan does NOT change it.**

### 2.2 The seam — `ScalperConfluenceGate.java`
- `@Component`, **LIVE-only** (class javadoc L29-33: the OI/macro/chain reads are current
  snapshots, never run on deterministic replay; the picked option + confluence are persisted at
  entry via the V009 side-channel). This is the parity firewall.
- `evaluate(...)` L100-280 assembles the per-bar context and runs the existing hard pre-gates,
  each behind a `ScalperConfig` flag:
  - time window L112-118; volume + RSI rails L157-163; two-candle L167-169; gap-fill L173-179;
  - **#5 call-put delta filter L196-199 — the canonical hard-pre-gate insertion shape**;
  - trend-change L204-211; open-high-low L218-229; hero-zero L236-245; straddle branch L132-147.
- The directional `side` is decided at L149-152 (CE when `close >= vwap`, else PE).
- The local `Chart chart` is built at L124 from `chart(bank, index)` (the method at L304-316),
  reading the engine `IndicatorBank` on the index FUTURE. **The new indicator-alignment gate must
  read THIS `chart` local (the bank-derived chart), not `ctx.chart()`** — they are constructed
  from the same bar but `chart` is the in-scope variable at the insertion point and matches what
  the side decision at L149-152 uses.
- `ScalperGateContext ctx` (carrying `ctx.oi()` for the OI/breadth/basis operands) is built at
  L191-192, AFTER the side is decided and AFTER the #5 gate. The OI/breadth/basis gates therefore
  insert **after L192** (they need `ctx.oi()` / `ctx.macro()`); the indicator-alignment gate can
  insert **right after the side decision at L152** (it needs only `chart` + `side`).
- `vwapHardGate` computed L249; `score` + validity L250-253; empty `Optional` BLOCKS.

### 2.3 The hard-gate library — `ScalperGates.java` (pure `GateOutcome` functions)
- **`indicatorAlignment(Chart c, OptionType side)` L102-118** — WRITTEN + UNIT-TESTED, **NOT
  called** on the confluence path (grep confirms the only references are the definition and
  `ScalperGatesTest` L85-97). The prime promotion target: CE requires
  `close>vwap && close>vwma20 && close>psar && supertrendDir>0`; PE the mirror.
- `oiQuadrant(Oi, side)` L121-125 — CE needs `futures().bullish()`, PE `futures().bearish()`.
- `breadth(Macro, side)` L128-133 — `advances/declines > 32`.
- `vix(Macro, side)` L136-143 — **unknown direction PASSES** (L137-138) — fail-open on null.
- `callPutDeltaFilter(Oi, floorPct)` L151-161 — **null imbalance DEGRADES to PASS** (L153-155) —
  the #5 fail-open precedent.
- `futuresBasis(Oi, side)` L164-171 — **null basis PASSES** (L166-167) — fail-open on null.
- Threshold constants L22-30 (tuning rides DB rows, not these).

### 2.4 Tag → gate wiring — `ScalperConfig.java`
- `record ScalperConfig(...)` fields L36-52 — each `requireXxx` boolean.
- `from(JsonNode config, List<String> tags)` L101-157 maps tags to flags: two-candle (L119),
  gap-theory (L121), trend-change (L123), open-high-low (L125), opening-tick (L128), hero-zero
  (L131), straddle (L135), entry-candle-stop (L149), **oi-cross-filter L153** (the #5 template).
- Constructor returns all flags L154-156. `THRESHOLD=0.6` L88.
- **Constructor arity is coupled** to every `new ScalperConfig(...)` literal in
  `ScalperConfluenceGateTest` (L43-90, **8 literals**: CFG L44, TWO_CANDLE_CFG L49, OI_CROSS_CFG L56,
  GAP_CFG L62, TREND_CHANGE_CFG L68, OPEN_HIGH_LOW_CFG L74, OPENING_TICK_CFG L81, STRADDLE_CFG L87).
  Adding a field forces a compile-time update to all **8** — a fan-out, not a parity risk. (AUDIT pass
  1: the plan originally said "9 literals"; there are 8. The only other `new ScalperConfig(...)` literal
  in the codebase is the canonical constructor inside `ScalperConfig.from` itself — the two
  `ScalperConfig.from(...)` call sites, `ScalperStrategyLoadTest` L130 and `SignalEngine` L195, do NOT
  break on an arity change.)

### 2.5 The YAML tag fields — `services/.../scalper-strategies/` (36 files)
- 12 strategies × {nifty, sensex-niftyoi, sensex-sensexoi}. The gate-arming field is the
  top-level `tags:` list (e.g. `scalp-two-candle-nifty.yaml` L13
  `[scalper, options, intraday, nifty, two-candle-pattern]`; `scalp-trending-oi-nifty.yaml` L12
  `[..., oi-cross-filter]`).
- `scalper` + `universe.mode: options_of_underlying` flips a strategy onto the confluence path
  (`SignalEngine` L192-196). Adding an opt-in hard-gate tag = append one token to `tags:`.
- Seeded by `ScalperStrategySeeder` (loads each `/scalper-strategies/<id>.yaml`; tags ride from
  the YAML onto the registry draft).

### 2.6 The parity firewall — golden / parity harnesses
- `GoldenSignalsJson.write()` L41-70 serializes ONLY
  `timestamp, exchange, tradingsymbol, direction, composite, breakdown{score,weight,activated}`.
  `SignalEvent.stopLoss/takeProfit` (L27-34) are a non-serialized side-channel — the
  parity-safe-additive precedent.
- `GoldenDeterminismTest.FEATURES` L33-36 = the 5 pure-engine goldens (no scalper). Twice-run
  byte-match + frozen-fixture byte-match (L47-71); regenerate-once via `-Dgolden.generate=true`
  (L61).
- `BacktestParityTest.FEATURES` L35-38 (no scalper). Two replays byte-identical signals + trades,
  and first replay byte-matches the frozen golden (L67-78).
- **Neither harness instantiates `ScalperConfluenceGate`/`ConnectTheDotsScorer`** — they drive
  pure-engine YAMLs through `TickwiseGoldenRunner` / `ReplayEngine`. The gate is LIVE-only,
  consulted via `Optional<ScalperConfluenceGate>` in `SignalEngine.scalperEntry` (L459-462).
  Therefore a tag-gated hard gate **cannot perturb the goldens** provided no golden/parity YAML
  carries the new tag (none can: the FEATURES arrays are fixed and contain no scalper strategy).

### 2.7 Side-channel → API → frontend (the data flow the dots already use)
- `Confluence.dots()` (`ConnectTheDotsScorer` L46-48) → `SignalEngine.scalperDetailJson`
  (L667-706) writes `confluence_aggregate` (L679) + a `dots[]` array (L680-686:
  `{dot, weight, supports}`) into the `scalper_detail` JSON.
- Persisted via `SignalRepository.stampScalperDetail` into the `scalper_detail` jsonb column
  (`SignalRepository` L184-188; column read back at L172 into `SignalRow.scalperDetail`).
- Surfaced by the signals API and typed FE-side as `ScalperDetail.dots: ConfluenceDot[]`
  (`frontend-react/src/api/signals.ts` L40-67).
- Rendered by `frontend-react/src/components/ManualVerifyChecklist.tsx` L73-95 (the
  "Automated confluence (<aggregate>)" chip row; ▲ supports / ▼ opposes).

---

## 3. Work items (from the audit)

Numbered, each with its doc-section / audit ref. Priority = recommended PR ordering.

| # | Item | Gate fn (exists) | Tag (new) | Audit / doc ref | Priority |
|---|------|------------------|-----------|-----------------|----------|
| WI-1 | Promote **indicator-alignment** (PSAR+VWMA+ST+VWAP all on far side) to a hard gate | `ScalperGates.indicatorAlignment` L102-118 | `indicator-alignment` | two-candle.md L27,L64; trend-change.md L62; README §4 L538 (§3.1#8 / §4.2) | **PR-1** |
| WI-2 | Promote **futures-OI quadrant** to a hard gate (live/forward only) | `ScalperGates.oiQuadrant` L121-125 | `futures-oi-gate` | two-candle.md L28 (§3.1#2,#6); connect-the-dots.md (§3.10 Setup/Entry 7); README §4 L538 | PR-2 |
| WI-3 | Promote **breadth (Adv/Dec>32)** to a hard gate | `ScalperGates.breadth` L128-133 | `breadth-gate` | two-candle.md L35 (§4.8); README §4 L538 | PR-2 |
| WI-4 | Promote **futures basis (premium/discount)** to a hard gate | `ScalperGates.futuresBasis` L164-171 | `basis-gate` | README §4 L538 ("promote soft dots to hard gates"; the basis soft dot is `ConnectTheDotsScorer.java:93`). **AUDIT pass 1:** two-candle.md L33 is the VIX row, NOT a basis row — basis is not enumerated as its own audit line, so WI-4's doc mandate is the generic README §4 L538 bullet (and `ScalperGates.futuresBasis`'s own javadoc), not a basis-specific audit row | PR-2 |

Out-of-scope, recorded as Open Points (§8): VIX directional gate (feed-starved), Dow global-cue
dot (would change scorer denominator → breaks goldens; needs a re-baseline, not a tag), and
arming the new tags onto real strategy YAMLs (PR-3, owner-driven).

---

## 4. Per-item design

### 4.0 Design choice (applies to all four): early-return gate, NOT a scorer AND-term
Two mechanisms can promote a dot to a hard gate:
- **(A)** an early `return Optional.empty()` inside `evaluate()` (the #5 `oi-cross-filter` shape),
  OR
- **(B)** a new AND-clause in the scorer's `valid` (L114-115) gated on a flag threaded through
  `score(...)`.

**We use (A) for all four.** Rationale: (A) matches every existing scalper hard gate, skips the
strike pick on failure (cheaper), keeps `ConnectTheDotsScorer` a pure function with an unchanged
signature, and leaves the dot's weight contribution to the aggregate exactly as-is. (B) would
mutate `score(...)`'s signature and the `Confluence` validity, touching the very method the
goldens would care about if they ever ran scalpers — strictly worse for parity isolation. The dot
remains scored in the aggregate either way; only the early-return adds the conjunctive block.

---

### 4.1 WI-1 — `indicator-alignment` hard gate (PR-1)

**File 1 — `ScalperConfig.java` (record field + tag parse + constructor).**

Add a field to the record (place after `requireStraddle` to keep the existing positional order
stable for readers; the constructor-arity fan-out is the same wherever it goes):

```java
public record ScalperConfig(
    String underlyingExchange,
    String underlying,
    String signalIndex,
    String oiIndex,
    int rollDays,
    StrikePicker.Params strikeParams,
    BigDecimal confluenceThreshold,
    boolean requireTwoCandle,
    StructuralStop structuralStop,
    boolean requireCallPutDeltaFilter,
    boolean requireGapFill,
    boolean requireTrendChange,
    boolean requireOpenHighLow,
    boolean openingTick,
    boolean requireHeroZero,
    boolean requireStraddle,
    boolean requireIndicatorAlignment) {   // NEW (PR-1)
```

In `from(...)`, alongside the L153 `oi-cross-filter` parse:

```java
    // FU2: the indicator-alignment tag makes the "ALL soldiers on the far side" conjunction
    // (PSAR + VWMA + Supertrend + VWAP all on the side) a HARD pre-gate. Default OFF — no
    // shipped YAML carries the tag, so every existing config is byte-identical.
    boolean indicatorAlignment = tags.contains("indicator-alignment");
```

And thread it through the constructor at L154-156:

```java
    return new ScalperConfig(
        exchange, underlying, signalIndex, oiIndex, rollDays, params, THRESHOLD, twoCandle, stop,
        callPutDeltaFilter, gapFill, trendChange, openHighLow, openingTick, heroZero, straddle,
        indicatorAlignment);
```

Also extend the class-javadoc tag inventory (L18-35) with one sentence describing the
`indicator-alignment` tag, matching the style of the existing tag descriptions.

**File 2 — `ScalperConfluenceGate.java` (the early-return hard gate).**

Insert immediately AFTER the side decision (after L152, before the volume/RSI rails at L153-163),
because the gate needs only `chart` + `side` and should fail fast before the chain-context
fan-out (mirroring how #5 fails closed before the pick). Use the bank-derived `chart` local
(built at L124), NOT `ctx.chart()` (which is not yet constructed here):

```java
    // FU2 indicator-alignment: when the strategy declares the `indicator-alignment` tag, the
    // doc's "ALL soldiers on the far side" conjunction (PSAR + VWMA + Supertrend + VWAP all on
    // the side) is a HARD pre-gate — fail-closed, like the volume/RSI rails. indicatorAlignment
    // is already fully written (ScalperGates.indicatorAlignment) and unit-tested; here it is
    // simply consulted. Default OFF (no shipped YAML carries the tag) so every existing config
    // is byte-identical.
    if (cfg.requireIndicatorAlignment()
        && !ScalperGates.indicatorAlignment(chart, side).pass()) {
      return Optional.empty();
    }
```

> Placement note: putting it after the side decision (L152) rather than after `ctx` (L192) means
> it short-circuits before `client.context(...)` (the OI fan-out), which is the cheaper, more
> faithful "fail before work" behaviour. `chart` is already in scope at L124+. Confirm the
> insertion compiles against the `chart`/`side` locals (both exist between L152 and L157).

**Fail-open behaviour:** `indicatorAlignment` does NOT degrade-to-pass on null operands — `gt(a,b)`
returns false when either is null (`ScalperGates` L173-175), and a null `supertrendDir` is `0`
(neither `>0` nor `<0`), so a missing indicator FAILS the gate (blocks). This is the desired
strict behaviour for a "soldiers aligned" precondition and is parity-irrelevant (live-only path).

**Data flow (definition → persistence → API → FE):** UNCHANGED. The four indicators are already
scored as soft dots (`supertrend`/`vwma`/`psar`/`vwap`) and already ride the `dots[]` side-channel
into `scalper_detail` → `ScalperDetail.dots` → the `ManualVerifyChecklist` chip row. The hard gate
only changes *whether the signal fires*, not the payload shape — so **no API, contract, or FE
change is required**. (Optional FE polish is recorded as an Open Point, §8.)

---

### 4.2 WI-2 — `futures-oi-gate` hard gate (PR-2)

**`ScalperConfig.java`:** add `boolean requireFuturesOiGate` to the record, parse
`tags.contains("futures-oi-gate")`, thread through the constructor (same pattern as 4.1).

**`ScalperConfluenceGate.java`:** insert AFTER `ctx` is built (after L192), because the operand
is `ctx.oi()`:

```java
    // FU2 futures-OI gate: when the strategy declares `futures-oi-gate`, the futures-OI quadrant
    // (LB/SC bullish for CE, SB/LU bearish for PE) is a HARD precondition. DERIVED-HISTORY CAVEAT:
    // OI degrades to NEUTRAL on backtests (CLAUDE.md), so this is a LIVE/FORWARD discriminator and
    // must be armed only on forward-paper variants — never on a parity/golden YAML (none exist).
    if (cfg.requireFuturesOiGate()
        && !ScalperGates.oiQuadrant(ctx.oi(), side).pass()) {
      return Optional.empty();
    }
```

**Fail-open behaviour:** `oiQuadrant` does NOT degrade-to-pass — a NEUTRAL/wrong quadrant FAILS.
On derived history the quadrant is NEUTRAL, so an armed `futures-oi-gate` would block almost every
historical signal. **This is why WI-2 is a forward-paper-only gate** and must not be armed on any
backtested/golden YAML. (It is still parity-safe for the goldens because no golden YAML is a
scalper; the caveat is about *strategy* behaviour on history, recorded in the YAML comment when
PR-3 eventually arms it.)

**Data flow:** UNCHANGED — `futures_oi` is already a scored dot in the side-channel.

---

### 4.3 WI-3 — `breadth-gate` hard gate (PR-2)

**`ScalperConfig.java`:** add `boolean requireBreadthGate`, parse `tags.contains("breadth-gate")`,
thread through.

**`ScalperConfluenceGate.java`:** insert after L192 (operand `ctx.macro()`):

```java
    // FU2 breadth gate: when the strategy declares `breadth-gate`, advances/declines > 32 (the
    // §4.8 cutoff) is a HARD precondition. Degrades to NEUTRAL on derived history → forward gate.
    if (cfg.requireBreadthGate()
        && !ScalperGates.breadth(ctx.macro(), side).pass()) {
      return Optional.empty();
    }
```

**Fail-open behaviour:** `breadth` uses a raw int count `> 32` — a zero/absent count FAILS
(blocks). Forward-only, same caveat as WI-2.

**Data flow:** UNCHANGED — `breadth` is already a scored dot.

---

### 4.4 WI-4 — `basis-gate` hard gate (PR-2)

**`ScalperConfig.java`:** add `boolean requireBasisGate`, parse `tags.contains("basis-gate")`,
thread through.

**`ScalperConfluenceGate.java`:** insert after L192 (operand `ctx.oi()` carries `futuresBasis`):

```java
    // FU2 basis gate: when the strategy declares `basis-gate`, futures premium (future > spot)
    // confirms CE / discount confirms PE as a HARD precondition. NULL basis DEGRADES to PASS
    // (ScalperGates.futuresBasis L166-167) — so on missing-data / derived history it never blocks
    // (fail-open, like #5/VIX). Tighten only if the open point on fail-open is resolved.
    if (cfg.requireBasisGate()
        && !ScalperGates.futuresBasis(ctx.oi(), side).pass()) {
      return Optional.empty();
    }
```

**Fail-open behaviour:** `futuresBasis` DEGRADES to PASS on null (L166-167). So `basis-gate`
inherits fail-open-on-missing-data: on derived history (null basis) it never blocks — *safer for
backtests but weaker as a live filter*. This is the same trade-off as #5 and is acceptable for v1
(recorded as an Open Point if the owner wants a strict variant).

**Data flow:** UNCHANGED — `basis` is already a scored dot.

---

### 4.5 Final `evaluate()` insertion-order summary
Within `evaluate()`, the new blocks land in two clusters:
1. **After the side decision (L152), before volume/RSI (L153):** WI-1 `indicator-alignment`
   (needs only `chart` + `side`; fails fast before the chain context).
2. **After `ctx` is built (L192), grouped with #5 (L196-199):** WI-2 `futures-oi-gate`,
   WI-3 `breadth-gate`, WI-4 `basis-gate` (need `ctx.oi()` / `ctx.macro()`). Place them
   immediately after the existing #5 block so all OI/macro hard gates are co-located and read
   top-to-bottom in declaration order.

All five (with #5) are independent early-returns; order among them is behaviourally irrelevant
(any failing gate blocks), so group for readability.

---

## 5. Tests

### 5.1 Unit — gate functions (`ScalperGatesTest.java`)
- **WI-1:** `indicatorAlignmentNeedsAllOnTheCorrectSide` (L85-97) already proves the function
  (all-on-side passes; one operand wrong-side or supertrend wrong-way fails). **Reuse as-is — no
  change.**
- **WI-2/3/4:** `oiQuadrantMatchesSide` (L100-107), `breadthThirtyTwoCutoff` (L110-115),
  `futuresBasisPremiumBullDiscountBear` (L126-131) already prove those functions, including
  `futuresBasis` null→pass (L130). **Reuse as-is.** No new gate-function tests are needed because
  the functions are unchanged; only the SEAM wiring is new.

### 5.2 Unit — seam wiring (`ScalperConfluenceGateTest.java`)
This is where each promotion's behaviour is proved. The constructor-arity change forces updating
**all 8 existing `new ScalperConfig(...)` literals** (L43-90) — append the new boolean(s) in
positional order (`false` for every existing literal, since none arm the new gates). With four new
fields the existing literals each get four trailing `false`s. (AUDIT pass 1: 8 literals, not 9.)

For **each** of WI-1..WI-4 add one new CFG literal + a 3-test triple, mirroring the #5 oi-cross
template (L292-312) and the "non-Xxx unaffected" template (L459-468 / L586-597):

**WI-1 `indicator-alignment` (concrete example):**

```java
  // FU2: a strategy with the indicator-alignment HARD pre-gate enabled.
  private static final ScalperConfig INDICATOR_ALIGNMENT_CFG =
      new ScalperConfig(
          "NSE", "NIFTY 50", "NIFTY 50", "NIFTY 50", 2,
          new StrikePicker.Params(0.6, 0.7, bd("100"), bd("400"), 0.065), bd("0.6"),
          false, ScalperConfig.StructuralStop.NONE, false, false, false, false, false, false, false,
          /* requireIndicatorAlignment */ true /* + false,false,false for WI-2/3/4 fields */);
```

```java
  @Test
  void indicatorAlignmentStrategyPassesWhenAllSoldiersAlignedAndBlocksWhenOneIsAgainst() {
    MarketOiClient client = mock(MarketOiClient.class);
    when(client.chain("NIFTY 50")).thenReturn(Optional.of(chainWithInBandCe()));
    when(client.context(eq("NIFTY 50"), any(), any(), any(), any(), any(), any()))
        .thenReturn(bullContext());
    // bullBank(): close 100 > vwap 99 > vwma20 98 > psar 97, supertrend +1 -> all aligned -> PASS.
    Optional<Decision> decision =
        new ScalperConfluenceGate(client, ScalperOiProps.defaults(), CAL)
            .evaluate(INDICATOR_ALIGNMENT_CFG, bullBank(), null, 0, NOW, IST_TIME, EOD);
    assertThat(decision).isPresent();
    assertThat(decision.get().pick().candidate().tradingsymbol()).isEqualTo("NIFTY19850CE");
  }

  @Test
  void indicatorAlignmentStrategyBlocksWhenAnIndicatorIsOnTheWrongSide() {
    MarketOiClient client = mock(MarketOiClient.class);
    when(client.chain("NIFTY 50")).thenReturn(Optional.of(chainWithInBandCe()));
    when(client.context(eq("NIFTY 50"), any(), any(), any(), any(), any(), any()))
        .thenReturn(bullContext());
    // a bank whose PSAR (101) sits ABOVE the close (100) -> CE alignment fails -> the HARD gate
    // blocks before the pick (close>vwap keeps the side CE so vwap/vwma/supertrend still bull).
    assertThat(
            new ScalperConfluenceGate(client, ScalperOiProps.defaults(), CAL)
                .evaluate(INDICATOR_ALIGNMENT_CFG, misalignedPsarBank(), null, 0, NOW, IST_TIME, EOD))
        .isEmpty();
  }

  @Test
  void nonIndicatorAlignmentStrategyIsUnaffectedByAMisalignedIndicator() {
    MarketOiClient client = mock(MarketOiClient.class);
    when(client.chain("NIFTY 50")).thenReturn(Optional.of(chainWithInBandCe()));
    when(client.context(eq("NIFTY 50"), any(), any(), any(), any(), any(), any()))
        .thenReturn(bullContext());
    // CFG carries no indicator-alignment tag -> the conjunction gate is never consulted; the
    // confluence still picks despite the misaligned PSAR (it remains a soft dot).
    assertThat(
            new ScalperConfluenceGate(client, ScalperOiProps.defaults(), CAL)
                .evaluate(CFG, misalignedPsarBank(), null, 0, NOW, IST_TIME, EOD))
        .isPresent();
  }
```

New helper (clone `bullBank()` L115-135 with `psar` above the close so alignment fails but the
side stays CE — close 100 ≥ vwap 99 keeps it CE, and the soft-dot aggregate still clears 0.6 so
the non-gated CFG passes):

```java
  // AUDIT pass 1 corrected: there is NO `barOf(...)` factory in ScalperConfluenceGateTest — bullBank()
  // (L115-135) and closeEqualsVwapBank() (L606-626) each inline an anonymous `new BarValues(){...}`.
  // Mirror that shape exactly (psar 101 sits ABOVE the close 100 → CE alignment fails; close 100 ≥ vwap
  // 99 keeps the side CE; volume 130k + rsi 65 clear the §0B rails so the soft-dot path still passes).
  private static BarValues misalignedPsarBank() {
    Map<String, BigDecimal> builtins =
        Map.of("close", bd("100"), "vwap", bd("99"), "volume", bd("130000"));
    Map<String, BigDecimal> aliases =
        Map.of("vwma20", bd("98"), "psar", bd("101"), "rsi14", bd("65"), "supertrend", bd("1"));
    return new BarValues() {
      @Override
      public BigDecimal valueAt(String alias, int i) {
        return aliases.get(alias);
      }

      @Override
      public BigDecimal previousValueAt(String alias, int i) {
        return null;
      }

      @Override
      public BigDecimal builtin(String name, int i) {
        return builtins.get(name);
      }
    };
  }
```

> Verify the misaligned-but-CFG-passes test: with `psar=101` only the `psar` soft dot flips to
> false; the aggregate over **18** dots (denominator **19.6**, see §2.1 — AUDIT pass 2 fixed 20.6→19.6)
> must still be ≥ 0.6 for the non-gated `CFG` PASS test to hold. Note the seam scorer reads `psar` from `ctx.chart()` (built by
> `client.context(...)`, the MOCK), NOT from the `bank`/`chart` local — so flipping `psar` in
> `misalignedPsarBank()` (the bank) makes the indicator-alignment GATE fail (it reads the bank-derived
> `chart`) while the soft-dot `psar` in the mocked `bullContext()` stays bullish and the aggregate is
> UNCHANGED. **AUDIT pass 1 (soundness):** because the gate reads the bank-`chart` and the scorer reads
> the mock-`ctx.chart()`, the WI-1 block-test and the non-gated PASS test use the SAME `misalignedPsarBank()`
> + `bullContext()` mock and CANNOT conflict — the aggregate never drops at all (the "drop one dot below
> 0.6" worry does not arise for WI-1; it only matters for WI-2/3/4 where the gate operand IS a scored dot
> in `ctx`). Keep the supertrend-flip fallback below for WI-2/3/4 fixture tuning.
> `bullContext()` already drives a passing aggregate in `confluenceConfirmsAndPicksTheInBandCe`
> (L167+); dropping one `W=1.0` dot from a passing total is unlikely to cross below 0.6, but the
> executor MUST run the test and, if it dips under threshold, instead misalign **supertrend**
> (set `supertrend` to `1` but `psar`/`vwma` bull) — pick the single-operand flip that keeps the
> aggregate ≥ 0.6. Goal: the gate blocks (WI-1 test) while the soft-dot path still passes
> (non-Xxx test). This is a fixture-tuning detail, not a design risk.

**WI-2 / WI-3 / WI-4** follow the identical triple. For each, build a context whose operand fails:
- WI-2: a `bullContext()` clone with `futures` = `OiQuadrant.SHORT_BUILDUP` (bearish) → CE blocks.
- WI-3: a `Macro` with `advances=10` (≤ 32) → CE blocks.
- WI-4: an `Oi` with `futuresBasis = bd("-5")` (discount) → CE blocks; and a null-basis variant
  that PASSES (proving the documented fail-open). The `basis(...)` / `imbalance(...)` /
  `macro(...)` helpers in `ScalperGatesTest` show the shapes; the seam test builds full
  `ScalperGateContext` values like `bullContextWithImbalance` (L148-156).

Each gets its own `non-Xxx-unaffected` test using the bare `CFG` (which arms none of the new
gates) against the same failing context, asserting `.isPresent()`.

### 5.3 Load test (`ScalperStrategyLoadTest.java`)
- The `ScalperConfig.from` call (L130) still compiles (the new `requireXxx` fields default false
  for every YAML, since no YAML carries the new tags). **Add four assertions** in the loop (after
  L153) that the new flags are OFF for every seeded strategy — this is the regression tripwire
  that proves PR-1/PR-2 do not silently arm anything:

```java
      assertThat(cfg.requireIndicatorAlignment())
          .as(id + " indicator-alignment gate must be OFF (no tag shipped)").isFalse();
      assertThat(cfg.requireFuturesOiGate()).as(id + " futures-oi gate off").isFalse();
      assertThat(cfg.requireBreadthGate()).as(id + " breadth gate off").isFalse();
      assertThat(cfg.requireBasisGate()).as(id + " basis gate off").isFalse();
```

(If PR-3 later arms a tag on a real variant, this map flips to a per-id expectation, mirroring the
existing `requireCallPutDeltaFilter` / `requireStraddle` per-family assertions at L150-159.)

### 5.4 Golden / parity tripwires (MUST stay byte-identical)
- `GoldenDeterminismTest` (`libs/strategy-engine`) — FEATURES L33-36 carry no scalper tag, so the
  change is invisible. **Re-run; assert byte-identical green. Do NOT regenerate.**
- `BacktestParityTest` (`services/backtest-service`) — FEATURES L35-38 carry no scalper. **Re-run;
  assert the three byte-match asserts (signals determinism + trades equality + replay==frozen
  golden, L67-78) stay green.**
- These two are the load-bearing proof that the gate change did not leak onto the deterministic
  replay path. No new golden variant is created (see §5.6).

### 5.5 e2e (`e2e/tests/signals.spec.ts`)
There is **no scalper-specific e2e spec**; `signals.spec.ts` is the existing signals-flow e2e.
Because PR-1/PR-2 ship **default-OFF** (no YAML arms a tag, no new signal fires or is suppressed),
**no e2e behaviour changes** and `signals.spec.ts` must stay green as-is — re-run it as a
regression check. If PR-3 later arms a tag on a published strategy, add an e2e assertion there
that the confluence chip row (the `ManualVerifyChecklist` dots) still renders for a gated signal;
do NOT add e2e in PR-1/PR-2 (nothing observable changes).

### 5.6 Optional positive golden coverage (NOT in scope for PR-1/PR-2)
Positive deterministic coverage *of the gate itself* would require a brand-new feature added to
the `TickwiseGoldenRunner`-driven goldens with a committed `expected/<name>.signals.json`
(generate-once), AND the runner would have to drive the LIVE-only gate deterministically (it
currently does not). This is explicitly **out of scope** — the seam unit tests (§5.2) already give
positive + negative coverage, and adding a scalper golden would be a large, separate effort. If
ever pursued: it MUST be additive (a new FEATURES entry + new fixtures), never a mutation of the 5
frozen goldens. Recorded as an Open Point (§8).

---

## 6. Migrations / schema / contract impact — NONE

- **DB migration: none.** No new table, column, index, role, or grant. The `scalper_detail` jsonb
  column already carries `dots[]` + `confluence_aggregate`; the gate adds no field. The flags are
  in-memory `ScalperConfig` record fields derived from YAML tags at load time (no persistence).
- **Schema (strategy-schema/v1): none.** The arming field is the existing top-level `tags:` list
  (a free-form string array already in the schema). No new YAML key is introduced — only new
  *values* in `tags:`, and only in PR-3, on real strategies.
- **Springdoc / contract (`ContractCaptureTest`): none.** No new `@*Mapping` path, no new query
  param, no new request/response DTO. `SignalEmitted.ScalpDetail` and the `ScalperDetail` /
  `ConfluenceDot` FE types are unchanged (the dots payload is identical). Per the CLAUDE.md note,
  even response-key additions to generic `Map<String,Object>` returns don't drift the spec — and
  here there are no additions at all. `ci-contracts` stays green with no re-capture.
- **Golden vectors: none** (see §5.4) — no fixture is added or regenerated.

---

## 7. Rollout sequence, risk register, backout

### 7.1 PR breakdown
- **PR-1 `feat(strategy-signal): indicator-alignment hard gate (tag-gated, default-off)`**
  - `ScalperConfig`: `requireIndicatorAlignment` field + parse + constructor + javadoc.
  - `ScalperConfluenceGate`: the WI-1 early-return after L152.
  - `ScalperConfluenceGateTest`: update the 8 CFG literals (add the one field) + the WI-1 triple +
    `misalignedPsarBank()` helper (inline the anonymous `new BarValues(){...}` shape — there is no
    `barOf` helper in the file; see §5.2 AUDIT note).
  - `ScalperStrategyLoadTest`: the `requireIndicatorAlignment` OFF assertion.
  - Verify: full strategy-signal-service `verify` (JaCoCo ≥ 60%) + the two golden/parity tripwires.
- **PR-2 `feat(strategy-signal): futures-OI / breadth / basis hard gates (tag-gated, default-off)`**
  - The three remaining fields + parses + constructor; the three early-returns after L192;
    three CFG literals + three triples; three OFF assertions in the load test.
  - Verify: same as PR-1. (Could be split into three PRs if the owner prefers smaller diffs; one PR
    is fine since they are mechanically identical and all default-off.)
- **PR-3 (DEFERRED, owner-driven) `feat(strategy-signal): arm <tag> on <variant(s)>`**
  - Append a tag to the chosen `scalper-strategies/*.yaml` `tags:` list (e.g. `indicator-alignment`
    on the two-candle family per the audit recommendation). Flip the matching `ScalperStrategyLoadTest`
    OFF-assertion to a per-id expectation. Re-run the goldens (still green — no golden YAML carries
    the tag). Add the forward-paper A/B note + (for OI/breadth) the derived-history caveat comment
    in the YAML header. **Not part of this plan's delivery** — it is a behavioural strategy change
    the owner must opt into per the "tune on live, not backtest" principle (MEMORY: scalper-tuning).

Each PR: short-lived `feat/` branch, Conventional Commit scoped `strategy-signal`, squash-merge,
single final PR (per CLAUDE.md trunk-based rules). Build services with the full reactor + `-am`
(`-pl services/strategy-signal-service -am verify`).

### 7.2 Risk register
| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Constructor-arity break missed in a CFG literal | Medium | Compile fail (caught instantly) | Update all 8 literals in the same PR; `mvn verify` is the gate. Pure compile-time fan-out, not a runtime/parity risk. |
| New gate accidentally armed on a shipped YAML | Low | A live strategy silently stops firing | The `ScalperStrategyLoadTest` OFF-assertions (§5.3) fail if any tag is present; PR-1/PR-2 ship zero YAML edits. |
| Golden/parity drift | Very low | Frozen vectors break | The gate is LIVE-only, not on the engine harness path; the two tripwires (§5.4) prove byte-identity. No fixture is touched. |
| WI-1 block-test fixture also drops the non-gated aggregate below 0.6 | Low | non-Xxx test flips to empty | §5.2 note: choose the single-operand flip that keeps aggregate ≥ 0.6; run the test and adjust the fixture. |
| Fail-open on null (basis) makes the gate a no-op on history | Expected | Weak live filter, not a bug | Documented in-line (§4.4) and as an Open Point; acceptable for v1, matches #5/VIX precedent. |
| Derived-history NEUTRAL blocks all backtests if WI-2/WI-3 armed | N/A in PR-1/2 | Would gut a backtest | WI-2/WI-3 are NOT armed in this plan; PR-3 arms forward-paper variants only, with the caveat comment. |

### 7.3 Backout
Each PR is purely additive and default-off. Backout = revert the squash commit; nothing persisted,
no migration to roll back, no fixture changed, no API surface altered. A partially-merged PR-1 with
no PR-3 leaves the codebase behaviourally identical to today (the flags exist but are never armed).

---

## 8. Open Points

1. **VIX directional gate (recon candidate 4).** `MarketOiClient.macro()` passes `vixLevel=null,
   vixRising=null`, so `ScalperGates.vix` always degrades to pass — a `vix-gate` tag would be a
   live no-op until the feed is wired. **Options:** (a) defer entirely to a separate "wire the VIX
   feed" plan (the India VIX candle series already read by `ConnectingDotsService.vixByBucket`
   needs threading into `macro()`); (b) ship a `vix-gate` tag now that is inert until the feed
   lands. **Recommended default: (a) defer** — promoting a starved gate adds dead config. Not in
   this plan's scope.

2. **Dow / global-cue dot (recon candidate 5).** There is NO Dow dot in `ConnectTheDotsScorer`
   today; adding one would change the aggregate denominator (L100-109) for every bar and **break
   the scorer math**, which — while still invisible to the engine goldens — would change live
   confluence for every scalper. This is a *dot-addition*, not a *gate-promotion*, and is
   categorically different from the four items here. **Recommended default: separate plan**
   (wire Dow into `Macro` + add the dot deliberately, accepting the live-confluence shift). Out of
   scope.

3. **Fail-open vs fail-closed for `basis-gate` (WI-4).** `futuresBasis` degrades to pass on null
   (L166-167), so the gate never blocks on missing/derived-history data. **Options:** (a) keep
   fail-open (matches #5/VIX, parity-friendly on backtests) — **recommended default**; (b) add a
   strict `futuresBasisStrict` overload that fails-closed on null for a `basis-gate-strict` tag.
   Recommend (a) for v1; (b) only if the owner wants basis to be a hard live filter that also
   suppresses history.

4. **Where to place new record fields / constructor order.** Appending after `requireStraddle`
   keeps existing positional reads stable but means the constructor tail grows. **Options:**
   (a) append (recommended — minimal churn, clear "FU2" cluster at the end); (b) group all
   `requireXxx` flags by theme (larger diff, touches more literals). Recommend (a).

5. **Should PR-1 and PR-2 be one PR or two (or four)?** They are mechanically identical and all
   default-off. **Recommended default: two PRs** (PR-1 = indicator-alignment, the documented
   high-value item, reviewed on its own; PR-2 = the three OI/macro gates as a batch). Splitting
   PR-2 into three is optional and only if review granularity is wanted.

6. **Arming the tags on real strategies (PR-3).** The audit recommends `indicator-alignment` on the
   two-candle family specifically (two-candle.md L64). **Options:** (a) leave entirely to the owner
   as a forward-paper experiment (recommended — consistent with "tune on live, not backtest");
   (b) pre-arm `indicator-alignment` on a single nifty variant in this plan. Recommend (a) — keep
   PR-1/PR-2 purely infrastructural and behaviourally inert.

7. **Optional FE affordance for "this dot is a hard gate".** Today the FE renders all dots
   uniformly (▲/▼). A gated dot could show a lock/asterisk so the operator knows it was a *block*,
   not just a soft contributor. **Options:** (a) no FE change (recommended for PR-1/PR-2 — nothing
   functional changes and the payload is unchanged); (b) extend `ConfluenceDot` with an optional
   `hard?: boolean` (a payload + FE change → a contract touch, deferred). Recommend (a); revisit
   with PR-3 if armed gates ship.

8. **Positive deterministic coverage of the gate (a scalper golden).** Not built today; would
   require driving the LIVE-only gate through `TickwiseGoldenRunner`. **Options:** (a) rely on the
   seam unit tests (recommended — they give pass/block/unaffected coverage); (b) invest in a
   scalper golden harness (large, separate). Recommend (a).

9. **(AUDIT pass 1) `close == vwap` edge for WI-1.** The side decision at L150-152 routes `close == vwap`
   to CE (`>=`), but `ScalperGates.indicatorAlignment` CE-branch requires `gt(close, vwap)` (STRICT — L106).
   So an armed `indicator-alignment` gate BLOCKS a `close == vwap` bar even though the side is CE. This is
   correct/intended (alignment demands price strictly beyond VWAP) and parity-irrelevant (live-only), but
   the executor should be aware the gate is marginally stricter than the side decision on the exact-equal
   tick. No action needed; documented for completeness.

10. **(AUDIT pass 1) WI-1 gate vs soft-dot read different sources IN THE SEAM TEST.** The WI-1 gate reads
    the bank-derived `chart` local (L124); the scorer reads `ctx.chart()` from `client.context(...)`. In
    production `context(...)` is built WITH that same `chart` (L192 passes it), so they agree. But the
    seam test mocks `client.context(...)` to return a fixed `bullContext()` regardless of args — so the
    WI-1 block fixture (`misalignedPsarBank()`) flips the GATE's psar without touching the mocked scorer's
    psar dot, leaving the aggregate unchanged. Consequence: the "drop one dot below 0.6" tuning worry in
    §5.2 does NOT apply to WI-1; it applies only to WI-2/3/4 (whose operands ARE scored dots inside the
    mocked `ctx`). The §5.2 supertrend-flip fallback is therefore a WI-2/3/4 concern.

---

## Audit pass 1 findings

Verdict: **sound-with-open-points**. The plan's architecture (early-return hard gate behind a
default-off tag, the #5 `oi-cross-filter` template, no scorer-signature change, no golden touched) is
correct and parity-safe. Every parity-critical claim was re-verified against the working tree and holds:
the engine goldens (`GoldenDeterminismTest.FEATURES` L33-36, `BacktestParityTest.FEATURES` L35-38) carry
ONLY the 5 pure-engine YAMLs (no scalper), the gate is consulted solely via
`Optional<ScalperConfluenceGate>` in `SignalEngine` (L459-462) on the scalper path, and the four new tags
are absent from all 36 shipped YAMLs — so emitted signals and both golden harnesses stay byte-identical.
**GoldenDeterminismTest and BacktestParityTest would pass unchanged.** No DB/contract/FE change is needed.

### Citations corrected (all were verified file-by-file)
- **§2.1 dot count: "19 dots" → 18.** `ConnectTheDotsScorer` adds 18 dots (L74-98), not 19. Added the
  enumerated list and the denominator math (Σ weights = 20.6) the §5.2 reasoning depends on.
- **§2.4 / §5.2 / §7.1 / risk register: "9 `new ScalperConfig(...)` literals" → 8.** `ScalperConfluenceGateTest`
  has exactly 8 (CFG, TWO_CANDLE, OI_CROSS, GAP, TREND_CHANGE, OPEN_HIGH_LOW, OPENING_TICK, STRADDLE; grep-confirmed
  at L44/49/56/62/68/74/81/87). The arity fan-out is correctly scoped — no third literal source exists
  (the two `ScalperConfig.from(...)` call sites do not break on arity).
- **§3 WI-4 doc ref: "two-candle.md L33 region" is the VIX row, not basis.** Basis is not enumerated as
  its own audit line; re-pointed WI-4's mandate to README §4 L538 + `ConnectTheDotsScorer.java:93`.

### Soundness issue fixed
- **§5.2 `misalignedPsarBank()` called a non-existent `barOf(builtins, aliases)` factory** — it would not
  compile. `ScalperConfluenceGateTest` has no `barOf`; every bank inlines an anonymous `new BarValues(){...}`
  (`bullBank()` L115-135, `closeEqualsVwapBank()` L606-626). Rewrote the example to inline the anonymous class.

### Verified ACCURATE (no change)
- `ScalperGates`: `indicatorAlignment` L102-118 (CE strict `gt`, supertrendDir>0), `oiQuadrant` L121-125,
  `breadth` L128-133, `vix` L136-143 (unknown→pass L137-138), `callPutDeltaFilter` L151-161 (null→pass
  L153-155), `futuresBasis` L164-171 (null→pass L166-167), `gt` L173-175.
- `ScalperConfluenceGate`: `evaluate` L100-280, time L112-118, side decision L149-152, `Chart chart` local L124,
  `chart(...)` L304-316, volume/RSI L157-163, two-candle L167-169, gap L173-179, `ctx` L191-192, #5 L196-199,
  trend-change L204-211, open-high-low L218-229, hero-zero L236-245, straddle branch L132-147 (returns before
  the side decision — the new gates never reach it), `vwapHardGate` L249, score+validity L250-253. The `Chart`
  record (`ScalperGateContext` L21-28) exposes `close/vwap/vwma20/psar/supertrendDir`, so
  `indicatorAlignment(chart, side)` typechecks at the insertion point; `Oi`/`Macro` expose the operands the
  WI-2/3/4 gates need.
- `ScalperConfig`: record L36-52, `from` L101-157, tag parses (two-candle L119, gap L121, trend-change L123,
  open-high-low L125, opening-tick L128, hero-zero L131, straddle L135, entry-candle-stop L149, oi-cross-filter
  L153), constructor return L154-156, THRESHOLD=0.6 L88. The proposed field/parse/constructor edits are positionally
  correct.
- `ConnectTheDotsScorer`: weights L32-36, score signature L63-65, aggregate L100-109, biasAligned L111, validity
  L114-115. Plan correctly adds NO term here.
- Golden/parity: `GoldenSignalsJson.write` L41-70 + `SignalEvent` side-channel L27-34 (stopLoss/takeProfit not
  serialized — the parity-safe precedent); `GoldenDeterminismTest` FEATURES L33-36 + generate-once L61;
  `BacktestParityTest` FEATURES L35-38 + the 3 byte-match asserts L67-78.
- Side-channel/API/FE: `scalperDetailJson` L667-706 (confluence_aggregate L679, dots[] L680-686),
  `SignalRepository.stampScalperDetail` L184-190 + read-back L172, `signals.ts` ConfluenceDot/ScalperDetail
  L40-68, `ManualVerifyChecklist.tsx` dots row L73-95. `SignalEmitted.ScalpDetail` (L36) confirmed real.
- Test cites: `ScalperGatesTest.indicatorAlignmentNeedsAllOnTheCorrectSide` L85-97, `oiQuadrantMatchesSide`
  L100-107, `breadthThirtyTwoCutoff` L110-115, `futuresBasisPremiumBullDiscountBear` L126-131 (null→pass L130).
  `ScalperConfluenceGateTest`: #5 template L292-312, non-Xxx templates L458-468/L585-597, `bullBank()` L115-135,
  `bullContextWithImbalance` L148-156. `ScalperStrategyLoadTest`: `from` call L130, per-family assertions L150-159.
- Doc refs: two-candle.md L27 (indicatorAlignment NOT called), L28 (oiQuadrant soft dot), L35 (breadth Adv>32),
  L64 (alignment-conjunction gap); trend-change.md L62 (alignment-as-hard-gate); connect-the-dots.md §3.10
  Setup/Entry 7 (oiQuadrant); README §4 (`docs/strategy-audit/README.md`) L538 ("Promote soft dots to hard gates").
  YAML tag lines: `scalp-two-candle-nifty.yaml` L13, `scalp-trending-oi-nifty.yaml` L12. Seeder loads
  `/scalper-strategies/<id>.yaml` (L113), STRATEGIES L36. All confirmed.

### Parity verdict
No parity hole. The four promotions are opt-in (new tag, absent from every shipped YAML), live-only
(`ScalperConfluenceGate` never runs on the deterministic replay path), and add NO term to
`ConnectTheDotsScorer.score`/`valid` and NO field to the serialized golden. With no golden/parity YAML
carrying a scalper tag, the gate cannot perturb the frozen vectors. The §5.4 instruction (re-run both
tripwires, do NOT regenerate) is the correct guard. **One nuance added (Open Point 10):** in the seam
TEST the gate and the soft dot read different sources (bank-`chart` vs mocked `ctx.chart()`), so the
WI-1 fixture cannot accidentally drop the soft-dot aggregate — the §5.2 below-0.6 worry is a WI-2/3/4
concern only.

### Completeness gaps noted (added as Open Points 9–10 / inline)
- The `close == vwap` strict-vs-`>=` edge for WI-1 (Open Point 9) — benign, documented.
- The seam-test source-decoupling nuance for the §5.2 aggregate worry (Open Point 10).
- The `misalignedPsarBank()` compile fix (no `barOf` helper) — corrected inline in §5.2 and §7.1.

No write-path, contract, or FE binding was found missing: the dots payload shape is unchanged, so the
"NONE" verdicts in §6 (migration / schema / springdoc / golden) all hold.

---

## Audit pass 2 findings

Verdict: **sound-with-open-points**. A fresh, independent re-verification (every cited file opened
against the working tree, not trusting pass 1) confirms the architecture is correct and parity-safe,
re-confirms all three pass-1 corrections, and found ONE new arithmetic error that both the author and
pass 1 missed (now fixed in place). No design or parity hole.

### New error found + corrected (missed by author AND pass 1)
- **§2.1 denominator: `20.6` → `19.6`; "15×1.0 unit dots" → "14".** Pass 1 fixed the dot COUNT
  (19→18) but left the denominator arithmetic wrong. Enumerated independently: 18 total dots − 4
  weighted (vwap @2.5, futures_oi @1.5, iv_rank @0.8, iv_pair @0.8) = **14** unit-weight dots, so
  Σ weights = `2.5 + 1.5 + 0.8 + 0.8 + 14×1.0 = 19.6`. The stated `15×1.0 = 20.6` over-counts by one
  unit dot. Propagated fixes: the "passing CFG clears 0.6" weight `12.36 → 11.76` (0.6 × 19.6), and the
  per-dot delta `1.0/20.6 ≈ 0.049 → 1.0/19.6 ≈ 0.051`. Corrected in §2.1 and the §5.2 verify note.
  This was load-bearing ONLY for an executor who recomputes the headroom by hand; the conclusion
  (dropping one dot keeps the aggregate ≥ 0.6) is unaffected — see the next item.

### Independently re-derived the §5.2 headroom (the plan never actually computed it)
Enumerated the supporting dots of the real `bullContext()` fixture (CE side, from
`confluenceConfirmsAndPicksTheInBandCe`): vwap 2.5 + futures_oi 1.5 + iv_rank 0.8 + (10 supporting
unit dots: supertrend, vwma, psar, rsi, volume, underlying_oi, sentiment, breadth, vix, basis) = **14.8**
supporting weight. Aggregate = **14.8 / 19.6 = 0.7551**. The non-gated `CFG` PASS test for each
promotion therefore holds with margin:
- **WI-2** (clone `futures = SHORT_BUILDUP`): drops `futures_oi` @1.5 → `13.3/19.6 = 0.6786` ≥ 0.6 ✓
- **WI-3** (`advances = 10`): drops `breadth` @1.0 → `13.8/19.6 = 0.7041` ≥ 0.6 ✓
- **WI-4** (`futuresBasis = -5`): drops `basis` @1.0 → `13.8/19.6 = 0.7041` ≥ 0.6 ✓; the null-basis
  variant leaves the aggregate at 0.7551 (basis dot degrades to pass), proving the documented fail-open.
- **WI-1**: the gate reads the bank-`chart`, the scorer reads the mocked `ctx.chart()`, so the aggregate
  never drops at all (Open Point 10 — re-confirmed correct). The §5.2 supertrend-flip fallback is genuinely
  never needed for ANY of the four with the current fixtures — but keep the "run the test" caution, since
  a future fixture edit could erode the ~0.08 margin on WI-2.

### Pass-1 corrections re-verified — all three correct, no new error introduced
- Dot count **18** (not 19): re-counted the `add(dots, …)` calls — 18, grep-confirmed. ✓
- **8** `new ScalperConfig(...)` literals in `ScalperConfluenceGateTest` (not 9): CFG L44 / TWO_CANDLE L49
  / OI_CROSS L56 / GAP L62 / TREND_CHANGE L68 / OPEN_HIGH_LOW L74 / OPENING_TICK L81 / STRADDLE L87, each
  16 args. The two `ScalperConfig.from(...)` call sites (`ScalperStrategyLoadTest` L130, `SignalEngine`
  L195) don't break on arity. ✓ The proposed `INDICATOR_ALIGNMENT_CFG` literal in §5.2 has the right
  17-arg shape.
- WI-4 doc-ref: two-candle.md **L33 is the VIX row** (verified), basis has no own audit line — the §3
  re-point to README §4 L538 is right. ✓
- `misalignedPsarBank()` inline `new BarValues(){…}` (no `barOf` factory): re-confirmed — `bullBank()`
  L115-135 inlines the anonymous class; no `barOf` exists. ✓

### Parity argument re-checked end-to-end (independently)
- `grep ScalperConfluenceGate` across the repo: referenced ONLY in `strategy-signal-service`
  (main + test), docs, and YAMLs — **never in `libs/strategy-engine` or `services/backtest-service`**.
  The gate lives solely in the LIVE `SignalEngine` (`Optional<ScalperConfluenceGate>`, consulted at
  L459-462). Deeper than the plan states: the gate does not run on ANY backtest (golden OR real-strategy
  replay), because `TickwiseGoldenRunner`/`ReplayEngine` never instantiate it — so an armed tag is inert
  on every deterministic run, not merely "invisible because no golden YAML is a scalper". Both conclusions
  reach the same place; the goldens cannot be perturbed.
- `GoldenDeterminismTest.FEATURES` L33-36 and `BacktestParityTest.FEATURES` L35-38 each carry exactly the
  5 pure-engine YAMLs (ema-crossover, optional-indicator-activation, btst-preclose, exit-intrabar,
  context-series) — no scalper. ✓
- `grep` for `indicator-alignment|futures-oi-gate|breadth-gate|basis-gate` across all **36** shipped
  scalper YAMLs (count independently verified): **zero matches**. Every config is byte-identical today,
  default-OFF. ✓
- `ConnectTheDotsScorer.score` signature (L63-65) and `valid` (L114-115) are untouched by the plan; no
  serialized golden field is added (`SignalEmitted.ScalpDetail` + FE `ConfluenceDot`/`ScalperDetail`
  unchanged — re-confirmed). §6's four "NONE" verdicts hold.

### New open point (framing, not a defect)
- **WI-3 / WI-4 doc backing is weaker than WI-1 / WI-2.** README §4 L538-539 names the audit's
  recommended next hard-gate promotions as: indicator-alignment, the ≥50% OI imbalance, the Trending-OI
  cross, and drastic-ΔOI — NOT breadth or basis. WI-1 (indicator-alignment) is squarely on that list and
  WI-3 (breadth) at least has the two-candle.md L35 soft-dot row, but **WI-4 (basis) and the README's
  named trio (OI-imbalance / Trending-cross / drastic-ΔOI) diverge**: the plan picked the four soft dots
  with a *ready-made `ScalperGates` function* (oiQuadrant/breadth/futuresBasis), not the four the audit
  names. That's a legitimate "lowest-friction first" choice and the §3 WI-4 note already concedes basis
  has no audit row — but the executor should know WI-3/WI-4 are author-prioritised, not audit-mandated,
  and that the README's literal next-three (imbalance-as-its-own-gate beyond #5, Trending-OI cross,
  drastic-ΔOI) remain unpromoted after this plan. No action required; recorded so a later "did we do what
  the audit asked?" check doesn't mistake this plan for completing the README §4 list.

### Readiness verdict
**sound-with-open-points** — implementation-ready. The single arithmetic defect is corrected; every
parity-critical claim independently re-verified true; the four promotions are opt-in, default-OFF,
live-only, and add no scorer-signature / serialized-golden / contract / FE / migration change. The
open points (fail-open basis, WI-3/WI-4 audit-backing framing, the unpromoted README trio, optional FE
affordance, scalper-golden coverage) are deferrable design choices the owner opts into, not blockers.
