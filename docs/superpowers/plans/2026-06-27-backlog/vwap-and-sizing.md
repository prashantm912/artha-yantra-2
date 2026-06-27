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
sizes with a flat `premium_budget` (the §3.5 audit calls this "fixed ₹15k") and VWAP is
a boolean side/hard-gate only — its DISTANCE is never read. (**AUDIT pass 2:** 33 of the 36 shipped
YAMLs use `budget_inr: 15000`; the 3 hero-zero / §7 variants use the smaller `budget_inr: 2000` — so
"flat ₹15k" is the dominant-but-not-universal case. Immaterial to the design: the §3.2 multiplier
defaults to `1.0` and rides on top of whatever budget the YAML carries.)

> **Source-row path (audit-corrected):** every `file:line` below is a row in
> `docs/strategy-audit/disposition/<file>.md` — the disposition tables, NOT the bare
> `docs/strategy-audit/<file>.md` audit tables (those carry different line numbers). `session-additions.md`
> is shorthand for `session-additions-and-manual-coverage.md` (the only file whose name is abbreviated).

| Package | gap count | Source disposition rows (file:line) | Doc-§ |
|---|---:|---|---|
| **`vwap-distance-sizing`** | 7 | connect-the-dots.md L33 (S22(a)); gap-theory.md L25 (§3.4 L606); hero-zero.md L29 (§3.7 filters); intro-terminology.md L15 (§1.2); morning-trade.md L21 (§3.9 S21/S22); risk-framework.md L21 (§2.2 r8); session-additions-and-manual-coverage.md L35 (§4.15.3) | §1.2, §2.2, §3.4/3.7/3.9/3.10, §4.15.3 |
| **`probability-graded-sizing`** | 13 | risk-framework.md L18,L19,L22,L23,L24,L44,L49,L53 (§2.2/2.3/2.10/2.12-14); open-high-low.md L27 (§3.2 risk[0]); trending-oi.md L24,L38 (§3.5 Risk/6.5); morning-trade.md L30 (§3.9 S21(d)); session-additions-and-manual-coverage.md L29 (§4.14.9) | §2.2/2.3/2.10/2.12/2.13/2.14, §3.2/3.5/3.9, §4.14.9 |
| **`time-of-day-preference`** | 1 | trending-oi.md L37 (§3.5 Setup.3 / S21(b) / Filters / 6.5) | §3.5; (also intro §1.2 ideal 9:15-10:00 — ACCEPT_BY_DESIGN) |

Stream total: **21 AUTOMATE_PKG gap rows** (`7 + 13 + 1`). Many `probability-graded-sizing` rows
collapse onto the SAME code surface (one confluence/VIX/OI-gap qty-multiplier + a few account-side caps),
so the *code surface* is far smaller than 13 — the count is gaps-closed, not files-touched.

**Scope boundary (so nothing double-counts the other backlog streams):**
- The `daily-target-caps` package (7 gaps: daily profit/loss % caps + over-trade taper) and the
  `five-account-ledgers` package (2 gaps: per-account split + first-loss freeze) are **account-side rails
  in a SEPARATE stream** — several risk-framework rows that *mention* sizing are dispositioned there, not
  here. This stream owns only the rows whose disposition cell literally says `probability-graded-sizing`
  or `vwap-distance-sizing` (risk-framework.md AUTOMATE_PKG-themes block L72-77 enumerates them).
  (**AUDIT pass 2 — count reconciliation:** the themes block at risk-framework.md L72 *labels*
  `probability-graded-sizing` "(9 rows)", but only **8** rows in that disposition table literally carry
  the tag (L18,19,22,23,24,44,49,53 — verified by grep); the "(9 rows)" is the disposition doc's own
  self-count and is off by one. This plan's §1 citation table cites those 8 risk-framework rows + 5 from
  the other files = the **13-gap** `probability-graded-sizing` total, which is internally consistent. Use
  8, not 9, for the risk-framework slice.) `vwap-distance-sizing` = 1 risk-framework row (L21) + 6 from
  the other files = 7.
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
  risk). **Two more producers of the canonical constructor must also change:** the `from(...)` factory's own
  `new ScalperConfig(...)` at **L154-156** (pass the new flag positionally) and the `record` header at
  L36-52. The two EXTERNAL `ScalperConfig.from(...)` call sites (`ScalperStrategyLoadTest.java:130`,
  `SignalEngine.java:195` — verified) do NOT break, because `from(...)`'s signature is unchanged. This is
  the same fan-out FU2 documents.

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
   freshly-derived tuning knobs live there, not as Java constants (`ScalperOiProps` javadoc L6-16).

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
*(**AUDIT-CORRECTED:** `ScalperGates.java` does NOT currently import `java.math.RoundingMode` (only
`ConnectTheDotsScorer`/`MarketOiClient` do) — ADD the import. `GateOutcome(boolean pass, BigDecimal
operand, String reason)` carries the `frac` as the operand so the reason rides the side-channel, like
every other gate; `GateOutcome.pass(null, …)` and the 3-arg constructor used above are both real
accessors — verified against `GateOutcome.java`.)*

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
*(Add to the record header (L18-29) + the compact-constructor null-fill block (L57-73) + the `defaults()`
all-null literal (L77, which must grow from 11 to 13 nulls), matching the existing 11-field pattern.
DEFAULT_VWAP_MAX = `new BigDecimal("0.004")`, DEFAULT_VWAP_MIN = `BigDecimal.ZERO`.)*
> **AUDIT note (comment vs pattern):** the established `ScalperOiProps` pattern null-fills every field to a
> default in the compact constructor, so `vwapMaxDistanceFrac` is NEVER null at runtime — it defaults to
> 0.004, not "inert". The "Null => inert" wording above is therefore inaccurate; correct it to "the gate is
> only consulted when `requireVwapDistance` is armed (default OFF), so the 0.004 default is dormant until a
> variant opts in." (No parity impact — the gate is tag-gated OFF.) **Scale caveat:** these two knobs are
> FRACTIONS (0.004 = 0.4 %), unlike the sibling `crossFilterPct`/`spurt*` knobs which are PERCENT-scale
> (50 = 50 %). Keep the units straight: `frac` (a fraction) is compared against `vwapMaxDistanceFrac`
> (also a fraction) — internally consistent — but do NOT reuse a percent-scale default here.

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

**File 2 — `EmissionGuard` SPI + `PaperEmissionGuard` (AUDIT-CORRECTED — the multiplier MUST be applied
inside the paper adapter, not in `SignalEngine.emitEntry`).**

> **Why the original "apply it in `SignalEngine.emitEntry`" was wrong (two hard blockers, both verified):**
> 1. **No `lotSize` / lot-round helper at that call site.** Lot size lives only in `InstrumentMetaClient`
>    (`meta.lotSize()`), which is in the **paper** module and reached ONLY through `PaperEmissionGuard`
>    (`PaperEmissionGuard.java:52-55`). `SignalEngine` (the **signals** module) must NOT import paper — the
>    `EmissionGuard` SPI exists precisely to keep the module graph acyclic (`EmissionGuard.java:7-10`). So
>    `lotRoundDown(qty.multiply(m), lotSize)` simply cannot be written in `emitEntry`; there is no `lotSize`
>    nor a `lotRoundDown` there, and re-rounding off-lot would also diverge from `PositionSizer`'s own
>    rounding.
> 2. **No `oiProps` in scope.** `SignalEngine` holds no `ScalperOiProps` bean (verified — its ctor/fields
>    carry none). Threading the grading knobs in would mean a NEW `SignalEngine` dependency just for sizing.
>
> **Correct design:** add a nullable `BigDecimal multiplier` parameter to the `EmissionGuard.suggestedQty`
> SPI; `PaperEmissionGuard` already has `meta.lotSize()` and calls `PositionSizer.size(...)`, so it applies
> the multiplier and re-lot-rounds DOWN there (one rounding authority). `SignalEngine.emitEntry` only
> COMPUTES the multiplier (pure arithmetic on the in-scope `decision`) and passes it through:

```java
// in SignalEngine.emitEntry, at the existing L605-614 sizing block (decision is in scope):
BigDecimal multiplier =
    decision == null ? null : ScalperSizing.sizeMultiplier(decision /* aggregate + the §3.2-File-2 scalars */);
BigDecimal suggestedQty =
    emissionGuard.get().suggestedQty(
        strategy.definition().sizing(), exchange, tradingsymbol, entryPrice, stopDistance, multiplier);
// PaperEmissionGuard.suggestedQty(...) now:
//   long base = PositionSizer.size(sizing, new Inputs(equity, price, stopDistance, lot));
//   long graded = multiplier == null ? base
//       : Math.max(lot, /* lot-round-down */ BigDecimal.valueOf(base).multiply(multiplier)…);  // never 0 an entry, never round UP
//   return graded <= 0 ? null : BigDecimal.valueOf(graded);
```
> **`ScalperSizing` needs `ScalperOiProps`** for its knobs. Either (a) make `sizeMultiplier` a method on a
> Spring-managed `ScalperSizing` bean that is constructor-injected with the `ScalperOiProps` bean and wire
> THAT bean into `SignalEngine` (one new ctor dep on `SignalEngine`, holding `ScalperSizing`, not raw
> `oiProps`) — **recommended, keeps the knob source single**; or (b) pass a pre-resolved `ScalperOiProps`
> into `emitEntry`. Either way the parameter shape above is the load-bearing fix; the original snippet's
> in-`emitEntry` lot-round does not compile.

*(Wrinkle: the OI-imbalance % and VIX live on the in-seam `ScalperGateContext`, which is built inside the
seam and NOT returned on the `Decision` (verified: `Decision` = `(side, legs, confluence, expiry,
structuralStop)` only). `confluence().aggregate()` IS on the decision, but the OI-imbalance % / VIX are
not. To keep `emitEntry` free of market-data, surface the two scalar operands on the `Decision` record
(additive fields `oiImbalancePct`, `vixLevel`/`vixRising`) — they are ALSO `[S]` since `Decision` is not a
golden-serialized type. Alternatively read them off `decision.confluence().dots()` (the per-dot
`DotScore{dot,weight,supports}` carries support-booleans, NOT the raw imbalance/VIX scalars — so this
fallback can only re-derive a coarse boolean, not the % — making option (a) the only faithful path). See
Open Point #3.)*

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
`LocalTime` knobs (`preferFrom` 10:00 / `preferTo` 13:30). Insert as an early-return immediately AFTER the
existing `timeWindow` block closes (after L118 — **AUDIT pass 2:** the block is `if (!timeOk) {` at L116,
the inner `return Optional.empty();` at **L117**, the closing `}` at L118; the new gate goes after that `}`
at L118, before the chain fetch at L119. Pass 1 mis-attributed the inner `return` to L116 — the insertion
point is unchanged, only the line label was off by one). Because it is tag-gated
default-OFF and is an early-return (not a scorer term),
it is parity-safe exactly like #5/FU2.
- Trade-off: (b) is a HARD skip, not a soft preference — it converts "prefer" into "only". The doc's intent
  is a *preference*, so the honest v1 is to make the window a tunable skip a variant opts into, and leave
  the genuine soft-weighting to the scorer-dot redesign (Open Point #5). Recorded so the owner picks.

**Files:** `ScalperGates.java` (the new gate), `ScalperConfig.java` (`requireTimeOfDayPreference` +
`time-of-day-preference` parse), `ScalperConfluenceGate.java` (early-return after L118 — the `}` that closes the `if (!timeOk)` block; the inner `return` is L117, not L116 — AUDIT pass 2 fix), `ScalperOiProps`
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
| 3.2 additive `Decision`/SPI scalar fields (`oiImbalancePct`, `vixLevel`) + new `multiplier` param on `EmissionGuard.suggestedQty` | **[S]** | `Decision` is not golden-serialized; the SPI is a live-only port (paper module) — neither is touched by `GoldenSignalsJson`/`ScoreBreakdownJson`. The SPI arity change is a compile-time fan-out (the one `PaperEmissionGuard` impl + the `emitEntry` call site), NOT a parity risk. |
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
- **Sizing seam:** `ScalperRiskIntegrationTest` / `PaperAccountRiskIntegrationTest` (both verified to
  exist; **AUDIT pass 2:** `PaperAccountRiskIntegrationTest` is the one that actually exercises the
  `suggestedQty` stamp — put the multiplier assertion there) get a case asserting the multiplied qty: a weak-confluence
  decision stamps a REDUCED `suggested_qty` vs a strong one, and a default-props decision stamps the SAME
  qty as today. **Also add a `PaperEmissionGuard` unit/IT case** for the new SPI `multiplier` param: a
  `null` multiplier returns the un-graded qty (byte-identical to today's signature behaviour) and a 0.5
  multiplier returns a lot-rounded-DOWN, never-zero qty — this is the seam where the multiplier is actually
  applied (NOT `emitEntry`), so it must be covered there.

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
   in-seam `ScalperGateContext`, not on the returned `Decision` (verified: `Decision` = `(side, legs,
   confluence, expiry, structuralStop)`). **Options:** (a) add additive scalar fields (`oiImbalancePct`,
   `vixLevel`, `vixRising`) to the `Decision` record — explicit, typed, `[S]` (not golden-serialized) —
   **recommended**; (b) parse them back out of `decision.confluence().dots()` — **AUDIT-CORRECTED: this
   fallback is NOT viable for the raw scalars.** `DotScore` is `(String dot, double weight, boolean
   supports)` — it carries only the support BOOLEAN, never the raw imbalance % or VIX level. So (b) can at
   best recover a coarse "OI-gap dot supported / not", not the magnitude the §2.14-r65 graded factor needs.
   Recommend (a) — it is the only faithful path. **Also note (relates to §3.2 File 2):** whichever path,
   the multiplier is COMPUTED in `emitEntry` from the (now-enriched) `decision` and APPLIED inside
   `PaperEmissionGuard` via the new SPI `multiplier` param — never lot-rounded in `emitEntry` (no `lotSize`
   there; signals must not import paper).
   **AUDIT pass 2 — `Decision` constructor fan-out the plan under-stated (the parallel of the §2.4
   `ScalperConfig` fan-out):** option (a) adds fields to the `Decision` record, so BOTH `new Decision(...)`
   call sites in `ScalperConfluenceGate` must change positionally — the directional one at **L279** and the
   #11 straddle one at **L141-146** (verified: exactly 2 sites; the `ExitEvaluator` `Decision(...)` is an
   unrelated engine type). The straddle path branches and RETURNS *before* `ctx` is built (`ctx` is L191-192,
   the straddle return is L136-146), so it has **no `ctx.oi()`/`ctx.macro()`** — it must pass `null` for the
   new `oiImbalancePct`/`vixLevel`/`vixRising` scalars (a neutral straddle would size at the multiplier
   floor/neutral, which is acceptable — a direction-neutral position carries no OI-imbalance side read).
   The directional path at L279 DOES have `ctx` in scope, so it passes
   `ctx.oi().callPutDeltaImbalancePct()` + `ctx.macro().vixLevel()`/`vixRising()`. This is a `[S]`
   compile-time fan-out (Decision is not golden-serialized), not a parity risk — but enumerate it so the
   executor doesn't hit a surprise compile error or try to source OI scalars on the straddle path.

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

8. **(ADDED, audit) The `max_deploy_pct` per-trade cap is NOT part of `sizeMultiplier` — it lives where
   equity + lotSize do.** `ScalperSizing.sizeMultiplier(aggregate, oiImbalance, vix, props)` is pure on
   confluence/OI/VIX scalars and knows neither account equity nor the option premium-per-lot, so it CANNOT
   evaluate "deployed % of capital ≤ 30%". That clamp is `qty × price × lot ≤ equity × maxDeployPct`, which
   only `PaperEmissionGuard` can compute (it holds `account.equity()` + `meta.lotSize()` + `price`). **Plan
   correction:** apply the `max_deploy_pct` ceiling in `PaperEmissionGuard.suggestedQty` AFTER the
   multiplier + lot-round (clamp DOWN, never raise), reading the ceiling from a `ScalperOiProps` knob
   (Open Point #6 option a). The §3.2 prose that folds `max_deploy_pct` "onto the same multiplier" is
   imprecise — it is a separate clamp at the paper adapter, not a factor inside `sizeMultiplier`.

---

## Audit pass 1 findings

**Verdict: sound-with-open-points.** Every load-bearing citation was opened and verified against source;
the parity argument is correct and the [P]/[S] classification holds. Two real soundness defects in the
§3.2 sizing wiring were corrected in place (they would not have compiled as written), plus several cite
imprecisions. No change moves an existing signal at defaults; all `[P]` changes are tag-gated default-OFF
and the goldens stay byte-identical.

**Citations — verified correct (opened the real files):**
- `ScalperConfluenceGate.java`: `evaluate` L100-280, class-javadoc L21-33 (LIVE-only firewall), `Chart`
  built L124, `chart(...)` L304-316 (close L309 / vwap L310), side L149-152, volume/RSI rails L157-163,
  two-candle L167-169, gap-fill L173-179, #5 delta-filter L196-199, trend-change L204-211, open-high-low
  L218-229, hero-zero L236-245, ctx L191-192, `Decision` record L71-87 (no sizing field), `vwapHardGate`
  L249. ALL correct.
- `ScalperGateContext.java`: `Chart(close, vwap, vwma20, psar, supertrendDir, rsi14, volume)` L21-28; `Oi`
  carries `callPutDeltaImbalancePct`/`ceOiDelta`/`peOiDelta`/`trendingPeMinusCePct` (L39-52); `Macro`
  carries `vixLevel`/`vixRising`/`advances`/`declines` (L59-68). ALL correct.
- `ScalperGates.java`: `volume` L64-68, `rsiBand` L76-84, `indicatorAlignment` L102-118,
  `callPutDeltaFilter` L151-161, `gt` L173-175, `timeWindow` L33-44. ALL correct. `GateOutcome` is
  `(boolean pass, BigDecimal operand, String reason)` with `pass(...)`/`fail(...)` — the §3.1a gate code is
  type-sound.
- `ScalperConfig.java`: record L36-52, `requireCallPutDeltaFilter` L46, `requireStraddle` L52, constants
  L82-98, `from(...)` L101-157, `oi-cross-filter` L153, `two-candle-pattern` L119, `open-high-low` L125,
  ctor return L154-156, `VWAP_ACTIONABLE_FROM`=10:30 L76, `OPENING_FROM` L72. ALL correct.
- `ScalperOiProps.java`: 11-field record (L18-29), compact-constructor null-fill (L57-73), `defaults()`
  all-null (L77); `crossFilterPct` default 50 (percent scale). Javadoc is L6-16 (plan said L6-12 — fixed).
- `SignalEngine.emitEntry` L573-658; the L605-614 sizing block quoted in §2.5 matches byte-for-byte;
  `scalperDetailJson` L667-706. `EmissionGuard.suggestedQty(...)` L31-36; `PaperEmissionGuard.suggestedQty`
  L45-57 (lot-round actually inside `PositionSizer`, a harmless attribution slip); `PositionSizer.size`
  L26-62 with `atr_risk` at L45-54 reading `risk_pct_equity` — all correct, `atr_risk` 0.5%-off-stop sizing
  confirmed present-but-unused.
- Tests: `ScalperConfluenceGateTest` 8 literals L43-90 (positional booleans — adding `requireVwapDistance`
  after `requireStraddle` forces +1 `false` to each, confirmed), `bullBank()` L115, `bullContext()` L137,
  time-block test L216; `ScalperGatesTest.breadthThirtyTwoCutoff` L110; `ScalperStrategyLoadTest` OFF-assert
  pattern L148-159; `from(...)` external call sites = exactly 2 (`ScalperStrategyLoadTest.java:130`,
  `SignalEngine.java:195`). `GoldenDeterminismTest.FEATURES` + `BacktestParityTest.FEATURES` = the same 5
  pure-engine YAMLs (`ema-crossover`, `optional-indicator-activation`, `btst-preclose`, `exit-intrabar`,
  `context-series`), NO scalper; `BacktestParityTest` has the three byte-match asserts. ALL correct.
- Disposition source rows (all in `docs/strategy-audit/disposition/`): connect-the-dots L33, gap-theory
  L25, hero-zero L29, intro-terminology L15, morning-trade L21/L30, risk-framework L18/19/21/22/23/24/44/49/53,
  open-high-low L27, trending-oi L24/37/38, session-additions-and-manual-coverage L29/35 — every disposition
  cell verified to read the package the plan claims. The risk-framework AUTOMATE_PKG-themes block L72-77
  exists and tags `probability-graded-sizing` ("9 rows") + `vwap-distance-sizing` (1 row).
  *(**AUDIT pass 2 correction:** the block's "(9 rows)" label is the disposition doc's own self-count and is
  off by one — only **8** rows literally carry the `probability-graded-sizing` tag; see pass-2 finding NEW-D.)*
- No shipped scalper YAML carries `vwap-distance`/`time-of-day-preference`/`prior-day-vwap` (grep clean);
  all 36 use `position_sizing: { method: premium_budget, params: { budget_inr: 15000 } }` — confirms the
  "flat ₹15k" premise and the `atr_risk` variant's YAML key.
  *(**AUDIT pass 2 correction:** the budget is NOT uniform — 33 use 15000, **3** (hero-zero/§7) use 2000;
  see pass-2 finding NEW-B. The "flat premium_budget" / `atr_risk`-key premise is otherwise intact.)*

**Cite imprecisions corrected in place:**
1. §1 table filename `session-additions.md` → `session-additions-and-manual-coverage.md`; added a note that
   all source rows live in `docs/strategy-audit/disposition/` (the bare `docs/strategy-audit/` files carry
   different line numbers). [FIXED]
2. §3.0.3 `ScalperOiProps` javadoc L6-12 → L6-16. [FIXED]
3. §3.1a "Reuse the existing `RoundingMode` import" — FALSE for `ScalperGates.java` (it imports no
   `RoundingMode`; only `ConnectTheDotsScorer`/`MarketOiClient` do). Corrected to "ADD the import". [FIXED]
4. §3.3 / §3.3-Files "early-return after L116" — L116 is the inner `return Optional.empty();`; the gate
   inserts after the block's `}` at L118 (before the chain fetch at L119). [FIXED]

**Soundness defects corrected (would not have compiled):**
5. **§3.2 File 2 multiplier-in-`emitEntry` is architecturally impossible.** (a) There is no `lotSize` /
   `lotRoundDown` at that call site — lot size is `meta.lotSize()` in the **paper** module, and `SignalEngine`
   (signals module) must not import paper (the `EmissionGuard` SPI exists to keep the graph acyclic,
   `EmissionGuard.java:7-10`). (b) `SignalEngine` holds no `ScalperOiProps`. Rewrote File 2: add a nullable
   `multiplier` param to the `EmissionGuard.suggestedQty` SPI, COMPUTE it in `emitEntry` (pure on
   `decision`), APPLY + re-lot-round-down inside `PaperEmissionGuard`; wire the knobs via a Spring
   `ScalperSizing` bean injected into `SignalEngine`. [FIXED]
6. **§3.2 fallback "read OI-imbalance/VIX off `decision.confluence().dots()`" cannot work** — `DotScore` is
   `(dot, weight, supports)`, carrying only a boolean, never the raw % / VIX level. Open Point #3 corrected:
   the additive `Decision` fields are the only faithful path. [FIXED]
7. **§3.1a `vwapMaxDistanceFrac` "Null => inert" contradicts the null-fill pattern** — the compact ctor
   defaults it to 0.004; it is never null at runtime. Reworded ("dormant because tag-gated OFF, not null").
   Added the fraction-vs-percent scale caveat (these knobs are fractions; `crossFilterPct`/`spurt*` are
   percent). [FIXED]
8. **§3.2 `max_deploy_pct` is a separate clamp, not a `sizeMultiplier` factor** — it needs equity + lotSize,
   which live only in `PaperEmissionGuard`. Added as Open Point #8 with the corrected placement. [FIXED]

**Completeness additions:**
- §2.4: named the two OTHER producers of the canonical constructor the executor must update — the `from(...)`
  factory's own `new ScalperConfig(...)` (L154-156) and the record header (L36-52) — not just the 8 test
  literals.
- §5.2: added a `PaperEmissionGuard` test case for the new SPI `multiplier` param (null → un-graded;
  0.5 → lot-rounded-down, never-zero), since that adapter — not `emitEntry` — is where the multiplier applies.

**Parity (critical) — confirmed safe.** `GoldenDeterminismTest` / `BacktestParityTest` would still pass:
their FEATURES carry no scalper, neither harness instantiates the scalper seam, and `suggested_qty` +
`scalper_detail` are stamped OUTSIDE the frozen `ScoreBreakdownJson`/`GoldenSignalsJson` serializers. The
three `[P]` tags (`vwap-distance`, `prior-day-vwap`, `time-of-day-preference`) are absent from all 36
shipped YAMLs, so every existing config compiles byte-identical. The §3.3-rejected scorer-dot (option a) is
correctly flagged [P]-REJECTED — it would change the unconditional `den` sum (L100-107) for every bar, the
documented FU2 Dow-dot failure mode.

**Dependency sequencing — correct.** 3.1a / 3.3 have no upstream dep (chart + clock in-hand); 3.2's VIX
factor is dormant until the macro-vix stream lands (degrades to 1.0); 3.1b prior-day VWAP is correctly
sequenced LAST behind a new market-data read + Open Point #1; day-P&L factors correctly deferred to the
ledger stream (Open Point #4). No SPAN / equity-universe coupling (index-options long-premium only). The
one subtlety the audit added: the SPI/bean wiring for 3.2 (above) is a prerequisite the original sequencing
under-stated — fold it into PR-3.

**Residual open points the executor still owns:** Open Points #1-#8 (including the two added by this audit,
#8 and the dots-fallback note in #3). None blocks PR-1 (3.1a), the lowest-risk slice.

---

## Audit pass 2 findings

**Verdict: sound-with-open-points (independently re-confirmed).** I re-opened every load-bearing source
file from scratch (not trusting pass 1's line numbers) and re-verified the parity argument end to end. All
8 pass-1 corrections are correct against source and introduced no new error. The [P]/[S] split holds: the
ONLY signal-affecting changes are the two tag-gated entry-skip gates (3.1a `vwap-distance`, 3.3
`time-of-day-preference`) + the 3.1b prior-day-VWAP gate-switch, each default-OFF and absent from all 36
shipped YAMLs (grep-confirmed), so every existing config stays byte-identical and the goldens are re-run
(not regenerated). Five new imprecisions were found and corrected in place — all cosmetic/completeness, none
a soundness defect.

**Independently re-verified (opened the real files, byte-checked line numbers):**
- `ScalperConfluenceGate.java`: `evaluate` L100-280, LIVE-only class-javadoc L21-33, `Chart` L124,
  `chart(...)` L304-316 (close L309 / vwap L310), side L149-152, volume/RSI L157-163, #5 delta L196-199,
  ctx L191-192, `Decision` L71-87, `vwapHardGate` L249, the `if (!timeOk)` block L116-118. **All exact.**
  The §3.1a insertion (after the side at L152) sits AFTER the straddle branch (L132-147), so a straddle —
  which has no directional side — correctly never reaches the VWAP-distance gate. ✓
- `ScalperGates.java`: `volume` L64-68, `rsiBand` L76-84, `indicatorAlignment` L102-118,
  `callPutDeltaFilter` L151-161, `gt` L173-175, `timeWindow` L33-44; imports only `BigDecimal`/`LocalTime`/
  `Map` (NO `RoundingMode` — pass-1's "ADD the import" is right). `GateOutcome` is `(boolean pass, BigDecimal
  operand, String reason)` with `pass`/`fail` statics — the §3.1a `vwapDistance` gate is type-sound. ✓
- `ScalperGateContext.java` `Chart`/`Oi`/`Macro` records (L21-28 / L39-52 / L59-68) — every field the plan
  reads (`close`,`vwap`,`callPutDeltaImbalancePct`,`vixLevel`,`vixRising`) is present. ✓
- `ScalperConfig.java`: record L36-52, `requireStraddle` is the LAST field (so the new `requireVwapDistance`
  appends cleanly), constants L82-98, `from(...)` L101-157, tag parses L119/125/153, canonical ctor L154-156.
  Exactly **2** external `from(...)` call sites (`ScalperStrategyLoadTest:130`, `SignalEngine:195`) — neither
  breaks on a record-field add. ✓
- `ScalperOiProps.java`: 11-field record L18-29, null-fill compact ctor L57-73, `defaults()` 11 nulls L77
  (→ 13 after the 2 VWAP knobs), `crossFilterPct` default 50. The class javadoc (L13-15) confirms the
  fraction-vs-percent scale split pass 1 flagged: IV/VWAP knobs are 0..1 fractions, `crossFilterPct`/`spurt*`
  are percent. ✓
- `SignalEngine`: sizing block L605-614 byte-matches; `decision` is a method param (L575, in scope);
  **no `ScalperOiProps` bean in the fields/ctor (L90-158)** — pass-1 defect #5 confirmed; `scalperDetailJson`
  L667-706; `DotScore` is `(dot, weight, supports)` (L683-685) — confirms Open Point #3's
  dots-fallback-not-viable correction. ✓
- `EmissionGuard` (SPI, signals module, L31-36) + `PaperEmissionGuard` (L46-57, holds `meta.lotSize()` via
  `InstrumentMetaClient`, calls `PositionSizer.size`) — the SPI-keeps-the-graph-acyclic claim and the
  "multiplier must apply in the paper adapter, not `emitEntry`" rewrite are both correct. `PositionSizer`
  has `atr_risk` at L45-54 reading `risk_pct_equity`, and **lot-rounds internally** (its `lotRound` is
  `private` L71) — so `PaperEmissionGuard` will need its OWN lot-round-down on `meta.lotSize()` after the
  multiply (the plan's `/* lot-round-down */` placeholder is right to flag it; it can't reuse PositionSizer's
  private helper). `atr_risk` yields 0 without a positive `stopDistance` (L47-49) — confirms §6 dep #4. ✓
- `ConnectTheDotsScorer.score(...)` L63-65 is a pure function with no conditional-dot flag; `den` accumulates
  EVERY dot's weight unconditionally (L100-107) — so the §3.3-rejected scorer dot WOULD shift the
  denominator for every bar. The [P]-REJECTED classification is correct, and the FU2 precedent it cites
  (FU2 L648-650, the Dow-dot denominator failure) is real. ✓
- `MarketOiClient` javadoc: **"Live-feed only … never part of a deterministic replay"** — confirms §3.1b's
  "a prior-day VWAP read there keeps the seam pure" (parity held via the V009 persisted-confluence replay).
  `seriesStore` is `LiveSeriesStore` (SignalEngine L94). ✓
- Disposition rows (re-opened `docs/strategy-audit/disposition/`): every cited `file:line` reads the package
  the plan claims — `vwap-distance-sizing` = connect-the-dots L33, gap-theory L25, hero-zero L29, intro L15,
  morning-trade L21, risk-framework L21, session-additions L35 (**7**); `probability-graded-sizing` =
  risk-framework L18/19/22/23/24/44/49/53 (**8**) + open-high-low L27 + trending-oi L24/L38 + morning-trade
  L30 + session-additions L29 (**5**) = **13**; `time-of-day-preference` = trending-oi L37 (**1**). The
  hero-zero L29 "VWAP-pin sit-out" maps to the §3.1a minFrac clause; the Open-Point-#1 10:30-vs-11:00
  disagreement is real (morning-trade L21 says "before 10:30", session-additions L23 / intro L14 say "until
  11 AM"). ✓
- Tests: 8 `new ScalperConfig(...)` literals at L43-90, `bullBank()` L115 / `bullContext()` L137,
  `breadthThirtyTwoCutoff` L110, `ScalperStrategyLoadTest` OFF-assert pattern L151-153;
  `GoldenDeterminismTest`/`BacktestParityTest` FEATURES = the same 5 pure-engine YAMLs (no scalper), with
  `BacktestParityTest`'s three byte-match asserts at L68-78. `PaperAccountRiskIntegrationTest` +
  `ScalperRiskIntegrationTest` both exist; `signals.spec.ts` exists; `ScalperSizing.java` does not (it's the
  new class). ✓

**New issues found by pass 2 (missed by both author and pass 1) — all corrected in place:**
1. **(citation, NEW-A) Pass-1's own correction #4 is off by one.** In `ScalperConfluenceGate.java` L116 is
   `if (!timeOk) {`, the inner `return Optional.empty();` is **L117**, the closing `}` is L118. Pass 1 (and
   §3.3 + the §3.3-Files line) said "L116 is the inner return". The **insertion point is unchanged** (after
   the `}` at L118, before L119); only the line label was wrong. Fixed in both spots. [FIXED]
2. **(factual, NEW-B) "All 36 YAMLs use `budget_inr: 15000`" is wrong** — 33 use 15000, **3** (hero-zero/§7)
   use `budget_inr: 2000` (grep-confirmed). The plan §1 premise and pass-1's "all 36 … 15000" over-generalize.
   Immaterial to the design (multiplier defaults 1.0 over whatever budget the YAML sets), but the literal
   claim was false. Reworded §1. [FIXED]
3. **(completeness, NEW-C) The `Decision` constructor fan-out for §3.2 option (a) was under-stated.** Adding
   scalar fields to the `Decision` record forces updating BOTH `new Decision(...)` sites — directional
   **L279** and #11 straddle **L141-146** (exactly 2, verified). The straddle path RETURNS before `ctx` is
   built (ctx L191-192), so it has no OI/VIX scalars and must pass `null` (a neutral straddle carries no
   directional OI read — acceptable). This is the analogue of the §2.4 `ScalperConfig` arity fan-out the plan
   documents meticulously; it's `[S]` (Decision isn't golden-serialized), not a parity risk, but omitting it
   would surprise the executor with a compile error. Added to Open Point #3. [FIXED]
4. **(count, NEW-D) §1.2 said risk-framework "9 rows → probability-graded-sizing"; only 8 rows literally
   carry the tag.** The "(9 rows)" is the disposition doc's own self-count at risk-framework.md L72 and is
   off by one against its own table (8 tagged rows: L18,19,22,23,24,44,49,53). The plan's 13-gap total
   (8+5) is internally consistent with its §1 citation table, so only the §1.2 prose was wrong. Reconciled
   to 8. [FIXED]
5. **(over-claim, NEW-E) §5.2 called BOTH ITs "the existing `suggestedQty` ITs"** — only
   `PaperAccountRiskIntegrationTest` exercises the `suggestedQty` stamp (`ScalperRiskIntegrationTest` exists
   but doesn't). Both are valid homes; clarified which to put the multiplier assertion in. [FIXED]

**Parity (critical) — re-confirmed safe end to end.** Every signal-affecting change is tag-gated default-OFF
behind a NEW tag absent from all 36 shipped YAMLs, lives in the LIVE-only `ScalperConfluenceGate`/
`MarketOiClient` seam (never on the deterministic replay path), and the sizing annotation is stamped
OUTSIDE the frozen `ScoreBreakdownJson`/`GoldenSignalsJson`. `GoldenDeterminismTest` + `BacktestParityTest`
carry no scalper in FEATURES and never instantiate the scalper seam → they stay byte-identical without
regeneration. The one scorer-dot option (3.3a) that WOULD perturb the unconditional denominator is correctly
[P]-REJECTED. The plan creates **no** new golden variant in any default-OFF PR — correct, because no golden
YAML is a scalper.

**Readiness verdict.** Implementation-ready as written after these fixes. The PR-1 (3.1a) and PR-2 (3.3)
slices are the cleanest #5/FU2 copies and carry no residual unknowns. PR-3 (3.2 multiplier) is sound but
the executor must (a) apply the multiplier + max_deploy_pct clamp + lot-round inside `PaperEmissionGuard`
(its own lot-round helper — PositionSizer's is private), and (b) thread the additive `Decision` scalars
through BOTH constructor sites with the straddle passing nulls (NEW-C). PR-5 (3.1b prior-day VWAP) remains
correctly deferred behind Open Point #1 (the 10:30-vs-11:00 switch-time disagreement is genuine). No
blocker found; the open points are owner decisions, not defects.
