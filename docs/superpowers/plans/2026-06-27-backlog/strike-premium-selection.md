# Strike/premium selection — backtest premium band, dynamic recenter, premium skew

Status: PLAN (implementation-ready). Owner: single-owner. Target services:
`services/backtest-service` (premium-replay strike selector — the bulk), `services/strategy-signal-service`
(scalper OI-window recenter + a per-side-premium-skew confluence dot), and a thin read-only field in
`services/market-data-service` (per-side ATM premium, for the skew dot). Date: 2026-06-27.

> Read order for the executor: this plan is self-contained. The **load-bearing precedents** are
> (a) the backtest premium-replay path `OptionsPremiumReplay` + `OptionContractSelector` +
> `JdbcExpiredContractCatalog` (the strike is chosen ATM-nearest-to-spot, the premium band is
> **ignored** — that is the whole `strike-premium-band-backtest` package); (b) the live
> `StrikePicker` (which already honours the band — the backtest must match it); (c) the
> `min_premium_inr` backtest-only knob already on `OptionsPremiumReplay` (the exact precedent for a
> new band knob); (d) the CLAUDE.md "parity-safe-additive" convention + FU2 plan
> (`docs/superpowers/plans/2026-06-27-followup2-soft-dots-to-hard-gates.md`) for the default-OFF
> tag-gating + new-golden-variant pattern that the two **[P]** changes copy exactly.
>
> **The headline asymmetry of this stream:** the 11-gap `strike-premium-band-backtest` package is
> **[S] safe** — it changes only the BACKTEST premium-replay path (its own dedicated golden,
> `OptionsPremiumGoldenTest`), never an engine signal-emission golden. The two single-gap packages
> (`dynamic-strike-recenter`, `per-side-premium-skew`) are **[P] parity-sensitive** — they touch the
> live scalper confluence seam / scorer and so ride the FU2 default-OFF tag + new-golden discipline.

---

## 1. Goal & the packages/gaps this stream closes

This stream makes the **strike a scalper actually trades** faithful to the Siva premium rules in three
places: the backtest premium-replay selector (which today ignores the premium band entirely and picks
the nearest strike to spot), the live OI-analysis strike window (fixed ATM±3, no re-centre on a >1%
move), and a missing "don't chase the higher-premium side without cues" warning on the directional path.

| Package | # gaps | Effort | Doc-§ (audit row refs) | P/S |
|---|---:|:--:|---|:--:|
| **`strike-premium-band-backtest`** | 11 | M | §3.1#6/§4.9 · §3.2/§6.2 · §3.7 S21/setup-7/Bearish-7 · §3.11/6.11 · §1.2 Premium · §5.2/§7 O=H bands | **[S]** backtest-only selector (its own golden) |
| **`dynamic-strike-recenter`** | 1 | S–M | §3.5 S21(e) / §6.5 | **[P]** live OI-window re-anchor (tag-gated) |
| **`per-side-premium-skew`** | 1 | M | §3.12 Risk / §6.12 | **[P]** new live confluence dot (tag-gated) |

**Total: 13 gaps.** Exact audit rows (each cited file:line in its disposition table):

`strike-premium-band-backtest` (11) — every AUTOMATE_PKG row tagged `strike-premium-band-backtest`:
- `disposition/two-candle.md:23` (§3.1#6 / §4.9) — "Premium 100–250 Nifty / 250–400 BankNifty band
  (backtest ignores band)" → make the backtest selector honour the premium band (live StrikePicker does).
- `disposition/open-high-low.md:14` (§3.2 / §6.2 setup[3]) — "Restrict to ATM/ITM strikes, ATM ±3;
  avoid OTM / deep ITM" → exclude OTM legs from selection (window is symmetric ATM±3 today).
- `disposition/open-high-low.md:21` (§3.2 / §6.2 entry[4]) — "Premium bands (S22-operative): Nifty
  150–350 avoid <130/>380; BN 250–550" → the S22 bands are not used (NIFTY hardcoded 100–250) and the
  backtest selector ignores the band entirely.
- `disposition/hero-zero.md:18` (§3.7 S21(b) / §6.7 exit.time_exit) — "Decide the SIDE after 1:30–2 PM
  by premium / where shorts build" → needs the per-option-chain premium series (partially automatable
  once a per-strike premium series exists; the side itself stays the VWAP+extreme proxy).
- `disposition/hero-zero.md:22` (§3.7 setup-7 / §6.7 setup_preconditions[7]) — "Strong-move confirmation
  runs on index spurt … NOT the specific entry strike (S24(d) per-strike thresholds)" → the per-strike
  >50% OI+price (CE ≥50% up + big OI jump; PE ~70–78% fall + ~85% OI jump) needs per-strike premium
  %-change (the per-strike-price residual FU2's OI-leg does not cover).
- `disposition/hero-zero.md:32` (§3.7 Bearish-7 / §6.7 entry.bearish[7]) — "CAUTION: do NOT take a PE
  trade when calls trade at a discount" → a CE-vs-fair-premium discount read off the per-strike series.
- `disposition/intro-terminology.md:17` (§1.2 Premium & Strike) — "Premium range (buying) — Nifty
  ~100–250 / BN ~250–400" → live StrikePicker enforces it; the backtest premium-replay selector
  intentionally bypasses it (make it honour the band).
- `disposition/completeness-sweep.md:15` (§5.2 / §7) — "S22-resolved Open=High premium bands (BN
  250–550 / N 150–350 operative) — superseded set encoded" → swap the `PREMIUM` map from the superseded
  N 100–250 to the S22-resolved N 150–350 / BN 250–550.
- `disposition/session-additions-and-manual-coverage.md:39` (§4.15.4 / §3.2 / §7) — "Open=High premium
  bands (Nifty 150–350 operative; BN 250–550) — superseded set encoded" → swap to the S22-resolved
  operative band (constant change; same source family as completeness-sweep:15).
- `disposition/straddle.md:24` (§3.11 / §6.11) — "Long SL = BELOW the (combined) VWAP; Short SL = ABOVE"
  → a real combined-premium-VWAP SL (engine uses a 50%-premium proxy); both leg premiums known at entry,
  computable. (Short-side SL groups with `short-premium-span`; the LONG combined-premium SL is in scope.)
- `disposition/straddle.md:34` (§3.11 / §6.11) — "5-minute straddle-chart timeframe (engine runs 3m on
  the index-future chart, not the combined-premium chart)" → resample to 5m + build the combined-premium
  series the gate reads. Pairs with the entry/SL seam.

`dynamic-strike-recenter` (1):
- `disposition/trending-oi.md:36` (§3.5 S21(e) / §6.5) — "Strike housekeeping: keep strikes if move
  <1%; reset to ATM ±7 if >1%" → re-anchor the OI window on a >1% price-move threshold (fixed ATM±3
  today, no re-centring, width 3 not ±7). Audit evidence: `trending-oi.md:40` — strikes fixed at
  `atm_window width: 3` (yaml:20); no <1%/>1% reset-to-ATM±7 logic.

`per-side-premium-skew` (1):
- `disposition/trend-change.md:37` (§3.12 Risk / §6.12) — "Don't chase a side when premiums are higher
  on that side with no positive cues" → per-side premium/IV-skew warning. Audit evidence:
  `trend-change.md:43` — "no per-side-premium-skew warning in the trend-change path (IV-pair dot L97 is
  a different signal)". Audit marks it Automatable: yes.

> **Scope notes (what is NOT in this stream).** The `straddle.md:21` / `session-additions:32`
> combined-premium-VWAP **entry trigger** rows are dispositioned **COVERED_FU1** (the
> `straddle_vwap_entry` manual check) — their *automation* home is `strike-premium-band-backtest` but
> they are already manual-covered, so they are not counted in the 11. The **short** straddle/STBT SL
> legs (`straddle.md:24` short half) ride `short-premium-span` (#47 SPAN-gated) → recorded in §8 Open
> Points, not built here. The Hero-Zero per-strike OI **>50% leg** is FU2's `≥50% ΔOI imbalance` gate
> (`hero-zero.md:21`, COVERED_FU2) — only the per-strike **premium** residual (`hero-zero.md:22`) is here.

---

## 2. Current state — relevant code (verified file:line)

All line numbers below were opened and confirmed against the working tree on 2026-06-27.

### 2.1 The backtest premium-replay strike selection (the `strike-premium-band-backtest` target)
- **`OptionsPremiumReplay.priceLeg(...)`**
  (`services/backtest-service/.../replay/options/OptionsPremiumReplay.java:486-510`) anchors the ATM
  strike on the strike-reference spot at entry (`strikeSpot`, L500) and calls
  `selector.select(underlyingSymbol, strikeSpot, …)` (L502-510). The **band is never consulted** —
  the only premium guard is the `min_premium_inr` floor (L546-548) and the `max_lots` cap (L558-560).
- **`OptionContractSelector.select(...)`** (`.../options/OptionContractSelector.java:73-90`) resolves
  the expiry then returns `catalog.nearestStrike(underlying, expiry, optionType, spot)` (L89) — the
  class javadoc itself states "the underlying spot picks the **nearest-listed ATM strike**" (L11-13),
  with no premium awareness.
- **`JdbcExpiredContractCatalog.nearestStrike(...)`**
  (`.../options/JdbcExpiredContractCatalog.java:40-60`) is literally
  `… ORDER BY abs(strike - ?) LIMIT 1` (L46) over `marketdata.expired_contracts`. It returns ONE row;
  it has no view of any strike's premium.
- **`CandlePremiumReader.premiumSeries(contract, from, to)`**
  (`.../options/CandlePremiumReader.java:38-47`) already reads a contract's 1m premium series via
  `candleReader.read(exchange, tradingsymbol, "1m", from, to)`, and `premiumAt(series, ts)` (L54-58)
  floor-looks-up the premium at an instant. **This is the existing primitive a band-aware selector
  reuses to learn each candidate strike's entry premium** — no new reader is required.
- **`OptionsPremiumReplay.minPremiumInr(config)`** (L309-312) is the exact precedent for a new
  band knob: it reads `risk.position_sizing.params.min_premium_inr` (default ₹1) from the config — a
  **backtest-only** field that does NOT exist in `strategy-schema/v1` and is read leniently. The new
  premium-band knobs follow this shape.
- The dedicated parity golden is **`OptionsPremiumGoldenTest`**
  (`.../options/OptionsPremiumGoldenTest.java`) — a 3-leg fixture pinning exact trades + the per-bar MTM
  curve (TP +3767.76 / SL −2987.51 / signal-exit +401.33, equity 200000→201181.58). Its fake `CATALOG`
  rounds spot to the nearest 500 (L60-66). `OptionsPremiumReplayTest` + `OptionContractSelectorTest`
  are the unit fixtures.
- **The live counterpart already honours the band.** `StrikePicker.pick(...)`
  (`services/strategy-signal-service/.../scalper/StrikePicker.java:74-109`) rejects any candidate whose
  `ltp` is `< premiumLo` or `> premiumHi` (L93-95) and selects the in-band strike nearest the delta-band
  midpoint. The band constants are `ScalperConfig.PREMIUM` (`ScalperConfig.java:93-98`): NIFTY 100–250,
  NIFTY BANK 250–400, SENSEX 300–800 — the comment at L89-92 explicitly says **"live StrikePicker only,
  the backtest selector ignores the band and picks nearest-strike-to-spot"**. (Note: these are the
  *superseded* bands per the audit; the S22-operative N 150–350 / BN 250–550 swap is part of this work —
  see §3.1 step 4.)

### 2.2 The live OI-analysis strike window (the `dynamic-strike-recenter` target)
- The YAML declares `universe.options.strikes: { selector: atm_window, width: 3 }`
  (`scalp-trending-oi-nifty.yaml` ~L20; `scalp-two-candle-nifty.yaml:21`). This is a **fixed** ATM±3.
- The live OI reads in `MarketOiClient.oi(...)`
  (`services/strategy-signal-service/.../scalper/MarketOiClient.java:267-336`) fan out to
  `/options/spurt`, `/options/active-strikes` (`buckets=SERIES_WINDOW=20`, L49), `/options/trending`,
  and `/futures/*`. **None pass a strike window** — the window is implicit in the market-data analytics
  service (the trending/spurt/sentiment endpoints choose their own strike set). `MarketOiClient` has no
  notion of "re-centre the strikes after a >1% move"; the OI dots in `ConnectTheDotsScorer`
  (`trending_cross` L83, `sentiment` L84/L88, `drastic_oi` L86, `oi_spurt` L90) consume whatever the
  endpoints returned.
- `ScalperOiProps` (`.../scalper/ScalperOiProps.java`) is the DB-/YAML-tunable threshold record the
  scorer and the #5 pre-gate read; it holds `openHighWindow` (default 3, L52) — the ATM±window for the
  #2 per-strike footprint — proving the "window as a tunable prop" pattern. There is **no** recenter
  threshold or recentered-width prop today.
- `ScalperConfig.from(JsonNode config, List<String> tags)` (`.../scalper/ScalperConfig.java:101-157`)
  is where every per-strategy `requireXxx` boolean tag is parsed (the #5 `oi-cross-filter` at L153 is
  the canonical shape). `ScalperGateContext` carries the per-bar OI snapshot the gate consults.

### 2.3 The directional confluence path (the `per-side-premium-skew` target)
- `ConnectTheDotsScorer.score(...)` (`.../scalper/ConnectTheDotsScorer.java:63-118`) adds 18 soft dots
  (L74-98) and computes a weighted aggregate (L100-109); validity at L114-115. There is an `iv_pair`
  dot (L97, the CE-vs-PE 6-strike IV gap) and an `iv_rank` dot (L94) — but **no per-side *premium* skew
  dot**. The audit explicitly notes the IV-pair dot "is a different signal" (`trend-change.md:43`).
- `ScalperGateContext.Macro` (built by `MarketOiClient.macro(...)`, `MarketOiClient.java:351-398`)
  already carries `ceIvAvg6`/`peIvAvg6` (the 6-strike IV averages, L387-397) but **no per-side ATM
  premium** — the operand a premium-skew dot needs is not yet read.
- `TrendChangeGate` (`.../scalper/TrendChangeGate.java`) is the #12 hard reversal pre-gate; the
  premium-skew row is §3.12-sourced but is a **scorer dot** concern (a soft "warning" that lowers the
  aggregate), not a `TrendChangeGate` hard leg — the audit calls for a per-side premium/IV-skew warning,
  best modelled as a default-OFF soft dot so it can apply to any directional scalper, not just #12.
- Per-side ATM premium analytics already exist server-side: `OiPremiumService`
  (`services/market-data-service/.../options/analytics/OiPremiumService.java`) and the straddle/premium
  chain services compute per-strike CE/PE LTPs — so the skew operand is a thin new analytics field, not
  a new capture.

### 2.4 Parity firewall (why the split P/S classification holds)
- `OptionsPremiumReplay` is the **backtest** path. Its only golden is `OptionsPremiumGoldenTest`
  (dedicated, regen-able). The engine signal-emission goldens (`GoldenDeterminismTest.FEATURES`,
  `BacktestParityTest.FEATURES`) carry **no scalper / no options_of_underlying strategy** — they drive
  pure-engine YAMLs through `TickwiseGoldenRunner`/`ReplayEngine`. So a change to the premium-replay
  **strike selection** cannot perturb those engine goldens; it only perturbs `OptionsPremiumGoldenTest`,
  which is this package's own fixture (updated deliberately, not "frozen"). → `strike-premium-band-backtest`
  is **[S]**.
- `MarketOiClient`/`ScalperConfluenceGate`/`ConnectTheDotsScorer` are **LIVE-only** (the confluence is
  persisted at entry via the V009 side-channel and replayed, never re-called) — so they too are
  invisible to the engine goldens. But `dynamic-strike-recenter` and `per-side-premium-skew` change
  *which live signals fire / the live aggregate*, which is exactly the thing the FU2 default-OFF tag +
  new-golden-variant discipline protects. → both are **[P]**, tag-gated.

---

## 3. Design — per package

### 3.1 `strike-premium-band-backtest` [S] — band-aware backtest strike selection

**Goal.** Make the premium-replay selector choose the strike whose **entry premium** lands in the index
premium band (nearest the band midpoint, ATM/ITM-only), matching the live `StrikePicker`, instead of the
single nearest-strike-to-spot. Backtest-only; its own golden is updated, no engine golden is touched.

**Data flow.** `priceLeg` already knows the entry instant + the strike-reference spot. The band-aware
selection: (1) ask the catalog for the **candidate strikes** in the ATM±window (not one row); (2) for
each candidate of the bias side, read its premium AT the entry instant via the existing
`CandlePremiumReader`; (3) keep candidates whose entry premium ∈ `[premiumLo, premiumHi]` and whose
strike is ATM/ITM (CE: strike ≤ spot+step ; PE: strike ≥ spot−step — i.e. not deep-OTM); (4) pick the
one nearest the band midpoint; (5) if none qualify, fall back to today's nearest-strike behaviour (a
config flag controls whether that fallback trades or skips — see Open Points #1).

**File 1 — `OptionContractSelector.Catalog` (new method) + `JdbcExpiredContractCatalog`.**
Add a window-returning catalog method so the band filter runs client-side over the listed strikes:

```java
// OptionContractSelector.Catalog
/** The listed strikes for (underlying, expiry, optionType) within ±window steps of the ATM,
 *  ascending by |strike − spot|. window<=0 ⇒ just the single nearest (back-compat). */
List<OptionContract> nearestStrikes(
    String underlying, LocalDate expiry, String optionType, BigDecimal spot, int window);
```

```java
// JdbcExpiredContractCatalog
@Override
public List<OptionContract> nearestStrikes(
    String underlying, LocalDate expiry, String optionType, BigDecimal spot, int window) {
  int limit = Math.max(1, 2 * window + 1);              // ATM ± window listed strikes
  return jdbc.query(
      "SELECT exchange, tradingsymbol, strike, lot_size FROM marketdata.expired_contracts "
          + "WHERE underlying_symbol = ? AND expiry = ? AND instrument_type = ? "
          + "AND strike IS NOT NULL ORDER BY abs(strike - ?) LIMIT ?",
      (rs, n) -> new OptionContract(rs.getString("exchange"), rs.getString("tradingsymbol"),
          rs.getBigDecimal("strike"), expiry, optionType, rs.getInt("lot_size")),
      underlying, java.sql.Date.valueOf(expiry), optionType, spot, limit);
}
```

The existing `nearestStrike(...)` (singular) stays for the fallback / non-band path.

**File 2 — `OptionContractSelector` (a new band-aware select overload).**
Keep `select(...)` byte-identical (back-compat); add a band-aware sibling that takes the band + window +
a strike→premium probe (a `Function<OptionContract, BigDecimal>` so the selector stays DB-free and
unit-testable — the replay supplies the `CandlePremiumReader`-backed probe):

```java
/** Band-aware ATM/ITM selection (mirrors live StrikePicker): among ±window listed strikes on the
 *  bias side, keep ATM/ITM strikes whose entry premium ∈ [lo,hi], pick the one nearest the band
 *  midpoint. Empty ⇒ caller decides fallback (nearest-strike) or skip. premiumProbe returns the
 *  candidate's entry premium (null ⇒ untradeable, excluded). */
public Optional<OptionContract> selectInBand(
    String underlying, BigDecimal spot, LocalDate onOrAfter, ExpiryMode mode, int expiryOffset,
    boolean longBias, Set<String> optionTypes, int window,
    BigDecimal premiumLo, BigDecimal premiumHi, BigDecimal strikeStep,
    java.util.function.Function<OptionContract, BigDecimal> premiumProbe) {
  String type = longBias ? "CE" : "PE";
  if (!optionTypes.isEmpty() && !optionTypes.contains(type)) return Optional.empty();
  LocalDate expiry = pickExpiry(catalog.expiriesOnOrAfter(underlying, onOrAfter), mode, expiryOffset);
  if (expiry == null) return Optional.empty();
  BigDecimal mid = premiumLo.add(premiumHi).divide(BigDecimal.valueOf(2));
  OptionContract best = null; BigDecimal bestErr = null;
  for (OptionContract c : catalog.nearestStrikes(underlying, expiry, type, spot, window)) {
    if (!atmOrItm(longBias, c.strike(), spot, strikeStep)) continue;     // exclude OTM (open-high-low:14)
    BigDecimal prem = premiumProbe.apply(c);
    if (prem == null || prem.compareTo(premiumLo) < 0 || prem.compareTo(premiumHi) > 0) continue;
    BigDecimal err = prem.subtract(mid).abs();
    if (bestErr == null || err.compareTo(bestErr) < 0) { bestErr = err; best = c; }
  }
  return Optional.ofNullable(best);
}
// CE ATM/ITM: strike <= spot + step ; PE ATM/ITM: strike >= spot - step  (one-step OTM tolerated).
```

**File 3 — `OptionsPremiumReplay`.**
- Read the band knobs from config (the `min_premium_inr` precedent), **backtest-only**, default = the
  live `ScalperConfig.PREMIUM` band per underlying so a backtest matches live by default:

```java
/** Backtest premium-band selection knobs (backtest-only; not in strategy-schema/v1). Mirror the live
 *  StrikePicker band. When ABSENT the legacy nearest-strike-to-spot path runs (existing golden holds). */
static PremiumBand premiumBand(JsonNode config) {
  JsonNode b = config.path("backtest").path("strike_premium_band");
  if (b.isMissingNode() || !b.path("enabled").asBoolean(false)) return PremiumBand.DISABLED;
  return new PremiumBand(true, dec(b, "lo"), dec(b, "hi"),
      b.path("window").asInt(strikeWindow(config)), dec(b, "strike_step"),
      b.path("fallback_nearest").asBoolean(true));  // see Open Point #1
}
/** ATM±window from universe.options.strikes.width (default 3) — the same width the YAML already declares. */
static int strikeWindow(JsonNode config) {
  return config.path("universe").path("options").path("strikes").path("width").asInt(3);
}
```

- In `priceLeg(...)`, when the band is enabled, route through `selectInBand(...)` with a probe that
  reads the candidate's entry premium via `CandlePremiumReader.premiumAt(premiumSeries(c, …), entryTs)`:

```java
Optional<OptionContract> resolved =
    band.enabled()
        ? selector.selectInBand(
            underlyingSymbol, strikeSpot, entryBar.bucketStart().toLocalDate(),
            spec.expiryMode(), spec.expiryOffset(), longBias, spec.optionTypes(),
            band.window(), band.lo(), band.hi(), band.strikeStep(),
            c -> premiumAtEntry(c, entryBar))
            .or(() -> band.fallbackNearest()
                ? selector.select(underlyingSymbol, strikeSpot, …)   // legacy nearest-strike
                : Optional.empty())                                  // skip when no in-band strike
        : selector.select(underlyingSymbol, strikeSpot, …);          // unchanged legacy path
```

where `premiumAtEntry(c, entryBar)` is a tiny helper that pulls the candidate's premium at the entry
instant (a one-bar read window `[entryBar, entryBar+1m)` is enough). **Crucially the legacy
`else`/no-band branch is byte-identical to today**, so the existing `OptionsPremiumGoldenTest` (which
runs no band) stays green unchanged.

**Step 4 — the S22-operative band swap (`completeness-sweep.md:15`, `session-additions:39`).**
Because the default band derives from `ScalperConfig.PREMIUM`, swapping that map ALSO corrects the live
StrikePicker — but that flips live behaviour, so do it as its own commit with the live golden discipline:
update `ScalperConfig.PREMIUM` to the S22-operative **NIFTY 150–350**, **NIFTY BANK 250–550** (SENSEX
300–800 unchanged unless the audit specifies otherwise). This is a **[P]** sub-change to the *live*
StrikePicker (it changes which live strike is picked) — but it is NOT golden-perturbing on the engine
goldens (StrikePicker is live-only) and is parity-irrelevant to the backtest replay (replay reads the
band from config, defaulting to the same map). Treat it as PR-3 (see §7) with a YAML note, so the
band-swap and the backtest-plumbing land separately and the swap is owner-reviewable.

> **Hero-Zero residual rows (`hero-zero.md:18,22,32`).** Once a per-strike premium series is selectable
> in the backtest, the Hero-Zero side-by-premium read (S21b), the per-strike >50% premium %-change
> confirmation (S24d), and the "PE-when-CE-at-discount" caution become computable as **backtest-only
> read-only diagnostics** on the chosen leg (e.g. surfaced on the trade's side-channel for value-verify),
> NOT as new entry gates — the live side decision stays the VWAP+extreme proxy (the disposition says
> "partially automatable"). Scope these as a follow-up read-only diagnostic within the same package; do
> NOT turn them into entry preconditions (that would be a live parity change with no data on history).

**Straddle rows (`straddle.md:24,34`).** The LONG combined-premium SL + the 5m combined-premium series
are an additive **new variant path** on the straddle replay (the straddle tag already routes a
direction-neutral 2-leg path; there is no existing straddle premium golden). Build the combined-premium
series (CE+PE entry premiums summed, resampled to 5m) and anchor the long SL below its VWAP; ship a
**new** `OptionsPremiumStraddleGoldenTest` fixture. **[S]** (brand-new path, no existing golden). The
short SL leg is `short-premium-span` (Open Points).

### 3.2 `dynamic-strike-recenter` [P] — re-anchor the OI window on a >1% move (tag-gated)

**Goal.** Encode §3.5 S21(e): keep the Trending-OI strike window if the underlying has moved <1% from
the session open; when it has moved ≥1%, re-centre the window to ATM±7 (wider) so the OI reads track the
new ATM. Today the window is a fixed ATM±3.

**Design (default-OFF tag, FU2 shape).** Because this changes the OI operand the live confluence reads
(→ different `trending_cross`/`sentiment`/`drastic_oi`/`oi_spurt` dot values → different live signals),
it is **[P]**. Gate it behind a new `oi-window-recenter` tag so every shipped config is byte-identical.

- **`ScalperOiProps`** — add two tunable props (the existing prop-default pattern):

```java
BigDecimal recenterMovePct,   // default 1.0 (% move from session open that triggers a re-centre)
BigDecimal recenterWidth,     // default 7   (the wider ATM±width after a >1% move; base width stays 3)
```

- **`ScalperConfig`** — add `boolean requireOiWindowRecenter` (record field + constructor) and parse
  `tags.contains("oi-window-recenter")` in `from(...)` (alongside L153 `oi-cross-filter`). Default OFF.
- **`MarketOiClient.oi(...)`** — thread an effective window: compute `movedPct = |spot − sessionOpen| /
  sessionOpen × 100`; if recenter is armed AND `movedPct ≥ recenterMovePct`, request the OI analytics
  with `width = recenterWidth` (ATM±7) instead of the base width. The trending/spurt/active-strikes
  endpoints must accept a `window`/`strikes` param for this to bite — **see the dependency in §6**: if
  the market-data analytics endpoints do not yet take a strike-window param, that param must be wired
  first (read-only [S] addition there), then `MarketOiClient` passes it. The session-open spot is
  available from the chart/engine context (the day's first bar) or a `/quote` read.
- The recenter decision must be **deterministic on the live bar** (no wall-clock beyond the bar instant)
  so the persisted confluence replays consistently; the session-open anchor is the first bar of the IST
  session, already known to the seam.

**PARITY plan.** New tag `oi-window-recenter`, default-OFF (no shipped YAML carries it). A **new golden
variant** is required only if/when the tag is armed on a real strategy AND a scalper golden harness
exists — today no scalper golden exists (FU2 §5.6), so the proof is the seam unit tests + the
`ScalperStrategyLoadTest` "OFF for every seeded strategy" tripwire. When PR-3 arms it on a forward-paper
trending-oi variant, add the YAML note "re-centres ATM±7 on a >1% move; forward-paper only" — on derived
history the OI is muted, so the recenter is a forward discriminator (identical on backtests).

### 3.3 `per-side-premium-skew` [P] — "don't chase the higher-premium side" dot (tag-gated)

**Goal.** Encode §3.12 Risk: a soft warning that *lowers* the confluence aggregate when the side being
traded is the **higher-premium** side with no corroborating positive cues — discouraging chasing into an
expensive side. Distinct from the existing `iv_pair` IV-gap dot (the audit explicitly flags it as "a
different signal").

**Design.** Add per-side ATM premium to the macro context, then a new default-OFF scorer dot.

- **Market-data field (`[S]` read-only).** Add `ceAtmLtp`/`peAtmLtp` (or a single `premiumSkewPct =
  (sideLtp − otherLtp)/otherLtp × 100`) to the chain/premium analytics already computed by
  `OiPremiumService` / the chain endpoint — a thin read-only field, no new capture. `MarketOiClient.macro`
  maps it into `ScalperGateContext.Macro` (new nullable fields, conservative null on absence).
- **`ConnectTheDotsScorer`** — add a default-OFF dot. Because adding a scored dot to the *default* path
  changes the aggregate denominator for every bar (the exact failure FU2 §8.2 calls out for a Dow dot),
  the skew dot must be **conditional**: only added to the `dots` list when the strategy arms it. The
  clean way that keeps `score(...)`'s positional signature stable is to thread a small flags object (or
  reuse the existing `ScalperOiProps`/a new boolean param) so an UNARMED strategy's dot list + aggregate
  are byte-identical to today. Recommended: add `boolean premiumSkewDot` to the `score(...)` call via a
  new overload (the base overload delegates with `false`), and inside:

```java
if (premiumSkewDot) {
  // A WARNING dot: supports (good) when the traded side is NOT the richer side, OR it is richer but a
  // positive cue (e.g. trending_cross / oi_spurt for the side) corroborates. "Higher-premium side with
  // no cues" → does NOT support → lowers the aggregate (discourages the chase). Null skew → neutral
  // (supports, so a missing feed never blocks).
  boolean richerSide = m.premiumSkewPct() != null && (ce ? m.premiumSkewPct().signum() > 0
                                                         : m.premiumSkewPct().signum() < 0);
  boolean cued = corroboratingCue(dots, ce);   // reuse already-computed trending_cross / oi_spurt dots
  add(dots, "premium_skew", W, m.premiumSkewPct() == null || !richerSide || cued,
      "not chasing the richer side without cues");
}
```

- **`ScalperConfig`** — `boolean requirePremiumSkewDot` field + `tags.contains("premium-skew")` parse;
  `ScalperConfluenceGate` passes it to the `score(...)` overload.

**PARITY plan.** New tag `premium-skew`, default-OFF. Because the dot is added to the list ONLY when
armed, the unarmed aggregate is bit-identical (no denominator change) — this is the load-bearing parity
property (FU2 §8.2's warning is satisfied by conditional-add, not unconditional-add). A new golden
variant is needed only when armed AND a scalper golden harness exists (none today) — the proof is the
scorer unit test (armed vs unarmed aggregate) + the `ScalperStrategyLoadTest` OFF tripwire. The dot
also rides the `dots[]` side-channel → `scalper_detail` → FE chip row for free (same as every FU2 dot).

---

## 4. PARITY classification (per change)

| Change | P/S | Why | Tag (new) | Golden plan |
|---|:--:|---|---|---|
| Band-aware backtest selector (`selectInBand`, `nearestStrikes`, `OptionsPremiumReplay` band knobs) | **[S]** | Backtest-only path; its own dedicated golden (`OptionsPremiumGoldenTest`). Legacy no-band branch byte-identical → existing golden untouched. | `backtest.strike_premium_band.enabled` (a backtest config flag, NOT a parity tag) | **No engine golden touched.** Add a NEW band-enabled fixture to `OptionsPremiumGoldenTest` (or a sibling `…BandGoldenTest`); the existing no-band fixture stays byte-identical. |
| Straddle long combined-premium SL + 5m series | **[S]** | Brand-new variant path; no existing straddle premium golden. | none (rides existing `straddle` tag) | **New** `OptionsPremiumStraddleGoldenTest` fixture (generate-once). |
| S22-operative band swap in `ScalperConfig.PREMIUM` | **[P]** (live StrikePicker) | Changes which strike the LIVE StrikePicker picks. NOT engine-golden-perturbing (StrikePicker is live-only) but it is a live behaviour change → owner-reviewed, separate commit. | none (constant change) | No engine golden touched; update any `StrikePickerTest` band fixtures + add a band-edge assertion. Ship as its own PR with a YAML/changelog note. |
| `dynamic-strike-recenter` OI-window re-anchor | **[P]** | Alters the live OI operand → different live confluence/signals. | `oi-window-recenter` (default-OFF) | No engine golden today (scalper goldens don't exist). Proof = seam unit tests + `ScalperStrategyLoadTest` OFF tripwire. New golden variant only if a scalper golden harness is built AND the tag is armed (PR-3). |
| `per-side-premium-skew` scorer dot | **[P]** | Adds a scored dot → would change the aggregate IF added unconditionally. | `premium-skew` (default-OFF; **conditional-add** keeps the unarmed aggregate bit-identical) | Same as above — proof = scorer unit test (armed vs unarmed aggregate identical when unarmed) + OFF tripwire. |

**The parity-critical invariants the executor MUST hold:**
1. The backtest no-band branch in `priceLeg` is unchanged → `OptionsPremiumGoldenTest`'s existing
   fixture asserts byte-identical trades + equity (200000→201181.58). Re-run, do NOT regenerate.
2. The two **[P]** scalper changes are added to the dot list / OI fan-out **only when their tag is
   present**; an unarmed strategy's emitted signal, confluence aggregate, and `dots[]` payload are
   bit-for-bit identical to today.
3. No shipped YAML carries `oi-window-recenter` or `premium-skew` in this work (arming is PR-3,
   owner-driven, per the "tune on live, not backtest" principle).

---

## 5. Tests

### 5.1 Unit — backtest selector (`strike-premium-band-backtest`, [S])
- **`OptionContractSelectorTest`** — new `selectInBandPicksNearestMidpointInBandStrike` (a fake catalog
  returning a ±window strike ladder + a premium probe map; assert the in-band, ATM/ITM, nearest-midpoint
  strike is chosen, OTM/out-of-band strikes excluded); `selectInBandEmptyWhenNoInBandStrike` (probe all
  out of band → empty); `selectInBandExcludesDeepOtm`.
- **`OptionsPremiumReplayTest`** — `bandEnabledRoutesThroughSelectInBand` (a multi-strike catalog +
  premium reader; assert the traded `tradingsymbol` is the in-band strike, not the nearest-spot strike);
  `bandDisabledIsByteIdenticalToLegacy` (no band config → same strike as the legacy path);
  `bandNoMatchFallbackVsSkip` (cover both Open Point #1 modes).
- **`OptionsPremiumGoldenTest`** — keep the existing 3-leg no-band fixture byte-identical (regression);
  add `pinsTheBandSelectedTradesAndEquity` (a NEW fixture where the nearest-spot strike is OUT of band so
  the band path selects a different strike → a NEW pinned trade set + equity curve, generate-once) +
  `isDeterministicAcrossRuns` for it.
- **`JdbcExpiredContractCatalog`** — covered by the existing `SnapshotPremiumReaderIntegrationTest`-style
  IT harness; add an IT asserting `nearestStrikes(...,window)` returns `2*window+1` rows ordered by
  `abs(strike−spot)` (Testcontainers, real `expired_contracts` rows).
- **Straddle**: new `OptionsPremiumStraddleGoldenTest` — pins the combined-premium 5m series + the long
  SL anchor below the combined VWAP for a 2-leg ATM straddle (generate-once).

### 5.2 Unit — live scalper ([P], both tag-gated)
- **`ScalperOiPropsTest`** (if present, else add) — the two new recenter props default 1.0 / 7 and honour
  partial YAML override (the existing prop-default test shape).
- **`ScalperConfigTest` / `ScalperStrategyLoadTest`** — assert `requireOiWindowRecenter` and
  `requirePremiumSkewDot` are **OFF for every seeded strategy** (the FU2 §5.3 tripwire) — proves no YAML
  arms either tag.
- **`ConnectTheDotsScorerTest`** — `premiumSkewDotAbsentWhenUnarmedKeepsAggregateIdentical` (armed=false
  → dot list size + aggregate bit-identical to the pre-change baseline — the parity property);
  `premiumSkewDotLowersAggregateWhenChasingRicherSideNoCues`;
  `premiumSkewDotNeutralOnNullSkew` (missing feed → supports → no block).
- **`ScalperConfluenceGateTest`** — update the 8 `new ScalperConfig(...)` literals for the two new
  fields (append `false` — same arity fan-out FU2 documents); add a recenter-armed CFG asserting
  `MarketOiClient.oi(...)` is invoked with the wider window after a >1% move (verify via the mock); a
  premium-skew-armed CFG asserting the skew dot appears in the decision's `dots`.
- **`MarketOiClientTest`** — the recenter width threading (mock the REST `/options/*` and assert the
  `window`/`strikes` query param flips 3→7 past the threshold); the `premiumSkewPct` mapping into `Macro`.

### 5.3 Golden / parity tripwires (MUST stay byte-identical)
- **`GoldenDeterminismTest`** (`libs/strategy-engine`) — no scalper / no options strategy; **re-run,
  assert byte-identical, do NOT regenerate.** Both [P] changes are tag-gated default-OFF and live-only.
- **`BacktestParityTest`** (`services/backtest-service`) — its FEATURES carry no options_of_underlying
  strategy, so the band selector change is invisible to it; **re-run, assert the three byte-match
  asserts stay green.**
- **`OptionsPremiumGoldenTest`** — the no-band fixture is the dedicated tripwire for [S]: re-run, assert
  byte-identical; ONLY the new band fixture is generate-once.

### 5.4 e2e
- No new e2e behaviour: the [P] changes ship default-OFF (no signal fires/suppresses), and the [S]
  backtest band defaults to "match live" only when explicitly enabled in a backtest config. Re-run
  `e2e/tests/signals.spec.ts` + the backtest-run spec as regression. A value-verify backtest run with
  `backtest.strike_premium_band.enabled: true` on a NIFTY scalper is the **manual** acceptance check
  (assert the traded strike's entry premium sits in 100–250 / 150–350) — record it in
  `docs/manual-tests/`.

---

## 6. Dependencies & sequencing

1. **Market-data strike-window param (gates `dynamic-strike-recenter`).** The recenter only bites if the
   `/options/trending`, `/options/spurt`, `/options/active-strikes` endpoints accept a strike-window
   (`width`/`strikes`) param. **Verify first** whether they already do (the OI-page suite may already
   pass a window); if not, add the read-only param to those endpoints ([S], market-data) **before**
   wiring `MarketOiClient`. If the analytics window is server-fixed and not easily parameterised, this
   package degrades to "recenter the StrikePicker/footprint width only" — record as Open Point #4.
2. **Per-side ATM premium field (gates `per-side-premium-skew`).** The `premiumSkewPct` analytics field
   must exist before `MarketOiClient.macro` can map it and the scorer dot can read it. Thin read-only
   add to the existing premium/chain analytics ([S], market-data) → then the scorer dot ([P]).
3. **`strike-premium-band-backtest` is self-contained** — it needs only the existing
   `CandlePremiumReader` + `expired_contracts`; no upstream feed. It can land first and independently.
4. **Band-swap ordering.** The `ScalperConfig.PREMIUM` S22 swap is a live behaviour change; land the
   backtest plumbing (defaulting to the *current* map) first, then the swap as its own PR so the band
   correction is isolated and owner-reviewable.
5. **SPAN gates the short legs.** The short straddle/STBT combined-premium SL (`straddle.md:24` short
   half) is `short-premium-span`/#47-gated — not in this stream.
6. **No equity-universe dependency** — this stream is index-options only; none of these three packages
   touch the per-stock sub-epic.

---

## 7. Effort + suggested PR breakdown

Overall effort: **M** (the band-aware selector is the bulk; the two [P] singles are S–M each).

- **PR-1 `feat(backtest): band-aware premium-replay strike selection (backtest-only, default nearest)`**
  — Effort **M**. `Catalog.nearestStrikes` + `JdbcExpiredContractCatalog`; `OptionContractSelector.selectInBand`;
  `OptionsPremiumReplay` band knobs + `priceLeg` routing (legacy branch byte-identical); the §5.1 unit +
  the NEW `OptionsPremiumGoldenTest` band fixture. **No engine golden touched.** (Closes 7 of the 11:
  two-candle:23, open-high-low:14/21, intro-terminology:17, completeness-sweep:15/session-additions:39
  *plumbing* — the band-swap *values* land in PR-3.)
- **PR-2 `feat(strategy-signal): S22-operative premium band (live StrikePicker)`** — Effort **S**. Swap
  `ScalperConfig.PREMIUM` → N 150–350 / BN 250–550; update `StrikePickerTest`. Owner-reviewed live change
  with a changelog note. (Closes the `completeness-sweep:15` / `session-additions:39` *value* gap on the
  live side; the backtest already honours whatever map config supplies.)
- **PR-3 `feat(backtest): Hero-Zero per-strike premium diagnostics + straddle combined-premium SL`** —
  Effort **M**. The Hero-Zero read-only per-strike premium diagnostics (hero-zero:18/22/32) on the trade
  side-channel; the straddle long combined-premium 5m SL + new straddle golden. (Closes hero-zero:18/22/32
  + straddle:24/34 = the remaining 5 of 11; short SL deferred to #47.)
- **PR-4 `feat(strategy-signal): oi-window-recenter hard tag (default-off)`** — Effort **S–M**, blocked
  on dependency #1. `ScalperOiProps` props + `ScalperConfig` tag + `MarketOiClient` width threading;
  seam + scorer + load-test tripwire. **[P]**, no YAML armed.
- **PR-5 `feat: per-side premium-skew confluence dot (default-off)`** — Effort **M**, blocked on
  dependency #2. Market-data `premiumSkewPct` field + `Macro` mapping + conditional scorer dot + tag;
  scorer/seam/load tests. **[P]**, no YAML armed.

Each PR: short-lived `feat/` branch, Conventional Commit scoped to the service, squash-merge; build
services with the full reactor + `-am`. PR-1 is the highest-leverage (7 gaps) and fully independent —
do it first.

---

## 8. Open Points

1. **Backtest band: fallback-to-nearest vs skip when no in-band strike.** When NO ATM/ITM strike on the
   bias side has an in-band entry premium, should the backtest (a) fall back to today's nearest-strike
   (a trade happens, but at an off-band premium — diverges from live, which would simply not fire), or
   (b) skip the leg (matches live "no tradeable strike → no entry")? **Options:** (a) `fallback_nearest:
   true`, (b) `fallback_nearest: false`. **Recommended default: (b) skip** — it matches the live
   StrikePicker's "empty ⇒ no signal" and keeps the backtest honest; expose the flag so an owner can
   choose (a) for a fuller (if less faithful) trade set. Coded as `band.fallbackNearest()` either way.

2. **S22-operative band values — exact numbers + SENSEX.** The audit gives NIFTY **150–350** (avoid
   <130/>380) and BN **250–550**. SENSEX is currently 300–800 (2b grill-locked); the audit rows do not
   restate a SENSEX S22 band. **Options:** (a) keep SENSEX 300–800, swap only NIFTY/BN; (b) re-derive
   SENSEX from the deck. **Recommended default: (a)** — only swap what the audit specifies; flag SENSEX
   for a separate confirmation. Also: should the "avoid <130/>380" outer guard be encoded as a hard
   reject or just a wider band? Recommend encoding the band as [130, 380] for selection and [150, 350]
   as the preferred midpoint target — but confirm with the owner before tuning.

3. **Hero-Zero per-strike premium reads: diagnostic vs gate.** §3.7 S21(b)/S24(d)/Bearish-7 could be
   either backtest-only read-only diagnostics (this plan's recommendation) OR live entry preconditions.
   **Options:** (a) read-only diagnostic on the trade side-channel (no parity change, safe);
   (b) a live `hero-zero` gate leg (parity-sensitive, muted on history, needs a forward A/B).
   **Recommended default: (a)** — the disposition says "partially automatable" and the side stays the
   VWAP+extreme proxy; promoting to a live gate is a separate owner decision.

4. **Market-data OI-window parameterisation (gates `dynamic-strike-recenter`).** If the
   `/options/trending|spurt|active-strikes` endpoints cannot easily take a strike-window param, the
   recenter cannot reach the OI reads. **Options:** (a) add the read-only window param to those endpoints
   first ([S] market-data work, recommended); (b) scope `dynamic-strike-recenter` down to re-centring
   only the `openHighWindow`/StrikePicker window (a narrower win). **Recommended default: (a)** if the
   param is a small add; (b) only if the analytics window is structurally fixed. **Verify the current
   endpoint signatures before estimating.**

5. **`dynamic-strike-recenter` width ±7 vs the base ±3 — and whether it should be a *prop* or always-on
   when armed.** The doc says ATM±7 after a >1% move. **Options:** (a) `recenterWidth` prop (default 7),
   tunable; (b) hardcode 7. **Recommended default: (a)** — consistent with the DB-tunable
   `ScalperOiProps` pattern. Also confirm the move-% anchor is **session open** (recommended) vs
   prior-day close vs the entry bar — the doc says "move" without naming the anchor; session-open is the
   most defensible intraday read.

6. **`per-side-premium-skew`: which cues count as "positive cues"?** The §3.12 rule is "higher-premium
   side with **no positive cues**". **Options:** (a) reuse the already-computed `trending_cross` +
   `oi_spurt` + `futures_oi` side-dots as the "cue" set (recommended — no new compute, deterministic);
   (b) a richer bespoke cue set. Recommend (a). Also: should the dot be a soft warning (lowers aggregate,
   recommended) or a hard veto? Recommend **soft** — the audit calls it a "warning", and a hard veto on a
   live-only, history-muted signal is hard to value-verify.

7. **Short-side straddle/STBT premium SL (`straddle.md:24` short half).** SPAN-gated (#47) — no sell path
   exists. **Recommended default: defer** to `short-premium-span`; record here so the gap is not lost.

8. **Scalper positive golden harness.** Neither [P] change can get a *deterministic* golden today (the
   LIVE-only confluence gate is not driven by `TickwiseGoldenRunner`; FU2 §5.6). **Options:** (a) rely on
   seam + scorer unit tests + the OFF tripwire (recommended, matches FU2); (b) invest in a scalper golden
   harness (large, separate). Recommend (a) for this stream; if a harness is ever built, both [P] changes
   get an additive new FEATURES variant, never a mutation of the 5 frozen engine goldens.
