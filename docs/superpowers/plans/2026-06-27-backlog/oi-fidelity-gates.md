# OI fidelity: promote OI reads to hard gates + new OI dots

Status: PLAN (implementation-ready). Owner: single-owner. Target services:
`services/strategy-signal-service` (scalper confluence seam — primary) and
`services/market-data-service` (one optional analytics-magnitude addition). Date: 2026-06-27.

> Read order for the executor: this plan is self-contained but **copies the FU2 plan's exact
> parity-safe-additive shape** — read
> `docs/superpowers/plans/2026-06-27-followup2-soft-dots-to-hard-gates.md` §2/§4.0/§5 first if you
> have not. The `#5 oi-cross-filter` gate (`ScalperConfluenceGate` L196-199 / `ScalperConfig` L153 /
> `ScalperGates.callPutDeltaFilter` L151-161) is the load-bearing precedent; every `[P]` change here
> is that template applied to a new OI rule. All line numbers were opened and confirmed against the
> working tree on 2026-06-27.

---

## 1. Goal & the packages/gaps this stream closes

This stream is the **OI-fidelity slice** of the `AUTOMATE_PKG` backlog
(`docs/strategy-audit/GAP-DISPOSITION.md` §3): take the OI reads that are currently **soft confluence
dots** (or inert degrade-to-pass gates) and promote them to **opt-in hard pre-gates**, plus add the
**missing OI temporal primitives** (divergence-magnitude %, slope-flip arrow, flat-OI stand-aside,
interval/60m read, dynamic strike re-centre, max-OI S/R). The defining strategy is Siva #5 Trending-OI
Crossover, but several dots are shared across #1/#2/#12.

The defining caveat (carried from `docs/strategy-audit/trending-oi.md` L10-13 and CLAUDE.md): **every
OI dot degrades to NEUTRAL on derived history** and a null imbalance degrades the cross gate to PASS,
so these gates are a **FORWARD-paper discriminator** — judge by code presence and live behaviour, not a
backtest. Each `[P]` gate, when armed on a backtested YAML, would block almost every historical signal;
that is why arming is deferred (PR-LAST, owner-driven) exactly as FU2 §7 PR-3 defers its arming.

### Packages and their gap counts + doc-§ (from GAP-DISPOSITION.md §3a/§3c + the per-dimension files)

| # | Package | gaps | Doc-§ (audit row) | Source disposition file | P/S |
|---|---------|-----:|-------------------|-------------------------|:---:|
| P1 | **`oi-cross-hard-gate`** | 8 | trending-oi §3.5 Entry-Bull.2 / Setup.2 / S22(b); gates-strike-sr-fiidii §4.9; intro-terminology §1.2 OI; completeness-sweep §5.8 | trending-oi.md L18-19,L31; gates-strike-sr-fiidii.md L17-18; intro-terminology.md L28; completeness-sweep.md L18,L25 | **[P]** |
| P2 | **`intraday-positional-oi`** | 11 | trending-oi §3.5 Exit.time / S21(d) / S21(f); two-candle §3.1 S21(g); + market-movers/morning-trade/hero-zero/session-additions legs | trending-oi.md L33-34,L39; two-candle.md L18 | **[P]** (the score-tie legs are `[S]` sizing — see §4) |
| P3 | `oi-divergence-magnitude` | 2 | trending-oi §3.5 S21(a)/S22 + Setup.4/Entry.4 | trending-oi.md L20,L23 | **[P]** |
| P4 | `flat-oi-stand-aside` | 1 | trending-oi §3.5 Setup.5-caveat / Edge-cases | trending-oi.md L32 | **[P]** |
| P5 | `oi-direction-change-arrows` | 1 | trending-oi §3.5 S21 / Filters | trending-oi.md L35 | **[P]** |
| P6 | `oi-quadrant-avoid-veto` | 1 | (single-gap pkg, §3c) — both-sides-heavy-OI strike avoid | GAP-DISPOSITION.md §3c L166; gates-strike-sr-fiidii.md L17 | **[P]** |
| P7 | `oi-both-sides-consolidation` | 1 | (single-gap pkg, §3c) — both sides building = consolidation stand-aside | GAP-DISPOSITION.md §3c L167 | **[P]** |
| P8 | `max-oi-sr-gate` | 1 | (single-gap pkg, §3c) — max-OI strike as a wall the entry must not trade into | GAP-DISPOSITION.md §3c L166 | **[P]** |
| P9 | `oi-support-resistance` | 1 | gates-strike-sr-fiidii §4.11 (OI-derived S/R, distinct from chart S/R) | GAP-DISPOSITION.md §3c L167 | **[P]** |
| P10 | `drastic-oi-floor` | 1 | trending-oi §3.5 Entry-Bull.5 — calibrate `drasticFloor` per index | GAP-DISPOSITION.md §3c L163 | **[P]** |
| P11 | `price-move-per-oi-demand` | 1 | trending-oi §3.5 Setup.4 / Entry.4 — price-impulse % over the cross window | GAP-DISPOSITION.md §3c L158 | **[P]** |
| P12 | `oi-interval-and-60m-trend` | 1 | trending-oi §3.5 Instruments/Setup.1 — explicit 5-15m analytics interval + a 60m OI broader-trend read | trending-oi.md L22,L26 | **[P]** |
| P13 | `trending-oi-strike-window` | 1 | trending-oi §3.5 S21(e) (`dynamic-strike-recenter`) — reset to ATM±7 on >1% move | trending-oi.md L36,L40 | **[P]** |
| P14 | `trending-oi-window-fidelity` | 1 | trending-oi §3.5 Setup.1 / §6.5 timeframe — the windowed-dOI vs day-cumulative reading | GAP-DISPOSITION.md §3c L160; completeness-sweep.md L29 (UNCERTAIN_OWNER) | **[P]** |
| P15 | `fake-cross-side-flip` | 1 | trending-oi §3.5 Exit.stop_loss / edge_cases | trending-oi.md L28 | **[S]** (exit/management; new path, no existing golden) |
| P16 | `incomplete-cross-reject` | 1 | trending-oi §3.5 Exec-notes / Edge-cases | trending-oi.md L30 | **[P]** |
| P17 | `same-candle-crossover-event` | 1 | (single-gap pkg, §3c) — the cross + price-thrust in the SAME bucket | GAP-DISPOSITION.md §3c L154 | **[P]** |

**Total: ~34 gaps across 17 packages.** (Counts: P1=8, P2=11 — of which 3 are trending-oi rows + 8 ride
in from market-movers/morning-trade/hero-zero/session-additions per GAP-DISPOSITION §3a L119 — plus 15
single/double-gap packages.) Several packages **share one mechanism** (a new `requireXxx` flag + a new
`ScalperGates` function); §4 groups them so the executor builds ~6 reusable gate primitives, not 17
disjoint ones.

### What does NOT change (parity firewall, copied from FU2 §1)
- **The scorer stays soft.** `ConnectTheDotsScorer.score(...)` signature (L63-65), the 18-dot list
  (L74-98), the weights (L32-36), the aggregate math (L100-109) and the `valid` rule (L114-115) are
  **untouched**. No new AND-term in `valid`, no new dot in the unarmed path. Every new behaviour is an
  **early-return hard gate** in `ScalperConfluenceGate.evaluate(...)`, armed by a new default-OFF tag.
- **No golden/parity fixture is regenerated.** The 5 engine goldens
  (`GoldenDeterminismTest.FEATURES`, `BacktestParityTest.FEATURES`) carry no scalper strategy and never
  instantiate `ScalperConfluenceGate`/`ConnectTheDotsScorer` (FU2 §2.6). A tag-gated gate cannot
  perturb them provided no golden/parity YAML carries the new tag (none can).
- **No new YAML carries a new tag in this work.** Arming real strategies is the deferred owner step
  (PR-LAST), per the "tune on live, not backtest" principle.
- **No DB migration; no springdoc contract drift.** New `ScalperConfig` fields are in-memory; the new
  market-data `divergencePct`/`maxOiStrike` fields ride the existing generic `Map<String,Object>` /
  record returns (CLAUDE.md: response-key additions to `Map` returns do NOT drift the spec). One new
  query *param* on `/options/trending` (the explicit `interval`) IS a spec touch — see §3 P12 and §6.

---

## 2. Current state (cited file:line — verified by opening each)

### 2.1 The scorer — `services/strategy-signal-service/.../scalper/ConnectTheDotsScorer.java`
- 18 dots added L74-98. OI-relevant: `futures_oi` @1.5 (L80), `underlying_oi` (L81), `trending_cross`
  (L83, helper L125-134), `sentiment` (L84), `drastic_oi` (L86, helper L141-153), `sentiment_slope`
  (L88), `oi_spurt` (L90, helper L159-167).
- `trendingCross` (L125-134): supports only when `(crossedThisWindow OR gapWidening)` AND the
  signed-delta pair favours the side; null deltas never confirm. **This is the only place the cross is
  consulted, and only as a soft dot (weight 1.0).**
- `drasticOi` (L141-153) reads `props.drasticFloor()` (default 50000, `ScalperOiProps` L36).
- Aggregate denominator = Σ weights = `2.5+1.5+0.8+0.8+14×1.0 = 19.6`; `THRESHOLD=0.6`.

### 2.2 The OI carrier — `scalper/ScalperGateContext.java`
- `record Oi(...)` (L39-52) carries: `underlying`/`futures` quadrants, `sentimentPct`,
  `trendingPeMinusCePct`, `futuresBasis`, **`ceOiDelta`, `peOiDelta`, `callPutDeltaImbalancePct`,
  `crossedThisWindow`, `gapWidening`, `sentimentSlope`, `spurtOiPct`, `spurtPricePct`**. Every temporal
  field is null/false when its series is short/absent. **New OI fields (divergence %, slope-flip arrow,
  60m-trend dir, max-OI strike) extend THIS record** (a fan-out to every `new Oi(...)` site — there are
  2: `MarketOiClient` L271-273 the suppression path + L322-335 the normal path).

### 2.3 The OI producer — `scalper/MarketOiClient.java`
- `deriveTrending(JsonNode)` (L456-488) computes `ceOiDelta`/`peOiDelta` (last−first), `imbalancePct`
  (L508-514, FLAT-OI → null), `crossed` (sign transition of `peOi−ceOi`, L484), `gapWidening`
  (`|gapLast| > |gapPrior|`, L485). **This is where divergence-% / monotonicity / slope-flip get
  computed** — all temporal math lives here so the scorer stays point-in-time (L431-434).
- `/options/trending` is fetched with **NO interval param** (L308-318) → market-data defaults to `M3`
  (`OiQuery.of` L21-22). The 60m broader-trend read is **not fetched at all** (only the 1h Supertrend
  `bias60m` from the engine bank, `ScalperConfluenceGate` L318-321).
- The monthly-expiry suppression path (L267-274) returns an all-inert `Oi` — any new `Oi` field must be
  added to BOTH constructors.

### 2.4 The hard-gate library — `scalper/ScalperGates.java`
- `oiQuadrant(Oi, side)` L121-125 (CE wants `futures().bullish()`); `callPutDeltaFilter(Oi, floorPct)`
  L151-161 (**null imbalance → PASS**, the #5 fail-open precedent). New OI gate functions land here as
  pure `GateOutcome` functions next to these.

### 2.5 The seam — `scalper/ScalperConfluenceGate.java`
- `evaluate(...)` L100-280. The side is decided L149-152 (CE when `close>=vwap`). `ctx` (carrying
  `ctx.oi()`) is built L191-192. **The #5 hard gate block is L196-199** — the canonical insertion
  shape: `if (cfg.requireXxx() && !ScalperGates.xxx(ctx.oi(), …).pass()) return Optional.empty();`.
  Every new OI gate inserts as a sibling block **after L192** (they all need `ctx.oi()`), co-located
  with #5 for readability.
- The OI chain for the confluence keys off `cfg.oiIndex()` (the niftyoi/sensexoi A/B), fetched at the
  `oiExpiry` (L187-192). The strike window is `atm_window width:3` from the YAML — **strike re-centre
  (P13) is a chain-fetch concern, see §4 P13.**

### 2.6 Tag → flag wiring — `scalper/ScalperConfig.java`
- `record ScalperConfig(...)` fields L36-52 (16 components today). `from(JsonNode, List<String>)`
  L101-157 maps tags to `requireXxx` booleans; the #5 line is L153 (`tags.contains("oi-cross-filter")`)
  and the constructor returns at L154-156. **Constructor arity is coupled to 8
  `new ScalperConfig(...)` literals in `ScalperConfluenceGateTest` (L43-90)** — each new field forces a
  trailing `false` in all 8 (a compile-time fan-out, not a parity risk; FU2 §2.4).
- `ScalperOiProps` (`scalper/ScalperOiProps.java`) holds the DB-tunable OI knobs (L18-29): `crossFilterPct`,
  `drasticFloor`, spurt floors, etc. **New thresholds (divergence %, price-impulse %, drastic per-index)
  are new `ScalperOiProps` fields** with documented defaults (the all-null `defaults()` factory L76-78
  fans out too).

### 2.7 Market-data analytics (the OI series producers — already exist)
- `OiTrendingService.trending(series)` (`market-data-service/.../analytics/OiTrendingService.java`)
  emits per-bucket `totalOi/ceOi/peOi/spot` + UP/DOWN/FLAT trend. `OiBigOiService.bigOi(latest, topN)`
  ranks legs by `|oiChange|` — **the max-OI strike (the S/R wall) is `bigOi().items[0].strike` already**.
- `OptionsAnalyticsController` (`.../analytics/OptionsAnalyticsController.java`): `/trending` (L212+),
  `/active-strikes` (L326+), `/big-oi`, all take an `interval` param via `OiQuery.of(...)`.
  `OiInterval` (`options/OiInterval.java`) already supports `M5/M15/M60` — **the 5-15m + 60m reads are
  query-param-only on the producer side; the consumer (`MarketOiClient`) just isn't passing them.**

### 2.8 The parity firewall (unchanged, per FU2 §2.6)
`GoldenSignalsJson.write()` serializes only the frozen signal fields; `GoldenDeterminismTest.FEATURES`
+ `BacktestParityTest.FEATURES` are the 5 pure-engine YAMLs (no scalper). Neither harness touches the
gate. This is why all `[P]` work below is byte-safe so long as no shipped YAML carries a new tag.

---

## 3. Design — per package, the exact change

The 17 packages reduce to **6 reusable mechanisms** + 1 analytics-magnitude field + 1 interval-wiring +
1 exit-path. Each `[P]` mechanism follows the identical FU2 shape: (a) a new `ScalperOiProps`/`Oi` field
or a new `ScalperGates` function, (b) a `requireXxx` flag on `ScalperConfig` + a `tags.contains(...)`
parse line, (c) an early-return block in `evaluate()` after L192, (d) the seam-test triple, (e) NO YAML
edit.

### M1 — `oi-cross-required` hard gate (closes P1 rows 1+2, P16, P17, P14 partly)
The cross is a soft dot today (`trendingCross` L83); the doc's defining trigger is a **required fresh
cross**. Promote it.

**`ScalperGates.java`** — new pure function (mirror `callPutDeltaFilter`):
```java
/**
 * The Trending-OI defining trigger as a HARD pre-gate: a REAL fresh cross this window AND the
 * signed-delta pair favours the side (CE: peΔ>0 && ceΔ<0). Unlike the soft `trending_cross` dot,
 * gapWidening alone does NOT satisfy this — it requires {@code crossedThisWindow}. Null deltas FAIL
 * (fail-closed) so a missing derivation cannot pass a fresh-cross requirement. (P16: a stalled cross
 * has crossedThisWindow=false → blocked.)
 */
public static GateOutcome oiCrossRequired(Oi oi, OptionType side) {
  boolean ce = side == OptionType.CE;
  boolean realCross = oi.crossedThisWindow()
      && oi.ceOiDelta() != null && oi.peOiDelta() != null
      && (ce ? oi.peOiDelta().signum() > 0 && oi.ceOiDelta().signum() < 0
             : oi.ceOiDelta().signum() > 0 && oi.peOiDelta().signum() < 0);
  return new GateOutcome(realCross, oi.callPutDeltaImbalancePct(),
      realCross ? "fresh OI cross favours " + side : "no completed OI cross for " + side);
}
```
> **P16 (`incomplete-cross-reject`)** is closed by the same gate IF `crossedThisWindow` already encodes
> a completed (not stalled) cross. It does — `deriveTrending` L484 tests a sign *transition* of
> `peOi−ceOi` from first→last, so an in-progress reversal that has not yet flipped sign reads
> `crossed=false`. The hard gate therefore rejects a stalled cross for free. **Strengthen** (optional,
> P16 fidelity): add an in-window per-bucket monotonicity flag (`oi.crossMonotonic`) computed in
> `deriveTrending` (require the favoured leg to rise on ≥N of the last K buckets, not just last-vs-first)
> — see Open Point #3.

**`ScalperConfig.java`** — `boolean requireOiCross` field + `tags.contains("oi-cross-required")` +
constructor thread (the M-pattern, identical to L153).

**`ScalperConfluenceGate.java`** — after L192, beside the #5 block:
```java
// P1: when armed, the Trending-OI defining trigger (a REAL fresh PE-over-CE / CE-over-PE cross
// favouring the side) is a HARD precondition, not just a soft dot. Fail-closed; NEUTRAL on history.
if (cfg.requireOiCross() && !ScalperGates.oiCrossRequired(ctx.oi(), side).pass()) {
  return Optional.empty();
}
```

**P14 (`trending-oi-window-fidelity`)** is the UNCERTAIN_OWNER reading (windowed-dOI vs day-cumulative,
completeness-sweep.md L29). The code already chose the **interval/windowed** reading (`deriveTrending`
over the `SERIES_WINDOW=20` bucket window). This package's only deliverable is a doc decision + a
javadoc note pinning that choice — **no code change** beyond a comment. Recorded as Open Point #5.

### M2 — `oi-slope-agree` hard sub-gate (closes P1 row "%-change AND slope must agree")
Today `sentiment` (level) and `sentiment_slope` are two independent soft dots; the doc requires they
**both agree**. New gate requiring slope-sign agreement with the side:
```java
/** P1: both the PE−CE %-change AND the sentiment slope must favour the side (a hard pair). */
public static GateOutcome oiSlopeAgree(Oi oi, OptionType side) {
  boolean ce = side == OptionType.CE;
  BigDecimal slope = oi.sentimentSlope();
  BigDecimal level = oi.sentimentPct();
  boolean ok = slope != null && level != null
      && (ce ? slope.signum() > 0 && level.signum() > 0
             : slope.signum() < 0 && level.signum() < 0);
  return new GateOutcome(ok, slope, ok ? "sentiment level+slope agree" : "level/slope disagree");
}
```
Flag `requireOiSlopeAgree` / tag `oi-slope-agree`, same insertion shape. **Fail-closed on null** (a
required conjunction). This shares the M-pattern with M1 — they can ship in the same PR (PR-A).

### M3 — `oi-divergence-magnitude` hard gate (closes P3, P11, P17, and `drastic-oi-floor` P10 indirectly)
Today only the boolean `gapWidening` exists; the doc thresholds **~20-30% immediate divergence / ≥50%
conviction**. Compute the divergence **%** in `MarketOiClient.deriveTrending` and threshold it in a gate.

**`MarketOiClient.java`** — add to `deriveTrending` (L456-488) a `divergencePct` =
`|gapLast| / max(totalOiLast,1) × 100` (the PE−CE gap as a % of the bucket's total OI) and a
`priceImpulsePct` is NOT here (price lives on the spurt path) — instead reuse `spurtPricePct`
(`oi.spurtPricePct()`) for the price-impulse leg (P11). Extend the `Trending` carrier (L440-448) +
`Oi` record with `BigDecimal oiDivergencePct`. Wire it through both `new Oi(...)` sites (L271-273 → null,
L322-335 → `trending.divergencePct()`).

**`ScalperOiProps.java`** — new fields `oiDivergenceMinPct` (default `"20"`) + `oiConvictionPct`
(default `"50"`) + `priceImpulseMinPct` (default `"50"`, reuse the spurt scale).

**`ScalperGates.java`**:
```java
/** P3/P11: the OI lines must diverge >= minPct AND a price impulse >= priceMinPct corroborates. */
public static GateOutcome oiDivergenceMagnitude(
    Oi oi, BigDecimal minPct, BigDecimal priceMinPct) {
  BigDecimal div = oi.oiDivergencePct();
  BigDecimal px = oi.spurtPricePct();
  boolean ok = div != null && div.compareTo(minPct) >= 0
      && px != null && px.abs().compareTo(priceMinPct) >= 0;
  return new GateOutcome(ok, div, ok ? "OI divergence + price impulse confirm" : "weak divergence/impulse");
}
```
Flag `requireOiDivergence` / tag `oi-divergence-magnitude`. **Fail-closed on null** (history has null
divergence → blocks → forward-only). **P17 (`same-candle-crossover-event`)**: closing the cross
(M1) and the price-impulse (M3) in the SAME armed strategy gives "cross + thrust in one window";
combining the `oi-cross-required` + `oi-divergence-magnitude` tags on one variant is the `same-candle`
semantics — no separate code, documented in PR-LAST. **P10 (`drastic-oi-floor`)** is a *calibration*
task on the existing `props.drasticFloor()` (50000 placeholder), not new code — make it **per-index**:
change `ScalperOiProps.drasticFloor` to a `Map<String,BigDecimal>` keyed by underlying (NIFTY vs SENSEX
absolute-OI scales differ), defaulting to 50000. See Open Point #2.

### M4 — `flat-oi-stand-aside` hard gate (closes P4, P7)
Today a null imbalance DEGRADES to PASS (the inverted caveat, trending-oi.md L32). When armed, a
**flat/null imbalance should STAND ASIDE (block)**, the doc's intent.
```java
/** P4: the doc's flat-OI trap — a null/flat call-put imbalance must STAND ASIDE, not pass. */
public static GateOutcome flatOiStandAside(Oi oi) {
  BigDecimal imb = oi.callPutDeltaImbalancePct();
  boolean ok = imb != null;          // present (non-flat) → pass; null/flat → block
  return new GateOutcome(ok, imb, ok ? "OI not flat" : "flat OI — stand aside");
}
```
Flag `requireNonFlatOi` / tag `flat-oi-stand-aside`. **This is the deliberate INVERSE of #5's fail-open**
— and is why it must be a separate opt-in tag (a strategy that wants the cross gate to fail-open keeps
#5; one that wants the doc's stand-aside arms this). **P7 (`oi-both-sides-consolidation`)**: both sides
building (both deltas positive, neither dominating) = consolidation. Add a sibling check in the same
gate: also block when `ceOiDelta>0 && peOiDelta>0 && imbalance < consolidationPct` (both building, no
imbalance) — one extra clause, same flag.

### M5 — `oi-direction-change-arrow` hard veto (closes P5)
A direction-change arrow = a **slope-sign flip** of the OI sentiment between the latest two buckets.
Compute a `sentimentFlip` (sign of `last−prior` differs from sign of `prior−beforePrior`) in
`MarketOiClient.deriveSentiment` (L526-538, currently only computes `last−first` slope). Add
`boolean sentimentFlip` to the `Sentiment` carrier + `Oi`. New gate:
```java
/** P5: a fresh OI direction-change (slope-sign flip) against the side is a veto. */
public static GateOutcome oiDirectionStable(Oi oi, OptionType side) {
  // a flip is only a veto if it flips AGAINST the side; same-direction flips are fine.
  boolean veto = oi.sentimentFlip()
      && (side == OptionType.CE ? oi.sentimentSlope() != null && oi.sentimentSlope().signum() < 0
                                : oi.sentimentSlope() != null && oi.sentimentSlope().signum() > 0);
  return new GateOutcome(!veto, oi.sentimentSlope(), veto ? "OI arrow flipped against " + side : "OI direction stable");
}
```
Flag `requireOiDirectionStable` / tag `oi-direction-change-arrows`. Fail-open on null slope (no flip
detectable → pass).

### M6 — `max-oi-sr-gate` / `oi-support-resistance` / `oi-quadrant-avoid-veto` (closes P6, P8, P9)
These are the **OI-wall** family: the strike with the largest OI is an S/R wall the entry must not trade
INTO, and a strike heavy on BOTH CE+PE OI is a low-probability "avoid". The max-OI strikes already exist
(`OiBigOiService.bigOi().items[0]`); surface them to the scalper.

**`MarketOiClient.java`** — extend the `oi(...)` read with a `maxCeOiStrike` + `maxPeOiStrike` (the
strikes carrying the largest CE / PE OI from the chain rows already fetched in `toChainSnapshot`, OR a
new `/big-oi` read). Add `BigDecimal maxCeOiStrike, maxPeOiStrike` to `Oi`.

**`ScalperGates.java`**:
```java
/** P8/P9: the entry strike must not sit AT/BEYOND the dominant OI wall on its side.
 *  For CE (bullish) the max-CE-OI strike is overhead resistance; block if spot >= it. Mirror PE. */
public static GateOutcome oiWallClear(Oi oi, BigDecimal spot, OptionType side) {
  BigDecimal wall = side == OptionType.CE ? oi.maxCeOiStrike() : oi.maxPeOiStrike();
  if (wall == null || spot == null) return GateOutcome.pass(spot, "no OI wall / spot");  // fail-open
  boolean ok = side == OptionType.CE ? spot.compareTo(wall) < 0 : spot.compareTo(wall) > 0;
  return new GateOutcome(ok, wall, ok ? "clear of OI wall " + wall : "into OI wall " + wall);
}
```
Flag `requireOiWallClear` / tag `max-oi-sr-gate`. **P6 (`oi-quadrant-avoid-veto`)** is the both-sides
variant: a separate gate `oiBothSidesHeavy(oi, strikeStat)` that vetoes when the candidate strike is in
the top-N OI on BOTH CE and PE (already-available chain OI). Flag `requireOiQuadrantAvoid` / tag
`oi-quadrant-avoid-veto`. **P9 (`oi-support-resistance`)** is the read-only surfacing of the OI S/R
levels into the `scalper_detail` side-channel for the operator (see §4 [S]) — distinct from the gate.

### M7 — `oi-interval-and-60m-trend` (closes P12, P13)
**P12 — explicit interval + 60m read.** Pass an explicit interval to `/options/trending`
(`MarketOiClient` L308-318) — add `.queryParam("interval", oiProps.trendingInterval())` (default `"5m"`,
the doc's 5-15m window). Add a SECOND `/options/trending?interval=60m` fetch and a
`int oiTrend60mDir` field on `Oi` (the UP/DOWN sign of the 60m total-OI series). New hard gate
`oi60mAgree(oi, side)` requires the 60m OI trend to agree with the side (fail-open on 0/unknown). Flag
`require60mOiAgree` / tag `oi-interval-and-60m-trend`. **NOTE this adds one query param to a
market-data endpoint** — a springdoc contract touch (§6).

**P13 — dynamic strike re-centre (`trending-oi-strike-window`).** Today the strike window is fixed
`atm_window width:3`; the doc resets to ATM±7 after a >1% intraday move. This is a **chain-fetch /
StrikePicker** concern, not a gate. The minimal change: when `oi-strike-recenter` is armed AND the
session move from open exceeds `recenterMovePct` (default 1%), widen the StrikePicker candidate window
to ±7 for that bar. Mechanically: `ScalperConfig` already carries `strikeParams`; add a
`recenterWidth`/`recenterMovePct` to `ScalperOiProps` and have `evaluate(...)` widen the candidate
filter before the `StrikePicker.pick(...)` call (L271-276). Flag `requireOiStrikeRecenter` / tag
`trending-oi-strike-window`. **[P]** because it changes which strike is picked → a different emitted
signal. (Lower-priority — see §7 PR-D.)

### Single-package summary table (mechanism → packages closed)
| Mechanism | New `ScalperGates` fn | New tag(s) | Packages closed | P/S |
|-----------|-----------------------|-----------|-----------------|:---:|
| M1 | `oiCrossRequired` | `oi-cross-required` | P1 (cross+failed-cross), P16, P17(½), P14(doc) | P |
| M2 | `oiSlopeAgree` | `oi-slope-agree` | P1 (slope-agreement) | P |
| M3 | `oiDivergenceMagnitude` | `oi-divergence-magnitude` | P3, P11, P17(½), P10(calib) | P |
| M4 | `flatOiStandAside` | `flat-oi-stand-aside` | P4, P7 | P |
| M5 | `oiDirectionStable` | `oi-direction-change-arrows` | P5 | P |
| M6 | `oiWallClear` + `oiBothSidesHeavy` | `max-oi-sr-gate`, `oi-quadrant-avoid-veto` | P6, P8, P9(gate) | P |
| M7 | `oi60mAgree` + recenter | `oi-interval-and-60m-trend`, `trending-oi-strike-window` | P12, P13 | P |
| X1 | (exit path) | `oi-fake-cross-flip` | P15 | S |
| X2 | (read-only side-channel) | — | P2 sizing legs, P9 read, P5 arrow surfacing | S |

---

## 4. PARITY classification

Every gate that adds an early-return to `evaluate()` **alters emitted signals when armed** → `[P]` →
behind a NEW default-OFF tag + (for arming) a NEW golden variant. Per the FU2 precedent (§2.6) and
CLAUDE.md, the existing 5 goldens stay byte-identical because no shipped YAML carries any new tag; the
gates are LIVE-only and never run on the deterministic replay harness.

| Change | Class | Tag (NEW, default-OFF) | Golden-variant plan when armed (PR-LAST) |
|--------|:-----:|------------------------|------------------------------------------|
| M1 cross-required | **[P]** | `oi-cross-required` | No new golden in PR-A/B/C (gate ships unarmed). When PR-LAST arms it on a forward-paper variant, add the YAML's `derived-history → NEUTRAL` caveat comment; goldens stay green (no golden YAML carries the tag). A positive *scalper* golden is out of scope (FU2 §5.6 — would need driving the LIVE-only gate through `TickwiseGoldenRunner`). |
| M2 slope-agree | **[P]** | `oi-slope-agree` | same as M1 |
| M3 divergence-magnitude | **[P]** | `oi-divergence-magnitude` | same as M1; ALSO the new `MarketOiClient.deriveTrending` divergence math is on the LIVE OI path only (never on replay), so it cannot perturb a golden. |
| M4 flat-oi-stand-aside | **[P]** | `flat-oi-stand-aside` | same; note this gate is the INVERSE of #5 fail-open — keep the two tags mutually exclusive in PR-LAST. |
| M5 direction-change-arrow | **[P]** | `oi-direction-change-arrows` | same |
| M6 OI-wall / both-sides | **[P]** | `max-oi-sr-gate`, `oi-quadrant-avoid-veto` | same |
| M7 60m-agree / recenter | **[P]** | `oi-interval-and-60m-trend`, `trending-oi-strike-window` | same; recenter changes the *picked strike* → distinctly perturbs the emitted `tradeable_*` columns, so its forward-paper A/B is the strongest discriminator. |
| X1 fake-cross-flip exit | **[S]** | `oi-fake-cross-flip` | An EXIT/management path (re-enters the opposite side after a confirmed opposite cross). It is a **brand-new sell/flip path with no existing golden** — `[S]` per the prompt's rubric. Still ships default-OFF (a tag) for safety; its determinism is exit-side, validated by a new exit unit test, not a signals golden. |
| X2 read-only surfacing | **[S]** | — | Read-only analytics (max-OI S/R levels + OI-arrow + positional-OI compare into `scalper_detail` JSON) + advisory `suggested_qty` sizing tie (P2). Does NOT change which signals fire → no golden impact. The `scalper_detail` JSON is a generic side-channel (FU2 §6 — Map/jsonb additions don't drift the spec). |
| M3 `MarketOiClient` divergence field | **[S]** (producer) | — | The new `oiDivergencePct` field is computed on the LIVE OI read only; adding it to the `Oi` record + `deriveTrending` is invisible to the goldens (no scalper on the replay path). |
| P12 `/options/trending?interval=` query param | **[S]** but **contract-touch** | — | A NEW query param on a market-data `@GetMapping` DOES drift the springdoc spec (CLAUDE.md) → re-capture `ContractCaptureTest` + regen TS. Behaviourally additive (defaults preserve today's M3). |

**Net:** 7 `[P]` mechanisms (M1-M7) each gated by a new default-OFF tag; 2 `[S]` (X1 exit path, X2
read-only). No existing golden is regenerated in any PR of this stream; arming (PR-LAST) is owner-driven
and still does not touch a golden YAML.

---

## 5. Tests

### 5.1 Unit — gate functions (`ScalperGatesTest.java`)
One test per new function, mirroring `oiQuadrantMatchesSide` / `callPutDeltaFilter`-null tests:
- `oiCrossRequiredNeedsCompletedCrossFavouringSide` — pass on `crossed && peΔ>0 && ceΔ<0` (CE); fail on
  `crossed=false` (P16 stalled), fail on null deltas.
- `oiSlopeAgreeRequiresLevelAndSlopeSameSign` — pass both-positive CE; fail on disagree; fail on null.
- `oiDivergenceMagnitudeNeedsBothThresholds` — pass div≥20 & |px|≥50; fail on weak div; fail on null px.
- `flatOiStandAsideBlocksNullImbalance` — pass on present imbalance; **block** on null (the inverse of
  the #5 fail-open test — assert the opposite of `callPutDeltaFilter`'s null case).
- `oiDirectionStableVetoesFlipAgainstSide` — veto on flip+slope-against; pass on no-flip; pass on null.
- `oiWallClearBlocksIntoWall` — CE: pass spot<wall, block spot≥wall, pass null wall (fail-open).
- `oi60mAgreeRequiresTrendAgreement` — pass dir>0 for CE; pass dir=0 (fail-open); block dir<0.

### 5.2 Unit — seam wiring (`ScalperConfluenceGateTest.java`)
This is where each promotion's *behaviour* is proved (FU2 §5.2). The constructor-arity change forces
appending a trailing `false` to **all 8 existing `new ScalperConfig(...)` literals** (L43-90) for each
new field. For EACH new tag add one CFG literal + the FU2 triple:
1. `<tag>StrategyPassesWhenOiConfirms` — context whose OI operand passes → `.isPresent()` + the picked
   `tradingsymbol` matches.
2. `<tag>StrategyBlocksWhenOiAgainst` — context whose OI operand fails → `.isEmpty()`.
3. `non<Tag>StrategyIsUnaffected` — the bare `CFG` (arms no new gate) against the same failing OI
   context → `.isPresent()` (proves the dot stays soft when the tag is absent).

Build the failing/passing `Oi` via the existing `Oi(...)` constructor in the test (e.g. `bullContext()`
clones with `crossed=false` for M1, `imbalance=null` for M4, `sentimentFlip=true` for M5,
`maxCeOiStrike` ≤ spot for M6). The "non-Tag unaffected" tests use the same `bullContext()` that already
drives a passing aggregate in `confluenceConfirmsAndPicksTheInBandCe`.

### 5.3 Producer unit (`MarketOiClientTest.java` — the `deriveTrending`/`deriveSentiment` unit)
- `deriveTrendingComputesDivergencePct` — a 3-bucket series → assert `oiDivergencePct` == the expected
  `|gapLast|/totalLast×100`; assert null when total OI is 0 (flat).
- `deriveSentimentFlagsSlopeFlip` — a series that rises then falls → `sentimentFlip=true`; a monotone
  series → `false`; <3 buckets → `false`.
- `oiPassesExplicitTrendingInterval` (WireMock) — assert the `/options/trending` request carries
  `interval=5m` and the 60m fetch carries `interval=60m`.

### 5.4 Load test (`ScalperStrategyLoadTest.java`)
Add an OFF-assertion per new flag inside the per-id loop (after L153), the regression tripwire that
PR-A/B/C/D arm nothing:
```java
assertThat(cfg.requireOiCross()).as(id + " oi-cross gate off (no tag shipped)").isFalse();
// ... one per new flag (oiSlopeAgree, oiDivergence, nonFlatOi, oiDirectionStable, oiWallClear,
//     oiQuadrantAvoid, 60mOiAgree, oiStrikeRecenter, oiFakeCrossFlip)
```

### 5.5 Golden / parity tripwires (MUST stay byte-identical — the load-bearing proof)
- `GoldenDeterminismTest` (`libs/strategy-engine`) — re-run; assert byte-identical green. **Do NOT
  regenerate.**
- `BacktestParityTest` (`services/backtest-service`) — re-run; the three byte-match asserts stay green.
- These prove the gate + the new `MarketOiClient`/`Oi` fields did not leak onto the deterministic
  replay path. No new golden variant is created in this stream.

### 5.6 Contract test (P12 only)
`ContractCaptureTest` (market-data) — the new `interval` query param on `/options/trending` drifts the
spec → re-capture with `-Dcontracts.capture=true`, regen TS via `npx openapi-typescript@7`, `tsc
--strict`. `ci-contracts` warns on the additive gen drift (a new optional param is non-breaking).

### 5.7 e2e
No new e2e in PR-A..D — all ship **default-OFF**, nothing observable changes; `signals.spec.ts` stays
green as a regression check (FU2 §5.5). When PR-LAST arms a tag on a published strategy, add an
assertion there that the confluence chip row (`ManualVerifyChecklist` dots) still renders for a gated
signal.

---

## 6. Dependencies & sequencing

1. **`Oi`-record fan-out is the spine.** M3/M5/M6/M7 each add a field to `ScalperGateContext.Oi`, which
   fans out to BOTH `new Oi(...)` sites in `MarketOiClient` (the suppression path L271-273 + the normal
   path L322-335) and to every test `Oi(...)` literal. **Land the record extensions FIRST within each
   PR**, then the gate that reads them — never a gate before its field exists (it won't compile).
2. **The producer (`MarketOiClient`) must compute a field before a gate can consume it** (e.g. M3's
   `oiDivergencePct` in `deriveTrending` precedes `oiDivergenceMagnitude`; M5's `sentimentFlip` precedes
   `oiDirectionStable`; M7's 60m fetch precedes `oi60mAgree`). Each PR contains both halves.
3. **M1/M2 have no new feed** (they reuse existing `crossed`/`deltas`/`sentiment` fields) → ship FIRST
   (PR-A), lowest risk, highest value (the defining Trending-OI edge).
4. **M6 (OI-wall) needs the max-OI strikes surfaced** — either from the chain rows already in
   `toChainSnapshot` (cheap, no new HTTP) or a new `/big-oi` read. Prefer the chain-rows derivation
   (no new endpoint dependency). M6 is independent of M1-M5.
5. **M7 (60m + recenter) is last** — it adds a market-data query param (the only contract touch) and a
   strike-pick change (the heaviest parity surface). Sequence after the no-feed gates prove out.
6. **SPAN / equity-universe gates are NOT in this stream** — every package here is index-options OI on
   the existing live capture; none needs the equity universe or SPAN. The short-side flip (X1) re-enters
   a LONG opposite leg (buy PE after a CE fake-cross), so it is **not** SPAN-gated (no sell leg).
7. **Arming (PR-LAST) is gated on forward-paper data** — per CLAUDE.md "judge OI-led strategies on
   FORWARD paper with real captured OI", the owner arms tags only after live OI accrues; this stream
   ships the infrastructure inert.

---

## 7. Effort + suggested PR breakdown

Overall effort: **M** (the stream is ~9 mechanically-identical FU2-shaped gates + 2 producer
derivations + 1 exit path; the heavy lifting is the repetitive seam-test triples and the `Oi`-record
fan-out, not novel design).

| PR | Scope | Mechanisms | Effort |
|----|-------|-----------|:------:|
| **PR-A** `feat(strategy-signal): OI cross + slope-agreement hard gates (tag-gated, default-off)` | M1 + M2 (no new feed) | `oi-cross-required`, `oi-slope-agree` | **S** |
| **PR-B** `feat(strategy-signal): OI divergence-magnitude + flat-OI stand-aside + direction-arrow gates` | M3 + M4 + M5 (+ the `deriveTrending` divergence + `deriveSentiment` flip producer fields; per-index `drasticFloor` calibration P10) | 3 tags + 2 producer fields | **M** |
| **PR-C** `feat(strategy-signal): OI-wall S/R + both-sides-heavy avoid gates` | M6 (+ max-OI strike surfacing from chain rows) | `max-oi-sr-gate`, `oi-quadrant-avoid-veto` | **M** |
| **PR-D** `feat(marketdata+strategy-signal): explicit 5-15m/60m OI interval + dynamic strike recenter` | M7 (the only contract touch + strike-pick change) | `oi-interval-and-60m-trend`, `trending-oi-strike-window` | **M** |
| **PR-E** `feat(strategy-signal): OI fake-cross side-flip exit + read-only OI-SR/positional surfacing` | X1 + X2 (`[S]`) | `oi-fake-cross-flip` + side-channel surfacing | **M** |
| **PR-LAST** (DEFERRED, owner-driven) `feat(strategy-signal): arm <oi-tag> on <forward-paper variant>` | append a tag to a chosen `scalper-strategies/*.yaml`; flip the matching `ScalperStrategyLoadTest` OFF-assertion to a per-id expectation; add the derived-history caveat comment | — | **S** |

Each PR: short-lived `feat/` branch, Conventional Commit scoped `strategy-signal` (or `marketdata` for
PR-D), squash-merge, full reactor `-am` build (`-pl services/strategy-signal-service -am verify`), the
two golden/parity tripwires (§5.5) green. PR-A..E are purely additive + default-OFF; backout = revert
the squash commit (nothing persisted, no migration, no fixture, no golden touched).

---

## Open Points

1. **M3 divergence denominator — `% of total OI` vs `% of the smaller leg`.** The doc says the lines
   "diverge ~20-30%" without defining the base. **Options:** (a) `|gapLast| / totalOiLast × 100` (gap as
   a share of total chain OI — stable, what §3 M3 proposes); (b) `|gapLast| / min(ceOi,peOi) × 100`
   (relative-to-the-thinner-side — more sensitive, matches "lines diverge"). **Recommended default: (a)**
   — it cannot blow up when one leg is tiny, and the threshold (20/50) is then interpretable. Revisit if
   forward data shows (a) rarely clears 20%.

2. **P10 `drasticFloor` per-index calibration.** Today one `drasticFloor=50000` for all indices; NIFTY
   and SENSEX absolute-OI scales differ. **Options:** (a) make `drasticFloor` a `Map<String,BigDecimal>`
   keyed by underlying (default 50000); (b) leave it a scalar and let DB-tuning handle per-strategy
   override. **Recommended default: (a)** — minimal, and the OI-led variants are exactly the NIFTY/SENSEX
   A/B that need different floors. Needs a real live-OI distribution sample to set the SENSEX value (the
   doc gives no number — same caveat as the existing placeholder).

3. **P16 incomplete-cross fidelity — endpoint-sign vs per-bucket monotonicity.** M1 rejects a stalled
   cross via `crossedThisWindow` (a first→last sign transition). That misses a cross that flipped sign
   then reverted mid-window. **Options:** (a) accept the endpoint test (cheap, what M1 ships); (b) add a
   `crossMonotonic` flag in `deriveTrending` requiring the favoured leg to rise on ≥K of the last N
   buckets. **Recommended default: (a)** for PR-A; promote to (b) only if forward paper shows false
   crosses slipping through. (b) is a pure producer-side addition, no new gate.

4. **M4 flat-oi-stand-aside vs the #5 fail-open — mutual exclusion.** `flat-oi-stand-aside` (block on
   null) is the deliberate inverse of `oi-cross-filter`'s fail-open (pass on null). A strategy must not
   arm both (contradictory on a flat day). **Options:** (a) document the exclusion in PR-LAST + a
   `ScalperConfig.from` guard that logs a warning if both tags are present; (b) silently let M4 win
   (block) when both are armed. **Recommended default: (a)** — an explicit warning + a load-test
   assertion that no shipped YAML carries both.

5. **P14 windowed-dOI vs day-cumulative (UNCERTAIN_OWNER, completeness-sweep.md L29).** The code computes
   the cross over the `SERIES_WINDOW=20` bucket *window* (interval reading); the doc §7 leaves
   windowed-vs-day-cumulative open. **Options:** (a) pin the windowed reading (what the code does) with a
   javadoc note + close the gap as a doc decision; (b) add a day-cumulative variant. **Recommended
   default: (a)** — the interval reading matches the 5-15m trading cadence; (b) is a larger producer
   change with no clear edge. This package is a **doc decision, not code** beyond the comment.

6. **M7 60m-OI read cost.** Adding a second `/options/trending?interval=60m` fetch doubles the
   trending HTTP call on every armed bar. **Options:** (a) fetch 60m always when the tag is armed
   (simple); (b) cache the 60m read for the bar's hour (it changes slowly). **Recommended default: (a)**
   for v1 — the read is best-effort/isolated (`get(...)` L647-663) and only the armed forward-paper
   variants pay it; add (b) caching only if live latency demands it.

7. **PR-E X1 fake-cross side-flip — re-entry vs flat-then-signal.** The doc says "book SL and switch to
   the other side where the next genuine cross prints". **Options:** (a) a true automated re-entry on the
   confirmed opposite cross (a new exit→entry path — heavier, needs position-aware state the signal layer
   does not hold today); (b) emit the opposite-side signal naturally on the next bar (the existing engine
   already re-evaluates each bar, so an opposite cross fires a fresh signal without special code) and only
   add the **exit** half (close the losing leg on the opposite cross). **Recommended default: (b)** — the
   flip is then "exit on opposite cross + the normal next-bar entry", which needs no new entry path and
   stays `[S]`. (a) is deferred unless the owner wants same-bar reversal.

8. **Arming order in PR-LAST (which tag on which variant).** The natural first arming is
   `oi-cross-required` + `oi-divergence-magnitude` on `scalp-trending-oi-nifty` (the defining OI
   strategy) as a forward-paper A/B against the unarmed config. **Options:** (a) arm the full OI stack on
   one trending-oi variant; (b) arm one tag at a time across separate variants for cleaner attribution.
   **Recommended default: (b)** — one tag per variant gives a clean A/B per gate, consistent with the
   niftyoi/sensexoi A/B pattern already seeded. Owner-driven, per "tune on live, not backtest".
