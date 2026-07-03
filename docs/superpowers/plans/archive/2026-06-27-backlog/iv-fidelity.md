# IV fidelity — per-strike IV direction, absolute band, both-sides-flat

> **ARCHIVED (2026-07-03 doc sweep):** historical planning doc — the work here is delivered, superseded, or consciously parked. Anything still open lives in `../2026-07-02-remaining-items.md` (ledger) or `../2026-07-03-10x-value-roadmap.md`. Do not mine this file for TODOs.


Status: ✅ BUILT / DONE (E4 — shipped #282/#288/#289/#290: iv-per-strike + iv-absolute-band dots, iv-buyer-cap
>40 veto, hero-zero both-sides-flat skip, low-iv-straddle LOW-IV skip). Kept as the as-built design ref (the
scalper-to-100 roadmap §E4 links it). Original status below.

Status (original): PLAN (implementation-ready, audited pass 1 + pass 2). Owner: single-owner. Target service:
`services/strategy-signal-service` ONLY (scalper confluence seam + scorer + Hero-Zero gate + the
`MarketOiClient` HTTP reader). **There is NO market-data code change** — the IV-direction series
(`activeStrikeIvSeries`) and the ATM-IV level (`iv-history.currentIv`) ALREADY ship from market-data;
this stream only *consumes* them. (`MarketOiClient` lives in the strategy-signal scalper package, not
market-data — see Audit pass 1.) Date: 2026-06-27.

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
  (per-strike ΔOI)" → per-strike ΔOI% from `strike-session-stats`. **NOT closed by this stream's §3
  design** (it is per-strike OI, not IV); deferred to the strike-stat sub-package (Open Point 10).
- `disposition/open-high-low.md:30` (§3.2 filters) — "Volume floor (50K BN / 125K N) for the breakout"
  → per-strike *option* breakout volume from `strike-session-stats`. **NOT closed by this stream's §3
  design** (per-strike volume, not IV); deferred to the strike-stat sub-package (Open Point 10).
- `disposition/gates-strike-sr-fiidii.md:19` (§4.9) — "Freshness: option price not moved >50% vs prev
  day; identified-strike OI change not >50%" → the specific strike's prev-close + per-strike ΔOI.
  **Per-strike ΔOI / prev-close half NOT closed by §3** (strike-stat work, Open Point 10).
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

### 2.3 The IV producer — `MarketOiClient.java` (in `services/strategy-signal-service/.../scalper/`, NOT market-data)
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
  and emits that strike's CE IV, PE IV, **price** (NOT `spot` — the record field is named `price`:
  `ActiveStrikeIvPoint(OffsetDateTime bucket, BigDecimal ceIv, BigDecimal peIv, BigDecimal price)`,
  L38-39) — **one point per bucket, newest-last**. The slope only reads `ceIv`/`peIv`, so the 3rd
  field's name is immaterial to this stream, but the slope JSON paths ARE `ceIv`/`peIv` (verified). It is
  surfaced by `OptionsAnalyticsController` (field L113-114, built L362-365) on `/options/active-strikes`
  as `activeStrikeIvSeries`. **CRITICAL: it is emitted ONLY when the request carries `buckets` (L347-349:
  with `buckets == null` all three series serialize as `null`)** — so the slope read MUST pass `buckets`
  (Open Point 9 is load-bearing, not optional). **`MarketOiClient.macro` does not request or read it today.**
- `ConnectingDotsService.ivFactor(iv, prevIv)` (L273-282): the OI-page's per-bucket ATM-IV direction
  read — **falling IV → BULLISH, rising IV → BEARISH** (IV as a fear gauge on the aggregate index IV).
  **This is the OPPOSITE sign from the per-strike `iv_slope` dot this plan builds**, which follows the
  DECK's literal §4.6 rule (`indicators-oi-vix-iv.md:30`: "Prefer **rising** IV in that strike for bull /
  falling for bear" — the strike-level demand read: a buyer paying UP for the CE you're buying confirms
  CE). The two are different reads (index-fear vs strike-demand) and legitimately have opposite signs —
  so do **NOT** "reuse the `ivFactor` convention"; the `iv_slope` dot's authority is the deck row, not
  `ivFactor`. (Pass-1 audit correction: the original draft mis-cited `ivFactor` as the convention to copy.)

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

**File 1 — `MarketOiClient.java`:** today `/options/active-strikes` is fetched inside `oi(...)` (L296-306,
with `name`+`expiry`+`buckets=SERIES_WINDOW`) for sentiment; the `macro(...)` method does NOT call it.
`macro(...)` re-reads the **same endpoint** for the IV series (one extra GET, isolated by `get(...)`),
using the SAME `name`+`expiry`+`buckets=SERIES_WINDOW` query (all three are required — `requireExpiry`
422s without `expiry`, and the series is null without `buckets`; see §2.5). Add a derivation that reads
the first & last `activeStrikeIvSeries` buckets and emits `last − first` per leg (the same `last − first`
slope shape `deriveSentiment` uses, L526-538):

> **SIGNATURE / CALLER CHANGE (load-bearing — pass-1 audit add).** `macro(String underlying, LocalDate
> tradeDate)` has **no `expiry` param**; the active-strikes read needs it. Thread `LocalDate expiry`
> into `macro(...)`'s signature and pass it at the single caller `context(...)` (L90-92), which already
> has `expiry` in scope. Without this the GET 422s and the slope is always null. (`oi(...)` already
> receives `expiry`; `macro` was the EOD-reads half and never needed it until now.)

```java
/** §IV-slope carrier: the per-strike CE/PE IV slope over the active-strike IV series window. */
record IvSlope(BigDecimal ceSlope, BigDecimal peSlope) {
  static final IvSlope EMPTY = new IvSlope(null, null);
}

/**
 * The per-strike IV DIRECTION: the signed slope (last − first) of the peak-OI strike's CE IV and PE
 * IV over the {@code activeStrikeIvSeries} window (newest-last). null per leg when the series is
 * shorter than 2 buckets or the leg's IV is absent on an endpoint bucket — so a short/empty series
 * can never confirm a side. The interpretation is the DECK's §4.6 strike-demand rule
 * (`indicators-oi-vix-iv.md:30`): RISING IV in the bought strike confirms (a buyer paying up = demand),
 * FALLING opposes. (NOTE: this is the OPPOSITE sign from ConnectingDotsService.ivFactor, which reads the
 * AGGREGATE index IV as a fear gauge — do not conflate the two; see §2.5.)
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
slopes into the `new Macro(...)` at L396-397). The GET MUST carry `name=underlying`, `expiry`, and
`buckets=SERIES_WINDOW` — `activeStrikeIvSeries` is serialized only with `buckets` present (verified:
`OptionsAnalyticsController` L347-349 returns null series when `buckets == null`; the field is built at
L362-365). Reuse the exact query shape from the `oi(...)` active-strikes read (L296-306).

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

> **SCORER CALL-SITE CHANGE (pass-1 audit add).** `score(...)` is today
> `score(ScalperGateContext, OptionType, int, BigDecimal, ScalperOiProps, boolean vwapHardGate)` — 6
> params, called at **one** production site: `ScalperConfluenceGate.evaluate` L250-252. Adding
> `ivSlopeGate`/`ivAbsBandGate` booleans (recommend ONE combined `boolean ivPerStrikeGate` since §4 folds
> 3.A.1/3.A.3/3.B under one tag — fewer params, one flag) means: (a) thread `cfg.requireIvPerStrike()`
> into that call; (b) update EVERY `score(...)` call in `ConnectTheDotsScorerTest` (the existing un-armed
> cases pass `false` → byte-identical). Keep the new param(s) trailing so the diff is additive. Note the
> per-side cap (§3.A.3) and the abs-band (§3.B) ride the SAME flag, so a single trailing boolean suffices.

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

> **PLACEMENT (pass-1 audit precision).** This **replaces** the existing `boolean standAside =
> ivBothHighStandAside(m, props);` at `ConnectTheDotsScorer.java` **L96** — NOT a second variable.
> `standAside` is read twice downstream: the `iv_pair` dot (L97-98) and the `valid` decision (L115). The
> combined assignment must sit at L96 (before both uses) so the per-side cap propagates to both. When
> `ivSlopeGate==false` the expression collapses to exactly the current L96 value → byte-identical.
> `ce` is already in scope at L69; `ivSlopeGate` is the new `score(...)` param.

#### 3.A.4 (Straddle, [S]) the LOW-IV long-skip gate (`straddle.md:29`, `straddle.md:31` long leg)

The straddle path (`ScalperConfluenceGate.evaluate` L132-147) returns BEFORE the directional confluence,
on side-agnostic §0B rails only — it never reads IV today. Add a LOW-IV precondition for the LONG
straddle: skip when the ATM IV is high (the deck: "high-IV long loses both legs on an IV crash" / "IV>40
stay away as a buyer"). Insert after the volume floor (L133), inside `if (cfg.requireStraddle())`:
```java
// straddle low-iv-straddle gate: a long straddle wants LOW IV (cheap both legs). Skip when the 6-strike
// avg on EITHER leg is rich (>= ivBothHighFloor). LIVE-only; a null avg never blocks. Only the macro
// half is needed (the IV pair), so call macro(...) directly — do NOT build the full context (the oi(...)
// fan-out is wasted on a neutral path that takes no directional dots).
if (cfg.lowIvStraddle()) {  // NEW tag flag (default-OFF), gates the extra fetch
  // macro(...) keys EOD reads off `eodDate` (a method param, in scope here) + the active-strikes read
  // off `expiry`; no `tradeDate` is needed on this path. Pass cfg.underlying() (NOT cfg.oiIndex()) so the
  // index and expiry are a MATCHED pair (see the pass-2 note below) — the gate reads the option-root's IV.
  Macro macro = client.macro(cfg.underlying(), eodDate, chain.expiry());  // NEW expiry param (see §3.A.1)
  if (StraddleIvGate.tooRichForLong(macro, oiProps)) {
    return Optional.empty();
  }
}
```
**Pass-1 audit fixes baked in above:** (a) the original snippet referenced `tradeDate`, which is NOT in
scope at the straddle branch (it is computed at L182, AFTER this branch); the gate only needs `eodDate`
(in scope) + `expiry`, so no hoist is required. (b) `client.context(...)` would fire the whole `oi(...)`
HTTP fan-out for nothing on a neutral path — call `client.macro(...)` directly. (c) the gate is behind a
NEW `lowIvStraddle()` config
flag (the `low-iv-straddle` tag), so the existing straddle draft pays NO extra fetch when unarmed.

> **PASS-2 AUDIT FIX — index/expiry must be a matched pair on the straddle path.** The pass-1 snippet
> passed `client.macro(cfg.oiIndex(), eodDate, chain.expiry())`, but `chain` is fetched with
> `cfg.underlying()` (`ScalperConfluenceGate` L119), so `chain.expiry()` is the OPTION-ROOT's weekly
> expiry. On the shipped `scalp-straddle-sensex-niftyoi.yaml` the OI-confluence index is `NIFTY 50` while
> the underlying is `SENSEX` (verified: that YAML's `oi_confluence_gate.index: "NIFTY 50"`), so passing
> `cfg.oiIndex()` ("NIFTY 50") with the SENSEX expiry asks market-data for a NIFTY chain at a non-NIFTY
> expiry — the active-strikes GET 422s/empties and the slope is null. That is harmless to the gate
> DECISION (the low-IV straddle gate reads only the IV *pair* from `/options/chain`, whose `macro` read is
> expiry-INDEPENDENT — it passes only `underlying`), but it fires a wasted, erroring HTTP call on every
> armed straddle bar. Pass `cfg.underlying()` so the index/expiry pair is consistent and the read is the
> option-root's own IV (the right index for "is THIS straddle's premium rich"). Unlike the directional
> path — which resolves `oiExpiry` for the `oiIndex` chain at L187-190 — the neutral straddle path never
> builds `oiExpiry`, so the option-root pairing is the only consistent choice without a second chain fetch.
Requires: a new `StraddleIvGate` helper class, a new `boolean requireLowIvStraddle`/`lowIvStraddle`
field on `ScalperConfig` mapped from the `low-iv-straddle` tag in `from(...)`, and `import` of
`in.arthayantra.strategysignal.scalper.ScalperGateContext.Macro` + `Ist`/`LocalDate` (already imported).
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
(Append to the record + the canonical compact-constructor null-fill (L57-73) + `defaults()` — the same
shape as the existing `iv*`/`openHigh*` knobs. **`defaults()` (L76-78) currently passes exactly 11
`null`s; bump to 13** for the two new fields. If 3.A.2 also adds `ivPairStrongGap` that is a 14th. The
class javadoc L13-15 already pins these to the 0..1 fraction scale — no new scale note needed.)

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
**Side note — scale RESOLVED in pass-1 audit (was Open Point 1, now CLOSED):** `MarketOiClient.macro`
L361 reads `currentIv` directly as `atmIv`. Traced through the producer: `IvAnalyticsService.ivHistory`
sets `currentIv = stat.current()` (L115), the latest `iv = s.iv30d() ?? s.atmIv()` (L105); `atmIv` is the
mean of `RollupRow.iv()` (`atmIvForExpiry`, L176-196) — the SAME Black-76 solver sigma that feeds
`deriveIvPair`'s `rows[].ce.iv` (documented fractions, e.g. 0.14). **So `currentIv` IS a 0..1 fraction —
the band is `0.10`–`0.12` as written.** (Unlike `iv_rank`, which is the rank/percentile 0..1 fraction
scaled ×100 at L365-366; `currentIv` is the IV LEVEL, not the rank, and is NOT ×100-scaled.) The executor
should still spot-check one live `/iv-history` response body confirms a ~0.1x magnitude before pinning.

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
and `ctx.macro().peIvAvg6()` into `HeroZeroGate.evaluate(...)` (append the two scalars — current arg order
is `future, index, side, ctx.oi(), chart.rsi14(), istTime, isWeeklyExpiryDay, isMonthlyExpiryDay`).

> **TAG-GATING WITHOUT A NEW GATE PARAM (pass-1 audit precision).** Open Point 4 recommends tag-gating
> this leg (default-OFF) so the live `scalp-hero-zero-*` YAMLs stay byte-identical. The cleanest way that
> needs NO extra `HeroZeroGate` param: at the call site, pass the averages **only when armed**, else
> `null` — `evaluate(..., cfg.heroZeroIvFlat() ? ctx.macro().ceIvAvg6() : null, cfg.heroZeroIvFlat() ?
> ctx.macro().peIvAvg6() : null)`. The leg's null-guard (`ceIvAvg6 != null && peIvAvg6 != null`) then
> skips it when unarmed → identical behaviour. This adds a new `boolean requireHeroZeroIvFlat`/
> `heroZeroIvFlat` field to `ScalperConfig` mapped from the `hero-zero-iv-flat` tag in `from(...)` (one
> more `tags.contains(...)` line + one constructor arg → the 8 `new ScalperConfig(...)` test literals
> update). If the owner chooses UNCONDITIONAL (Open Point 4 option a), skip the flag and always pass the
> averages.

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
| 3.A.1 `iv_slope` dot (per-strike IV direction) | **[P]** | `iv-per-strike` | New BEHAVIOURAL seam-test variant (`IV_PER_STRIKE_CFG` in `ScalperConfluenceGateTest`) — there is NO scalper byte-golden fixture (Open Point 8 RESOLVED), so do NOT author a `*.golden.json` here. The parity guard is the unit assertion `dots().size()==18 && aggregate==<current>` when unarmed (the dot is added only when `ivSlopeGate`), NOT a fixture diff. (Pass-2 audit fix: the original "new `*.golden.json`" wording contradicted §5.2 + Open Point 8.) |
| 3.A.2 7–10 IV-pair band (`ivPairStrongGap` + lowered floor) — **DEFERRED by default (Open Point 3 rec)** | **[P]** if built | `iv-per-strike` (same tag) | Only if the owner picks Open Point 3 option (a): floor change is gated on the tag; un-armed `ivPairMinGap` default stays `0.10`. New scorer-test cases for the band tiers. Default = ship nothing here (the floor is already DB-tunable). |
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
- **`ScalperStrategyLoadTest.java`** (exists) — if §7 **PR-4** (the owner-driven arming PR) arms a real
  YAML with `iv-per-strike`, assert it loads and the flag is set; until then (default-deferred) NO YAML
  carries the tag, so this is a no-op. (Confirmed by audit: no shipped scalper YAML in
  `services/strategy-signal-service/src/main/resources/scalper-strategies` carries any of the 4 new tags.)
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

1. **Market-data field availability (read-only check, no market-data code change).** `activeStrikeIvSeries`
   already ships on `/options/active-strikes`; `iv-history.currentIv` already ships. **Gate task:** wire
   the NEW active-strikes GET in `MarketOiClient.macro` with `name`+`expiry`+`buckets=SERIES_WINDOW` — all
   three required (§2.5). **This forces a `macro(...)` signature change**: it is `macro(String underlying,
   LocalDate tradeDate)` today (no `expiry`); add `LocalDate expiry` and pass it from the sole caller
   `context(...)` L90-92 (which has `expiry` in scope). This must be wired BEFORE the `iv_slope` dot can
   consume it.
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
   side-channel and the existing `/options/active-strikes` response (no new endpoint, no new column). The
   only signature/arity fan-out is internal Java: `macro(...)` (+expiry), `score(...)` (+ivPerStrike flag),
   `HeroZeroGate.evaluate(...)` (+2 IV scalars), `ScalperConfig` (+ up to 2 tag flags → the 8 test
   literals), `ScalperOiProps` (+ band defaults → 11→13 nulls in `defaults()`). None of these are
   contract-spec-visible (no new `@*Mapping` path, no new query param on a captured endpoint — the
   active-strikes `buckets`/`expiry` params already exist), so `ContractCaptureTest` does not drift.

---

## 7. Effort (S/M/L) + suggested PR breakdown

**Overall: M** (one mechanical market-data read + scorer/record extensions + one Hero-Zero leg; the
hard part is the parity discipline, which is well-precedented by FU2/#5).

| PR | Scope | Effort | Parity |
|---|---|:--:|:--:|
| **PR-1** | `iv-per-strike` directional epic: `MarketOiClient.deriveActiveStrikeIvSlope` + `Macro` slopes + `iv_slope` dot + `iv_abs_band` dot + per-side IV>40 cap, ALL behind one `iv-per-strike` tag (default-OFF). Scorer + derivation unit tests + seam `IV_PER_STRIKE_CFG`. **The 7–10 IV-pair band (3.A.2) is DEFERRED per Open Point 3's recommended default** (the floor is already a DB-param); include the `ivPairStrongGap` two-tier only if the owner picks Open Point 3 option (a). | **M** | [P] — new tag, new seam-variant; existing goldens untouched |
| **PR-2** | `iv-flat-both-sides`: thread `ceIvAvg6`/`peIvAvg6` into `HeroZeroGate.evaluate` + the both-flat block leg (gate on `hero-zero-iv-flat`, default-OFF) + `HeroZeroGateTest` cases + seam call-site. | **S** | [S] (forward-only, stricter-only; tag-gated as belt-and-braces) |
| **PR-3** | `low-iv-straddle`: the LONG-straddle LOW-IV skip on the neutral path (tag `low-iv-straddle`, default-OFF) + seam `LOW_IV_STRADDLE_CFG`. | **S** | [S] (new variant, no existing golden) |
| **PR-4** (deferred, owner-driven) | ARM the tags onto real strategy YAMLs (the niftyoi/sensexoi forward-paper A/B) once the live IV-slope feed is value-verified. NO golden change (forward-paper only). | **S** | [P] arming is owner-gated; do NOT arm on a backtest-judged config (IV degrades on derived history) |

> The constructor-arity fan-out (the 8 `new ScalperConfig(...)` literals in `ScalperConfluenceGateTest`)
> is a compile-time chore in PR-1 — not a parity risk. Keep each PR's scorer-signature change additive
> (a new trailing `boolean` param defaulting false at the un-armed call site).

---

## 8. Open Points

Every unresolved decision, with options + a recommended default. Resolve the scale checks before coding.

1. **`/iv-history.currentIv` scale — RESOLVED (pass-1 audit): 0..1 FRACTION, band = `0.10`–`0.12`.**
   Traced the producer: `IvAnalyticsService.ivHistory` sets `currentIv = stat.current()` = the latest
   `iv = s.iv30d() ?? s.atmIv()`; `atmIv` is the mean of `RollupRow.iv()` (the Black-76 solver sigma, the
   SAME source as `deriveIvPair`'s documented-fraction `rows[].ce.iv`). So `currentIv` is a 0..1 fraction,
   NOT a percentage; the band is `0.10`–`0.12` as written. (It is the IV LEVEL — unlike `iv_rank`, which
   is the rank/percentile fraction scaled ×100; `currentIv` is NOT ×100-scaled.) **Residual action:** the
   executor spot-checks ONE live `/iv-history` body shows ~0.1x before pinning. `ceIvSlope` is
   sign-only → scale-robust regardless.

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

8. **Golden model for the scalper seam — RESOLVED (pass-1 audit): NO byte-golden fixture exists; the seam
   is asserted BEHAVIOURALLY via `ScalperConfluenceGateTest`.** Verified: no `*scalp*golden*` file under
   `services/strategy-signal-service/src/test/resources`; the engine-level `GoldenDeterminismTest`/
   `BacktestParityTest` FEATURES = `{ema-crossover, optional-indicator-activation, btst-preclose,
   exit-intrabar, context-series}` — 5 pure-engine vectors, NO scalper. So the §5.2 seam-test cases ARE
   the parity coverage; **do NOT author a new byte-golden harness.** The "byte-identical guard" for the
   [P] dots is the unit assertion `dots().size() == 18 && aggregate == <current>` when unarmed (§5.1),
   NOT a fixture diff. `GoldenDeterminismTest`/`BacktestParityTest` need NO change.

9. **`iv_slope` window length + `buckets` requirement (load-bearing, not just a tuning knob).** The slope
   is `last − first` over the `activeStrikeIvSeries` window. **The endpoint returns the series ONLY when
   `buckets` is passed** (`OptionsAnalyticsController` L347-349 → null series when `buckets==null`), so
   macro's new GET MUST pass `buckets=SERIES_WINDOW` (=20) AND `expiry` (`requireExpiry` 422s otherwise)
   AND `name`. **Recommended:** pass `buckets=20` (consistent with the `oi(...)` read); surface as a
   tunable later if needed.

10. **Per-strike ΔOI / per-strike option-volume rows are tagged `iv-per-strike` in the disposition but are
    NOT IV work and are NOT closed by §3 (pass-1 audit gap).** `open-high-low.md:15` (per-strike ΔOI%),
    `open-high-low.md:30` (per-strike option breakout volume), and the per-strike-ΔOI half of
    `gates-strike-sr-fiidii.md:19` all need per-strike fields off `strike-session-stats` (which `#2`
    already reads via `client.openHighStats`), NOT the IV-direction series this stream builds. The plan's
    "12 gaps" count includes these 3, but the §3 design only closes the ~9 IV-direction/band/cap rows.
    **Options:** (a) carve a separate `strike-stat-fidelity` sub-package/PR for the 3 per-strike-OI/volume
    rows (they share the `strike-session-stats` endpoint already wired for #2); (b) re-scope this stream's
    gap count to the IV rows only and move the 3 to the open-high-low/strike-stat backlog item.
    **Recommended:** (b) — keep `iv-fidelity` strictly IV; record the 3 rows as belonging to a strike-stat
    package so the audit trail stays honest. Either way, do NOT claim these 3 closed by PR-1.

11. **`iv_slope` sign convention — CONFIRM against the deck, NOT `ivFactor` (pass-1 audit).** The dot fires
    CE-confirm on a RISING strike CE-IV (§4.6 `indicators-oi-vix-iv.md:30` strike-demand read). This is the
    OPPOSITE sign from `ConnectingDotsService.ivFactor` (falling index-IV → bullish, the fear-gauge read).
    The two are legitimately different reads, but the executor MUST NOT "reuse the `ivFactor` convention"
    (the original draft said to) — the authority is the deck row. **Recommended:** keep rising-IV-confirms
    and add a unit-test comment pinning the deck citation so a future reader does not "fix" it to match
    `ivFactor`. (Owner may still want to A/B the sign on forward paper — note it, do not flip silently.)

12. **Aggregate-dilution when the [P] dots are armed (pass-1 audit).** Arming `iv-per-strike` adds up to 3
    new low-weight dots (`iv_slope` W_IV=0.8, `iv_abs_band` W_IV=0.8) into the denominator (`den` grows
    from 19.6 toward ~21.2). With a fixed `confluenceThreshold=0.6`, an armed config needs MORE supporting
    weight to clear the same 0.6 — so arming can make a strategy STRICTER even when the new dots support.
    This is correct parity-wise (un-armed is byte-identical) but is a behaviour the owner should know when
    arming PR-4. **Recommended:** when arming, re-tune `confluenceThreshold` on the forward variant (it is
    already a per-strategy knob, `ScalperConfig.THRESHOLD` default 0.6); do NOT assume the same threshold
    transfers. Record in the PR-4 description.

---

## Audit pass 1 findings

Audited 2026-06-27 by opening every cited file against the working tree. Verdict:
**sound-with-open-points** — the core design is correct and parity-safe, but several citations were stale
and several load-bearing steps were under-specified. All corrected in place above. Detail:

### Citations — checked, with corrections
- **Service mislocation (FIXED, header + §2.3).** The plan framed `MarketOiClient` as a
  `market-data-service` "IV producer" and listed market-data as a target service. It actually lives in
  `services/strategy-signal-service/.../scalper/MarketOiClient.java` (an HTTP *reader*). There is NO
  market-data code change in this stream. Header + §2.3 corrected; §1 target list narrowed.
- **`ivFactor` convention mis-cited (FIXED, §2.5 + §3.A.1).** The plan said the `iv_slope` dot "follows
  `ConnectingDotsService.ivFactor`" (rising-confirms). `ivFactor` (L273-282) is the OPPOSITE: *falling*
  index-IV → bullish (a fear gauge). The deck's actual `iv-per-strike` row (`indicators-oi-vix-iv.md:30`)
  DOES say "rising IV in that strike for bull", so the plan's *direction is right* but its *authority
  citation was wrong*. Re-anchored to the deck row; added Open Point 11 warning against a future "fix" to
  match `ivFactor`.
- **`activeStrikeIvSeries` 3rd field name (FIXED, §2.5).** Record is
  `ActiveStrikeIvPoint(bucket, ceIv, peIv, price)` (L38-39) — the 3rd field is `price`, not `spot` as the
  prose said. Immaterial to the slope (reads `ceIv`/`peIv` only), but the doc now matches.
- **All other cites VERIFIED ACCURATE:** `ConnectTheDotsScorer` 18 dots / W_IV=0.8 (L34) / IV_RANK_LOW
  (L36) / `iv_rank` L94 / `iv_pair` L97-98 / `ivPair` L173-180 / `ivBothHighStandAside` L186-195;
  `Macro` L59-68; `MarketOiClient.macro` L351-398, atmIv L361, ivRank×100 L365-366, `deriveIvPair`
  L567-617, `deriveSentiment` L526-538; `HeroZeroGate.evaluate` L98-145; `ScalperConfig.from` L101-157,
  oi-cross-filter L153; `ScalperConfluenceGate` Hero-Zero call L237-240, straddle path L132-147, volume
  L133; `OptionsAnalyticsController` activeStrikeIvSeries field L113-114/built L362-365; `GoldenSignalsJson.write`
  serializes the 6 frozen keys; both golden FEATURES arrays = 5 pure-engine, no scalper; exactly 8 `new
  ScalperConfig(...)` literals in the seam test; every disposition row (`indicators-oi-vix-iv.md:29/30`,
  `session-additions:37/38`, `hero-zero.md:31`, `straddle.md:28/29/30/31`, `open-high-low.md:15/29/30`,
  `two-candle.md:24`, `gates-strike-sr-fiidii.md:19`, `intro-terminology.md:24`, `market-movers.md:23`)
  exists and says what the plan claims. FU2 plan + `ScalperManualChecks` `vix_normal` (L47) exist.

### Soundness — fixes applied
- **`macro(...)` lacks an `expiry` param (FIXED, §3.A.1 + §6.1).** The active-strikes slope read needs
  `expiry` (endpoint 422s via `requireExpiry`) but `macro(String underlying, LocalDate tradeDate)` has
  none. Added explicit signature + caller (`context` L90-92) threading.
- **`activeStrikeIvSeries` requires `buckets` (FIXED, §2.5 + §3.A.1 + Open Point 9).** The series
  serializes ONLY when `buckets` is in the query (`OptionsAnalyticsController` L347-349 returns null
  series otherwise). The plan treated this as an optional tuning knob; it is load-bearing — without it the
  slope is always null.
- **Straddle snippet used out-of-scope `tradeDate` + a wasteful full `context()` (FIXED, §3.A.4).** At the
  straddle branch (L132-147) `tradeDate` is not yet computed (L182). Rewrote to hoist a local and call
  `client.macro(...)` directly (the neutral path takes no directional OI dots, so the `oi(...)` fan-out is
  waste). Added the `low-iv-straddle` flag + `StraddleIvGate` helper requirements.
- **Scorer call-site under-specified (FIXED, §3.A.1).** Adding `score(...)` params requires updating the
  one production call (L250-252) + every `ConnectTheDotsScorerTest` call. Recommended ONE combined
  `ivPerStrikeGate` flag (since §4 folds 3 sub-changes under one tag).
- **`standAside` placement (FIXED, §3.A.3).** The per-side cap must REPLACE the L96 assignment (read twice
  downstream: dot L97-98 + `valid` L115), not introduce a shadow variable.
- **Hero-Zero tag-gating mechanism (FIXED, §3.C).** Showed the no-new-param way to keep it default-OFF:
  pass `null` averages from the call site when unarmed (the leg's null-guard then skips), plus the new
  `hero-zero-iv-flat` config flag.
- **`ScalperOiProps.defaults()` arity (FIXED, §3.B).** 11 nulls today → 13 (or 14 with the band).

### Parity — confirmed
- The default-OFF invariant is REAL: `grep` over `scalper-strategies/` confirms **no shipped YAML carries
  any of the 4 new tags** (`iv-per-strike` / `iv-absolute-band` / `hero-zero-iv-flat` / `low-iv-straddle`).
- The [P] dots (`iv_slope`, `iv_abs_band`, per-side cap) are correctly gated inside `if (gate)` so the
  18-dot / den=19.6 aggregate is bit-identical when unarmed. The `iv-flat-both-sides` [S] and
  `low-iv-straddle` [S] are stricter-only / new-path with no existing golden — classifications hold.
- `GoldenDeterminismTest` / `BacktestParityTest` are untouched (5 pure-engine FEATURES, no scalper YAML)
  and stay green. **NO scalper byte-golden fixture exists** (Open Point 8 RESOLVED) — the seam parity
  guard is the behavioural unit assertion `dots().size()==18 && aggregate==<current>`, not a fixture diff.
  No [P] change is mis-marked [S] and no [S] change actually moves an existing-config signal.
- One behavioural caveat the owner must heed (Open Point 12): arming dilutes the denominator, so a fixed
  `confluenceThreshold=0.6` makes an armed config STRICTER — re-tune the threshold on the forward variant.

### Completeness — gaps surfaced
- **3 of the "12" `iv-per-strike` gaps are NOT IV work and NOT closed by §3 (Open Point 10).**
  `open-high-low.md:15` (per-strike ΔOI%), `:30` (per-strike option volume), and the per-strike-ΔOI half
  of `gates-strike-sr-fiidii.md:19` need `strike-session-stats` per-strike fields (the endpoint #2 already
  reads), not the IV series. Re-scoped: this stream closes the ~9 IV rows; the 3 per-strike-OI/volume rows
  move to a strike-stat sub-package. The "14 gaps total" headcount is honest only if those 3 are tracked
  as deferred, not delivered.
- **Scale question RESOLVED (Open Point 1 CLOSED):** `iv-history.currentIv` is a 0..1 fraction (traced to
  the Black-76 solver sigma via `IvAnalyticsService`), band = `0.10`–`0.12` as written.
- Dependency sequencing (feeds → record → scorer; Hero-Zero & straddle separable; SPAN gates the short
  legs; equity universe out of scope) is CORRECT. FE needs no change (the generic `dots[]` map in
  `ManualVerifyChecklist.tsx` L80-89 auto-renders a new dot — verified).

---

## Audit pass 2 findings

Independent second pass, 2026-06-27 — re-opened every load-bearing file against the working tree, re-ran
the parity end-to-end, and re-verified the pass-1 corrections. **Verdict: sound-with-open-points.** The
core design and the parity-safety are correct; the pass-1 corrections all hold and introduced no new
error; I found 3 issues both the author and pass-1 missed (all corrected in place) plus one minor semantic
note. The plan is implementation-ready once the 3 corrections below are read alongside the existing Open
Points.

### Citations — independently re-verified (a fresh sample, not pass-1's list)
Opened and confirmed BYTE-EXACT against the tree (not trusting pass-1):
- `ConnectTheDotsScorer`: 18 dots counted by hand (L74-98), `W_VWAP=2.5`/`W_OI=1.5`/`W_IV=0.8`/`W=1.0`
  (L32-35), `IV_RANK_LOW="50"` (L36), `den` computed = 2.5+1.5+0.8+0.8+14×1.0 = **19.6** (matches),
  `iv_rank` L94, `standAside` L96 read twice (dot L97-98 + `valid` L115 — the §3.A.3 replacement target is
  right), `ivBothHighStandAside` L186-195, `ivPair` L173-180.
- `MarketOiClient`: `macro(String, LocalDate)` L351 (no expiry — pass-1's signature fix is REQUIRED),
  `atmIv=currentIv` L361, `ivRank=rank×100` L365-366, `deriveIvPair` L567-617 (chain read passes only
  `underlying`, expiry-INDEPENDENT — load-bearing for the pass-2 straddle fix), `deriveSentiment`
  last−first slope L526-538 (the shape `deriveActiveStrikeIvSlope` copies), the `oi(...)` active-strikes
  read uses `name`+`expiry`+`buckets=SERIES_WINDOW` L296-306, `SERIES_WINDOW=20` L49, `decimal(...)` L671,
  `HUNDRED` L47 — all present, the `deriveActiveStrikeIvSlope` snippet would compile.
- `context(...)` L80-92: `expiry` IS in scope (param L85), calls `macro(underlying, eodDate)` L92 — the
  pass-1 threading is feasible.
- `OptionsAnalyticsController` L347-349: **CONFIRMED** the series serialize as `null` when `buckets==null`
  (the load-bearing Open Point 9 fact); built L362-365. `ActiveStrikeIvPoint(bucket, ceIv, peIv, price)`
  L38-39 with JSON paths `ceIv`/`peIv` — confirmed (note: the actual package is
  `marketdata.options.analytics`, not the `marketdata.analytics` the plan's prose implies — immaterial,
  the plan refers to it by class name only).
- `ScalperConfluenceGate`: straddle path L132-147 (volume floor L133), `tradeDate` computed at L182 AFTER
  the straddle branch (pass-1's no-`tradeDate` fix is correct), `eodDate` is a method param L107 (in scope
  at the straddle branch), `chain` fetched with `cfg.underlying()` L119, Hero-Zero call L237-240 (8-arg
  order matches §3.C), `score(...)` call L250-252, `client`/`oiProps` fields L46-47 in scope.
- `HeroZeroGate.evaluate(...)` L98-106: 8 params, no `Macro`/IV access — the §3.C threading is needed; the
  RSI leg is L121-124 (the §3.C "after the RSI check" insertion point); `BLOCK` is a valid `Verdict`
  constant (e.g. L109) so the snippet's `return BLOCK;` compiles.
- `ScalperConfig`: record L36-52, `from(...)` L101-157, `oi-cross-filter` template L153, ONE production
  `new ScalperConfig(...)` L154-156.
- `ScalperOiProps`: `defaults()` L76-78 passes EXACTLY 11 nulls (the §3.B "11→13" claim is right),
  `ivBothHighFloor=0.40` L40, `ivPairMinGap=0.10` L38, class javadoc pins the 0..1 fraction scale L13-15.
- Parity firewall: `GoldenSignalsJson.write()` serializes ONLY `timestamp/exchange/tradingsymbol/
  direction/composite/breakdown` (L52-65); `SignalEvent.stopLoss/takeProfit` are non-serialized
  side-channel — a `scalper_detail` IV read cannot perturb it. `GoldenDeterminismTest.FEATURES` = the 5
  pure-engine vectors (L33-36, no scalper). **`grep` confirms NO shipped scalper YAML carries any of the 4
  new tags.** Exactly 8 `new ScalperConfig(...)` literals in `ScalperConfluenceGateTest` (L44-87).
- `ConnectingDotsService.ivFactor` L273-282: **CONFIRMED** "falling IV → BULLISH, rising IV → BEARISH" —
  the OPPOSITE sign from the per-strike `iv_slope` dot. Pass-1's re-anchoring to the deck row
  (`indicators-oi-vix-iv.md:30` "Prefer rising IV in that strike for bull") and Open Point 11's warning
  are CORRECT and load-bearing — the original draft would have wired the wrong sign.
- Disposition rows independently opened: `indicators-oi-vix-iv.md:29/30`, `session-additions:37/38`,
  `hero-zero.md:31`, `straddle.md:28/29/30/31`, `open-high-low.md:29/30`, `two-candle.md:24`,
  `gates-strike-sr-fiidii.md:19`, `intro-terminology.md:24` — ALL exist and say what the plan claims. The
  UNCERTAIN_OWNER exclusion (`straddle.md:28`) and the short-gated defer (`straddle.md:30`) are honest.
- FE: `ManualVerifyChecklist.tsx` L80-92 maps `detail.dots` generically — a new dot auto-renders, no FE
  change (confirmed).

### Soundness — 3 issues pass-1 + the author both missed (all corrected in place)
1. **Straddle index/expiry MISMATCH (FIXED, §3.A.4).** The pass-1 snippet passed
   `client.macro(cfg.oiIndex(), eodDate, chain.expiry())`. But `chain` is fetched with `cfg.underlying()`,
   so `chain.expiry()` is the OPTION-ROOT expiry, while the shipped `scalp-straddle-sensex-niftyoi.yaml`
   has `oi_confluence_gate.index: "NIFTY 50"` ≠ underlying `SENSEX` (verified in the YAML). Passing
   `cfg.oiIndex()` ("NIFTY 50") with the SENSEX expiry asks market-data for a NIFTY chain at a non-NIFTY
   expiry → the new active-strikes GET 422s/empties. It is HARMLESS to the gate DECISION (the low-IV
   straddle gate reads only the IV *pair* from `/options/chain`, whose `macro` read passes only
   `underlying` and is expiry-independent), but it fires a wasted, erroring HTTP call on every armed
   straddle bar. Changed the snippet to `cfg.underlying()` (a matched index/expiry pair = the option-root's
   own IV, the correct read for "is THIS straddle rich") and added a pass-2 note explaining why the neutral
   path cannot reuse the directional path's `oiExpiry` resolution.
2. **§4 table contradicted §5.2 + Open Point 8 on the golden model (FIXED, §4 row 3.A.1).** The parity
   table said "a new `*.golden.json`" for the `iv_slope` dot, but Open Point 8 (RESOLVED) and §5.2 establish
   there is NO scalper byte-golden fixture and "do NOT author a new byte-golden harness" — the parity guard
   is the behavioural unit assertion `dots().size()==18 && aggregate==<current>`. Rewrote the row to drop
   the `*.golden.json` and point at `IV_PER_STRIKE_CFG` + the unit assertion.
3. **PR-1 scope contradicted Open Point 3 on the 7–10 band (FIXED, §7 PR-1 + §4 row 3.A.2).** PR-1 listed
   "the 7–10 band" as in-scope, but Open Point 3's recommended default is to DEFER it (the `ivPairMinGap`
   floor is already a DB-param). Aligned PR-1 + the §4 row to "deferred by default; build only if the owner
   picks Open Point 3 option (a)".

### Minor semantic note (not a blocker — recorded for the executor)
- **Hero-Zero both-flat leg overlaps the scorer's both-high case (§3.C).** The leg blocks when
  `|ceIvAvg6 − peIvAvg6| <= 0.02` with NO absolute-level floor, so two IVs both at ~0.45 with a tiny gap
  (the both-HIGH case) also trip it, not only the both-LOW/flat case the deck's "sellers pinning, only
  erosion" describes. There is no double-count (Hero-Zero is a separate buy-side gate; the scorer's
  `ivBothHighStandAside` doesn't run on the Hero-Zero path), so this is a defensible first-order reading of
  "flat on both sides" = near-zero CE−PE skew. If the owner wants the strict "flat AND low" semantics, add
  a `&& both < someFloor` clause. Left as-is (the gap-only check is faithful to the §3.7 S22 (i) wording);
  flagged so the executor chooses deliberately, not accidentally.

### Parity — re-confirmed end-to-end
- The default-OFF invariant is REAL and independently re-verified: no shipped YAML carries
  `iv-per-strike`/`iv-absolute-band`/`hero-zero-iv-flat`/`low-iv-straddle`. The [P] dots (`iv_slope`,
  `iv_abs_band`, per-side cap) sit inside `if (gate)` so the 18-dot / `den=19.6` aggregate is BIT-IDENTICAL
  when unarmed. Armed, `den` grows 19.6 → ~21.2 (two W_IV=0.8 dots) — Open Point 12's STRICTER-when-armed
  caveat is correct and the threshold-retune advice is sound.
- The two [S] changes (`iv-flat-both-sides`, `low-iv-straddle`) are stricter-only / new-path-with-no-golden
  — classifications hold. `GoldenSignalsJson.write` (frozen 6 keys) + the 5 pure-engine FEATURES guarantee
  the engine goldens stay byte-identical; the scalper IV reads ride the non-serialized `scalper_detail`
  side-channel. No [P] change is mis-marked [S]; no [S] change moves an existing-config signal.

### Final readiness verdict
**READY to execute** with the 3 in-place corrections absorbed. The IV-direction data flow, the parity
discipline (tag-gated default-OFF + behavioural seam assertion, no new byte-golden), the signature/arity
fan-outs (macro+expiry, score+flag, HeroZero+2 scalars, ScalperConfig+flags, ScalperOiProps 11→13), and
the honest gap accounting (8 IV rows closed; the 3 per-strike-OI/volume rows deferred to a strike-stat
package per Open Point 10; the short-straddle legs SPAN-deferred) are all sound. The single behavioural
risk to brief the owner on is unchanged: arming dilutes the denominator (re-tune `confluenceThreshold` on
the forward variant), and IV degrades to NEUTRAL on derived history (judge on FORWARD paper, never a
backtest — PR-4 is owner-gated). No new migration, no contract-spec drift, no market-data code change.
