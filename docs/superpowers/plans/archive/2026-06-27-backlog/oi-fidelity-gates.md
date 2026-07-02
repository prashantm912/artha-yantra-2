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

> **Cite-accuracy note (audit pass 1):** the `Source disposition file` line numbers below were re-opened
> and corrected on 2026-06-27. The earlier draft had a systematic ~2–6-row offset against
> `trending-oi.md` and mis-keyed several `GAP-DISPOSITION.md §3c` names (§3c L153-177 is a comma-separated
> prose list, not line-keyed rows — cite the line the NAME sits on). Corrected cites are shown; the
> §3.5/§6.5 doc-§ anchors held.

| # | Package | gaps | Doc-§ (audit row) | Source disposition file | P/S |
|---|---------|-----:|-------------------|-------------------------|:---:|
| P1 | **`oi-cross-hard-gate`** | 8 | trending-oi §3.5 Entry-Bull.2 / Setup.2 / S22(b); gates-strike-sr-fiidii §4.9; intro-terminology §1.2; completeness-sweep §7 | trending-oi.md L17-20 (cross/condition/slope-agree rows), L35 (S22b failed-cross), L19 (≥50% imbalance); gates-strike-sr-fiidii.md §4.9 (L17-23); intro-terminology.md §1.2 (L24-31); completeness-sweep.md L34 | **[P]** |
| P2 | **`intraday-positional-oi`** | 11 | trending-oi §3.5 S21(d) (monthly positional+intraday) / Exit.time (S21 end-of-series) / S21(f) (EOD-OI bias); + market-movers/morning-trade/hero-zero/session-additions legs | trending-oi.md L37 (end-of-series), L38 (monthly positional+intraday), L44 (EOD-OI next-day bias); GAP-DISPOSITION.md §3a L119 | **[P]** (the score-tie / sizing legs are `[S]` — see §4) |
| P3 | `oi-divergence-magnitude` | 2 | trending-oi §3.5 S21(a) "immediately diverge ~20-30% / ≥50% gap" + Setup.4/Entry.4 (price corroboration) | trending-oi.md L21 (divergence ~20-30%/≥50%), L27 (substantial price move) | **[P]** |
| P4 | `flat-oi-stand-aside` | 1 | trending-oi §3.5 Setup.5-caveat / Edge-cases (flat-OI trap) | trending-oi.md L36 | **[P]** |
| P5 | `oi-direction-change-arrows` | 1 | trending-oi §3.5 S21 / Filters (direction-change arrows) | trending-oi.md L39 | **[P]** |
| P6 | `oi-quadrant-avoid-veto` | 1 | (single-gap pkg, §3c) — both-sides-heavy-OI strike avoid | GAP-DISPOSITION.md §3c L156; gates-strike-sr-fiidii.md L21 (both-sides-OI avoid) | **[P]** |
| P7 | `oi-both-sides-consolidation` | 1 | (single-gap pkg, §3c) — both sides building = consolidation stand-aside | GAP-DISPOSITION.md §3c L166 | **[P]** |
| P8 | `max-oi-sr-gate` | 1 | (single-gap pkg, §3c) — max-OI strike as a wall the entry must not trade into | GAP-DISPOSITION.md §3c L166 | **[P]** |
| P9 | `oi-support-resistance` | 1 | gates-strike-sr-fiidii §4.11 — the OI-derived S/R variant of the chart-S/R gap row (audit pass 2: the L30 audit row is itself the *chart* 1d/15m pivot S/R, marked "Automatable: true (pivot/zone detection on 1d+15m candles)"; P9 is the OI-wall-derived cousin of it, surfaced read-only) | GAP-DISPOSITION.md §3c L167; gates-strike-sr-fiidii.md L30 | **[P]** |
| P10 | `drastic-oi-floor` | 1 | trending-oi §3.5 Entry-Bull.5 — calibrate `drasticFloor` per index | GAP-DISPOSITION.md §3c L163; trending-oi.md L28 | **[P]** |
| P11 | `price-move-per-oi-demand` | 1 | trending-oi §3.5 Setup.4 / Entry.4 — price-impulse % over the cross window | GAP-DISPOSITION.md §3c L157; trending-oi.md L27 | **[P]** |
| P12 | `oi-interval-and-60m-trend` | 1 | trending-oi §3.5 Instruments/Setup.1 — explicit 5-15m analytics interval + a 60m OI broader-trend read | trending-oi.md L26; GAP-DISPOSITION.md §3c L158 | **[P]** |
| P13 | `trending-oi-strike-window` | 1 | trending-oi §3.5 S21(e) (`dynamic-strike-recenter`) — reset to ATM±7 on >1% move | trending-oi.md L40; GAP-DISPOSITION.md §3c L156 | **[P]** |
| P14 | `trending-oi-window-fidelity` | 1 | trending-oi §3.5 Setup.1 / §6.5 timeframe — the windowed-dOI vs day-cumulative reading | GAP-DISPOSITION.md §4.3 L250; completeness-sweep.md L34 (UNCERTAIN_OWNER) | **[P]** |
| P15 | `fake-cross-side-flip` | 1 | trending-oi §3.5 Exit.stop_loss / edge_cases (fake/double cross switch) | trending-oi.md L32 | **[S]** (exit/management; new path, no existing golden) |
| P16 | `incomplete-cross-reject` | 1 | trending-oi §3.5 Exec-notes / Edge-cases (failed/incomplete cross) | trending-oi.md L34 | **[P]** |
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
- `OiTrendingService.trending(series)` (`market-data-service/src/main/java/in/arthayantra/marketdata/options/analytics/OiTrendingService.java`)
  emits per-bucket `totalOi/ceOi/peOi/spot` + UP/DOWN/FLAT trend (`TrendPoint` L28-36). `OiBigOiService.bigOi(latest, topN)`
  ranks legs by **`|oiChange|`** (the biggest interval OI **MOVERS**, `OiBigOiService` L30), NOT by absolute
  OI. **CORRECTION (audit pass 1): the max-OI strike (the S/R wall = largest STANDING OI) is therefore NOT
  `bigOi().items[0].strike`** — that is the biggest mover. The wall must be derived by ranking the
  per-strike CE/PE OI ladder (raw `oi`), which the scalper already fetches in
  `MarketOiClient.toChainSnapshot` (`strikeOi` L240-241, the `(strike, ceOi, peOi)` ladder). M6 sources the
  wall from that ladder (no new endpoint) — see §3 M6.
- `OptionsAnalyticsController` (`.../options/analytics/OptionsAnalyticsController.java`, package
  `marketdata.options.analytics`): `/trending` (**L684**, was mis-cited L212 = `/pcr-series`),
  `/active-strikes` (L326), `/big-oi` (L571) — **each ALREADY declares `@RequestParam(required=false)
  String interval`** (e.g. `/trending` at L689) and resolves it via `OiQuery.of(...)`.
  `OiInterval` (`options/OiInterval.java`) enumerates `M1/M3/M5/M10/M15/M30/M60` (L10-16; tokens
  `1m/3m/5m/10m/15m/30m/60m` via `token()`), so the M7/P12 `5m`+`60m` tokens both `parse()` cleanly
  (corrected from pass 1's "`M5/M15/M60` (L13-16)" — M5 is L12). **KEY (audit pass 1): the
  `interval` param ALREADY EXISTS on `/trending` — passing it from the consumer (`MarketOiClient`) adds NO
  new endpoint param, so M7/P12 is NOT a springdoc contract touch** (corrects §3 M7 + §4 + §5.6 + §6).

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
completeness-sweep.md L34). The code already chose the **interval/windowed** reading (`deriveTrending`
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
`|gapLast| / max(totalOiLast,1) × 100` (the PE−CE gap as a % of the bucket's total OI). **NOTE (audit
pass 1): `deriveTrending` does NOT currently compute `totalOiLast`; it must be added (`peLast + ceLast`,
both already in scope at L472-473).** The price-impulse leg (P11) reuses `spurtPricePct`
(`oi.spurtPricePct()`) — price lives on the spurt path, NOT the trending path. Extend the `Trending`
carrier (L440-448, also bump `Trending.EMPTY` L447) + the `Oi` record with `BigDecimal oiDivergencePct`.
Wire it through both `new Oi(...)` sites (suppression L271-273 → null, normal L322-335 →
`trending.divergencePct()`).

**`ScalperOiProps.java`** — new fields `oiDivergenceMinPct` (default `"20"`) + `priceImpulseMinPct`
(default `"50"`, reuse the spurt scale). Each new field needs (a) a record component (L18-29), (b) a
default in the compact constructor (L57-73), AND (c) a trailing `null` appended to `defaults()` (L77, the
all-null factory). **DROPPED (audit pass 1): the earlier `oiConvictionPct` (default 50) — the proposed
`oiDivergenceMagnitude` gate consumes only `minPct` + `priceMinPct`; `oiConvictionPct` was a dead,
never-read field. Fold the "≥50% conviction" reading into `oiDivergenceMinPct` if a higher floor is wanted.**

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
semantics — no separate code, documented in PR-LAST. **P10 (`drastic-oi-floor`)** — **CORRECTION (audit
pass 1): this is NOT "just calibration, not new code".** `GAP-DISPOSITION.md §3c L163` lists
`drastic-oi-floor` as a `[P]` (parity-sensitive) package, and `props.drasticFloor()` is read by the SOFT
`ConnectTheDotsScorer.drasticOi` (L141-153) on **every scalper's** unarmed path. Two options:
(a) keep `drasticFloor` a scalar `BigDecimal` (the existing field/type) and DB-tune it per strategy — zero
code, truly parity-neutral; or (b) make it per-index `Map<String,BigDecimal>` — but that **changes the
type of an existing record component, breaks the `drasticOi` call site (L146-147), and requires a
keyed-by-underlying lookup** in the scorer. (b) is a non-additive change touching the soft scorer, so it
is `[P]` and must follow the same default-preserving discipline (default 50000 for every key) AND
re-verify `ScalperOiPropsTest`/`ConnectTheDotsScorerTest` stay green. **Recommended: (a) for this stream;
defer (b)** unless forward data demands distinct NIFTY/SENSEX floors. See Open Point #2.

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

> **DESCOPED (S24 ratification 2026-06-27):** builds the dropped OI direction-change-arrow tool-UI primitive; dropped rule = "direction-change arrows" per RATIFICATION-PACK P1 #26/#63 (the KEPT confluence uses the OI cross + quadrant + sentiment level, not the arrow). See S24-PRUNE.md.

A direction-change arrow = a **slope-sign flip** of the OI sentiment between the latest two buckets.
Compute a `sentimentFlip` (sign of `last−prior` differs from sign of `prior−beforePrior`) in
`MarketOiClient.deriveSentiment` (L526-538). **NOTE (audit pass 1): `deriveSentiment` today reads only
`series.get(0)` (first) and `series.get(size-1)` (last) for a 2-point `last−first` slope; a flip needs
the LAST THREE buckets — extend it to also read `series.get(size-2)` and require `series.size() >= 3`
(else `sentimentFlip=false`).** Add `boolean sentimentFlip` to the `Sentiment` carrier (L517, also its
`EMPTY` L518) + `Oi`. New gate:
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
INTO, and a strike heavy on BOTH CE+PE OI is a low-probability "avoid". **CORRECTION (audit pass 1): do
NOT source the wall from `OiBigOiService.bigOi().items[0]` — that ranks by `|oiChange|` (the biggest
MOVER), not the largest standing OI (the wall).** The wall is `argmax(oi)` over the per-strike CE/PE OI
ladder, which the scalper already fetches: `MarketOiClient.toChainSnapshot` builds `strikeOi`
(`List<StrikeOi(strike, ceOi, peOi)>`, L240-241) per strike. This is confirmed by the audit row itself —
`gates-strike-sr-fiidii.md L21` notes "chain CE/PE OI per strike is already fetched in
`MarketOiClient.toChainSnapshot`".

**`MarketOiClient.java`** — derive `maxCeOiStrike` / `maxPeOiStrike` by scanning `chain.strikeOi()` for
the strike with the max `ceOi` / `peOi` (no new HTTP call). **WIRING CAVEAT (audit pass 1): the `Oi`
record is assembled in `oi(...)` (L267-336), which does NOT have the chain in scope** — `chain` is fetched
separately in `ScalperConfluenceGate.evaluate` (L119) and the `Oi` comes from `client.context(...)`
(L191-192). So either (i) add a small `oiWalls(chain.strikeOi())` derivation called in `evaluate` and
thread the two strikes into the gate directly (NOT via the `Oi` record), or (ii) extend `context(...)` /
`oi(...)` to accept the already-fetched `strikeOi` ladder so they ride the `Oi` record. Prefer (i) — it
avoids a fan-out to both `new Oi(...)` sites and the monthly-suppression path, and keeps the wall on the
chain that `evaluate` already holds. If (ii) is chosen, add `BigDecimal maxCeOiStrike, maxPeOiStrike` to
`Oi` and fan out to BOTH constructors + every test `Oi(...)` literal.

**`ScalperGates.java`** — **CONSISTENCY NOTE (audit pass 2): the body below reads `oi.maxCeOiStrike()`,
which presumes wiring route (ii) (the wall strikes ride the `Oi` record). But the RECOMMENDED route is (i)
(thread the two wall strikes from `evaluate`, where the chain is in scope, WITHOUT touching `Oi`). If route
(i) is taken, the signature is `oiWallClear(BigDecimal ceWall, BigDecimal peWall, BigDecimal spot,
OptionType side)` — take the two strikes as params, not from `oi`. Pick the signature to match the wiring
route chosen above; do not ship the `oi.maxCeOiStrike()` form alongside route (i) (the field won't exist).**
```java
/** P8/P9: the entry strike must not sit AT/BEYOND the dominant OI wall on its side.
 *  For CE (bullish) the max-CE-OI strike is overhead resistance; block if spot >= it. Mirror PE.
 *  (Route (ii) form — reads the walls off the Oi record; see the consistency note for the route (i) form.) */
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

> **DESCOPED (S24 ratification 2026-06-27) — the P13 `trending-oi-strike-window` sub-package ONLY (not P12):** P13 builds the dropped ATM±7 strike-housekeeping re-centre (reset the OI window to ATM±7 on a >1% move); dropped rule = "strike housekeeping ATM±7" / "60-min + 15m=5×3m + ATM±7" per RATIFICATION-PACK P1 #25/#62/#74. S24 keeps the 5-15min interval read (P12) — only the ATM±7 re-centre drops. See S24-PRUNE.md.

**P12 — explicit interval + 60m read.** Pass an explicit interval to `/options/trending`
(`MarketOiClient` L308-318) — add `.queryParam("interval", oiProps.trendingInterval())` (default `"5m"`,
the doc's 5-15m window; `oiProps.trendingInterval()` is a NEW `ScalperOiProps` String field). Add a SECOND
`/options/trending?interval=60m` fetch and a `int oiTrend60mDir` field on `Oi` (the UP/DOWN sign of the
60m total-OI series, parsed from `TrendSeries.items`). New hard gate `oi60mAgree(oi, side)` requires the
60m OI trend to agree with the side (fail-open on 0/unknown). Flag `require60mOiAgree` / tag
`oi-interval-and-60m-trend`. **CORRECTION (audit pass 1): this is NOT a springdoc contract touch** — the
`/options/trending` endpoint ALREADY declares `@RequestParam(required=false) String interval` (L689);
passing it from the consumer adds no new endpoint param. So **§5.6 (ContractCaptureTest re-capture) and
the §4/§6 "contract-touch" flags for P12 are dropped.** `oiTrend60mDir` extends the `Oi` record → fan-out
to BOTH `new Oi(...)` constructors + every test `Oi(...)` literal (see §6.1).

**P13 — dynamic strike re-centre (`trending-oi-strike-window`).** Today the candidate window is the
`atm_window width:3` the **market-data `/options/chain` endpoint** applies — the chain is fetched ONCE in
`evaluate` at L119 (`client.chain(cfg.underlying())`) and `StrikePicker.pick(...)` (L272-275) selects from
those already-filtered `chain.candidates()`. **CORRECTION (audit pass 1): the earlier "widen the
StrikePicker candidate filter before the pick call" is mechanically wrong — the seam has no candidate
filter to widen; the window is set upstream at the chain fetch.** A faithful ±7 re-centre therefore needs
EITHER (i) a `/options/chain` width param (a NEW market-data query param → a genuine springdoc contract
touch + a `chain(underlying, width)` overload on `MarketOiClient`), OR (ii) widen the seeded YAML
`strikes.width` to 7 on the recenter variant (no code, but then ATM±7 is ALWAYS in effect, not gated on
the >1% move). Given the cost, P13 is **deferred to its own PR/decision** — see Open Point #9. Flag
`requireOiStrikeRecenter` / tag `trending-oi-strike-window` if pursued. **[P]** (changes which strike is
picked → a different emitted signal). (Lower-priority — see §7 PR-D.)

### Single-package summary table (mechanism → packages closed)
| Mechanism | New `ScalperGates` fn | New tag(s) | Packages closed | P/S |
|-----------|-----------------------|-----------|-----------------|:---:|
| M1 | `oiCrossRequired` | `oi-cross-required` | P1 (cross+failed-cross), P16, P17(½), P14(doc) | P |
| M2 | `oiSlopeAgree` | `oi-slope-agree` | P1 (slope-agreement) | P |
| M3 | `oiDivergenceMagnitude` | `oi-divergence-magnitude` | P3, P11, P17(½), P10(calib) | P |
| M4 | `flatOiStandAside` | `flat-oi-stand-aside` | P4, P7 | P |
| M5 | `oiDirectionStable` | `oi-direction-change-arrows` | P5 | P |
| M6 | `oiWallClear` + `oiBothSidesHeavy` | `max-oi-sr-gate`, `oi-quadrant-avoid-veto` | P6, P8, P9(gate) | P |
| M7 | `oi60mAgree` (P12); recenter P13 DEFERRED (Open Point #9) | `oi-interval-and-60m-trend` (P13 tag `trending-oi-strike-window` only if pursued) | P12; P13 deferred | P |
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
| M7 60m-agree | **[P]** | `oi-interval-and-60m-trend` | same as M1 (gate ships unarmed). The `interval=5m`/`60m` consumer change is NOT a contract touch (param pre-exists). |
| M7 recenter (P13) | **[P]** | `trending-oi-strike-window` | DEFERRED (Open Point #9). Recenter changes the *picked strike* → distinctly perturbs the emitted `tradeable_*` columns, so its forward-paper A/B is the strongest discriminator — but it needs an upstream chain-width change (route (i) = a contract touch, or route (ii) = a YAML-width change that is always-on). |
| X1 fake-cross-flip exit | **[S]** | `oi-fake-cross-flip` | An EXIT/management path (re-enters the opposite side after a confirmed opposite cross). It is a **brand-new sell/flip path with no existing golden** — `[S]` per the prompt's rubric. Still ships default-OFF (a tag) for safety; its determinism is exit-side, validated by a new exit unit test, not a signals golden. |
| X2 read-only surfacing | **[S]** | — | Read-only analytics (max-OI S/R levels + OI-arrow + positional-OI compare into `scalper_detail` JSON) + advisory `suggested_qty` sizing tie (P2). Does NOT change which signals fire → no golden impact. The `scalper_detail` JSON is a generic side-channel (FU2 §6 — Map/jsonb additions don't drift the spec). |
| M3 `MarketOiClient` divergence field | **[S]** (producer) | — | The new `oiDivergencePct` field is computed on the LIVE OI read only; adding it to the `Oi` record + `deriveTrending` is invisible to the goldens (no scalper on the replay path). |
| P12 `/options/trending?interval=` query param | **[S]**, **NOT a contract-touch** | — | **CORRECTED (audit pass 1): the `interval` param ALREADY EXISTS on `/options/trending` (L689), so passing it from the consumer drifts NOTHING.** No `ContractCaptureTest` re-capture needed. Behaviourally additive (the consumer's new default `5m` replaces the producer's `M3` default — confirm that intended change with the owner; it is a behaviour shift on the armed `oi-interval-and-60m-trend` variant only). |
| P13 `/options/chain?width=` (IF (i) chosen) | **[P]** + **contract-touch** | `trending-oi-strike-window` | Only if P13 takes route (i) (a NEW `width` param on `/options/chain`) is there a real springdoc touch (re-capture + regen TS). Route (ii) (widen the YAML width) has no contract impact but loses the >1%-move gating. Deferred — Open Point #9. |

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
This is where each promotion's *behaviour* is proved (FU2 §5.2). The `ScalperConfig`-arity change forces
appending a trailing `false` to **all 8 existing `new ScalperConfig(...)` literals** (L43-90: `CFG`,
`TWO_CANDLE_CFG`, `OI_CROSS_CFG`, `GAP_CFG`, `TREND_CHANGE_CFG`, `OPEN_HIGH_LOW_CFG`, `OPENING_TICK_CFG`,
`STRADDLE_CFG`) for each new flag. **Additionally (audit pass 1): any `Oi`-record extension (M3/M5/M6(ii)/M7)
fans out to EVERY `new Oi(...)` literal — there are exactly 8 in `ScalperConfluenceGateTest` (L141, L152,
L198, L382, L446, L501, L634, L649) PLUS the `Oi(...)` literals in `ConnectTheDotsScorerTest` (12, e.g.
L40/64/126/142/270/276), `ScalperGatesTest` (3: L144/L150/L154), `HeroZeroGateTest` (L86),
`TrendChangeGateTest` (L71), and `OpenHighLowGateTest` (L38). **NOTE (audit pass 2): the derivation tests
in `MarketOiClientDerivationTest` work on the `Trending`/`Sentiment`/`Spurt` carriers, NOT the `Oi` record
— there is no `new Oi(` there (corrected from pass 1, which listed it and omitted the three gate tests).**
Grep `new Oi(` across the scalper test tree before extending the record; every literal needs the new
trailing field. For EACH new tag add one CFG
literal + the FU2 triple:
1. `<tag>StrategyPassesWhenOiConfirms` — context whose OI operand passes → `.isPresent()` + the picked
   `tradingsymbol` matches.
2. `<tag>StrategyBlocksWhenOiAgainst` — context whose OI operand fails → `.isEmpty()`.
3. `non<Tag>StrategyIsUnaffected` — the bare `CFG` (arms no new gate) against the same failing OI
   context → `.isPresent()` (proves the dot stays soft when the tag is absent).

Build the failing/passing `Oi` via the existing `Oi(...)` constructor in the test (e.g. `bullContext()`
clones with `crossed=false` for M1, `imbalance=null` for M4, `sentimentFlip=true` for M5,
`maxCeOiStrike` ≤ spot for M6). The "non-Tag unaffected" tests use the same `bullContext()` that already
drives a passing aggregate in `confluenceConfirmsAndPicksTheInBandCe`.

### 5.3 Producer unit (CORRECTED test-file targets — audit pass 1)
The `deriveTrending`/`deriveSentiment`/`deriveSpurt`/`deriveIvPair` derivation unit tests live in
**`MarketOiClientDerivationTest.java`** (NOT `MarketOiClientTest.java` — that is the WireMock/HTTP unit).
Put the pure-derivation cases there, beside the existing `trendingThreeBucketSeriesWith...` /
`trendingFlatSeries...` tests:
- `deriveTrendingComputesDivergencePct` (`MarketOiClientDerivationTest`) — a 3-bucket series → assert
  `oiDivergencePct` == the expected `|gapLast|/totalLast×100`; assert null/zero-safe when total OI is 0
  (flat). (Decide the flat case in lockstep with Open Point #1.)
- `deriveSentimentFlagsSlopeFlip` (`MarketOiClientDerivationTest`) — a series that rises then falls →
  `sentimentFlip=true`; a monotone series → `false`; **<3 buckets → `false`** (the extended ≥3-bucket
  requirement from §3 M5).
- `oiPassesExplicitTrendingInterval` (**`MarketOiClientTest.java`** — the WireMock unit) — assert the
  `/options/trending` request carries `interval=5m` and the 60m fetch carries `interval=60m`.

### 5.4 Load test (`ScalperStrategyLoadTest.java`)
Add an OFF-assertion per new flag inside the per-id loop (after the existing
`requireCallPutDeltaFilter`/`requireStraddle` assertions at L150-159), the regression tripwire that
PR-A/B/C/D arm nothing. **The loop runs over all 36 seeded variants (`UNDERLYING.keySet()`), so each
assertion fires 36×.** Assert `.isFalse()` UNCONDITIONALLY for every id (no new tag is seeded):
```java
assertThat(cfg.requireOiCross()).as(id + " oi-cross gate off (no tag shipped)").isFalse();
// ... one per new flag actually added in the shipped PRs (oiSlopeAgree, oiDivergence, nonFlatOi,
//     oiDirectionStable, oiWallClear, oiQuadrantAvoid, require60mOiAgree, oiFakeCrossFlip).
// NOTE: oiStrikeRecenter is added ONLY if P13 is pursued (deferred, Open Point #9) — assert it only
//       when the flag exists, else the line won't compile.
```

### 5.5 Golden / parity tripwires (MUST stay byte-identical — the load-bearing proof)
- `GoldenDeterminismTest` (`libs/strategy-engine`) — re-run; assert byte-identical green. **Do NOT
  regenerate.**
- `BacktestParityTest` (`services/backtest-service`) — re-run; the three byte-match asserts stay green.
- These prove the gate + the new `MarketOiClient`/`Oi` fields did not leak onto the deterministic
  replay path. No new golden variant is created in this stream.

### 5.6 Contract test — NOT NEEDED for P12 (audit pass 1)
**CORRECTED:** P12 does NOT drift the springdoc spec — `/options/trending` ALREADY declares
`@RequestParam(required=false) String interval` (L689), so the consumer passing it adds no new endpoint
param. **No `ContractCaptureTest` re-capture / TS regen is required for this stream.** The ONLY scenario
that would re-introduce a contract touch is P13 route (i) (a NEW `width` param on `/options/chain`) — if
that route is taken, re-capture with `-Dcontracts.capture=true`, regen TS via `npx openapi-typescript@7`,
`tsc --strict`; `ci-contracts` warns on the additive gen drift. P13 is deferred (Open Point #9), so this
step is dormant.

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
4. **M6 (OI-wall) needs the max-OI strikes surfaced from the chain `strikeOi` ladder** (largest STANDING
   `oi`, NOT `bigOi().items[0]` which ranks by `|oiChange|` — see §2.7 correction). The ladder is already
   in `chain.strikeOi()` (`toChainSnapshot` L240-241), held by `evaluate` at L119 — no new HTTP. Prefer
   threading the two wall strikes into the gate directly from `evaluate` rather than fanning the `Oi`
   record (the wall isn't in `oi(...)` scope; see §3 M6 wiring caveat). M6 is independent of M1-M5.
5. **M7 60m-agree is last among the gates** — it adds a SECOND `/options/trending` fetch (per bar, when
   armed) but **NO contract touch** (the `interval` param pre-exists). The P13 strike-recenter is split
   off entirely (Open Point #9) — it is the only genuine contract-or-YAML-width decision and the
   heaviest parity surface. Sequence M7-60m after the no-feed gates prove out.
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
| **PR-D** `feat(strategy-signal): explicit 5-15m/60m OI interval OI-agree gate` | M7 60m-agree only (NO contract touch — `interval` param pre-exists). P13 strike-recenter is SPLIT OUT/deferred (Open Point #9) | `oi-interval-and-60m-trend` | **S-M** |
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
   override. **Recommended default (REVISED audit pass 1): (b)** — `drasticFloor()` is read by the SOFT
   `ConnectTheDotsScorer.drasticOi` on every scalper's unarmed path, so (a) changes an existing
   record-component type and the existing call site (a `[P]` non-additive edit per GAP-DISPOSITION §3c
   L163), which contradicts framing P10 as "calibration". Keep it a scalar and DB-tune per strategy for
   this stream; pursue (a) only as a deliberate `[P]` change with default 50000 for every key and a
   `ConnectTheDotsScorerTest`/`ScalperOiPropsTest` re-verify. Needs a real live-OI distribution sample to
   set the SENSEX value (the doc gives no number — same caveat as the existing placeholder).

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

5. **P14 windowed-dOI vs day-cumulative (UNCERTAIN_OWNER, completeness-sweep.md L34; GAP-DISPOSITION §4.3
   L250).** (Cite corrected audit pass 1 — L29 is the counter-trend-volume rule, not this.) The code computes
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

9. **(NEW, audit pass 1) P13 strike re-centre — the candidate window is set UPSTREAM, not at the seam.**
   The `atm_window width:3` filter is applied by the market-data `/options/chain` endpoint; `evaluate`
   fetches the chain ONCE (L119) and `StrikePicker.pick` selects from the already-filtered candidates, so
   there is no seam-side window to "widen". **Options:** (a) add a `width` query param to `/options/chain`
   + a `chain(underlying, width)` overload, and have `evaluate` re-fetch at ±7 when the session move >1%
   (a NEW market-data param → a real springdoc contract touch); (b) seed a separate `*-recenter` YAML
   with `strikes.width: 7` (no code, but ATM±7 is then ALWAYS on, not gated on the >1% move); (c) drop
   P13 for v1. **Recommended default: (c) defer** — the faithful version (a) is the only one true to the
   doc and it is the single contract touch in the stream; (b) is a degraded approximation. Revisit when a
   trending-oi forward variant shows the fixed ±3 window dropping the moved strike.

10. **(NEW, audit pass 1) The `oi-cross-filter` (#5) precedent already ships ARMED on the
    `scalp-trending-oi-*` family** (`ScalperStrategyLoadTest` L150-153 asserts `requireCallPutDeltaFilter`
    TRUE for that family). The new tags here are genuinely default-OFF (none is seeded), so the parity
    firewall holds — but PR-LAST arming of `oi-cross-required`/`flat-oi-stand-aside` onto the SAME
    trending-oi variants will INTERACT with the already-armed `oi-cross-filter` (esp. the M4 vs #5
    fail-open/fail-closed inversion in Open Point #4). Confirm the intended interaction on a flat-OI day
    before co-arming on a trending-oi variant.

11. **(NEW, audit pass 1) P12 consumer interval default `5m` is a BEHAVIOUR change, not just plumbing.**
    `MarketOiClient` currently passes NO interval, so the producer defaults to `M3` (`OiQuery.of` L21-22)
    for the trending cross derivation TODAY. Passing `interval=5m` (the §3 M7 default) changes the bucket
    width of the LIVE cross/divergence derivations even on the cross/divergence gates of OTHER armed
    variants — it is not isolated to the `oi-interval-and-60m-trend` tag unless the `5m` is applied only
    when that tag is armed. **Decide:** apply the explicit interval ONLY on the `oi-interval-and-60m-trend`
    armed path (keep M3 default elsewhere), OR globally switch the consumer to 5m (and accept the changed
    cross-derivation cadence for `oi-cross-filter`/`oi-cross-required` too). Recommended: scope the `5m` to
    the armed tag to avoid silently shifting the existing #5 gate's cadence.

---

## Audit pass 1 findings

Reviewer opened every cited Java file, YAML/test, and the `docs/strategy-audit/` source rows on
2026-06-27. **Verdict: SOUND-WITH-OPEN-POINTS.** The core design (FU2-shaped early-return hard gates,
tag-gated default-OFF, parity firewall via the scalper-free goldens) is correct and would compile and
preserve parity. The corrections below were applied IN PLACE; none changes the design, but several fix
load-bearing citations, remove an unnecessary contract step, and re-classify one "[S] calibration" as a
real `[P]` change.

### Citations corrected (all verified against re-opened files)
- **§1 package table — systematic `trending-oi.md` row drift (~2–6 rows).** Corrected: P3 divergence
  L20/L23→**L21**; P4 flat-OI L32→**L36**; P5 direction-arrows L35→**L39**; P12 interval L22→**L26** (kept);
  P15 fake-cross L28→**L32**; P16 incomplete-cross L30→**L34**; P10 added L28; P11 added L27. P13 (L40) and
  P12 (L26) were already right.
- **§1 — `GAP-DISPOSITION.md §3c` is a prose name-list (L153-177), not line-keyed rows.** Corrected:
  P6 `oi-quadrant-avoid-veto` L166→**L156**; P7 `oi-both-sides-consolidation` L167→**L166**; P14 moved to
  the real UNCERTAIN_OWNER home (**§4.3 L250** + completeness-sweep **L34**, not L29/L160). P8/P9/P10/P11/P17
  were within ±1 line and kept.
- **§1 P6 — `gates-strike-sr-fiidii.md` both-sides-OI avoid is L21**, not L17 (L17 = ATM±3). Corrected.
- **§5 — wrong test file.** The `deriveTrending`/`deriveSentiment` derivation unit lives in
  **`MarketOiClientDerivationTest.java`**, not `MarketOiClientTest.java` (that is the WireMock/HTTP unit).
  §5.3 split accordingly: derivation cases → `MarketOiClientDerivationTest`, the interval-WireMock case →
  `MarketOiClientTest`.
- **§2.7 — stale endpoint cite.** `/trending` is **L684** (L212 = `/pcr-series`). `/active-strikes`
  (L326) and `/big-oi` (L571) confirmed. Package names in §3c all confirmed to exist.

### Soundness corrections
- **`/options/trending` already declares `@RequestParam(required=false) String interval` (L689).** The
  plan treated passing it as a NEW param + a springdoc contract touch (§2.3/§3 M7/§4/§5.6/§6). It is NOT —
  the consumer passing an existing param drifts nothing. Removed the `ContractCaptureTest` step for P12;
  re-scoped the only possible contract touch to P13 route (i) (a new `/options/chain?width=`), which is
  deferred. **This also means the §2.7 vs §2.3 internal contradiction is resolved in favour of §2.7.**
- **`OiBigOiService.bigOi()` ranks by `|oiChange|` (biggest MOVER), not absolute OI.** The plan's §2.7 and
  §6#4 claimed `bigOi().items[0].strike` IS the max-OI S/R wall — wrong. The wall (largest STANDING OI) is
  `argmax(oi)` over `chain.strikeOi()` (the per-strike ladder already in `toChainSnapshot` L240-241,
  confirmed by `gates-strike-sr-fiidii.md L21`). M6 re-pointed at the ladder + a wiring caveat (the wall
  isn't in `oi(...)` scope — thread it from `evaluate`, which holds the chain).
- **P13 strike-recenter mechanics were wrong.** The candidate window is applied UPSTREAM by
  `/options/chain` (`width:3`); the seam fetches the chain once (L119) and `StrikePicker.pick` (L272-275)
  selects from it — there is no seam-side filter to "widen before the pick call". Rewrote P13 with the two
  real routes (chain-width param vs YAML width) and deferred it (Open Point #9).
- **`MarketOiClient.deriveTrending` does not compute `totalOiLast` today** — M3's `divergencePct` needs it
  added (`peLast + ceLast`, in scope L472-473). Noted.
- **`MarketOiClient.deriveSentiment` is a 2-point (`last−first`) slope** — M5's `sentimentFlip` needs the
  last THREE buckets (`size>=3`, read `series.get(size-2)`). Noted in §3 M5 + §5.3.
- **M3's `oiConvictionPct` field was dead** (never read by the proposed gate). Dropped.

### Parity / classification corrections
- **P10 `drastic-oi-floor` is `[P]`, not "[S] calibration".** `props.drasticFloor()` feeds the SOFT
  `ConnectTheDotsScorer.drasticOi` (L141-153) on every scalper's unarmed path; turning it into a
  `Map<String,BigDecimal>` changes an existing record-component type + breaks the existing call site (a
  non-additive `[P]` edit per GAP-DISPOSITION §3c L163). Revised Open Point #2 to recommend keeping it a
  scalar (DB-tuned) for this stream.
- **The `oi-cross-filter` (#5) precedent already ships ARMED on `scalp-trending-oi-*`** (load test
  L150-153). The NEW tags here are genuinely default-OFF, so the parity firewall is intact, but PR-LAST
  arming interacts with the live #5 gate (new Open Point #10). Added.
- **P12 consumer `interval=5m` is a behaviour change**, not pure plumbing — it shifts the bucket cadence of
  the existing live cross/divergence derivations unless scoped to the armed tag (new Open Point #11). Added.

### Parity firewall — CONFIRMED INTACT
- `GoldenDeterminismTest.FEATURES` = `{ema-crossover, optional-indicator-activation, btst-preclose,
  exit-intrabar, context-series}` (L33-36) and `BacktestParityTest.FEATURES` (L35) carry **no scalper
  strategy** and never instantiate `ScalperConfluenceGate`/`ConnectTheDotsScorer`. No new YAML in this
  stream carries any new tag. **Every `[P]` change is byte-safe; the two tripwires stay green.**
- The `ScalperConfig` arity fan-out (8 `new ScalperConfig(...)` literals, L43-90) and the `Oi` fan-out
  (~8 `new Oi(...)` literals in `ScalperConfluenceGateTest` + literals in `ConnectTheDotsScorerTest` /
  `ScalperGatesTest` / `MarketOiClientDerivationTest`) are compile-time, not parity, risks — tightened in
  §5.2/§6.1.

### Completeness
- Dependency sequencing (feeds-before-gates, no-feed M1/M2 first, M6 ladder-not-`bigOi`, M7 last) is
  right. SPAN/equity-universe correctly out of scope (all packages are index-options OI on existing live
  capture; X1 re-enters a LONG opposite leg, no sell leg). The `[S]` X1 exit-path classification is sound
  (new path, no existing golden).
- One residual gap left as an open point: the M6(i)-vs-M6(ii) wiring choice (thread the wall strikes from
  `evaluate` vs fan them through the `Oi` record) is a real decision the executor must make — documented
  in §3 M6.

**Net:** no design-level defect; the corrections are citation accuracy (≈12 cites), one removed
contract-test step, one re-classification (P10→[P]), and three new open points (#9/#10/#11). Hand-off is
safe once Open Points #1/#2/#9/#11 are decided by the owner.

---

## Audit pass 2 findings

Second, INDEPENDENT reviewer. Re-opened every file the plan and pass 1 cite (the 8 scalper Java sources,
the 5 scalper test files, `OptionsAnalyticsController`/`OiBigOiService`/`OiTrendingService`/`OiInterval`/
`OiQuery` in market-data, the 5 golden/parity FEATURES arrays, and the 4 `docs/strategy-audit/` source
files), re-derived the parity argument end-to-end, and re-checked the pass-1 corrections for over- or
under-reach. **Verdict: SOUND-WITH-OPEN-POINTS.** The design is correct and parity-safe; the two
load-bearing pass-1 soundness corrections are independently CONFIRMED; the residual issues are citation
precision + two package-accounting over-claims, all corrected in place below.

### Pass-1 corrections RE-VERIFIED (independently confirmed correct)
- **`/options/trending` already declares `@RequestParam(required=false) String interval` at L689** —
  confirmed by opening `OptionsAnalyticsController` (`@GetMapping("/trending")` at **L684**, the `interval`
  param at **L689**). Passing it from `MarketOiClient` is NOT a springdoc touch. The dropped
  `ContractCaptureTest` step is correctly dropped. `/pcr-series` is indeed at L212 (the old mis-cite).
- **`OiBigOiService.bigOi()` ranks by `|oiChange|`, NOT absolute OI** — confirmed: L30 is
  `Comparator.comparingLong(r -> Math.abs(r.oiChange())).reversed()`, javadoc L9 "ranked by absolute
  interval OI-change (the movers)". So sourcing the max-OI **wall** from `bigOi().items[0]` would be wrong;
  the ladder `chain.strikeOi()` (`toChainSnapshot` L240-241) is the right source. `gates-strike-sr-fiidii.md
  L21` independently confirms "chain CE/PE OI per strike is already fetched in
  `MarketOiClient.toChainSnapshot`". (Caveat the executor should heed: `BigOiRow` *does* carry a raw `oi`
  field, but `topN` truncates by `|oiChange|` first, so re-sorting `bigOi().items` by `oi` still misses the
  largest-standing-OI strike if it isn't a top-N mover — the ladder is the only correct source.)
- **`deriveTrending` does not compute `totalOiLast`; `peLast`/`ceLast` are in scope at L472/L473** —
  confirmed. **`deriveSentiment` is a 2-point `last−first` slope (L532-533); a flip needs `series.get(size-2)`
  + `size>=3`** — confirmed (the early-out is `series.size() < 2` at L529; the extension is additive and does
  not perturb the existing 2-bucket slope). **P13 has no seam-side candidate filter** — confirmed:
  `StrikePicker.pick` (L272-275) consumes `chain.candidates()` from the single L119 chain fetch; the window
  is set upstream by `/options/chain width:3`. **P10 `drasticFloor` is `[P]`** — confirmed: it is read by the
  SOFT `ConnectTheDotsScorer.drasticOi` (L146-147) on every scalper, so a per-index `Map` retype is
  non-additive. **`oi-cross-filter` (#5) ships ARMED on the trending-oi family** — confirmed:
  `ScalperStrategyLoadTest` L150-153 asserts `requireCallPutDeltaFilter == isTrendingOi`. **Open Point #11**
  (consumer interval default M3 → `OiQuery.of` L21-22) — confirmed; switching to `5m` IS a live behaviour
  change, and scoping it to the armed tag is the right call.

### Parity firewall — INDEPENDENTLY RE-CONFIRMED INTACT
- `GoldenDeterminismTest.FEATURES` (libs/strategy-engine, L33-36) and `BacktestParityTest.FEATURES`
  (backtest-service, L35-37) are BOTH exactly `{ema-crossover, optional-indicator-activation, btst-preclose,
  exit-intrabar, context-series}` — zero scalper YAMLs, never instantiate `ScalperConfluenceGate`/
  `ConnectTheDotsScorer`. Every `[P]` change is an early-return guarded by a `cfg.requireXxx()` that is
  `false` unless a NEW tag is present, and no shipped YAML (PR-A..E) carries one → the two tripwires stay
  byte-identical. The producer-side `deriveTrending`/`deriveSentiment` additions compute NEW fields only,
  leaving the existing `crossed`/`imbalance`/`gapWidening`/`slope` returns unchanged, so even the SOFT
  scorer's existing dots are byte-stable. The X2 `scalper_detail` surfacing rides a real `jsonb` column
  (`SignalRepository` L188) rendered into a generic Map DTO (`SignalsController` L126) → no spec drift.
  **The "EVERY signal-affecting change is tag-gated default-OFF" claim holds.**
- Type-check of the 7 proposed gate bodies against `GateOutcome(boolean,BigDecimal,String)` + its static
  `pass(BigDecimal,String)`/`fail(...)`: all compile-correct (`oiWallClear`'s `GateOutcome.pass(spot, ...)`
  and the `new GateOutcome(ok, value, reason)` forms match the record). The 8 `ScalperConfig` literals
  (L43-90) and the `new Oi(` fan-out are compile-time, not parity, risks — confirmed.

### Issues pass 1 missed (CORRECTED in place by pass 2)
1. **Internal cite contradiction L29 vs L34.** Pass 1 corrected the P14/Open-Point-#5 windowed-vs-cumulative
   cite to `completeness-sweep.md L34` in §1 and Open Point #5, but **left `L29` in the §3 M1 P14 paragraph**
   — and pass 1 itself noted "L29 is the counter-trend-volume rule, not this". Verified: L29 = the S24
   Trend-Change counter-trend volume rule; L34 = the S7 "≥50% Call-vs-Put: day-cumulative or interval?
   UNCERTAIN" row. **Fixed §3 M1 to L34.**
2. **P11 §3c cite off-by-one.** `price-move-per-oi-demand` sits on `GAP-DISPOSITION.md §3c` **L157**, not
   L158 (L158 holds `trending-oi-window-fidelity`/`oi-interval-and-60m-trend`). **Fixed the §1 P11 row.**
3. **§5.2 `new Oi(` enumeration was both over- and under-inclusive.** It listed `MarketOiClientDerivationTest`
   (which has NO `new Oi(` — it works on the `Trending`/`Sentiment`/`Spurt` carriers) and OMITTED three gate
   tests that DO carry `new Oi(` literals: `HeroZeroGateTest` (L86), `TrendChangeGateTest` (L71),
   `OpenHighLowGateTest` (L38). The pass-1 "grep `new Oi(` first" advice mitigated the risk, but the list was
   wrong. **Fixed §5.2** with the verified full inventory (8 in `ScalperConfluenceGateTest`, 12 in
   `ConnectTheDotsScorerTest`, 3 in `ScalperGatesTest`, + the 3 gate tests).
4. **M6 `oiWallClear` code body presumes wiring route (ii) while §3 M6 RECOMMENDS route (i).** The shown body
   reads `oi.maxCeOiStrike()` (a field that exists only under route (ii) = fan the wall through the `Oi`
   record), but the recommended route (i) threads the wall strikes from `evaluate` WITHOUT touching `Oi` (so
   no such field exists). **Added a consistency note** giving the route-(i) signature
   (`oiWallClear(ceWall, peWall, spot, side)`) so the executor doesn't ship the `oi.maxCeOiStrike()` form
   against route (i) (a compile error).
5. **P9 cite L30 is the CHART S/R row, not OI-derived S/R.** `gates-strike-sr-fiidii.md L30` is "Mark S/R on
   1-Day, refine on 15-min … Automatable: true (pivot/zone detection on 1d+15m candles)" — a chart-pivot
   gap, whereas the P9 package (`oi-support-resistance`, §3c L167) is the OI-wall-derived cousin. **Clarified
   the §1 P9 row** so the parenthetical no longer claims L30 itself is "OI-derived".
6. **`OiInterval` cite imprecision.** "supports `M5/M15/M60` (L13-16)" — the enum is `M1/M3/M5/M10/M15/M30/M60`
   (M5 is L12, not within L13-16). The substance (5m + 60m both `parse()`) is right. **Tightened §2.7.**

### Residual concerns (NOT corrected — flagged for the owner/executor)
A. **P17 "same-candle crossover event" is a loose fidelity proxy.** Combining `oi-cross-required` (cross
   derived over the trending `SERIES_WINDOW=20` window) with `oi-divergence-magnitude` (whose price leg is
   `oi.spurtPricePct()` = the `/options/spurt` `summary.priceChangePct`, a SEPARATE endpoint/window, not the
   trending bucket) does NOT literally co-locate the cross and the price thrust in one bucket. The plan's §3
   M3 already states "price lives on the spurt path, NOT the trending path", but the P17 claim that the two
   tags together give "same-candle semantics" over-states fidelity. Acceptable as a forward-paper-judged soft
   gate, but the owner should know the "same bucket" is approximate. (No code defect; a labelling caveat.)
B. **P2 `intraday-positional-oi` (11 gaps) is listed as a closed `[P]` package but only its READ-ONLY sliver
   is designed.** The plan addresses P2 solely via X2 (`scalper_detail` positional-vs-intraday compare,
   `[S]` read-only). The package's core — a genuine cross-series/positional OI state primitive — is rated
   "Automatable: false (needs cross-series OI state)" (trending-oi.md L37) / "partial" (L38) by the source
   audit, and is NOT built here. The §1 "Total ~34 gaps across 17 packages" accounting therefore counts P2's
   11 as in-stream when ~8 of them are effectively DEFERRED (no cross-series OI state engine in this plan).
   **Recommend** the owner read P2 as "read-only surfacing now; positional-OI gating deferred", not closed.
C. **M3 divergence denominator (Open Point #1) and P10 SENSEX `drasticFloor` (Open Point #2) still need live
   OI distribution data to set thresholds** — correctly left open; both block ARMING (PR-LAST), not the
   infrastructure PRs. No additional concern.

### Completeness re-check
- Dependency order (feeds-before-gates; no-feed M1/M2 first in PR-A; M6 ladder-not-`bigOi`, independent of
  M1-M5; M7 last; P13 split off) is correct and re-verified against the seam's actual data scoping.
- SPAN/equity-universe out of scope is correct (all packages are index-options OI on existing live capture;
  X1 buys a LONG opposite leg). The `[S]` X1 exit-path and X2 read-only classifications are sound.
- The 36-variant load-test (`UNDERLYING.keySet()`, 36 `Map.entry`) and the 8 `ScalperConfig` literals are
  precisely cited. The score denominator (19.6) and weights re-computed and confirmed.

**Net (pass 2):** no design-level or parity defect introduced or found. Six citation/consistency corrections
applied; two package-accounting over-claims (P17 fidelity, P2 closure) flagged for owner awareness; the
load-bearing pass-1 soundness fixes are independently confirmed. The plan is implementation-ready for PR-A
(M1+M2, the no-feed defining edge) immediately; PR-B..E follow the same shape. Arming (PR-LAST) remains
owner-gated on forward-paper OI and on deciding Open Points #1/#2/#9/#11. **READINESS: GO for the infra PRs;
the residual concerns are forward-judgment/accounting, not blockers.**
