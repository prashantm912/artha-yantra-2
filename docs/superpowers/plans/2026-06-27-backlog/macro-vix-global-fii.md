# Macro confluence: directional VIX, global cues, FII/DII, constituent weight

Status: PLAN (implementation-ready). Owner: single-owner. Primary service:
`services/strategy-signal-service` (scalper confluence seam) + `services/market-data-service`
(macro analytics feeds). Date: 2026-06-27. Stream slug: **`macro-vix-global-fii`**.

> Read order for the executor: this plan is self-contained but assumes the
> **FU2 parity-safe-additive convention** (`docs/superpowers/plans/2026-06-27-followup2-soft-dots-to-hard-gates.md`)
> and the `oi-cross-filter` (#5) hard-gate template are the load-bearing precedents. Every
> parity-sensitive change here copies that exact shape: a NEW per-strategy YAML tag, absent from
> all 36 shipped configs, default-OFF, so existing emitted signals stay byte-identical. The CLAUDE.md
> "Extend engine records parity-safely" + "Kite wire DTO" + "directional-vix-gate is OUT of FU2" notes
> govern.

---

## 1. Goal & the packages/gaps this stream closes

This stream wires the **macro half** of the Connect-the-Dots confluence — the four feeds that are
currently *starved* or *unread* in `MarketOiClient.macro()` and `ConnectTheDotsScorer`, plus two
read-only analytics it depends on. The unifying theme: the `ScalperGateContext.Macro` record already
*declares* all the fields (`vixLevel`, `vixRising`, `advances`, `declines`, `fiiLongPct`), but the
producer passes `null`/unread values, so the corresponding dots/gates degrade to pass (no-ops). This
stream populates them and adds the two missing dots (FII, constituent), each parity-gated.

| Package | # gaps | Doc § | Disposition source rows |
|---|---:|---|---|
| **`directional-vix-gate`** | 11 | 4.5 / 4.14.1 / 4.14.5 / 4.17.5 / 3.10 / 3.12 / §7 | `indicators-oi-vix-iv.md` L25,L27 (2); `gates-strike-sr-fiidii.md` L27 (1); `connect-the-dots.md` L23 (1); `trend-change.md` L27 (1); `completeness-sweep.md` L34 (1); + the `open-high-low` / `two-candle` / `intro-terminology` / `session-additions` directional-VIX rows tallied in GAP-DISPOSITION §3a (11 total) |
| **`global-cues-feed`** | 1 (+ tail) | 3.10 / 4.7 | `connect-the-dots.md` L24 (Dow dot, AUTOMATE) + L25 (Dollar/Asian/Crude — KEEP_MANUAL); `gates-strike-sr-fiidii.md` L14 + `completeness-sweep.md` L36 reference it (rows themselves COVERED_EXISTING). The single-gap `[P]` package is the **Dow dot**. |
| **`fii-dii-bias`** | 6 | 4.13 / 4.17.4 / 3.10 / 3.12 | `gates-strike-sr-fiidii.md` L28,L29,L30,L31 (4); `connect-the-dots.md` L26 (1); `completeness-sweep.md` L35 (1) |
| **`constituent-contribution`** | 1 | 3.12 / 4.14.4 | `trend-change.md` L29 (data-side AUTOMATE; FU1 carries the manual reminder) |
| **`volume-pump-attribution`** | 1 | 4.15.3 | `indicators-oi-vix-iv.md` L32 |

**Total: 20 AUTOMATE gaps** (11 + 1 + 6 + 1 + 1), all in the GAP-DISPOSITION §3 `AUTOMATE_PKG`
backlog, none in FU1/FU2. The FU1 manual reminders that shadow these (`vix_regime_bands`,
`fii_ls_ratio`, `constituent_contribution`, `global_cues_ok`) are already shipped and stay — this
stream is the *automation* behind them.

**Net deliverable:** populate `Macro.vixLevel/vixRising` (was null), add a `dow` macro field + dot,
add a `fii` confluence dot consuming the dead-wired `fiiLongPct` + a new participant L/U/B/C
classifier, add a `constituent` macro field + dot, and refine the `volume` dot to attribute
bull/bear pump direction. Every one that alters emission is behind a new default-OFF tag.

---

## 2. Current state (verified file:line)

All line numbers opened and confirmed against the working tree on 2026-06-27.

### 2.1 The macro record — already has the slots, producer feeds nulls
`services/strategy-signal-service/.../scalper/ScalperGateContext.java` L59-68:
```java
public record Macro(
    BigDecimal atmIv, BigDecimal ivRank,
    BigDecimal vixLevel, Boolean vixRising,   // <-- both null today
    int advances, int declines,
    BigDecimal fiiLongPct,                     // <-- read, but NO dot consumes it
    BigDecimal ceIvAvg6, BigDecimal peIvAvg6) {}
```

### 2.2 The producer — `MarketOiClient.macro(...)` (L350-398)
- Reads `atmIv`, `ivRank` (`/options/iv-history`), breadth (`/breadth` → `advances/declines`),
  `fiiLongPct` (`/fii-dii/long-short` → `latestFiiLongPct` L625-641), the IV pair (`/options/chain`).
- **L394-397 the VIX gap:** `return new Macro(atmIv, ivRank, null, null, breadth[0], breadth[1],
  fiiLongPct, ...)` — the comment at L394-395 states "VIX has no market-data endpoint yet (§12.2
  follow-up)". **This is the root of `directional-vix-gate`.** (NB: a `/vix` endpoint *does* exist —
  see 2.6 — so the comment is stale; the gap is that `macro()` never calls it.)
- `advanceDecline` L619-623, `latestFiiLongPct` L625-641 already map the JSON.

### 2.3 The scorer — `ConnectTheDotsScorer.score(...)` (L63-118)
The 18-dot list (L74-98). Relevant rows:
- `add(dots, "volume", W, ScalperGates.volume(ctx.signalIndex(), c.volume()).pass(), ...)` L79 —
  a **floor-only** check; no bull/bear pump attribution (the `volume-pump-attribution` gap).
- `add(dots, "breadth", W, ScalperGates.breadth(m, side).pass(), ...)` L91.
- `add(dots, "vix", W, ScalperGates.vix(m, side).pass(), ...)` L92 — **the starved dot**: with
  `m.vixRising()==null` (2.2) `ScalperGates.vix` L137-138 returns pass, so this dot *always supports*.
- **There is NO `fii` dot, NO `dow` dot, NO `constituent` dot** (grep-confirmed: `fiiLongPct` has
  zero readers in the scorer). Aggregate denominator math: 18 dots, Σweights = 19.6 (FU2 §2.1 audit).

### 2.4 The gate library — `ScalperGates.java`
- `vix(Macro, side)` L136-143 — unknown direction PASSES (the fail-open the null feed relies on).
- `breadth(Macro, side)` L128-133 — `advances/declines > 32`.
- `volume(String underlying, BigDecimal)` L64-68 — floor only (no price-sign).
- **No `dow`, `fii`, `constituent` gate fns exist.**

### 2.5 The tag → gate wiring — `ScalperConfig.java`
- `record ScalperConfig(...)` L36-52 (15 fields today, ending `requireStraddle`).
- `from(JsonNode, List<String> tags)` L101-157: each `tags.contains("<tag>")` parse (L119-153) +
  the single canonical `new ScalperConfig(...)` at L154-156.
- 36 YAMLs under `services/strategy-signal-service/src/main/resources/scalper-strategies/` (12
  strategies × {nifty, sensex-niftyoi, sensex-sensexoi}); the arming field is the top-level `tags:`
  list. None carry any tag this stream introduces.

### 2.6 Market-data feeds that ALREADY exist (the data is captured; only the wiring is missing)
- **INDIA VIX:** `MarketSurfaceController.vix()` (`feed/MarketSurfaceController.java` L59-74) →
  `GET /api/v1/market/vix` returns `{ltp, prevClose, change, changePct, asOf}` from the pinned
  `INDIA VIX` quote. The 1m candle series `read("NSE","INDIA VIX","1m",...)` is read by
  `ConnectingDotsService.vixByBucket` (`options/analytics/ConnectingDotsService.java` L353-367).
  **So the VIX feed exists end-to-end; `macro()` just never calls it.**
- **Dow:** `GlobalQuoteSource.latest(DOW)` (`kite/GlobalQuoteSource.java`) →
  `DOWJONES@GLOBAL_INDEX` LTP+prevClose, flag-gated (`artha.openalgo.global-quotes-enabled`).
  Consumed by `ConnectingDotsService.dowFactor` (L310-326, `int dow` L167). **No market-data
  REST endpoint surfaces it for the scalper** — only the connecting-dots matrix uses it.
- **FII participant matrix:** `GET /api/v1/market/fii-dii/participant-oi`
  (`nse/analytics/FiiDiiController.java` L49-55) → `NseEodReader.ParticipantOiRow` (L30-46) with
  `clientType`, `futureIndexLong/Short`, `optionIndexCallLong/PutLong/CallShort/PutShort`,
  `totalLong/ShortContracts`. **Two days are queryable (from/to) → the L/U/B/C delta classifier is
  pure data already present.** The `/long-short` endpoint (L57-78) already derives the FII
  index-future L/S ratio that `fiiLongPct` reads.
- **Constituent contribution:** `EquityIndexContributionService`
  (`nse/analytics/EquityIndexContributionService.java`) computes per-constituent `weight × %change`
  → `IndexContribution{indexChangePct, advanceTotal, declineTotal, advances[], declines[]}` from
  `StaticIndexWeights` + the EQ bhavcopy. **The directional read (advanceTotal vs declineTotal sign)
  is exactly the `constituent-contribution` signal — needs only an endpoint + a macro field.**
- **Volume pump:** `ConnectingDotsService.volumeFactor(vol, prevVol, priceDelta)` L240-246 already
  signs a rising-volume bucket by price direction (bull/bear). The scalper's `volume` dot does NOT —
  that is the entire `volume-pump-attribution` gap (lift the same price-signed logic into the dot).

### 2.7 The parity firewall (unchanged, cited so the executor honours it)
- `ScalperConfluenceGate` is **LIVE-only** (class javadoc L29-33): OI/macro/chain reads never run on
  deterministic replay; the picked option + confluence persist at entry (V009 side-channel).
- `GoldenDeterminismTest` (`libs/strategy-engine/.../golden/GoldenDeterminismTest.java`) FEATURES +
  `BacktestParityTest` (`services/backtest-service/.../replay/BacktestParityTest.java`) FEATURES
  carry ZERO scalper YAMLs, so a tag-gated scalper change cannot perturb them. **The byte-identity
  proof is: no golden/parity YAML carries a tag this stream introduces (none can — the FEATURES
  arrays are fixed pure-engine features).**

---

## 3. Design — per package

> **Macro-record extension strategy (applies to all four `[P]` macro-field additions).** The
> `Macro` record is read by `ConnectTheDotsScorer` (a pure function) and assembled by `MarketOiClient`.
> Adding a field is a constructor-arity fan-out (the canonical `new Macro(...)` at `MarketOiClient`
> L396-397 + every test builder). It is **parity-safe ONLY because the scorer reads the new field via
> a NEW dot that is itself gated** — see §4. We do NOT thread a flag into `score(...)`; instead each
> new dot is added to the list but its *support* contribution is controlled by whether the producing
> tag populated the field (null field → dot reads false/neutral identically to today is NOT enough,
> because adding a dot to the list changes the denominator). **Therefore the new dots are added to the
> scorer list conditionally — see §4.0 for the mechanism that keeps the aggregate byte-identical when
> unarmed.**

### 3.0 Mechanism for parity-safe NEW dots (the load-bearing decision)
FU2 promoted *existing* dots to gates without touching the scorer (the dots were already in the list).
This stream is different for `fii`/`dow`/`constituent`: those dots **do not exist** in the scorer, so
adding them naively changes the denominator (19.6 → higher) on EVERY scalper bar → breaks live
confluence for every shipped strategy (and is exactly the trap FU2 §8 point 2 flagged for Dow).

Two options:

- **(A) Early-return HARD gate in `ScalperConfluenceGate.evaluate` (the #5 / FU2 template).** The new
  macro field is populated only when the strategy carries the tag; the dot is consulted as a hard
  precondition (early-return on fail), NOT added to the scorer list. **Scorer untouched → aggregate
  byte-identical when unarmed.** This is the recommended default for `directional-vix-gate`,
  `fii-dii-bias`, `constituent-contribution`, `global-cues-feed (Dow)`.
- **(B) New SCORED dot threaded behind a per-call boolean.** Would require changing `score(...)`'s
  signature and the denominator conditionally — strictly worse for parity isolation (FU2 §4.0
  rejected this). **Not used.**

**Decision: use (A) for all four signal-emitting packages.** Each becomes a `requireXxx` flag + a
`ScalperGates.xxx(...)` pure fn + an early-return block — identical in shape to FU2's four gates.
The VIX feed wiring into `macro()` (3.1 step 1) is the ONE producer change that is shared; it is
parity-safe on its own because **nothing reads `vixRising` until the `vix-gate` tag is armed** — the
existing `vix` *soft dot* (scorer L92) currently reads `m.vixRising()`, so populating it WOULD change
that dot. See the critical carve-out in 3.1.

### 3.1 `directional-vix-gate` (11 gaps) — `[P]`

**The carve-out that makes this non-trivial:** the scorer ALREADY has a `vix` soft dot (L92) reading
`m.vixRising()`. Today `vixRising` is null → the dot always passes. If `macro()` simply starts
returning a real `vixRising`, the EXISTING soft dot flips from always-pass to a real confirm/block on
EVERY scalper → **non-parity-safe even without any new tag.** So the producer change must be gated too.

**Step 1 — gate the VIX feed read in the producer.** `MarketOiClient.macro(...)` must only populate
`vixLevel/vixRising` when the calling strategy opts in. Since `macro()` does not know the strategy's
tags, thread a flag from the seam:

- `MarketOiClient.context(...)` (the assembler, ~L88-92) already takes the oi-index/signal-index; add a
  `boolean vixEnabled` param it forwards to `macro(underlying, eodDate, vixEnabled)`.
- New helper `MarketOiClient.vixDirection(LocalDate tradeDate)`:
  ```java
  /** Live INDIA VIX level + intra-session direction for the directional-vix-gate. Reads GET /vix
   *  (ltp + prevClose); rising = ltp > prevClose. null/null when the feed is absent (off-hours,
   *  mock, or derived history) so the gate fail-opens (never blocks). */
  private VixRead vixDirection() {
    return get(uri -> uri.path("/api/v1/market/vix").build(),
        json -> {
          BigDecimal ltp = decimal(json.path("ltp"));
          BigDecimal chg = decimal(json.path("change"));   // ltp - prevClose, server-computed
          if (ltp == null || chg == null) return VixRead.EMPTY;
          return new VixRead(ltp, chg.signum() > 0);       // rising iff change > 0
        },
        VixRead.EMPTY, "vix");
  }
  record VixRead(BigDecimal level, Boolean rising) { static final VixRead EMPTY = new VixRead(null, null); }
  ```
- In `macro(...)`: `VixRead vix = vixEnabled ? vixDirection() : VixRead.EMPTY;` then return
  `new Macro(atmIv, ivRank, vix.level(), vix.rising(), breadth[0], breadth[1], fiiLongPct, ...)`.
  **When `vixEnabled` is false (every shipped strategy today), `vixLevel/vixRising` stay null →
  the existing `vix` soft dot behaves bit-identically to today.**

**Step 2 — the hard gate (the directional rule + the supporting inferences).** `ScalperConfig`:
add `boolean requireVixGate` (tag `vix-gate`). `ScalperConfluenceGate.evaluate`, after `ctx` is built
(after the `client.context(...)` call ~L192), insert:
```java
// directional-vix-gate: VIX falling confirms CE / rising confirms PE (the §4.5 inverse grid).
// Populated only when vix-gate is armed (macro() reads /vix); null direction DEGRADES to pass
// (fail-open, like #5/FU2) so off-hours / derived history never block. OUT of FU2 by design.
if (cfg.requireVixGate() && !ScalperGates.vix(ctx.macro(), side).pass()) {
  return Optional.empty();
}
```
The seam must pass `vixEnabled = cfg.requireVixGate()` into `client.context(...)`.

**Step 3 — the richer inferences (the 2 extra gaps: "rising VIX = fresh shorts", "VIX stable + price
falling = longs exiting", "VIX vs prev-day close").** These ride the SAME feed. The `change`-sign
already gives prev-close direction. Add a second optional rule inside the gate behind the same tag:
the "VIX abnormally spiking" magnitude is already manual-covered (`vix_normal`); encode only the
directional confirm here (the spike veto stays manual per the disposition). Document the "fresh
shorts / longs exiting" interpretation as the dot's reason string for the side-channel; no separate
numeric gate (it is the same `rising` sign already used).

**Data flow:** `/vix` → `MarketOiClient.macro` → `Macro.vixRising` → `ScalperGates.vix` →
early-return in the seam. No new persisted field (the `vix` dot already rides `scalper_detail`).

### 3.2 `global-cues-feed` (Dow dot, 1 gap) — `[P]`; Dollar/Asian/Crude — `[S]` (KEEP_MANUAL, no code)

**The Dow dot.** Dow LTP-direction exists (`GlobalQuoteSource`) but is consumed only by the
connecting-dots matrix, never surfaced to the scalper. Two sub-steps:

- **Market-data:** add `GET /api/v1/market/global-cues` to a new tiny controller (or extend
  `MarketSurfaceController`) returning `{dow: {ltp, prevClose, rising}}` by calling
  `GlobalQuoteSource.latest(DOW)` (the same bean `ConnectingDotsService` uses). Flag-gated identically
  (returns `rising=null` when `global-quotes-enabled=false`).
- **Strategy-signal:** add `Boolean dowRising` to `Macro` (arity fan-out); `macro()` reads it only
  when `dowEnabled` (threaded like `vixEnabled`); a new `ScalperGates.dow(Macro, side)`:
  ```java
  /** Dow rising confirms CE / falling confirms PE (global-cue alignment). Unknown never blocks. */
  public static GateOutcome dow(Macro m, OptionType side) {
    if (m.dowRising() == null) return GateOutcome.pass(null, "dow direction unknown");
    boolean ok = side == OptionType.CE ? m.dowRising() : !m.dowRising();
    return new GateOutcome(ok, null, "dow " + (m.dowRising() ? "rising" : "falling")
        + (ok ? " supports " : " opposes ") + side);
  }
  ```
- **Gate:** `ScalperConfig.requireDowGate` (tag `global-cues-gate`); early-return after `ctx`,
  fail-open on null. Same shape as VIX.

**Dollar Index / Asian / European / Crude / Bond / USD-INR (the L25 row).** Disposition =
KEEP_MANUAL_NEW: **no live feeds on the platform** → not automatable now. Coarsely covered by the
shipped `global_cues_ok` manual check. **No code in this stream.** Recorded as Open Point §8.1 (a
future feed-onboarding effort would extend `GlobalQuoteSource` keys, e.g. `DXY`/`CRUDEOIL`, then add
dots behind the same `global-cues-gate` tag).

### 3.3 `fii-dii-bias` (6 gaps) — `[P]`

Four data sub-rules collapse into one consuming dot + one classifier:

1. **Consume the dead-wired `fiiLongPct`** (already in `Macro`, no reader). The "FII index-future L/S"
   directional read: `fiiLongPct > 50` is FII-net-long (CE-favouring), `< 50` net-short (PE).
2. **The L/U/B/C change-in-OI classifier** (the most concrete sub-rule). New market-data analytic:
   add to `NseEodReader` / a new `ParticipantBiasService` a 2-day delta classifier over
   `participant-oi`:
   - For `clientType=FII` (and optionally Pro/DII), compare day-T vs day-(T-1):
     `ΔfutLong = futLong_T - futLong_{T-1}`, `ΔfutShort = futShort_T - futShort_{T-1}`,
     `Δprice` (index close delta, from the candle archive).
   - Classify into the 4×2 LB/SC/LU/SB table: price↑ & ΔlongOI↑ = Long Build-up (bull);
     price↑ & ΔshortOI↓ = Short Covering (bull); price↓ & ΔshortOI↑ = Short Build-up (bear);
     price↓ & ΔlongOI↓ = Long Unwinding (bear). Same enum as the existing `OiInterpretation`.
   - Importance weighting FII > Pro > DII > Client (the disposition's stated priority): take FII's
     classification as primary; Pro as tiebreak.
3. **Leg-level seller read** (4 participants × 6 legs): net-seller of Calls = bearish, net-seller of
   Puts = bullish. From the same `ParticipantOiRow`:
   `callNet = optionIndexCallLong - optionIndexCallShort`, `putNet = optionIndexPutLong -
   optionIndexPutShort`. FII net-short calls (callNet<0) + net-long puts skew bearish, mirror bullish.
4. **Next-morning-only validity / voided by strong global moves** — a scope qualifier (the bias is an
   EOD read valid only into the next session). Encode as: the FII gate is consulted only on the FIRST
   N bars of the session (e.g. before 10:00 IST); after that it degrades to pass. The "voided by
   strong global moves" leg rides the Dow gate (3.2) — if Dow strongly opposes, suppress the FII bias.
   v1: keep simple — gate active all session, fail-open on null; record the time-window refinement as
   Open Point §8.3.

**Wiring:**
- **Market-data:** `GET /api/v1/market/fii-dii/bias?date=<T>` → `{fiiClassification: "LONG_BUILDUP",
  bias: "BULL", fiiLongPct, callNet, putNet, legBias: "BULL"}` from the new `ParticipantBiasService`
  (reads `participant-oi` for T and T-1 + the index close delta). Reuses the existing repo.
- **Strategy-signal:** add `BigDecimal fiiBiasSign` to `Macro` (+1 bull / -1 bear / 0 neutral),
  populated by `macro()` only when `fiiEnabled`. (`fiiLongPct` already present; the new field carries
  the COMBINED classifier verdict so the dot is a single read.)
- **Gate:** `ScalperConfig.requireFiiGate` (tag `fii-dii-gate`); `ScalperGates.fii(Macro, side)`:
  ```java
  /** FII EOD bias: net-long / LB / SC + net-Put-seller = bullish -> CE; mirror -> PE. Combined into
   *  Macro.fiiBiasSign (+1/-1/0); 0 (no data / conflicting) DEGRADES to pass (fail-open). */
  public static GateOutcome fii(Macro m, OptionType side) {
    if (m.fiiBiasSign() == null || m.fiiBiasSign().signum() == 0)
      return GateOutcome.pass(null, "fii bias unavailable/neutral");
    boolean bull = m.fiiBiasSign().signum() > 0;
    boolean ok = side == OptionType.CE ? bull : !bull;
    return new GateOutcome(ok, m.fiiBiasSign(), "fii bias " + (bull ? "bull" : "bear")
        + (ok ? " supports " : " opposes ") + side);
  }
  ```
  Early-return after `ctx`, fail-open on null/zero.

**Derived-history caveat:** the participant matrix is real EOD NSE data (not OI-derived), so unlike
the OI gates this one DOES carry signal on a past EOD date — but the next-session intraday backtest
still reads the *prior* completed session's `eodDate` (the seam already passes `eodDate` for
breadth/FII). So `fii-dii-gate` is usable in backtest, unlike `futures-oi-gate`. Note this in the
YAML header when PR-3 arms it.

### 3.4 `constituent-contribution` (1 gap) — `[P]`

`EquityIndexContributionService` already computes `advanceTotal` vs `declineTotal` per index. The
directional read: `advanceTotal > |declineTotal|` ⇒ heavyweights pushing up (CE-favouring), mirror PE.

**Wiring:**
- **Market-data:** the service exists; confirm/add a controller endpoint
  `GET /api/v1/market/equity/index-contribution?index=<NIFTY 50>&date=<T>` returning the
  `IndexContribution` (advanceTotal/declineTotal/indexChangePct). (If the endpoint already exists from
  the oipulse wave, reuse it — grep `EquityIndexContributionService` controller; otherwise add a thin
  `@GetMapping`.)
- **Strategy-signal:** add `BigDecimal constituentBiasSign` to `Macro` (+1/-1/0 from
  `signum(advanceTotal + declineTotal)` or `signum(indexChangePct)` weighted by breadth), populated by
  `macro()` only when `constituentEnabled`.
- **Gate:** `ScalperConfig.requireConstituentGate` (tag `constituent-gate`);
  `ScalperGates.constituent(Macro, side)` mirroring `fii(...)`; early-return after `ctx`, fail-open
  on null. FU1's `constituent_contribution` manual check stays (this automates it).

**Derived-history caveat:** uses the EQ bhavcopy + static weights → real on a past EOD date, but
needs the index daily close (the service notes `points` is null when the index close isn't archived).
The *sign* (advance vs decline total) is robust even without the level, so the gate works on history.

### 3.5 `volume-pump-attribution` (1 gap) — `[P]`

The scorer's `volume` dot (L79) is floor-only. The doc's >50K/125K dark-green/dark-red rule attributes
a high-volume candle to a bull vs bear pump by price sign (exactly `ConnectingDotsService.volumeFactor`
L240-246: rising volume + price up = bull, + price down = bear).

**Design:** add a NEW gate fn `ScalperGates.volumePump(Chart c, BigDecimal prevClose, String underlying,
OptionType side)`:
```java
/** Volume-pump attribution (§4.15.3): a candle clearing the floor AND closing in the side's
 *  direction (close > open for CE / < open for PE) is a confirming pump; clearing the floor against
 *  the side is an OPPOSING pump (block). prevClose/open null DEGRADES to pass. */
```
Use `Chart.close()` vs the bar `open` (the seam has the `future` series — pass `future.candle(index)`
open). **This is parity-sensitive** (it can turn a passing floor into a block when the pump opposes
the side). So it is a tag `volume-pump`, `requireVolumePump`, early-return after the volume/RSI rails.
The existing floor `volume` dot in the scorer is UNCHANGED (still floor-only) — the pump is an
*additional* hard precondition, not a replacement, keeping the aggregate byte-identical.

**Note on the open price:** `ScalperGateContext.Chart` has no `open`. Rather than extend `Chart`
(arity fan-out into the scorer), read the open from the `future` `EngineSeries` already in scope in
`evaluate` (`future.candle(index).open()`), exactly as the structural-stop code does (L294). So
`volumePump` takes the open as a param, not via `Chart`.

---

## 4. PARITY classification

| Package | Change | Class | Tag (new, default-OFF) | Golden-variant plan |
|---|---|:--:|---|---|
| `directional-vix-gate` | Populate `Macro.vixRising` in `macro()` **only when vix-gate armed** + early-return gate | **[P]** | `vix-gate` | None needed (LIVE-only seam; no golden YAML is a scalper). Proof = `ScalperStrategyLoadTest` asserts `requireVixGate` OFF for all 36 + the two engine tripwires stay byte-identical. |
| `global-cues-feed` (Dow dot) | New `Macro.dowRising` + `ScalperGates.dow` + early-return gate | **[P]** | `global-cues-gate` | Same as above. |
| `global-cues-feed` (DXY/Asian/Crude) | none (KEEP_MANUAL — no feed) | **[S]** | — | n/a (no code). |
| `fii-dii-bias` | New `ParticipantBiasService` (read-only analytic) | **[S]** | — | Read-only market-data analytic + endpoint; no signal change until the gate consumes it. |
| `fii-dii-bias` | `Macro.fiiBiasSign` + `ScalperGates.fii` + early-return gate | **[P]** | `fii-dii-gate` | Same LIVE-only-seam proof. |
| `constituent-contribution` | Endpoint over existing `EquityIndexContributionService` | **[S]** | — | Read-only; the service already exists. |
| `constituent-contribution` | `Macro.constituentBiasSign` + `ScalperGates.constituent` + gate | **[P]** | `constituent-gate` | Same. |
| `volume-pump-attribution` | `ScalperGates.volumePump` + early-return gate (scorer `volume` dot UNCHANGED) | **[P]** | `volume-pump` | Same. The floor dot stays in the aggregate untouched → byte-identical when unarmed. |

**Why no NEW golden vectors are created** (mirrors FU2 §5.4/§5.6): every `[P]` change here lives on the
**LIVE-only `ScalperConfluenceGate` path**, which `GoldenDeterminismTest` and `BacktestParityTest`
never instantiate (their FEATURES arrays are the 5 pure-engine YAMLs with no scalper). A tag-gated
gate **cannot** perturb those goldens as long as no golden/parity YAML carries a tag — and none can.
The byte-identity guarantee for shipped scalper configs is enforced by the **`ScalperStrategyLoadTest`
OFF-assertions** (each new `requireXxx` is asserted false for all 36 seeded strategies), NOT by a new
golden. **A new golden variant would only be required if a `[P]` change ran on the deterministic
replay path — none here do.** Producing one is explicitly out of scope (would require driving the
LIVE-only gate through `TickwiseGoldenRunner`; FU2 §5.6 / §8.8 deferred this). Recorded Open Point §8.6.

**The one subtle non-tagged parity risk** (flagged so the executor cannot miss it): populating
`Macro.vixRising` unconditionally would flip the EXISTING `vix` soft dot (scorer L92) for every
scalper. §3.1 step 1 prevents this by gating the producer read on `vixEnabled = requireVixGate`. The
`ScalperStrategyLoadTest` must therefore ALSO assert that, for an un-armed strategy, `macro()` still
yields `vixRising == null` (a producer-level tripwire, not just a config-flag one) — see §5.3.

---

## 5. Tests

### 5.1 Unit — gate functions (`ScalperGatesTest.java`)
- `vix(...)` already tested (`vixDirectionFavoursSideAndUnknownNeverBlocks` L118-122). **Reuse.**
- `breadth(...)` already tested (L110-115). **Reuse.**
- **New:** `dowDirectionFavoursSideAndUnknownNeverBlocks` (mirror the vix test: rising→CE pass,
  rising→PE pass, null→pass).
- **New:** `fiiBiasFavoursSideAndNeutralPasses` (+1→CE pass, +1→PE fail, 0→pass, null→pass).
- **New:** `constituentBiasFavoursSide` (mirror fii).
- **New:** `volumePumpConfirmsWithDirectionAndBlocksAgainst` (floor-clearing close>open + CE pass;
  floor-clearing close<open + CE block; below-floor → the floor gate already blocks separately;
  null open → pass). Add `macro(...)`/`chart(...)` builder overloads for the new `Macro` fields.

### 5.2 Unit — seam wiring (`ScalperConfluenceGateTest.java`)
- **Arity fan-out:** the 5 new `requireXxx` fields force updating **all 8** existing
  `new ScalperConfig(...)` literals (L44,49,56,62,68,74,81,87) — append 5 trailing `false`s to each
  (none arm the new gates). Pure compile-time fan-out (FU2 §2.4 precedent: 8 literals, confirmed).
- For **each** of `vix-gate`, `global-cues-gate`, `fii-dii-gate`, `constituent-gate`, `volume-pump`
  add a CFG literal + the FU2 triple (pass / block / non-armed-unaffected), mirroring the #5
  `oi-cross-filter` template (the `confluenceConfirmsAndPicksTheInBandCe` + block + `nonXxxUnaffected`
  shape):
  - **vix-gate:** mock `client.context(...)` to return a `bullContext()` whose `Macro.vixRising=TRUE`
    (rising) → CE blocks; `FALSE` (falling) → CE passes; bare `CFG` (no tag) → passes despite rising
    (proving the soft dot, not the gate, governs un-armed). Plus assert the `vixEnabled` flag is
    forwarded: verify `client.context(...)` is called with the 8th arg true only for `VIX_CFG`.
  - **global-cues-gate:** `Macro.dowRising=FALSE` + CE → block; `TRUE` → pass; bare `CFG` → pass.
  - **fii-dii-gate:** `Macro.fiiBiasSign=bd("-1")` + CE → block; `+1` → pass; `0`/null → pass.
  - **constituent-gate:** `Macro.constituentBiasSign=bd("-1")` + CE → block; `+1` → pass.
  - **volume-pump:** a `future` series whose `candle(index)` closes DOWN (close<open) but clears the
    floor + CE → block; closes UP → pass; bare `CFG` → pass. (Use the real `EngineSeries` test
    fixture, since the gate reads `future.candle(index).open()`.)
  - Each gets a `non<Pkg>Unaffected` test using bare `CFG` against the same failing context →
    `.isPresent()`.

### 5.3 Load test (`ScalperStrategyLoadTest.java`)
- Add 5 OFF-assertions in the per-strategy loop (after L153), mirroring the `requireCallPutDeltaFilter`
  per-family assertion (L151):
  ```java
  assertThat(cfg.requireVixGate()).as(id + " vix-gate off").isFalse();
  assertThat(cfg.requireDowGate()).as(id + " global-cues-gate off").isFalse();
  assertThat(cfg.requireFiiGate()).as(id + " fii-dii-gate off").isFalse();
  assertThat(cfg.requireConstituentGate()).as(id + " constituent-gate off").isFalse();
  assertThat(cfg.requireVolumePump()).as(id + " volume-pump off").isFalse();
  ```
- **Producer tripwire (the §4 subtle risk):** add a focused test in `MarketOiClientTest` (or a new
  `MarketOiClientMacroTest`) that `macro(underlying, date, /*vixEnabled*/ false)` returns
  `vixRising == null` and `vixLevel == null` even when the mocked `/vix` endpoint would return a
  value — proving the un-armed path is byte-identical to today.

### 5.4 Market-data unit/IT — the new analytics
- `ParticipantBiasService` unit test: feed two `ParticipantOiRow`s (T-1, T) + a price delta → assert
  the LB/SC/LU/SB classification + the leg-level callNet/putNet sign + the combined `bias`.
- `ParticipantBiasController` slice test + a Testcontainers IT seeding `nse_eod_participant_oi` two
  days (the IT harness already migrates the NSE lineage) → assert `/fii-dii/bias` returns the verdict.
- `MarketSurfaceController` (or the new global-cues controller): a `/vix` direction + `/global-cues`
  slice test (mock `GlobalQuoteSource`); these are read endpoints (no contract-breaking change — new
  paths DO drift springdoc, so re-capture per §6).
- `EquityIndexContributionService` already has coverage; add an endpoint slice test if a new
  `@GetMapping` is introduced.

### 5.5 Golden / parity tripwires (MUST stay byte-identical — re-run, do NOT regenerate)
- `GoldenDeterminismTest` (`libs/strategy-engine`) — FEATURES carry no scalper → invisible. Assert
  byte-identical green.
- `BacktestParityTest` (`services/backtest-service`) — FEATURES carry no scalper → assert the three
  byte-match asserts stay green.

### 5.6 e2e
No scalper-specific e2e spec; the stream ships default-OFF (no YAML arms a tag) → no observable
behaviour change. Re-run `e2e/tests/signals.spec.ts` as a regression check only. If PR-arming
(deferred) later arms a tag, add an assertion there that the confluence chip row still renders.

### 5.7 Contracts (`ContractCaptureTest`)
The new market-data GET paths (`/global-cues`, `/fii-dii/bias`, optionally `/equity/index-contribution`)
ADD `@GetMapping` paths → **springdoc spec DOES drift** (per CLAUDE.md: new paths drift, generic Map
returns do not). Re-capture with `-Dcontracts.capture=true`, regen TS via
`npx openapi-typescript@7` → `contracts/gen/*.d.ts`, `tsc --strict`. The `/vix` direction fields ride
the existing `/vix` response (a generic shape) — confirm whether it drifts.

---

## 6. Dependencies & sequencing

1. **VIX feed wiring (3.1) is the spine** — it establishes the `vixEnabled`-threaded
   `macro()`/`context()` signature that Dow/FII/constituent reuse. **Do it first.** The `/vix`
   endpoint already exists (no market-data work needed for VIX itself).
2. **`global-cues-feed` (Dow)** depends on the `GlobalQuoteSource` flag (`global-quotes-enabled`)
   being on for live signal; ships fail-open so it is harmless when off. Needs the new
   `/global-cues` endpoint before the gate can read Dow → **market-data PR precedes the gate PR.**
3. **`fii-dii-bias`** needs the `ParticipantBiasService` + `/fii-dii/bias` endpoint
   (market-data, `[S]`) BEFORE the `Macro.fiiBiasSign` producer read + gate (strategy-signal, `[P]`).
   **Two-step: analytic first, gate second.**
4. **`constituent-contribution`** needs the contribution endpoint (verify it exists from the oipulse
   wave; else add it) BEFORE the gate. Lightest — the service is built.
5. **`volume-pump-attribution`** is self-contained (no new feed; reuses the in-scope `future` series).
   Can ship independently of 1-4.
6. **No SPAN / equity-universe dependency** — this stream is all index-level macro, so it is NOT
   gated on `equity-fno-universe-screener` (unlike the market-movers per-stock packages) and NOT on
   the SPAN appliance (no sell legs). It can proceed immediately.
7. **Arming any tag on a real YAML (PR-X, deferred)** is owner-driven forward-paper, AFTER the
   infrastructure PRs, per "tune on live, not backtest" (MEMORY: scalper-tuning). Not in this delivery.

The macro-record arity fan-out (each `[P]` adds a `Macro` field) means **batch the field additions or
accept N successive arity bumps** to `MarketOiClient`'s `new Macro(...)` + all `Macro` test builders.
Recommend adding all four macro fields (`vixRising` already exists; add `dowRising`, `fiiBiasSign`,
`constituentBiasSign`) in the FIRST strategy-signal PR to pay the fan-out once. See Open Point §8.4.

---

## 7. Effort + PR breakdown

Overall effort: **M** (mostly wiring existing feeds; the one genuinely new analytic is the FII L/U/B/C
classifier). Suggested PRs (short-lived `feat/`|`feat(strategy-signal)`/`feat(market-data)` branches,
Conventional Commits, squash-merge, build with `-pl <svc> -am verify`):

- **PR-1 `feat(strategy-signal): directional-vix-gate (tag-gated, default-off)`** — **M.**
  Thread `vixEnabled` through `context()`/`macro()`, `vixDirection()` reader, `requireVixGate` flag +
  `vix-gate` tag, early-return gate, the seam triple + the producer null-tripwire, load-test OFF
  assertion. (The macro-field fan-out batch — adding `dowRising/fiiBiasSign/constituentBiasSign`
  placeholders — can land here to pay arity once; OR keep PR-1 minimal and fan out per-PR. §8.4.)
- **PR-2 `feat(market-data): participant FII/DII bias + global-cues + index-contribution endpoints`**
  — **M.** `ParticipantBiasService` + `/fii-dii/bias`, `/global-cues` (Dow), confirm/add
  `/equity/index-contribution`; unit + slice + IT tests; contract re-capture. (`[S]` — read-only.)
- **PR-3 `feat(strategy-signal): fii-dii / constituent / global-cues macro gates (tag-gated)`** —
  **M.** Consume PR-2's endpoints: `Macro.fiiBiasSign/constituentBiasSign/dowRising` reads,
  `ScalperGates.fii/constituent/dow`, three `requireXxx` flags + tags, three early-returns, the three
  seam triples, three load-test OFF assertions.
- **PR-4 `feat(strategy-signal): volume-pump attribution gate (tag-gated)`** — **S.** Self-contained:
  `ScalperGates.volumePump`, `requireVolumePump` + `volume-pump` tag, early-return reading
  `future.candle(index).open()`, the seam triple, load-test OFF assertion.
- **PR-X (DEFERRED, owner-driven) `feat(strategy-signal): arm <tag> on <variant>`** — forward-paper
  A/B (e.g. `vix-gate` + `fii-dii-gate` on the connect-the-dots family). Flip the matching load-test
  OFF assertion to a per-id expectation; re-run goldens (still green). Add the YAML-header
  derived-history note for `constituent-gate`/`fii-dii-gate`. Not part of this stream's delivery.

PR-1 and PR-4 are independent of PR-2/PR-3 (PR-4 needs no feed). PR-3 hard-depends on PR-2.

---

## 8. Open Points

1. **Dollar Index / Asian / European / Crude / Bond / USD-INR feeds (global-cues-feed L25).** No live
   source on the platform today (only Dow via `GlobalQuoteSource`). **Options:** (a) leave as the
   shipped `global_cues_ok` MANUAL check (disposition = KEEP_MANUAL_NEW) — **recommended default**;
   (b) onboard `DXY`/`CRUDEOIL`/etc. as new `GlobalQuoteSource` keys (OpenAlgo/Upstox global indices)
   then add dots behind the same `global-cues-gate` tag — a separate feed-onboarding effort.
   Recommend (a); revisit if the owner funds the feeds.

2. **VIX directional rule: live `/vix` change-sign vs intra-session candle slope.** §3.1 uses the
   `/vix` endpoint's `change` (ltp − prevClose) for direction = "vs prev-day close". The deck also
   mentions intra-session rising/falling (the candle slope `ConnectingDotsService.vixByBucket` uses).
   **Options:** (a) use the prev-close sign only (simplest, one read) — **recommended default**;
   (b) also read the last two 1m INDIA VIX candles for an intra-session slope and require both to
   agree. Recommend (a) for v1; the "erratic intraday VIX → ignore" nuance stays manual (`vix_normal`).

3. **FII bias validity window (fii-dii-bias gap 4 — "next-morning-only, voided by strong global
   moves").** §3.3 v1 keeps the gate active all session, fail-open on null. **Options:** (a) all-session
   (simplest) — **recommended default**; (b) restrict the FII gate to before 10:00 IST (next-morning
   only) and suppress it when the Dow gate strongly opposes (the "voided by global moves" leg).
   Recommend (a) for v1, (b) as a forward-paper refinement once armed.

4. **Where to pay the `Macro`-record arity fan-out.** Adding `dowRising`/`fiiBiasSign`/
   `constituentBiasSign` to the record forces updating every `new Macro(...)` + test builder.
   **Options:** (a) add all three placeholders in PR-1 (pay the fan-out once; PR-3 then only wires the
   reads) — **recommended default**; (b) add each field in its own PR (N smaller arity bumps, more
   churn). Recommend (a).

5. **`fii-dii-gate` / `constituent-gate` ARE usable on backtest (unlike the OI gates).** They read
   real NSE EOD participant/bhavcopy data, not OI-derived history, so they carry signal on a past
   `eodDate`. **Decision needed:** should the arming YAML (PR-X) mark them backtest-usable (a
   discriminator the OI gates can't be) or still forward-paper-only for consistency? Recommend:
   **backtest-usable** — they are a genuine historical filter; document the distinction in the YAML
   header. (Open for owner sign-off.)

6. **Positive deterministic (golden) coverage of the new gates.** Not built (would require driving the
   LIVE-only seam through `TickwiseGoldenRunner`; FU2 §8.8 deferred this). **Options:** (a) rely on the
   seam unit triples (pass/block/unaffected) — **recommended default**; (b) invest in a scalper golden
   harness (large, separate). Recommend (a).

7. **`volume-pump` open-price source.** §3.5 reads `future.candle(index).open()` rather than extending
   `Chart` with an `open` field (avoids a scorer arity fan-out). **Options:** (a) read from `future`
   in the seam (no `Chart`/scorer change) — **recommended default**; (b) add `Chart.open()` and a
   scorer `volume_pump` dot (a `[P]` denominator change — heavier, needs the FU2 carve-out). Recommend
   (a) — keeps the change a pure hard gate with the scorer untouched.

8. **Single `macro-confluence` mega-tag vs five granular tags.** This stream introduces 5 tags. **Options:**
   (a) five granular tags (`vix-gate`, `global-cues-gate`, `fii-dii-gate`, `constituent-gate`,
   `volume-pump`) — **recommended default**, lets the owner A/B each macro confirm independently
   (the scalper-tuning lesson: isolate each gate for the A/B); (b) one `macro-confluence` tag arming
   all five (fewer config tokens, no per-gate A/B). Recommend (a).

9. **Does the existing `/vix` response shape drift the springdoc contract?** §3.1 reads existing
   `/vix` fields (`ltp`, `change`); no new path, so likely no drift — but `/global-cues` and
   `/fii-dii/bias` ARE new paths and DO drift. **Action:** run `ContractCaptureTest` after PR-2 and
   re-capture; confirm `/vix` itself is unchanged. (Not a decision — a verification reminder.)
