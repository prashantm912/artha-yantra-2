# IV fidelity — per-strike IV direction, absolute band, both-sides-flat

Status: PLAN (implementation-ready). Owner: single-owner. Target services:
`services/strategy-signal-service` (scalper confluence seam + scorer + Hero-Zero gate) and
`services/market-data-service` (IV-direction analytics field). Date: 2026-06-27.

> Read order for the executor: this plan is self-contained. The **load-bearing precedents** are
> (a) the `oi-cross-filter` (#5) hard pre-gate shape in `ScalperConfluenceGate.evaluate`, (b) the
> `iv_pair`/`iv_rank`/`ivBothHighStandAside` IV dots already in `ConnectTheDotsScorer`, and (c) the
> CLAUDE.md "parity-safe-additive" convention + the FU2 plan
> (`docs/superpowers/plans/2026-06-27-followup2-soft-dots-to-hard-gates.md`) for the
> default-OFF tag-gating + new-golden-variant pattern. Every parity-sensitive change here copies that
> exact shape. The straddle path is a **brand-new variant with no existing golden** — its IV gate is [S].

---

## 1. Goal & the packages/gaps this stream closes

This stream raises the **IV-fidelity** of the scalper from the current three coarse aggregate IV reads
(`iv_rank` absolute-rank dot, `iv_pair` CE-vs-PE-gap dot, `ivBothHighStandAside` 40/40 suppression) to
the source-faithful per-strike / absolute-band / both-sides-flat reads the Siva deck specifies.

| Package | # gaps | Effort | Doc-§ (audit row refs) | P/S |
|---|---:|:--:|---|:--:|
| **`iv-per-strike`** | 12 | M | 4.6 · 1.2 Volatility · 3.1 desirables · 3.2 indicators · 4.15.4/4.17.5 · 3.11/6.11 filters | **[P]** (one [S] straddle leg) |
| **`iv-absolute-band`** | 1 | S | 4.6 | **[P]** |
| **`iv-flat-both-sides`** | 1 | S | §3.7 S22 (i) / §5.7 S22 (Hero-Zero) | **[S]** new Hero-Zero leg (no existing golden) |

**Total: 14 gaps.** The exact audit rows (each cited file:line in its disposition table):

`iv-per-strike` (12) — the AUTOMATE_PKG rows tagged `iv-per-strike` across 7 dimension files:
- `disposition/indicators-oi-vix-iv.md:30` (§4.6) — "Prefer rising IV in that strike for bull /
  falling IV for bear" → per-strike IV-slope in the emitter (today only the static CE-vs-PE gap).
- `disposition/two-candle.md:24` (§3.1 desirables / §4.6) — "IV rising in strike for bull / falling
  for bear (per-strike Desirable)" → live per-strike IV trend series.
- `disposition/open-high-low.md:29` (§3.2 indicators) — "IV rising (bull) / falling (bear) in that
  strike; IV-rank low = cheap" → per-strike IV direction for the bought strike not gated.
- `disposition/open-high-low.md:15` (§3.2 setup[5]) — "Change in OI on identified strike not >50%
  (per-strike ΔOI)" → per-strike ΔOI% (groups with the per-strike-stat work).
- `disposition/open-high-low.md:30` (§3.2 filters) — "Volume floor (50K BN / 125K N) for the breakout"
  → per-strike *option* breakout volume (groups with the strike-stat work).
- `disposition/gates-strike-sr-fiidii.md:19` (§4.9) — "Freshness: option price not moved >50% vs prev
  day; identified-strike OI change not >50%" → the specific strike's prev-close + per-strike ΔOI.
- `disposition/intro-terminology.md:24` (§1.2 Volatility) — "IV — 6-strike avg; 10–12 good for trend;
  higher on trending side" (the per-strike-direction half; the absolute band is `iv-absolute-band`).
- `disposition/session-additions-and-manual-coverage.md:37` (§4.15.4/4.17.5) — "IV trending-difference
  band = 7–10 pts (CE-vs-PE, higher on trending side)" → make the single `ivPairMinGap` threshold a 7–10 band.
- `disposition/session-additions-and-manual-coverage.md:38` (§4.15.4/4.17.5) — "IV above 40 → buyer
  stays away" → a per-side IV>40 buyer cap (6-strike averages already computed).
- `disposition/straddle.md:29` (§3.11/6.11 filters) — "LOW-IV gate for the long straddle" **[S]**.
- `disposition/straddle.md:31` (§3.11/6.11/§4.6) — "IV > 40 → stay away as a buyer; a 40/40 reading →
  play short straddle" (the long-skip leg; the 40/40→short leg is short-gated, see Open Points) **[S]**.
- `disposition/straddle.md:30` (§3.11/6.11) — "Short wants Call-side & Put-side IV similar/equal" —
  **short-gated** (rides `short-premium-span`/#47); listed here for completeness, **deferred** (Open Points).

> Note — the `straddle.md:28` "pick LONG vs SHORT from volatility view" row is dispositioned
> **UNCERTAIN_OWNER** (the "event" half is judgemental), so it is NOT one of the 12 automatable gaps —
> it is recorded in §8 Open Points. The `market-movers.md:23` `per-stock-strike-iv-direction` row is a
> DIFFERENT package (gated on the equity universe), out of this stream.

`iv-absolute-band` (1):
- `disposition/indicators-oi-vix-iv.md:29` (§4.6) — "10–12 IV good for Trend play (low IV = most of
  move captured)" → gate the absolute 10–12 ATM-IV low band; only an `iv_rank < 50` soft dot exists today.

`iv-flat-both-sides` (1):
- `disposition/hero-zero.md:31` (§3.7 S22 (i); §5.7 S22) — "IV flat on BOTH sides = no trade (sellers
  control both, only erosion)" → wire a `ceIvAvg6`/`peIvAvg6` IV-gap check into `HeroZeroGate`. Distinct
  from FU1's `iv_crush_awareness` (that is the expiry-afternoon IV-decay manual check, not the
  both-sides-flat skip).

---

## 2. Current state (verified file:line — opened against the working tree 2026-06-27)

### 2.1 The IV consumption today — `ConnectTheDotsScorer.java`
- `W_IV = 0.8` (L34); `IV_RANK_LOW = new BigDecimal("50")` (L36).
- The scorer adds **18** dots (L74-98). The three IV-bearing ones:
  - `iv_rank` (L94): `m.ivRank() != null && m.ivRank() < 50` — the only **absolute** IV read today,
    and it is *rank* (0..100 percentile), NOT the absolute IV *level* the deck's "10–12 trend-play"
    band wants. **This is the `iv-absolute-band` gap.**
  - `iv_pair` (L97-98) via `ivPair(m, ce, props)` (L173-180): CE supports when `ceIvAvg6 − peIvAvg6 >=
    ivPairMinGap`; PE the mirror. Single threshold `ivPairMinGap` (default `0.10`). **The 7–10 *band*
    is the `iv-per-strike` session-additions:37 gap.**
  - `standAside = ivBothHighStandAside(m, props)` (L96, L186-195): both `ceIvAvg6`/`peIvAvg6` >=
    `ivBothHighFloor` (0.40) AND their gap < `ivPairMinGap` → the whole signal is suppressed.
- **There is NO per-strike IV *direction/slope* read anywhere** — `grep ivDirection|ivSlope|
  activeStrikeIv` over `services/strategy-signal-service` returns nothing. The scorer only sees the
  static `ceIvAvg6`/`peIvAvg6` *pair averages*, never whether the bought strike's IV is *rising/falling*.
- **There is NO per-side IV>40 buyer cap** — `ivBothHighStandAside` only fires on the symmetric
  40/40-both-high+narrow-gap case; a unilateral CE-IV>40 (buy-side too rich) still passes.

### 2.2 The IV operands — `ScalperGateContext.Macro` (L59-68)
```java
public record Macro(
    BigDecimal atmIv, BigDecimal ivRank, BigDecimal vixLevel, Boolean vixRising,
    int advances, int declines, BigDecimal fiiLongPct,
    BigDecimal ceIvAvg6, BigDecimal peIvAvg6) {}
```
`atmIv` (the absolute ATM IV level) is **already carried but NEVER read by the scorer** — only `ivRank`
is. `ceIvAvg6`/`peIvAvg6` are the 6-strike pair averages. **No `ceIvSlope`/`peIvSlope` field exists.**

### 2.3 The IV producer — `MarketOiClient.java`
- `macro(...)` (L351-398) reads `/options/iv-history` → `atmIv = currentIv` (L361), `ivRank = rank×100`
  (L365-366), then `/options/chain` → `deriveIvPair(...)` (L387-392, L567-617) for `ceIvAvg6`/`peIvAvg6`.
- `deriveIvPair` (L567-617): sorts chain rows by strike, finds the ATM (nearest to spot), averages the
  CE IV and PE IV over the 3 strikes above + 3 below (ATM excluded). Returns null on any missing leg or
  <6 usable strikes. **This is a point-in-time snapshot — no temporal slope.**
- The macro is **LIVE-only** (class javadoc L33-41): never on the deterministic replay; parity is held
  by persisting the computed `Confluence` at entry (V009 side-channel). So adding a temporal field here
  is parity-safe by construction — a replay reads the persisted confluence, not a re-call.

### 2.4 The Hero-Zero gate — `HeroZeroGate.java`
- `evaluate(...)` (L98-145) gating legs: expiry-day-only, 14:30–15:20 window, OI non-null, RSI band,
  both-sides-LONG_UNWINDING skip, `realMove` (>50% OI+price), `shortCovering`, toward-extreme stop.
- **It takes `Oi oi` but NOT `Macro` — it has no access to `ceIvAvg6`/`peIvAvg6` today.** The
  both-sides-flat skip (§3.7 S22 (i)) is unimplemented; the gate cannot see IV at all. **This is the
  `iv-flat-both-sides` gap.** (The scorer's `ivBothHighStandAside` is a DIFFERENT rule — "both-high",
  not "both-flat"; the deck's S22 (i) is the *flat* case: a near-zero CE−PE gap, sellers pinning both.)

### 2.5 The market-data IV-direction source ALREADY EXISTS (read-side)
- `ActiveStrikeService.activeStrikeIvSeries(...)` (L151-186): per-bucket, picks the peak-total-OI strike
  and emits that strike's CE IV, PE IV, spot — **one point per bucket, newest-last**. This is exactly the
  per-strike IV *time series* the slope needs; it is surfaced by `OptionsAnalyticsController` (L114, L362-365)
  on `/options/active-strikes` as `activeStrikeIvSeries`. **`MarketOiClient` does not request or read it today.**
- `ConnectingDotsService.ivFactor(iv, prevIv)` (L274-282): the OI-page's per-bucket ATM-IV direction
  read (falling IV → bullish, rising → bearish) — the *interpretation convention* to reuse for the
  per-strike slope (rising IV in the bought strike confirms the buy side, falling opposes).

### 2.6 The tag → gate wiring — `ScalperConfig.java`
- `record ScalperConfig(...)` fields L36-52; each `requireXxx`/`openingTick` boolean.
- `from(JsonNode, List<String> tags)` (L101-157) maps tags → flags (the L153 `oi-cross-filter` line is
  the canonical template). Constructor arity is coupled to the **8** `new ScalperConfig(...)` literals in
  `ScalperConfluenceGateTest` — adding a field forces compile-time updates to all 8 (a fan-out, not a parity risk).

### 2.7 The parity firewall (FU2 §2.6, re-verified)
- `GoldenSignalsJson.write()` serializes only `timestamp/exchange/tradingsymbol/direction/composite/
  breakdown` — scalper IV reads ride the non-serialized `scalper_detail` side-channel.
- `GoldenDeterminismTest.FEATURES` + `BacktestParityTest.FEATURES` = 5 **pure-engine** goldens, **no
  scalper** — neither harness instantiates `ScalperConfluenceGate`/`ConnectTheDotsScorer`. A tag-gated
  gate cannot perturb them PROVIDED no golden/parity YAML carries the new tag (none do; the FEATURES
  arrays are fixed).

---

## 3. Design — per package

### 3.A `iv-per-strike` (12 gaps) — the per-strike IV-slope dot + the 7–10 band + the IV>40 buyer cap

Three concrete sub-changes, all on the directional confluence path. The straddle long-skip is a 4th
sub-change on the neutral path ([S]).

#### 3.A.1 New per-strike IV-slope dot (`iv_slope`) — the headline gap (`indicators-oi-vix-iv.md:30` etc.)

**Data flow:** `ActiveStrikeService.activeStrikeIvSeries` (exists, §2.5) → a NEW
`MarketOiClient.deriveActiveStrikeIvSlope` read off `/options/active-strikes` → two new `Macro` fields
`ceIvSlope`/`peIvSlope` → a new `iv_slope` dot in `ConnectTheDotsScorer` (default-OFF, see PARITY §4).

**File 1 — `MarketOiClient.java`:** the `macro(...)` method already fetches `/options/active-strikes`
inside `oi(...)` for sentiment; macro re-reads the **same endpoint** for the IV series (one extra GET,
isolated by `get(...)`). Add a derivation that reads the newest two `activeStrikeIvSeries` buckets and
emits `last − first` per leg (the same `last − first` slope shape `deriveSentiment` uses, L526-538):

```java
/** §IV-slope carrier: the per-strike CE/PE IV slope over the active-strike IV series window. */
record IvSlope(BigDecimal ceSlope, BigDecimal peSlope) {
  static final IvSlope EMPTY = new IvSlope(null, null);
}

/**
 * The per-strike IV DIRECTION: the signed slope (last − first) of the peak-OI strike's CE IV and PE
 * IV over the {@code activeStrikeIvSeries} window (newest-last). null per leg when the series is
 * shorter than 2 buckets or the leg's IV is absent on an endpoint bucket — so a short/empty series
 * can never confirm a side. The interpretation convention follows ConnectingDotsService.ivFactor:
 * RISING IV in the bought strike confirms (a buyer paying up = demand), FALLING opposes.
 */
IvSlope deriveActiveStrikeIvSlope(JsonNode json) {
  JsonNode series = json.path("activeStrikeIvSeries");
  if (!series.isArray() || series.size() < 2) {
    return IvSlope.EMPTY;
  }
  BigDecimal ceFirst = decimal(series.get(0).path("ceIv"));
  BigDecimal ceLast  = decimal(series.get(series.size() - 1).path("ceIv"));
  BigDecimal peFirst = decimal(series.get(0).path("peIv"));
  BigDecimal peLast  = decimal(series.get(series.size() - 1).path("peIv"));
  return new IvSlope(
      ceFirst == null || ceLast == null ? null : ceLast.subtract(ceFirst),
      peFirst == null || peLast == null ? null : peLast.subtract(peFirst));
}
```

Wire it in `macro(...)` (add the GET alongside the existing `ivPair` read at L387-392, and pass the two
slopes into the `new Macro(...)` at L396-397). **Confirm `activeStrikeIvSeries` is in the
`/options/active-strikes` response body** (it is — `OptionsAnalyticsController` L362-365 adds it). If it
is NOT present when `macro` calls the endpoint without an `expiry` param, add `expiry` to this read (the
active-strikes endpoint already takes it in `oi(...)` L298-306).

**File 2 — `ScalperGateContext.java`:** extend `Macro` with two fields (append to keep positional order):
```java
public record Macro(
    BigDecimal atmIv, BigDecimal ivRank, BigDecimal vixLevel, Boolean vixRising,
    int advances, int declines, BigDecimal fiiLongPct,
    BigDecimal ceIvAvg6, BigDecimal peIvAvg6,
    BigDecimal ceIvSlope, BigDecimal peIvSlope) {}   // NEW (iv-per-strike)
```
(Update the record javadoc L54-58 with one sentence.)

**File 3 — `ConnectTheDotsScorer.java`:** the new dot, gated on a flag threaded from the seam (see §4 —
this is [P], so it must be opt-in to keep the 18-dot aggregate byte-identical). Add a `boolean
ivSlopeGate` param to `score(...)` (default-false path), and when set, add the dot:
```java
// iv-per-strike: the bought strike's IV DIRECTION — CE confirms when its strike IV is RISING
// (demand), PE when its strike IV is rising on the PE leg. Null slope never confirms.
boolean ivSlopeOk = ce
    ? m.ceIvSlope() != null && m.ceIvSlope().signum() > 0
    : m.peIvSlope() != null && m.peIvSlope().signum() > 0;
if (ivSlopeGate) {
  add(dots, "iv_slope", W_IV, ivSlopeOk, "per-strike IV rising on the buy side");
}
```
**Design choice — dot vs hard gate.** Per §4 we use the **opt-in dot** form (added to the 18→19 dot
list only when the tag is armed), NOT an early-return hard gate, because the disposition rows phrase it
as a *Desirable* ("prefer rising IV"), not a hard block, and a dot keeps the aggregate-vs-threshold
calculus the deck intends. (Contrast the Hero-Zero both-flat which IS a hard block — §3.C.) When the tag
is unarmed the dot list stays at 18 and the aggregate is bit-identical.

#### 3.A.2 The 7–10 IV-pair BAND (`session-additions:37`) — replace the single threshold with a band

`ScalperOiProps.ivPairMinGap` is today a single floor (0.10). The deck's "7–10 pt band" means the
CE-vs-PE gap should be *at least 7 pts AND the trending-side richer*, with the 10 the strong edge. This
is **already tunable** (the floor is a config knob) — the residual is exposing a `ivPairMaxGap`/band
*shape* so a 7-floor + 10-strong split can be tuned. **Recommendation: a SAFE prep change** — add an
optional `ivPairStrongGap` (default 0.10, = today's value) and lower the default `ivPairMinGap` floor to
`0.07` ONLY behind the same `iv-per-strike` tag (changing the default un-gated would alter live emission
→ [P]). The `iv_pair` dot's *supports* threshold stays `ivPairMinGap`; the band's "strong" tier is a new
booster only when the slope gate is armed. **If the owner prefers minimal surface area, defer the band
to tuning** (the floor is already a DB-param) — see Open Points.

#### 3.A.3 The per-side IV>40 buyer cap (`session-additions:38`) — a new stand-aside leg

Today `ivBothHighStandAside` only suppresses the symmetric 40/40 case. The deck also says a *unilateral*
buy-side IV>40 means "buyer stays away". Add, gated on the `iv-per-strike` tag, a per-side cap that
forces the existing `standAside` true when the BUY side's `ceIvAvg6`/`peIvAvg6` >= `ivBothHighFloor` (0.40):
```java
// iv-per-strike: a unilateral buy-side IV>40 → "buyer stays away" (the 6-strike avg on the side).
boolean buySideTooRich = ce
    ? m.ceIvAvg6() != null && m.ceIvAvg6().compareTo(props.ivBothHighFloor()) >= 0
    : m.peIvAvg6() != null && m.peIvAvg6().compareTo(props.ivBothHighFloor()) >= 0;
boolean standAside = ivBothHighStandAside(m, props) || (ivSlopeGate && buySideTooRich);
```
(Reuses `ivBothHighFloor` = 0.40, on the 0..1 fraction scale per `ScalperOiProps` javadoc.) Gated on
the same `iv-per-strike` flag so un-armed configs are byte-identical.

#### 3.A.4 (Straddle, [S]) the LOW-IV long-skip gate (`straddle.md:29`, `straddle.md:31` long leg)

The straddle path (`ScalperConfluenceGate.evaluate` L132-147) returns BEFORE the directional confluence,
on side-agnostic §0B rails only — it never reads IV today. Add a LOW-IV precondition for the LONG
straddle: skip when the ATM IV is high (the deck: "high-IV long loses both legs on an IV crash" / "IV>40
stay away as a buyer"). Insert after the volume floor (L133):
```java
// straddle iv-per-strike (LOW-IV long gate): a long straddle wants LOW IV (cheap both legs). Skip
// when the 6-strike avg on EITHER leg is rich (>= ivBothHighFloor). LIVE-only; a null avg never blocks.
ScalperGateContext sctx = client.context(cfg.oiIndex(), cfg.signalIndex(), istTime, eodDate, chain.expiry(), tradeDate, chart);
if (StraddleIvGate.tooRichForLong(sctx.macro(), oiProps)) {
  return Optional.empty();
}
```
This is **[S]** because the straddle is a *brand-new variant path with no existing golden* (FU2 confirms
the neutral straddle path is never reached by any golden/parity YAML, and it emits a v1 two-leg draft).
The 40/40→short-straddle leg and the CE-vs-PE-symmetry-for-short leg are **short-gated** (#47 SPAN) and
**deferred** (Open Points). Note this adds an OI-context fetch on the straddle path that does not exist
today — keep it behind a `low-iv-straddle` tag if the extra HTTP call on every straddle bar is unwanted
(recommended: tag-gate it, default-OFF, to avoid changing the existing straddle draft's behaviour).

### 3.B `iv-absolute-band` (1 gap) — the absolute 10–12 ATM-IV trend-play band

**Gap (`indicators-oi-vix-iv.md:29`, §4.6):** "10–12 IV good for Trend play (low IV = most of move
captured)". Today only `iv_rank < 50` exists — a *percentile*, not the absolute IV *level*. `Macro.atmIv`
(the absolute ATM IV from `/iv-history.currentIv`) is **already carried but unread** (§2.2).

**Design:** a new `iv_abs_band` dot reading `m.atmIv()` against a tunable band, on the same `iv-per-strike`
tag (or its own `iv-absolute-band` tag — see Open Points; recommend folding into `iv-per-strike` to avoid
tag proliferation, since they share the directional path and the same golden variant).

**File — `ScalperOiProps.java`:** add two fields + defaults (the absolute ATM-IV band is on the same
0..1 fraction scale as `ceIvAvg6`, per the class javadoc — so "10–12 IV" = `0.10`–`0.12`):
```java
BigDecimal ivAbsBandLow,    // DEFAULT 0.10  — the trend-play "low IV" floor (10 IV pts)
BigDecimal ivAbsBandHigh,   // DEFAULT 0.12  — the trend-play band ceiling (12 IV pts)
```
(Append to the record + the canonical constructor null-fill + `defaults()` — the same shape as the
existing `iv*`/`openHigh*` knobs.)

**File — `ConnectTheDotsScorer.java`:** gated on the tag, the dot supports when atmIv sits in the
low-IV trend-play band:
```java
// iv-absolute-band: ATM IV in the 10-12 "trend-play" band (low IV = most of the move still ahead).
boolean ivAbsOk = m.atmIv() != null
    && m.atmIv().compareTo(props.ivAbsBandLow()) >= 0
    && m.atmIv().compareTo(props.ivAbsBandHigh()) <= 0;
if (ivAbsBandGate) {
  add(dots, "iv_abs_band", W_IV, ivAbsOk, "ATM IV in 10-12 trend-play band");
}
```
**Side note (verify):** `MarketOiClient.macro` L361 reads `currentIv` directly as `atmIv`. Confirm the
`/iv-history` `currentIv` is on the 0..1 fraction scale (like `ceIvAvg6`), NOT 0..100 — if it is a
percentage, scale the band to `10`/`12` instead. The `iv_rank` scaling at L365-366 (`rank × 100`)
proves rank is a fraction; **check `currentIv` separately** before pinning the band scale.

### 3.C `iv-flat-both-sides` (1 gap) — the Hero-Zero both-sides-flat skip

**Gap (`hero-zero.md:31`, §3.7 S22 (i)):** "IV flat on BOTH sides = no trade (sellers control both, only
erosion)". `HeroZeroGate.evaluate` (§2.4) takes `Oi` but not `Macro`, so it cannot see IV. This is the
deck's *flat* case (near-zero CE−PE gap) — distinct from the scorer's `ivBothHighStandAside` (*high*).

**Design — a new hard block leg in `HeroZeroGate`** (Hero-Zero is buy-side-only, high-conviction;
all legs are blocks, so a both-flat read is a clean additional block):

**File — `HeroZeroGate.java`:** thread the two IV averages into `evaluate(...)` (add `BigDecimal ceIvAvg6,
BigDecimal peIvAvg6` params — `Macro` is local-domain, so pass the two scalars, not the whole record, to
keep the gate's dependency minimal), and add the leg after the RSI check:
```java
/** §3.7 S22 (i): IV flat on BOTH sides (sellers pinning, only erosion) → no trade. */
private static final BigDecimal IV_FLAT_MAX_GAP = new BigDecimal("0.02"); // ~2 IV pts, 0..1 scale

// (7) both-sides-flat skip: when both 6-strike IVs are present and their gap is under the flat-max,
// sellers control both sides (only premium erosion) — block. A null average (data absent) does NOT
// block here (the gate already requires OI; IV is a refinement, not a hard data dependency).
if (ceIvAvg6 != null && peIvAvg6 != null
    && ceIvAvg6.subtract(peIvAvg6).abs().compareTo(IV_FLAT_MAX_GAP) <= 0) {
  return BLOCK;
}
```
**File — `ScalperConfluenceGate.java`:** the Hero-Zero call site (L237-240) passes `ctx.macro().ceIvAvg6()`
and `ctx.macro().peIvAvg6()` into `HeroZeroGate.evaluate(...)`.

**PARITY classification — [S].** Hero-Zero is an **expiry-day-only, LIVE/forward gate with NO existing
golden** (`MarketOiClient` is never on the replay path; the picked leg + confluence ride the V009
side-channel). Adding a block leg makes the gate *stricter* (it can only reject more, never emit a new
signal), and there is no parity/golden YAML carrying the `hero-zero` tag. So this is read-only-stricter
on a forward-only path — **safe**, no new tag/golden required. **However**, to be conservative and match
the deck's "refinement" framing, the executor MAY gate it on a `hero-zero-iv-flat` tag (default-OFF) so
the existing `scalp-hero-zero-*` YAMLs are byte-identical for behaviour comparison — see Open Points
(recommended default: gate it, so the niftyoi/sensexoi Hero-Zero A/B stays clean).

### Data-flow summary (all changes)
`ActiveStrikeService.activeStrikeIvSeries` / `iv-history.currentIv` (market-data, exist) →
`MarketOiClient.macro` (new slope derivation + already-read atmIv) → `ScalperGateContext.Macro`
(+`ceIvSlope`/`peIvSlope`) → `ConnectTheDotsScorer` (new `iv_slope`/`iv_abs_band` dots + per-side cap,
tag-gated) and `HeroZeroGate` (new both-flat block) → the `dots[]`/decision side-channel
(`scalper_detail` JSON) → signals API → `frontend-react` `ManualVerifyChecklist` confluence chip row
(no FE change required — new dots ride the existing `dots[]` array shape).

---

## 4. PARITY classification (per change)

The parity rule (CLAUDE.md + FU2): any change that alters EMITTED signals on an existing shipped config
is **[P]** and MUST be (a) behind a NEW opt-in YAML tag absent from every shipped strategy (default-OFF)
and (b) covered by a NEW golden variant; existing goldens stay byte-identical. Read-only analytics, a
new manual check, backtest-only fidelity, or a brand-new sell/variant path with no existing golden is **[S]**.

| Change | Class | Tag (new, default-OFF) | Golden-variant plan |
|---|:--:|---|---|
| 3.A.1 `iv_slope` dot (per-strike IV direction) | **[P]** | `iv-per-strike` | New seam-test variant + a new `*.golden.json` for a synthetic `iv-per-strike`-tagged scalper YAML run through the scalper seam test (the live-gate goldens), NOT a pure-engine golden. Existing 18-dot scorer aggregate is byte-identical when the tag is unarmed (dot is added only when `ivSlopeGate`). |
| 3.A.2 7–10 IV-pair band (`ivPairStrongGap` + lowered floor) | **[P]** | `iv-per-strike` (same tag) | Floor change is gated on the tag; un-armed `ivPairMinGap` default stays `0.10`. New scorer-test cases for the band tiers. |
| 3.A.3 per-side IV>40 buyer cap (extends `standAside`) | **[P]** | `iv-per-strike` (same tag) | Gated on `ivSlopeGate`; un-armed `standAside` is unchanged. New scorer-test case (unilateral CE-IV>40 suppresses only when armed). |
| 3.A.4 straddle LOW-IV long gate | **[S]** | `low-iv-straddle` (recommended; or unguarded) | NO existing golden on the neutral straddle path (FU2-confirmed). New seam-test variant only. |
| 3.B `iv_abs_band` dot (absolute 10–12 band) | **[P]** | `iv-per-strike` (fold in) or `iv-absolute-band` | Same as 3.A.1 — added to the dot list only when armed; new scorer-test cases for in-band / below / above. |
| 3.C Hero-Zero both-sides-flat block | **[S]** (recommend tag-gate as belt-and-braces) | `hero-zero-iv-flat` (recommended, default-OFF) | NO existing golden (Hero-Zero is forward-only); stricter-only. New `HeroZeroGateTest` cases. |

**Why the directional dots are [P] but a no-op when unarmed:** the scorer aggregate = supporting-weight /
total-weight over the dot list. Adding `iv_slope`/`iv_abs_band` to the list (even with W_IV=0.8) changes
BOTH numerator and denominator → a different aggregate → a different emit decision on a live config. So
they MUST be added only inside `if (ivSlopeGate)` / `if (ivAbsBandGate)`, and the gate flags MUST default
false (no shipped YAML carries the tag). This is the FU2 invariant: 18 dots / `den = 19.6` stays
bit-identical for every existing strategy.

**Single shared tag recommendation:** fold 3.A.1/3.A.2/3.A.3/3.B under ONE `iv-per-strike` tag (they are
the same directional IV-fidelity epic and share a golden variant), keep `hero-zero-iv-flat` and
`low-iv-straddle` separate (different paths). This minimises tag/golden proliferation (3 tags, not 6).

---

## 5. Tests

### 5.1 Unit (pure, no Testcontainers)
- **`MarketOiClientDerivationTest.java`** (exists) — add cases for `deriveActiveStrikeIvSlope`:
  rising-CE / falling-CE / <2-bucket→null / missing-leg-IV→null. Mirror the existing `deriveSentiment`
  slope tests.
- **`ConnectTheDotsScorerTest.java`** (exists) — new cases (gate-armed vs unarmed):
  - `ivSlopeUnarmedKeeps18DotsAndAggregate` — with `ivSlopeGate=false`, `dots()` size == 18 and the
    aggregate equals the current `allDotsAlignedFiresBullishCe` value (byte-identical guard).
  - `ivSlopeArmedAddsDotRisingConfirms` — armed, CE with `ceIvSlope>0` → `iv_slope` supports; falling → opposes.
  - `ivAbsBandInBandSupports` / `ivAbsBandBelowOpposes` / `ivAbsBandAboveOpposes`.
  - `perSideIvOver40SuppressesWhenArmed` — unilateral `ceIvAvg6=0.45` with `peIvAvg6=0.10` → `standAside`
    true ONLY when armed (unarmed: false).
  - `ivPairBandStrongTierBooster` — gap 0.07 supports the floor, 0.10 is the strong tier.
- **`ScalperOiPropsTest.java`** (exists) — assert the new defaults (`ivAbsBandLow=0.10`,
  `ivAbsBandHigh=0.12`, `ivPairStrongGap=0.10`) fill on a partial-YAML override.
- **`HeroZeroGateTest.java`** (exists) — new cases:
  - `bothSidesFlatIvBlocks` — `ceIvAvg6≈peIvAvg6` (gap ≤ 0.02) with everything else passing → BLOCK.
  - `divergentIvDoesNotBlock` — gap > 0.02 → the gate still passes on the existing legs.
  - `nullIvAveragesDoNotBlock` — null `ceIvAvg6`/`peIvAvg6` → the both-flat leg is skipped (OI still gates).

### 5.2 Seam / golden-variant (the live-gate goldens)
- **`ScalperConfluenceGateTest.java`** (exists, 8 `new ScalperConfig(...)` literals) — update all 8
  literals for the new `requireXxx`/gate booleans (compile fan-out), then add:
  - `IV_PER_STRIKE_CFG` — a config with the `iv-per-strike` flags armed; assert a context whose
    `iv_slope`/`iv_abs_band` confirm fires, and a context whose IV opposes BLOCKS, while an
    un-armed `CFG` with the same context is UNAFFECTED (the byte-identical guard).
  - `HERO_ZERO_IV_FLAT_CFG` — Hero-Zero with the flat-IV block armed; both-flat IV BLOCKS, divergent passes.
  - `LOW_IV_STRADDLE_CFG` — straddle with the low-IV gate armed; high-IV BLOCKS, low-IV emits the draft.
- **New golden fixtures:** if the repo carries scalper-seam goldens (check
  `services/strategy-signal-service/src/test/resources` for a `*scalper*golden*` fixture), add a NEW
  variant fixture for each armed tag; if NOT (the seam is asserted via `ScalperConfluenceGateTest`
  behavioural assertions, not a byte-golden), the seam-test cases above ARE the parity coverage — and
  the pure-engine `GoldenDeterminismTest`/`BacktestParityTest` need NO change (no scalper YAML in FEATURES).
  **Verify which model the repo uses before writing fixtures** (Open Points).

### 5.3 Strategy-load + e2e
- **`ScalperStrategyLoadTest.java`** (exists) — if §7 PR-3 arms a real YAML with `iv-per-strike`, assert
  it loads and the flag is set; until then (default-deferred) NO YAML carries the tag, so this is a no-op.
- **No e2e change** for the BE-only fidelity work. The only FE-visible surface is a new `iv_slope`/
  `iv_abs_band` chip in `ManualVerifyChecklist` — covered by the existing `dots[]` rendering (no new
  Playwright case needed unless a real YAML is armed; if so, add a `confluence chip shows iv_slope`
  assertion to the signals-page e2e).

### 5.4 Market-data IT (the new endpoint field)
- `activeStrikeIvSeries` is already surfaced by `OptionsAnalyticsController` (L362-365) and covered by
  `OptionsAnalyticsControllerIntegrationTest` / `ActiveStrikeServiceTest`. **No new market-data change**
  is required for the slope (it reads the existing field) — confirm the field is in the response when
  `MarketOiClient.macro` calls `/options/active-strikes` (it is for the OI-page caller; verify the param
  set macro uses returns it).

---

## 6. Dependencies & sequencing

1. **Market-data field availability (read-only check, no new code expected).** `activeStrikeIvSeries`
   already ships on `/options/active-strikes`; `iv-history.currentIv` already ships. **Gate task:**
   confirm `MarketOiClient.macro`'s call to `/options/active-strikes` returns `activeStrikeIvSeries`
   (the `expiry` param must be passed — it is in `oi(...)` but `macro` reads active-strikes via a
   NEW GET; add `expiry` to that GET). This must be wired BEFORE the `iv_slope` dot can consume it.
2. **`Macro` record extension (3.A.1) before the scorer dot (3.A.1/3.B).** The scorer reads
   `m.ceIvSlope()`/`m.atmIv()`; the record fields must exist first. `atmIv` already exists — only the
   slopes are new.
3. **The directional dots (3.A/3.B) are independent of the Hero-Zero block (3.C) and the straddle gate
   (3.A.4)** — three separable PRs.
4. **SPAN gates the short-straddle IV legs** (`straddle.md:30` CE-vs-PE symmetry; the 40/40→short leg) —
   these ride `short-premium-span`/#47 and are **out of this stream** (deferred, Open Points). The
   LONG-straddle low-IV gate (3.A.4) is NOT SPAN-gated (it only blocks a buy).
5. **No equity universe dependency** — `per-stock-strike-iv-direction` (`market-movers.md:23`) is a
   SEPARATE package gated on the equity-futures capture; this stream is index-only.
6. **No DB migration, no springdoc contract change** — all new fields ride the `scalper_detail` JSON
   side-channel and the existing `/options/active-strikes` response (no new endpoint, no new column).

---

## 7. Effort (S/M/L) + suggested PR breakdown

**Overall: M** (one mechanical market-data read + scorer/record extensions + one Hero-Zero leg; the
hard part is the parity discipline, which is well-precedented by FU2/#5).

| PR | Scope | Effort | Parity |
|---|---|:--:|:--:|
| **PR-1** | `iv-per-strike` directional epic: `MarketOiClient.deriveActiveStrikeIvSlope` + `Macro` slopes + `iv_slope` dot + `iv_abs_band` dot + per-side IV>40 cap + the 7–10 band, ALL behind one `iv-per-strike` tag (default-OFF). Scorer + derivation unit tests + seam `IV_PER_STRIKE_CFG`. | **M** | [P] — new tag, new seam-variant; existing goldens untouched |
| **PR-2** | `iv-flat-both-sides`: thread `ceIvAvg6`/`peIvAvg6` into `HeroZeroGate.evaluate` + the both-flat block leg (gate on `hero-zero-iv-flat`, default-OFF) + `HeroZeroGateTest` cases + seam call-site. | **S** | [S] (forward-only, stricter-only; tag-gated as belt-and-braces) |
| **PR-3** | `low-iv-straddle`: the LONG-straddle LOW-IV skip on the neutral path (tag `low-iv-straddle`, default-OFF) + seam `LOW_IV_STRADDLE_CFG`. | **S** | [S] (new variant, no existing golden) |
| **PR-4** (deferred, owner-driven) | ARM the tags onto real strategy YAMLs (the niftyoi/sensexoi forward-paper A/B) once the live IV-slope feed is value-verified. NO golden change (forward-paper only). | **S** | [P] arming is owner-gated; do NOT arm on a backtest-judged config (IV degrades on derived history) |

> The constructor-arity fan-out (the 8 `new ScalperConfig(...)` literals in `ScalperConfluenceGateTest`)
> is a compile-time chore in PR-1 — not a parity risk. Keep each PR's scorer-signature change additive
> (a new trailing `boolean` param defaulting false at the un-armed call site).

---

## 8. Open Points

Every unresolved decision, with options + a recommended default. Resolve the scale checks before coding.

1. **`/iv-history.currentIv` scale (BLOCKING for 3.B).** Is `currentIv` a 0..1 fraction (like `ceIvAvg6`)
   or a 0..100 percentage? `iv_rank` is scaled ×100 at `MarketOiClient` L365-366 (proving *rank* is a
   fraction), but `currentIv` is read raw at L361. **Options:** (a) fraction → band `0.10`–`0.12`;
   (b) percentage → band `10`–`12`. **Recommended:** open the `/options/iv-history` producer and confirm
   before pinning `ivAbsBandLow/High`; do NOT guess — a wrong scale makes the absolute-band dot fire
   never/always. (Same check applies to whether `ceIvSlope` magnitude needs scaling, though slope only
   uses the SIGN, so it is scale-robust.)

2. **One `iv-per-strike` tag vs separate `iv-absolute-band` tag.** The disposition lists them as two
   packages, but they share the directional path + a golden variant. **Options:** (a) fold the absolute
   band into the `iv-per-strike` tag (1 tag, 1 golden); (b) a distinct `iv-absolute-band` tag (2 tags,
   2 goldens, independently armable). **Recommended:** (a) fold — fewer tags, fewer goldens, and the two
   are always tuned together. Record the package-vs-tag mismatch in the PR description.

3. **The 7–10 IV-pair band: build the band-shape now, or defer to tuning?** `ivPairMinGap` is already a
   DB-param floor. **Options:** (a) add `ivPairStrongGap` + lower the gated floor to 0.07 now;
   (b) defer — the owner tunes the single floor to 0.07 on a forward variant, no code. **Recommended:**
   (b) defer the band-shape; ship only the floor as-is (the gap is "make it the 7–10 band (tunable
   today)" per `session-additions:37` — it is *already tunable*). Keep PR-1 lean. If the owner wants the
   explicit two-tier booster, do (a).

4. **Hero-Zero both-flat: tag-gate it or unconditional?** It is [S] (forward-only, stricter-only), so
   strictly it needs no tag. **Options:** (a) unconditional (simplest, faithful to the deck — it is a
   hard deck rule); (b) tag-gate `hero-zero-iv-flat` default-OFF (keeps the existing `scalp-hero-zero-*`
   YAMLs behaviourally identical for the niftyoi/sensexoi A/B). **Recommended:** (b) tag-gate — the
   Hero-Zero A/B is an active forward discriminator; a behaviour change muddies it. Cheap to add.

5. **Straddle LOW-IV gate: extra HTTP fetch on the neutral path.** The straddle path does NOT build an
   OI context today; the low-IV gate needs `macro()` (one GET to `/options/active-strikes` or
   `/iv-history`). **Options:** (a) fetch macro on every straddle bar (extra latency); (b) tag-gate it so
   only armed straddles pay the fetch. **Recommended:** (b) tag-gate `low-iv-straddle` default-OFF — no
   extra fetch on the existing straddle draft.

6. **`straddle.md:28` LONG-vs-SHORT auto-selection (UNCERTAIN_OWNER).** "Pick LONG vs SHORT from
   volatility view (event+LOW IV → long; range+similar IV → short)". The IV/range half is automatable;
   the "event" half is judgemental. **Options:** (a) build IV-only auto-selection (LOW IV → long, 40/40
   → short) and leave the event read manual; (b) leave the whole selection discretionary. **Recommended:**
   defer to owner — and note (a) is blocked anyway by the short path being SPAN-gated (#47). Out of this
   stream's scope; recorded here per the disposition.

7. **Short-straddle CE-vs-PE IV symmetry (`straddle.md:30`).** Short-gated (#47 SPAN, no sell path
   exists). **Deferred** — it rides `short-premium-span`; build when the short straddle is built. No
   action this stream.

8. **Golden model for the scalper seam (BLOCKING for §5.2 fixtures).** Does the repo assert the scalper
   seam via a byte-golden fixture or via `ScalperConfluenceGateTest` behavioural assertions only?
   **Action:** `ls services/strategy-signal-service/src/test/resources` for a `*scalper*golden*` file.
   If none, the seam-test cases ARE the parity coverage (no new fixture); if present, add one variant
   fixture per armed tag. **Recommended:** confirm before writing fixtures — do not invent a golden
   harness that does not exist.

9. **`iv_slope` window length.** The slope is `last − first` over the `activeStrikeIvSeries` window. The
   active-strikes endpoint is requested with `buckets=SERIES_WINDOW` (=20) in `oi(...)`; macro's new GET
   should request the same so the slope is over a comparable window. **Recommended:** pass `buckets=20`
   to match; surface as a tunable later if needed (consistent with `SERIES_WINDOW`).
