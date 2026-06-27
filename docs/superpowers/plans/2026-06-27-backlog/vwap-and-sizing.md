# VWAP-distance + probability-graded position sizing

Status: PLAN (implementation-ready). Owner: single-owner. Date: 2026-06-27.
Target modules: `services/strategy-signal-service` (the scalper confluence seam + `ScalperConfig` +
`ScalperOiProps` + `ScalperGates` + the 36 YAMLs + `SignalEngine.emitEntry` sizing stamp), and the
paper port (`EmissionGuard`/`PaperEmissionGuard`) for the qty-multiplier wiring.

> Read order for the executor: this plan is self-contained but assumes the CLAUDE.md
> "parity-safe-additive" convention and the **FU2 plan**
> (`2026-06-27-followup2-soft-dots-to-hard-gates.md`) as the load-bearing precedents for every `[P]`
> change. The `oi-cross-filter` (#5) tag and the FU2 `indicator-alignment` tag are the EXACT shapes
> copied here for a new opt-in default-OFF hard gate; the existing `suggestedQty` stamp in
> `SignalEngine.emitEntry` (L605-614) is the precedent for the advisory-sizing changes (which are
> [S] — they never change WHICH signal fires).

---

## 1. Goal & the packages/gaps this stream closes

This stream automates the **VWAP-proximity and probability-graded sizing** half of the Siva money-
management layer: *don't enter when price is too far from VWAP/Supertrend* (a skip / wait), *deploy
more size near VWAP and less when the confluence is weak / VIX is high / the OI-gap is thin*, and *prefer
the high-probability time-of-day windows* (best 10:00-11:30, ease off after ~13:30). Today the scalper
sizes with a flat `premium_budget: budget_inr 15000` (the §3.5 audit calls this "fixed ₹15k") and VWAP is
a boolean side/hard-gate only — its DISTANCE is never read.

| Package | gap count | Source disposition rows (file:line) | Doc-§ |
|---|---:|---|---|
| **`vwap-distance-sizing`** | 7 | connect-the-dots.md L33 (S22(a)); gap-theory.md L25 (§3.4 L606); hero-zero.md L29 (§3.7 filters); intro-terminology.md L15 (§1.2); morning-trade.md L21 (§3.9 S21/S22); risk-framework.md L21 (§2.2 r8); session-additions.md L35 (§4.15.3) | §1.2, §2.2, §3.4/3.7/3.9/3.10, §4.15.3 |
| **`probability-graded-sizing`** | 13 | risk-framework.md L18,L19,L22,L23,L24,L44,L49,L53 (§2.2/2.3/2.10/2.12-14); open-high-low.md L27 (§3.2 risk[0]); trending-oi.md L24,L38 (§3.5 Risk/6.5); morning-trade.md L30 (§3.9 S21(d)); session-additions.md L29 (§4.14.9) | §2.2/2.3/2.10/2.12/2.13/2.14, §3.2/3.5/3.9, §4.14.9 |
| **`time-of-day-preference`** | 1 | trending-oi.md L37 (§3.5 Setup.3 / S21(b) / Filters / 6.5) | §3.5; (also intro §1.2 ideal 9:15-10:00 — ACCEPT_BY_DESIGN) |

Stream total: **21 AUTOMATE_PKG gap rows** (`7 + 13 + 1`). Many `probability-graded-sizing` rows
collapse onto the SAME code surface (one confluence/VIX/OI-gap qty-multiplier + a few account-side caps),
so the *code surface* is far smaller than 13 — the count is gaps-closed, not files-touched.

**Scope boundary (so nothing double-counts the other backlog streams):**
- The `daily-target-caps` package (7 gaps: daily profit/loss % caps + over-trade taper) and the
  `five-account-ledgers` package (2 gaps: per-account split + first-loss freeze) are **account-side rails
  in a SEPARATE stream** — several risk-framework rows that *mention* sizing are dispositioned there, not
  here. This stream owns only the rows whose disposition cell literally says `probability-graded-sizing`
  or `vwap-distance-sizing` (risk-framework.md AUTOMATE_PKG-themes block L72-77 enumerates them: 9 rows →
  `probability-graded-sizing`, 1 → `vwap-distance-sizing`).
- The `scale-in-ladder` package (smallest-first multi-leg deployment) is dispositioned to the
  trade-management stream (it needs a multi-leg entry engine the signal seam does not have).
- The FU1 `time_of_day_vwap` manual check (a parity-neutral on-card reminder) ALREADY covers the
  *manual* read of "prev-day VWAP until 11 AM" (intro-terminology.md L14, session-additions.md L23,
  morning-trade.md ties to it). This stream is the **automation** those rows point at (the disposition
  notes say e.g. "the prior-day VWAP series automation is the `vwap-distance-sizing` pkg") — it does NOT
  re-do the manual check.

**Key architectural finding (verified, drives the whole design).** There are **two distinct seams**:
1. **Entry skip / VWAP-distance** lives at `ScalperConfluenceGate.evaluate` — `Chart` already carries
   `close` + `vwap` (`ScalperGateContext.java:21-28`), so `|close − vwap|` is in-hand at the seam with NO
   new feed. A distance band is a pure early-return hard gate, the exact #5/FU2 shape → **`[P]`**.
2. **Sizing (`suggested_qty`)** is computed in `SignalEngine.emitEntry` (L605-614) by calling
   `emissionGuard.suggestedQty(strategy.definition().sizing(), …)` — it reads the **engine `SizingSpec`**
   (`PositionSizer.size`, `libs/strategy-engine`), NOT `ScalperConfig`, and is stamped OUTSIDE the frozen
   score breakdown. A confluence/VIX/OI-gap **multiplier** applied to that already-advisory qty is a pure
   read-only annotation — it never changes whether a signal fires → **`[S]`**.

This split is the whole reason the stream is *mostly* `[S]`: only the one VWAP-distance *entry skip*
changes signal emission (and only when an opt-in tag is armed).

---

## 2. Current state (verified by opening each file)

### 2.1 The confluence seam — `ScalperConfluenceGate.java`
- `evaluate(...)` L100-280 is `@Component`, **LIVE-only** (class javadoc L21-33: the OI/macro/chain reads
  are current snapshots, never run on deterministic replay; the picked option + confluence are persisted
  at entry via the V009 side-channel). This is the parity firewall — the same one FU2 relies on.
- The local `Chart chart` is built at **L124** from `chart(bank, index)` (L304-316), reading the engine
  `IndicatorBank` on the index FUTURE: `close` = `bank.builtin("close", index)`, `vwap` =
  `bank.builtin("vwap", index)` (L309-310). So `chart.close()` and `chart.vwap()` are both non-null on a
  live bar and the distance `|close − vwap|` is computable right there.
- The directional `side` is decided at **L149-152** (CE when `close >= vwap`, else PE).
- The §0B hard rails sit AFTER the side decision: volume + RSI (L157-163), then the per-strategy
  early-return gates — two-candle (L167-169), gap-fill (L173-179), **#5 call-put delta filter L196-199
  (the canonical hard-pre-gate shape)**, trend-change (L204-211), open-high-low (L218-229), hero-zero
  (L236-245). `ctx` (carrying `ctx.oi()`/`ctx.macro()`) is built at **L191-192**.
- The returned `Decision` (L71-87) carries `side`, `legs`, `confluence` (with
  `confluence().aggregate()`), `expiry`, `structuralStop`. **There is no sizing field** — sizing happens
  downstream in `SignalEngine`.

### 2.2 The `Chart` snapshot — `ScalperGateContext.java`
- `Chart(close, vwap, vwma20, psar, supertrendDir, rsi14, volume)` L21-28. `close`/`vwap`/`vwma20`/`psar`
  are all `BigDecimal` and present on a live bar, so a VWAP-distance OR a VWMA/Supertrend-distance band is
  pure arithmetic on the record — no new context field is strictly required for the *current-day* VWAP
  distance. (A **prior-day** VWAP would need a new series — see §2.6 and the Open Point.)

### 2.3 The pure gate library — `ScalperGates.java`
- All gates are pure `(operand) -> GateOutcome` functions (e.g. `volume` L64-68, `rsiBand` L76-84,
  `indicatorAlignment` L102-118, `callPutDeltaFilter` L151-161). **There is NO VWAP-distance gate today** —
  VWAP appears only as the side decision (seam) and the `vwap` soft dot (scorer L74). This is the slot the
  new `vwapDistance(...)` gate fills, single-sourced like the rest.
- `private static boolean gt(BigDecimal a, BigDecimal b)` L173-175 is the null-safe comparator pattern to
  reuse.

### 2.4 The tag→flag wiring — `ScalperConfig.java`
- `record ScalperConfig(...)` fields L36-52 — each `requireXxx` boolean (e.g. `requireCallPutDeltaFilter`
  L46, `requireStraddle` L52). The §0B delta/premium/threshold constants live here (L82-98), NOT in YAML;
  `ScalperOiProps` holds the DB-tunable OI/IV knobs.
- `from(JsonNode config, List<String> tags)` L101-157 maps tags to flags: `oi-cross-filter` L153 (the #5
  template), `two-candle-pattern` L119, `open-high-low` L125, etc. The constructor returns all flags
  L154-156.
- **Constructor arity is coupled** to the 8 `new ScalperConfig(...)` literals in
  `ScalperConfluenceGateTest` (L43-90: CFG, TWO_CANDLE, OI_CROSS, GAP, TREND_CHANGE, OPEN_HIGH_LOW,
  OPENING_TICK, STRADDLE) — adding a field forces a compile-time update to all 8 (a fan-out, not a parity
  risk; the two `ScalperConfig.from(...)` call sites do NOT break on arity). This is the same fan-out FU2
  documents.

### 2.5 The sizing path — `SignalEngine.emitEntry` + `PositionSizer`
- `SignalEngine.emitEntry` (L573-658). After the insert it stamps the advisory qty L605-614:
  ```java
  if (emissionGuard.isPresent()) {
    BigDecimal stopDistance = stopLoss == null ? null : entryPrice.subtract(stopLoss).abs();
    BigDecimal suggestedQty =
        emissionGuard.get().suggestedQty(
            strategy.definition().sizing(), exchange, tradingsymbol, entryPrice, stopDistance);
    if (suggestedQty != null) {
      signals.stampSuggestedQty(id, suggestedQty);
    }
  }
  ```
  The `decision` (with `decision.confluence().aggregate()`, `decision.side()`) is in scope here (it's the
  method parameter) — so a probability multiplier has its operand ready.
- `EmissionGuard.suggestedQty(SizingSpec sizing, exchange, tradingsymbol, price, stopDistance)`
  (`EmissionGuard.java:31-36`) is the SPI; `PaperEmissionGuard` (L46-57) calls
  `PositionSizer.size(sizing, new Inputs(account.equity(), price, stopDistance, meta.lotSize()))` and
  lot-rounds. `PositionSizer.size` (`libs/strategy-engine`) supports `fixed_quantity`/`percent_equity`/
  `premium_budget`/`atr_risk`/`kelly_fraction` (L26-62) — **`atr_risk` already implements 0.5%-risk-off-
  stop sizing** (L45-54: `risk_pct_equity` × equity ÷ stopDistance), it is simply never used by a scalper
  YAML (all use `premium_budget`).
- The qty is stamped via `signals.stampSuggestedQty(id, qty)` into a dedicated column (OUTSIDE the frozen
  `ScoreBreakdown`), and the per-dot detail rides `scalperDetailJson` (L667-706) → the `scalper_detail`
  jsonb → `ScalperDetail` FE type. **A new advisory annotation (multiplier / distance) belongs here, in
  the side-channel, exactly like the dots.**

### 2.6 VWAP provenance + the prior-day gap
- The scalper VWAP is the engine `IndicatorBank` session VWAP on the index future (`bank.builtin("vwap",
  index)`), i.e. the **current** session's VWAP. A grep over `services/strategy-signal-service/src/main`
  and `services/market-data-service/src/main` finds **no prior-day VWAP series, no `priorVwap`/`prevDayVwap`
  field**. So the §4.14.5/§3.9 "use yesterday's VWAP until 10:30/11:00, then today's" rule has no data
  source today — it is the one part of `vwap-distance-sizing` that needs a new series (Open Point #1). The
  *current-day* distance band (the bulk of the gap rows) needs nothing new.

### 2.7 The OI-gap + VIX operands for graded sizing
- `ScalperGateContext.Oi` (L39-52) carries `callPutDeltaImbalancePct`, `ceOiDelta`/`peOiDelta`,
  `trendingPeMinusCePct` — the "Trending-OI gap %" the §2.14 r65 sizing rule reads. `Macro` (L59-68)
  carries `vixLevel`/`vixRising` (today null — see the macro-vix stream) and `advances`/`declines`. The
  confluence `aggregate` (`Confluence.aggregate()`) is the single best probability proxy already computed.
  So a graded multiplier can read: aggregate (always present), OI-imbalance % (present on live), VIX band
  (present once the macro-vix stream wires the feed; degrades to neutral until then).

### 2.8 The parity firewall (unchanged from FU2's analysis)
- `GoldenDeterminismTest.FEATURES` and `BacktestParityTest.FEATURES` carry ONLY the 5 pure-engine YAMLs
  (no scalper). Neither harness instantiates `ScalperConfluenceGate`/`ConnectTheDotsScorer`/`SignalEngine`
  on the scalper path. `suggestedQty` is stamped OUTSIDE the frozen `ScoreBreakdownJson`/`GoldenSignalsJson`
  serializer, so it can never perturb a golden. **A tag-gated VWAP-distance hard gate cannot perturb the
  goldens** provided no golden/parity YAML carries the new tag (none can).

---

## 3. Design — per package

### 3.0 Design choices (apply across the stream)
1. **Entry skip = early-return hard gate behind a default-OFF tag** (the #5/FU2 shape), NOT a scorer
   AND-term — keeps `ConnectTheDotsScorer.score(...)` a pure function with an unchanged signature and
   leaves the existing `vwap` soft dot's weight contribution untouched.
2. **Sizing = advisory multiplier on the already-advisory `suggested_qty`**, computed in
   `SignalEngine.emitEntry`, stamped in the side-channel. It NEVER gates emission, so it is `[S]` and needs
   no tag (it is on by default *as a number*, but since it only annotates an advisory qty it is
   parity-irrelevant — see §4). A conservative default of multiplier `1.0` keeps the stamped qty
   byte-identical to today unless the strategy opts into grading via a knob.
3. **All new thresholds ride `ScalperOiProps`** (DB-/config-tunable), matching the established rule that
   freshly-derived tuning knobs live there, not as Java constants (`ScalperOiProps` javadoc L6-12).

---

### 3.1 `vwap-distance-sizing` (7 gaps)

Two mechanically-distinct features the 7 rows fold into:

#### 3.1a A VWAP/indicator-distance ENTRY SKIP gate  — `[P]`
*(connect-the-dots.md L33, gap-theory.md L25, hero-zero.md L29, risk-framework.md L21, intro L15,
session-additions.md L35 — "if candle-VWAP/Supertrend gap too wide, wait or skip"; "no trades when sellers
pin price at VWAP / low range".)*

**File 1 — `ScalperGates.java`: a new pure gate.**
```java
/**
 * VWAP-distance band [§2.2 r8 / §3.4 L606]. The entry must be a PULLBACK, not extended away from
 * VWAP: PASS when |close - vwap| / refPrice (a fraction) is at/BELOW {@code maxFrac} (price is near
 * VWAP). When {@code minFrac > 0} a SECOND clause also requires the distance be at/ABOVE {@code
 * minFrac} (the Hero-Zero "VWAP-pin low-range sit-out": too CLOSE to VWAP = premium-erosion chop, no
 * trade). Null close/vwap/refPrice DEGRADES to PASS (fail-open, like #5/VIX) so missing data never
 * gates an entry. refPrice = the close (so the band is a % of price, index-agnostic).
 */
public static GateOutcome vwapDistance(
    BigDecimal close, BigDecimal vwap, BigDecimal minFrac, BigDecimal maxFrac) {
  if (close == null || vwap == null || close.signum() <= 0) {
    return GateOutcome.pass(null, "vwap distance unavailable (degrade -> pass)");
  }
  BigDecimal frac = close.subtract(vwap).abs().divide(close, 6, RoundingMode.HALF_UP);
  boolean tooFar = maxFrac != null && frac.compareTo(maxFrac) > 0;
  boolean tooClose = minFrac != null && minFrac.signum() > 0 && frac.compareTo(minFrac) < 0;
  boolean ok = !tooFar && !tooClose;
  return new GateOutcome(ok, frac, "|close-vwap|/close " + frac.toPlainString()
      + (ok ? " within band" : tooFar ? " too far from VWAP" : " too close (VWAP pin)"));
}
```
*(Reuse the existing `RoundingMode` import; `GateOutcome` carries the `frac` so the reason rides the
side-channel, like every other gate.)*

**File 2 — `ScalperOiProps.java`: two tunable knobs + defaults.**
```java
// vwap-distance-sizing: the max |close-vwap|/close fraction an entry may sit at (a "pullback, not
// extended" band). 0.004 = 0.4% of price (~80 NIFTY pts at 20000) is a cautious v1 placeholder —
// DB-tunable per-index. Null => the gate's maxFrac clause is inert.
BigDecimal vwapMaxDistanceFrac,
// vwap-distance-sizing: the Hero-Zero "VWAP-pin sit-out" min fraction; below it = premium-erosion
// chop. Default 0 (the min clause OFF) so only the #7 variant that wants it sets it.
BigDecimal vwapMinDistanceFrac,
```
*(Add to the record header + the compact-constructor null-fill block + the `defaults()` all-null literal,
matching the existing 11-field pattern L18-78. DEFAULT_VWAP_MAX = `new BigDecimal("0.004")`,
DEFAULT_VWAP_MIN = `BigDecimal.ZERO`.)*

**File 3 — `ScalperConfig.java`: the arming tag.**
- Add `boolean requireVwapDistance` to the record (after `requireStraddle`, the FU2 placement
  convention).
- Parse `boolean vwapDistance = tags.contains("vwap-distance");` alongside L153.
- Thread through the constructor (L154-156). Extend the class-javadoc tag inventory.

**File 4 — `ScalperConfluenceGate.java`: the early-return.**
Insert immediately AFTER the side decision (after L152, before the volume/RSI rails) — it needs only
`chart` + the `oiProps`, and should fail fast before the chain-context fan-out (mirroring #5):
```java
// vwap-distance: when the strategy declares `vwap-distance`, an entry too far from (or, for #7, too
// close to / pinned at) VWAP is a HARD skip — the Siva "wait for a pullback near VWAP, don't chase
// the extended move" discipline (§2.2 r8 / §3.4 L606). Null operands DEGRADE to pass (fail-open).
if (cfg.requireVwapDistance()
    && !ScalperGates.vwapDistance(
            chart.close(), chart.vwap(),
            oiProps.vwapMinDistanceFrac(), oiProps.vwapMaxDistanceFrac())
        .pass()) {
  return Optional.empty();
}
```
**Data flow:** the gate only changes *whether* the signal fires; the `vwap` dot already rides the
side-channel, and the gate's `frac` reason rides the `GateOutcome` (no new persisted field, no API/FE/
contract change). Default OFF (no shipped YAML carries `vwap-distance`) → every existing config
byte-identical.

> **Supertrend/VWMA-distance note (the "or Supertrend gap too wide" half of risk-framework.md L21 &
> session L35):** v1 anchors the band on VWAP only (the §0B-decisive line). A VWMA/PSAR/ST-distance
> variant is a trivial superset (same arithmetic on `chart.vwma20()`/`chart.psar()`); recorded as Open
> Point #2 rather than guessed, since the doc emphasises VWAP as the decisive line.

#### 3.1b Prior-day VWAP series + the 10:30/11:00 switch  — `[S]` (read-only series) + `[P]` (if it gates)
*(intro-terminology.md L14, session-additions.md L23, morning-trade.md L21: "use yesterday's VWAP
open→~10:30/11:00, then today's"; this is the data source FU1's `time_of_day_vwap` manual check points
at.)*

This is the one part with no data source today (§2.6). **Recommended v1: build the prior-day VWAP only as
a read-only annotation** (a `priorDayVwap` value computed from the prior session's 1m bars of the index
future, surfaced in the side-channel next to the live VWAP so the operator sees both), and feed the
**3.1a distance gate** from `priorDayVwap` instead of the live `vwap` *only* before 10:30 IST when an
opt-in `prior-day-vwap` sub-tag is set. The "before 10:30 use prior-day, after use today's" switch already
has a precedent in the seam: `ScalperConfig.VWAP_ACTIONABLE_FROM = 10:30` (L76) and the opening-tick
`vwapHardGate` toggle (`ScalperConfluenceGate` L249). Reuse that constant.

- **Series source:** the index-future 1m series is already in `seriesStore`; a prior-session VWAP is
  `Σ(typical×vol)/Σvol` over the prior session's bars — computable deterministically. Surface it via a new
  read on `MarketOiClient` (the live-only client the seam already uses), keeping the seam pure. If the
  prior session is unavailable it returns null → the switch degrades to the live VWAP (fail-open).
- **Parity:** the annotation is `[S]` (side-channel only). The *gate-switch* (feeding the distance gate
  from prior-day VWAP before 10:30) is `[P]` and rides the SAME `vwap-distance` tag plus a sub-flag
  `prior-day-vwap` — both default OFF.

**Effort note:** 3.1b is the heavy slice. Recommend shipping 3.1a first (no new series) and deferring 3.1b
to its own PR gated on the Open Point.

---

### 3.2 `probability-graded-sizing` (13 gaps)  — `[S]`

All 13 rows are advisory `suggested_qty` shaping — none changes which signals fire (the disposition
explicitly marks the package `[S]`: "sizing / `suggested_qty` — advisory, does not change which signals
fire"). The single code surface is a **multiplier** applied to the existing `suggestedQty` stamp.

**File 1 — a new pure multiplier function (new class `ScalperSizing.java` in the scalper package, or a
static in `ScalperGates`).**
```java
/**
 * Probability-graded size multiplier in (0, 1] (master plan §2.14 r65, §3.5 Risk, §3.9 S21(d)). FULL
 * lot when the confluence is strong + the OI gap wide + VIX calm; a graded REDUCTION otherwise:
 *   m = base(aggregate) × oiGapFactor × vixFactor   (each clamped to [floor, 1.0])
 * Reads only values already on the Decision/context, so it is pure + advisory. Returns 1.0 when no
 * grading inputs are present (byte-identical to today's flat budget).
 */
public static BigDecimal sizeMultiplier(
    BigDecimal aggregate,                 // Confluence.aggregate(), 0..1
    BigDecimal oiImbalancePct,            // Oi.callPutDeltaImbalancePct(), nullable
    Boolean vixRising, BigDecimal vixLevel,  // Macro, nullable today
    ScalperOiProps p) { ... }
```
Grading rules (each a `ScalperOiProps` knob, default a no-op):
- **Confluence base** (§2.14 r65, §3.5 Risk "size off the confluence aggregate"): below a `sizeFullAggregate`
  cut (e.g. 0.75) scale linearly down to a `sizeFloorMultiplier` (e.g. 0.5) at the entry threshold 0.6.
- **OI-gap factor** (§2.14 r65 "size to the Trending-OI gap"; §3.5): a thin imbalance (< a band, e.g. the
  same `crossFilterPct` 50%) halves; ≥ the band = full.
- **VIX factor** (§2.14 r65 "size to VIX"): high VIX (once the feed lands) reduces; null VIX → 1.0
  (no-op today).
- Defaults: every knob null/unset → the function returns `1.0`, so **the stamped qty is byte-identical
  to today** until the owner tunes a knob (the "advisory, doesn't change emission" guarantee, and even the
  *number* is unchanged at defaults).

**File 2 — `EmissionGuard` SPI + `PaperEmissionGuard`.** Extend `suggestedQty` to accept the multiplier
(or apply it at the call site — see below). Cleanest: keep `PositionSizer` unchanged and apply the
multiplier in `SignalEngine.emitEntry`:
```java
// probability-graded sizing: scale the advisory qty by the confluence/OI-gap/VIX multiplier
// (§2.14 r65). Advisory only — never changes whether the signal fired. Default 1.0 => no change.
if (suggestedQty != null && decision != null) {
  BigDecimal m = ScalperSizing.sizeMultiplier(
      decision.confluence().aggregate(), /* oi imbalance + vix from the decision/ctx */ …, oiProps);
  BigDecimal graded = lotRoundDown(suggestedQty.multiply(m), lotSize);   // never round UP
  suggestedQty = graded.signum() > 0 ? graded : suggestedQty;           // never zero out an entry
}
```
*(Wrinkle: the OI-imbalance % and VIX live on the `ScalperGateContext`, which is built inside the seam and
not currently returned on the `Decision`. To keep `emitEntry` pure of market-data, surface the two scalar
operands on the `Decision` record (additive fields `oiImbalancePct`, `vixLevel`/`vixRising`) — they are
ALSO `[S]` since `Decision` is not a golden-serialized type. Alternatively read them off
`decision.confluence().dots()` which already carries the scored values. See Open Point #3 for the cleaner
of the two.)*

**Specific rows folded in (all advisory annotations on the same multiplier or a side-channel note):**
- *0.5%-risk-off-stop* sizing (risk-framework.md L18, §2.2 r5): this is already `atr_risk` in
  `PositionSizer` (L45-54) — close it by shipping a new YAML *variant* with
  `position_sizing: { method: atr_risk, params: { risk_pct_equity: 0.5 } }` keyed off the structural stop
  (a NEW default-OFF variant, no edit of a shipped config → `[S]`).
- *deployment caps* (risk-framework.md L19 §2.2 r6 ">10-20% single / >20% day"), *30%-of-capital cap*
  (open-high-low.md L27 §3.2 risk[0]): a per-strategy `max_deploy_pct` advisory ceiling on the multiplied
  qty (clamp, never raise). Account-day caps belong to the `daily-target-caps` stream; the per-TRADE
  fraction is this one's.
- *win=loss qty symmetry / recycle-profit risk / deployed-vs-overall frame / survive-a-quarter / Hero-Zero
  low-delta cap* (risk-framework.md L22-24, L44, L49): these are read-DAY-state advisory warnings (the
  multiplier reads `dayPnl` once it is plumbed) — v1 ships the multiplier hooks + records the day-P&L read
  as an Open Point (#4), since `dayPnl` is an account-side feed in the `daily-target-caps`/ledger stream.
- *align/oppose lot-modulation* (morning-trade.md L30, §3.9 S21(d) "full lot when aligned, reduced when
  opposing/neutral"): this is EXACTLY the confluence-base term — a strong aggregate = full, weak = reduced.
- *grade off RSI proximity + drastic-OI* (trending-oi.md L24, L38): an extra factor reading the RSI
  distance-to-extreme + the `drastic_oi` dot; folded into the same multiplier (a `rsiProximityFactor`
  knob).

**Parity:** all `[S]`. The multiplier defaults to 1.0; `suggested_qty` is OUTSIDE the frozen breakdown and
invisible to both goldens; the new `atr_risk` variant is a brand-new YAML with no existing golden.

---

### 3.3 `time-of-day-preference` (1 gap)  — `[P]`

*(trending-oi.md L37, §3.5 Setup.3 / S21(b) / Filters: "best 10:00-11:30 AM; avoid initiating after
~13:30-14:00". The hard rails ≥09:45 / 11:00-13:00 block / ≤15:30 are already encoded in
`ScalperGates.timeWindow` L33-44; the 10:00-11:30 *preference* + the ~13:30 cutoff are NOT weighted.)*

Two faithful options; recommend **(a)** for v1:

**(a) A soft time-of-day DOT in the scorer (preferred — it grades, doesn't block):** add one
`time_of_day` dot to `ConnectTheDotsScorer` that supports when `istTime` is in the high-probability window
(10:00-11:30) and withholds outside it.
- **⚠ PARITY trap:** adding a dot to the scorer changes the aggregate denominator (`den` L100-107) for
  EVERY bar → it would shift live confluence for every scalper AND is exactly the failure mode FU2 calls
  out for the Dow dot (FU2 §8 Open Point 2). So a scorer dot is **only** parity-safe if it is added behind
  a tag that ALSO gates it INTO the scorer — which the current pure `score(...)` signature cannot express
  without a new flag. **Not recommended for v1.**

**(b) A soft time-of-day SKIP gate behind a default-OFF tag (recommended):** mirror 3.1a — a
`ScalperGates.timeOfDayPreference(istTime, from, to)` that PASSES inside the preferred window and FAILS
(skips the entry) outside it, armed by a `time-of-day-preference` tag, with the window as `ScalperOiProps`
`LocalTime` knobs (`preferFrom` 10:00 / `preferTo` 13:30). Insert as an early-return after the existing
`timeWindow` check (L116). Because it is tag-gated default-OFF and is an early-return (not a scorer term),
it is parity-safe exactly like #5/FU2.
- Trade-off: (b) is a HARD skip, not a soft preference — it converts "prefer" into "only". The doc's intent
  is a *preference*, so the honest v1 is to make the window a tunable skip a variant opts into, and leave
  the genuine soft-weighting to the scorer-dot redesign (Open Point #5). Recorded so the owner picks.

**Files:** `ScalperGates.java` (the new gate), `ScalperConfig.java` (`requireTimeOfDayPreference` +
`time-of-day-preference` parse), `ScalperConfluenceGate.java` (early-return after L116), `ScalperOiProps`
(the two `LocalTime` knobs — or hold them as `ScalperConfig` constants like `OPENING_FROM` L72, since
they are clock bounds not OI knobs; recommend `ScalperConfig` constants for consistency).

---

## 4. PARITY classification (every change)

| Change | Class | Why / tag + golden plan |
|---|:--:|---|
| 3.1a VWAP-distance entry-skip gate | **[P]** | Alters emission when armed. NEW tag **`vwap-distance`**, default-OFF (no shipped YAML carries it → every existing config byte-identical). NO new golden variant is created in this PR (the gate is LIVE-only, invisible to `GoldenDeterminismTest`/`BacktestParityTest` whose FEATURES carry no scalper). Positive coverage = the seam unit-test triple (§5). |
| 3.1b prior-day VWAP **annotation** | **[S]** | Read-only side-channel value; never gates. |
| 3.1b prior-day-VWAP **gate-switch** (feed the 3.1a gate from prior-day VWAP < 10:30) | **[P]** | Rides the `vwap-distance` tag + a default-OFF `prior-day-vwap` sub-flag. Same golden-invisible argument. |
| 3.2 probability-graded size **multiplier** (+ `max_deploy_pct` clamp, RSI/OI-gap/VIX factors) | **[S]** | Annotates the advisory `suggested_qty` ONLY (stamped outside the frozen `ScoreBreakdown`/`GoldenSignalsJson`); defaults to `1.0` → stamped qty byte-identical until a knob is tuned. Never changes which signal fires. |
| 3.2 new `atr_risk` 0.5%-risk YAML **variant** | **[S]** | Brand-new strategy file → no existing golden to perturb; `atr_risk` already in `PositionSizer`. |
| 3.2 additive `Decision`/SPI scalar fields (`oiImbalancePct`, `vixLevel`) | **[S]** | `Decision` is not golden-serialized; the SPI is a live-only port. |
| 3.3 time-of-day skip gate (option b) | **[P]** | NEW tag **`time-of-day-preference`**, default-OFF, early-return (not a scorer term) → golden-invisible like 3.1a. |
| 3.3 time-of-day scorer **dot** (option a) | **[P], REJECTED** | Would change the aggregate denominator for every bar (the FU2 Dow-dot failure mode) → not parity-safe without a scorer-signature redesign. Deferred (Open Point #5). |

**The `[P]` golden discipline (per FU2 + CLAUDE.md):** for each new tag (`vwap-distance`,
`prior-day-vwap`, `time-of-day-preference`) — (1) it is absent from all 36 shipped YAMLs so the configs are
byte-identical; (2) `GoldenDeterminismTest` + `BacktestParityTest` are re-run and asserted byte-identical
(NOT regenerated); (3) arming any tag on a real strategy is a SEPARATE owner-driven follow-up (the "tune on
live, not backtest" principle), and even then the goldens stay green because no golden YAML is a scalper.

---

## 5. Tests

### 5.1 Unit — pure gate / multiplier functions
- **`ScalperGatesTest.java`** (existing — mirrors `breadthThirtyTwoCutoff` etc.):
  - `vwapDistancePassesNearVwapAndSkipsWhenTooFar` — close=20080/vwap=20000 (0.4%) at maxFrac 0.004 PASS;
    close=20200 (1.0%) FAIL; null close/vwap PASS (fail-open).
  - `vwapDistanceMinClauseSkipsWhenPinned` — minFrac 0.001, close=20010/vwap=20000 (0.05%) FAIL (pinned);
    minFrac 0 → the min clause never fires.
  - `timeOfDayPreferenceWindow` — 10:30 in [10:00,13:30] PASS; 14:00 FAIL; boundary at `to` exclusive.
- **`ScalperSizingTest.java`** (new):
  - `multiplierIsOneAtDefaults` — all-null props → `1.0` (the byte-identical guarantee).
  - `weakConfluenceReducesSize` — aggregate 0.6 → floor 0.5; aggregate ≥ full-cut → 1.0; monotone in
    between.
  - `thinOiGapHalvesAndWideOiGapFull`; `nullVixIsNeutral`.

### 5.2 Unit — seam wiring (`ScalperConfluenceGateTest.java`)
The constructor-arity change forces appending the new boolean(s) (`false`) to all **8** existing
`new ScalperConfig(...)` literals (L43-90) — positional, every existing literal stays unarmed.
For **each** new tag (`vwap-distance`, `time-of-day-preference`) add one CFG literal + the #5-template
triple (the helper bank/context shapes are `bullBank()` L115, `bullContext()` L137; the time-block test
at L216 shows the `LocalTime` override pattern):
- `vwapDistanceStrategySkipsWhenPriceFarFromVwap` — a bank with close far above vwap (still CE side) +
  the `vwap-distance` CFG → `.isEmpty()`; a near-VWAP bank → `.isPresent()`.
- `nonVwapDistanceStrategyIsUnaffectedByAFarPrice` — bare `CFG` + the far bank → `.isPresent()` (the gate
  is never consulted; vwap stays a soft dot).
- `timeOfDayPreferenceStrategySkipsAfterCutoff` — pass `LocalTime.of(14,0)` with the time-of-day CFG →
  `.isEmpty()`; `LocalTime.of(10,30)` → `.isPresent()`. Bare `CFG` at 14:00 still passes the §0B window.
- **Sizing seam:** `ScalperRiskIntegrationTest` / `PaperAccountRiskIntegrationTest` (the existing
  `suggestedQty` ITs) get a case asserting the multiplied qty: a weak-confluence decision stamps a
  REDUCED `suggested_qty` vs a strong one, and a default-props decision stamps the SAME qty as today.

### 5.3 Load test (`ScalperStrategyLoadTest.java`)
After the seed loop, add OFF assertions (the regression tripwire that no tag is silently armed):
```java
assertThat(cfg.requireVwapDistance()).as(id + " vwap-distance gate off").isFalse();
assertThat(cfg.requireTimeOfDayPreference()).as(id + " time-of-day gate off").isFalse();
```

### 5.4 Golden / parity tripwires (MUST stay byte-identical, NOT regenerated)
- `GoldenDeterminismTest` (`libs/strategy-engine`) — re-run, assert byte-identical green.
- `BacktestParityTest` (`services/backtest-service`) — re-run, assert the three byte-match asserts stay
  green. These prove the gate + the sizing annotation did not leak onto the deterministic replay path.

### 5.5 e2e (`e2e/tests/signals.spec.ts`)
Default-OFF → no new signal fires/suppresses and `suggested_qty` is unchanged at default props, so
`signals.spec.ts` must stay green as-is (re-run as a regression check). When the `[S]` multiplier ships
with a non-default knob on a published strategy (a later owner step), add an assertion that the signal
card renders the (reduced) suggested qty — NOT in this stream's default-off PRs.

---

## 6. Dependencies & sequencing

1. **3.1a (VWAP-distance gate) has NO upstream dependency** — `chart.close()`/`chart.vwap()` are in-hand.
   Ship first; it is the highest-value, lowest-risk slice and the cleanest #5/FU2 copy.
2. **3.3 (time-of-day skip) has NO dependency** — pure clock arithmetic. Ship alongside or right after
   3.1a (same mechanical shape).
3. **3.2 confluence-base + OI-gap multiplier** depends on surfacing the OI-imbalance % / aggregate to
   `emitEntry` (additive `Decision`/SPI fields, §3.2 File 2). Independent of the macro-vix stream EXCEPT
   the **VIX factor**, which is inert until the `directional-vix-gate`/macro-vix stream wires
   `Macro.vixLevel`/`vixRising` (today null → factor 1.0). So 3.2 ships with the VIX factor present but
   dormant; it activates when the macro stream lands. **Sequence: 3.2 can ship independently; the VIX term
   light up later for free.**
4. **3.2 `atr_risk` variant** depends on a structural stop being present (`decision.structuralStop()` /
   the §0B bounding-exit rule) — already true for the gated strategies; a `signal_exit`-only strategy
   would size to zero on `atr_risk` (acceptable — it just won't use that method).
5. **3.1b (prior-day VWAP series)** depends on a NEW market-data read (the prior-session VWAP) + an Open-
   Point decision; sequence LAST, in its own PR, gated on Open Point #1. Do not block 3.1a on it.
6. **Cross-stream:** the `daily-target-caps` + `five-account-ledgers` streams own `dayPnl` and the per-
   account split; 3.2's day-P&L-aware factors (recycle-profit, win=loss symmetry) plug into that feed when
   it exists — until then those specific rows ship as dormant multiplier hooks (Open Point #4). No SPAN /
   equity-universe dependency (this stream is index-options long-premium only).

---

## 7. Effort + suggested PR breakdown

Overall effort: **M** (mostly the #5/FU2 copy + an advisory multiplier; the only L slice is 3.1b prior-day
VWAP, deferred).

- **PR-1 `feat(strategy-signal): vwap-distance entry-skip gate (tag-gated, default-off)`** — **S/M.**
  3.1a: `ScalperGates.vwapDistance` + `ScalperOiProps` two knobs + `ScalperConfig.requireVwapDistance` +
  the seam early-return + the gate unit tests + the seam triple + the load-test OFF assertion + the two
  golden tripwires. (Pure #5 copy; lowest risk.)
- **PR-2 `feat(strategy-signal): time-of-day-preference skip gate (tag-gated, default-off)`** — **S.**
  3.3 option (b): the gate + tag + early-return + tests. (Could merge into PR-1 — mechanically identical —
  but a separate PR keeps each new tag reviewable on its own, per the FU2 PR-split convention.)
- **PR-3 `feat(strategy-signal): probability-graded suggested-qty multiplier (advisory, default 1.0)`** —
  **M.** 3.2: `ScalperSizing.sizeMultiplier` + the additive `Decision`/SPI scalar fields + the `emitEntry`
  multiply + `max_deploy_pct` clamp + the `ScalperSizingTest` + the sizing-IT case. `[S]` throughout;
  defaults keep the stamped qty byte-identical.
- **PR-4 (optional, same scope) `feat(strategy-signal): atr_risk 0.5%-risk scalper variant`** — **S.** A
  new YAML variant (or three: nifty + two sensex) using `atr_risk` — a brand-new default-OFF config.
- **PR-5 (DEFERRED, gated on Open Point #1) `feat: prior-day VWAP series + pre-10:30 switch`** — **L.**
  3.1b: the new market-data prior-session-VWAP read + the side-channel annotation + the `prior-day-vwap`
  sub-flag gate-switch.

Each PR: short-lived `feat/` branch, Conventional Commit scoped `strategy-signal`, squash-merge, build
with the full reactor + `-am` (`-pl services/strategy-signal-service -am verify`, JaCoCo ≥ 60%).

---

## Open Points

1. **Prior-day VWAP series source + the 10:30-vs-11:00 switch time (3.1b).** The doc says "yesterday's
   VWAP until ~10:30" (intro §1.2) AND "until 11 AM" (§4.14.5) — the two sections disagree by 30 min, and
   there is no prior-day VWAP feed today. **Options:** (a) build the prior-session VWAP off the index-
   future 1m series in `MarketOiClient` and reuse the existing `VWAP_ACTIONABLE_FROM = 10:30` constant for
   the switch — **recommended default** (one source of truth for the switch time, matches the opening-tick
   precedent); (b) make the switch time a `ScalperOiProps` `LocalTime` knob defaulting 10:30; (c) defer
   3.1b entirely and ship only the FU1 manual `time_of_day_vwap` reminder. Recommend (a), PR-5, deferred.

2. **VWAP-only vs VWMA/Supertrend-distance band (3.1a).** risk-framework.md L21 + session-additions.md L35
   say "candle-VWAP/Supertrend gap"; v1 anchors on VWAP only. **Options:** (a) VWAP-only band (recommended
   — VWAP is the §0B-decisive line; simplest, covers 5 of the 7 rows); (b) a max over
   {VWAP, VWMA20, PSAR/ST} distance (stricter "near ALL the soldiers"); (c) a separate `st-distance` tag.
   Recommend (a) for v1, (b) as a tunable superset later.

3. **How `emitEntry` reads the OI-imbalance % / VIX for the multiplier (3.2).** The operands live on the
   in-seam `ScalperGateContext`, not on the returned `Decision`. **Options:** (a) add additive scalar
   fields (`oiImbalancePct`, `vixLevel`, `vixRising`) to the `Decision` record — explicit, typed,
   `[S]` (not golden-serialized) — **recommended**; (b) parse them back out of
   `decision.confluence().dots()` (already carried) — avoids touching `Decision` but is stringly-typed and
   fragile. Recommend (a).

4. **Day-P&L-aware sizing factors (recycle-profit / win=loss symmetry / Hero-Zero low-delta cap).** These
   need `dayPnl`, which is an account-side feed owned by the `daily-target-caps`/ledger stream. **Options:**
   (a) ship the multiplier hooks now but leave the day-P&L factor a no-op until that feed lands (recommended
   — keeps this stream self-contained); (b) block these specific rows on the ledger stream. Recommend (a):
   the rows are dispositioned `probability-graded-sizing` here but their DATA dependency is the other
   stream — close them as dormant hooks, light them up when `dayPnl` is plumbed.

5. **time-of-day: hard skip gate (option b) vs soft scorer dot (option a) (3.3).** A soft dot is the more
   faithful "preference" but changes the scorer denominator for every bar (the FU2 Dow-dot parity failure)
   unless `score(...)` gains a flag-threaded conditional dot — a scorer-signature redesign out of scope
   here. **Options:** (a) ship the tag-gated HARD skip now (recommended — parity-safe, honest as a
   tunable "only trade this window" knob a variant opts into); (b) defer to a scorer-dot redesign that adds
   conditional dots behind tags without breaking the denominator (a larger, separate effort). Recommend
   (a) for v1, (b) recorded for the scorer-redesign backlog.

6. **`max_deploy_pct` per-trade cap — `ScalperConfig` constant vs `ScalperOiProps` knob vs YAML.** The
   30%/20% single-trade deployment caps (§3.2 risk[0], §2.2 r6) need a ceiling. **Options:** (a) a
   `ScalperOiProps` knob (DB-tunable, default high so it never binds) — **recommended**, matches the OI/IV
   knob convention; (b) read `risk.max_deploy_pct` from the YAML (a new schema key → a springdoc/contract
   consideration). Recommend (a); the per-DAY cap stays in the `daily-target-caps` stream.

7. **Default `vwapMaxDistanceFrac` value.** 0.004 (0.4% of price) is a placeholder — the doc gives no
   number ("too wide"). **Options:** (a) ship 0.4% as a cautious DB-tunable default and calibrate on
   forward paper (recommended — never armed by default anyway); (b) leave it null so the tag is inert until
   the owner sets it. Recommend (a) so an armed variant has a sane starting band.
