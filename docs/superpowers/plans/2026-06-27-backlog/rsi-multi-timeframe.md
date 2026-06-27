# RSI work-stream — multi-timeframe caps, per-strategy bands, cool-off

Status: PLAN (implementation-ready). Owner: single-owner. Target service:
`services/strategy-signal-service` (scalper confluence seam) + the `scalper-strategies/` YAMLs.
Supporting libs/engine read: `libs/strategy-engine` (already multi-timeframe-capable — no change).
Date: 2026-06-27.

> Read order for the executor: this plan is self-contained but it **copies the exact shape** of
> [`2026-06-27-followup2-soft-dots-to-hard-gates.md`](../2026-06-27-followup2-soft-dots-to-hard-gates.md)
> (FU2) — the `oi-cross-filter` (#5) early-return hard-gate + default-OFF tag + CFG-literal-arity
> pattern. The CLAUDE.md "parity-safe-additive" convention is load-bearing: **every emission-altering
> change is behind a NEW default-OFF tag, no existing golden is regenerated, and no shipped YAML carries
> the new tag in the infra PRs** (arming real strategies is a deliberate, separate, owner-driven PR).

---

## 1. Goal & the packages/gaps this stream closes

Make RSI a **multi-timeframe, per-strategy, sequence-aware** gate instead of a single shared
3-minute band. The scalper seam reads RSI only on the 3m primary today
(`ScalperConfluenceGate.chart()` → `bank.valueAt("rsi14", index)`,
`ScalperConfluenceGate.java:314`); §3.x/§4.2 of the Siva deck want a 5m overbought cap, a daily-RSI
cross-check, per-strategy band overrides, and a cool-off / post-vertical recovery sequence.

| Package | # gaps | Doc-§ (audit row) | P/S |
|---|---:|---|:--:|
| **`multi-timeframe-rsi`** | 3 | connect-the-dots §3.10 Filters/§6.10 (`disposition/connect-the-dots.md:19`); open-high-low §3.2/§6.2 entry[3] (`disposition/open-high-low.md:16`); session-additions §4.15.5 (`disposition/session-additions-and-manual-coverage.md:41`) | **[P]** |
| **`rsi-band-per-strategy`** | 3 | golden-crossover §3.6 entry bull-3/§6.6 (`disposition/golden-crossover.md:21`); morning-trade §3.9/§6.9 filters (`disposition/morning-trade.md:17`); morning-trade §3.9 entry-bearish 4 (`disposition/morning-trade.md:18`); completeness-sweep S24 §5.1 (`disposition/completeness-sweep.md:19`) | **[P]** |
| **`rsi-cooloff-pullback-entry`** | 1 | two-candle §3.1 S21(f)/S24(a) (`disposition/two-candle.md:14`) | **[P]** |
| **`multi-tf-rsi-crosscheck`** | 1 | two-candle §4.2/§3.1/§6.1 (`disposition/two-candle.md:40`) | **[P]** |
| **`daily-rsi-crosscheck`** | 1 | indicators-oi-vix-iv §4.2 (`disposition/indicators-oi-vix-iv.md:18`) | **[P]** |
| **`daily-rsi-hard-block`** | 1 | btst-stbt §3.8/§6.8 risk (`disposition/btst-stbt.md:31`) | **[P]** |
| **`post-vertical-rsi-recovery`** | 1 | trend-change §3.12 Edge-cases Day 07 (`disposition/trend-change.md:36`) | **[P]** |

**Total: 11 AUTOMATE_PKG gaps, all `[P]` parity-sensitive.** Note `rsi-band-per-strategy`'s
completeness-sweep S24 row (the RSI>85 overbought-defer) **overlaps in intent** with
`rsi-cooloff-pullback-entry`'s two-candle S21(f)/S24(a) row — both want "RSI overbought → wait to
cool → enter on the pullback candle." This plan implements **one** cool-off mechanism (§3.6 below)
and points both gaps at it; the `rsi-band-per-strategy` package's other two gaps (per-strategy
band override) use a distinct mechanism (§3.4).

**Adjacent, deliberately NOT in this stream** (recorded so the executor doesn't scope-creep):
- The §4.2-vs-§3.x **RSI band-value conflict** (CE 60–80 vs 50–75) is `UNCERTAIN_OWNER`
  (`GAP-DISPOSITION.md:246`, `disposition/two-candle.md:13`, `disposition/connect-the-dots.md:18`).
  It gates the **default** band-value but NOT the per-strategy-override mechanism — see §8 Open Point 1.
- `backtest-fidelity-rails` (lifting the live RSI band into the backtest `gate.all`) is a separate
  package (`disposition/gap-theory.md:23`), not here.
- `per-stock-daily-rsi` (Market-Movers daily RSI per stock) is gated on the equity universe
  (`disposition/market-movers.md:19`) — a different sub-epic; this stream only does the **index**
  daily-RSI. The session-additions §4.15.5 row IS counted here because its disposition note routes the
  "stock daily-RSI BTST screen" to `multi-timeframe-rsi` while the data is index-computable today
  (`disposition/session-additions-and-manual-coverage.md:41`).

---

## 2. Current state (verified file:line — opened and confirmed against the working tree 2026-06-27)

### 2.1 The seam reads RSI on the 3m primary only
- `ScalperConfluenceGate.chart(BarValues bank, int index)` (`ScalperConfluenceGate.java:304-316`)
  builds the `Chart` record reading **only** `bank.valueAt(RSI, index)` where `RSI = "rsi14"`
  (`ScalperConfluenceGate.java:41`). No 5m or 1d RSI alias is read anywhere in the seam.
- The RSI hard rail: `ScalperConfluenceGate.java:157-160` selects between
  `ScalperGates.rsiAbove(chart.rsi14(), oiProps.openHighRsiFloor())` (#2 open-high path, floor 50)
  and `ScalperGates.rsiBand(chart.rsi14(), side)` (everything else); blocked at `:161-163`.
- `ScalperGates.rsiBand` (`ScalperGates.java:76-84`): CE `> 60 && < 80`, PE `> 20 && < 40`, null
  fails. `ScalperGates.rsiAbove` (`ScalperGates.java:92-99`): strict `rsi > floor`, null fails.
- The soft `rsi` dot in the scorer reads `ctx.chart().rsi14()` via
  `ScalperGates.rsiBand(c.rsi14(), side)` (`ConnectTheDotsScorer.java:78`) — the **3m** RSI again.

### 2.2 The engine ALREADY supports multi-timeframe indicators (the key enabler)
- `IndicatorBank.build(...)` (`IndicatorBank.java:43-82`) creates one bound instance per declared
  indicator on its own `timeframe` series. `valueAt(alias, primaryIndex)` (`IndicatorBank.java:104-109`)
  → `mappedIndex(bound, primaryIndex)` (`IndicatorBank.java:130-141`) maps a higher-timeframe
  indicator to the **last COMPLETED bar of its series at the primary bar's close** (a half-built 5m
  bucket never leaks — identical in live + replay; `IndicatorBank.java:14-20` javadoc).
- `RSI` is a registered indicator (`IndicatorRegistry.java:38-39`, Wilder RSI, param `period`,
  `requiresContext=false`). A YAML line `{ name: RSI, alias: rsi5m, timeframe: 5m, ... }` already
  works — the engine computes a 5m RSI and `bank.valueAt("rsi5m", index)` returns it cross-mapped.
- `intervalOf` (`IndicatorBank.java:143-154`) handles `5m` and `1d`. **No engine change is needed**
  to surface a 5m or daily RSI; the seam just needs to read new aliases.

### 2.3 The engine warms the higher-timeframe series automatically when declared
- `SignalEngine.reload()` (`SignalEngine.java:220-231`) iterates `definition.indicators()` and adds a
  `SeriesKey(exchange, tradingsymbol, spec.timeframe())` (plus the 1m base) for **every declared
  indicator's timeframe**, then `keys.forEach(seriesStore::ensureWarm)` (`:232`). So declaring
  `RSI@5m` / `RSI@1d` on the signal future warms those series with no extra wiring.
- The `additional` timeframe list already pulls `1h` for the `bias60m` Supertrend
  (`scalp-connect-the-dots-nifty.yaml:25-26,34`), proving the multi-timeframe declaration pattern in
  the scalper YAMLs.

### 2.4 The tag → gate wiring (the FU2 / #5 pattern to copy)
- `ScalperConfig` record fields (`ScalperConfig.java:36-52`); `from(JsonNode, List<String> tags)`
  maps tags to `requireXxx` flags (`ScalperConfig.java:101-157`); the #5 template is
  `boolean callPutDeltaFilter = tags.contains("oi-cross-filter");` (`ScalperConfig.java:153`) threaded
  through the canonical constructor (`ScalperConfig.java:154-156`).
- The #5 early-return hard gate in the seam: `ScalperConfluenceGate.java:196-199`
  (`if (cfg.requireCallPutDeltaFilter() && !ScalperGates.callPutDeltaFilter(...).pass()) return Optional.empty();`).
- Tuning knobs that are owner-tunable live in `ScalperOiProps` (`@ConfigurationProperties("artha.scalper.oi")`,
  `ScalperOiProps.java:17-78`) with per-field defaults in the compact constructor; the all-defaults
  factory is `ScalperOiProps.defaults()` (`:76-78`). `openHighRsiFloor` (default 50) is the existing
  RSI knob there (`ScalperOiProps.java:54,72`).

### 2.5 The directional `side` and the CE-only YAML reality
- The seam derives `side` = CE when `close >= vwap`, else PE (`ScalperConfluenceGate.java:149-152`).
- **All 36 shipped YAMLs are `direction: long` / `option_types: [CE]`** (e.g.
  `scalp-connect-the-dots-nifty.yaml:22,37`). The chart `EntryEvaluator` only fires on the long gate,
  so in practice `side` is CE on every live signal today. PE-side RSI caps (`RSI(D) > 25`) are coded
  for completeness but **unreachable until a `direction: short` / `[PE]` variant is seeded** (that is
  the separate `bearish-side-seeding` package, `disposition/golden-crossover.md:22` — NOT this stream).

### 2.6 The parity firewall
- The seam is **LIVE-only** (`ScalperConfluenceGate.java:29-33` javadoc); the OI/macro/chain reads
  are current snapshots, never on deterministic replay; the picked option + confluence persist at
  entry (V009 side-channel) and a replay reads them back.
- `GoldenDeterminismTest.FEATURES` (`libs/strategy-engine`) and `BacktestParityTest.FEATURES`
  (`services/backtest-service`) carry **only the 5 pure-engine YAMLs** (no scalper), and neither
  harness instantiates `ScalperConfluenceGate`/`ConnectTheDotsScorer` (FU2 §2.6). A tag-gated hard
  gate cannot perturb them **provided no golden/parity YAML carries the new tag** (none can — the
  FEATURES arrays are fixed and contain no scalper strategy).
- The frozen `GoldenSignalsJson.write()` serializes only
  `timestamp/exchange/tradingsymbol/direction/composite/breakdown`; new `SignalEvent`/`Trade` fields
  ride a non-serialized side-channel (CLAUDE.md parity-safe-additive).

---

## 3. Design — per package

Two new pieces of machinery cover all 11 gaps, both copying the FU2 early-return-hard-gate shape:

- **(M1) Higher-timeframe RSI caps** — read NEW `rsi5m` / `rsiDaily` aliases from the existing
  `bank`, gate them behind new default-OFF tags. Closes `multi-timeframe-rsi`,
  `multi-tf-rsi-crosscheck`, `daily-rsi-crosscheck`, `daily-rsi-hard-block`.
- **(M2) Per-strategy band override + cool-off/recovery sequence state** — a per-strategy CE/PE band
  override and a `BarValues`-driven cool-off sequencer. Closes `rsi-band-per-strategy`,
  `rsi-cooloff-pullback-entry`, `post-vertical-rsi-recovery`.

All RSI thresholds (5m cap, daily cap, cool-off level, recovery level, per-strategy band bounds)
become **tunable `ScalperOiProps` fields**, not Java constants — they are exactly the
"freshly-calibrated knobs the owner tunes" that `ScalperOiProps` is for (`ScalperOiProps.java:6-11`).

---

### 3.1 New gate functions in `ScalperGates.java` (pure, single-sourced)

Add four pure `GateOutcome` helpers next to `rsiBand` / `rsiAbove`:

```java
  /**
   * Higher-timeframe overbought/oversold cap (§3.2/§3.10/§4.2 RSI multi-TF cross-check). CE must be
   * BELOW {@code ceCap} (not overbought on the higher TF); PE must be ABOVE {@code peFloor} (not
   * oversold). A null RSI FAILS (the higher-TF data is required when the strategy opts into the cap).
   */
  public static GateOutcome rsiHigherTfCap(
      BigDecimal rsi, OptionType side, BigDecimal ceCap, BigDecimal peFloor) {
    if (rsi == null) {
      return GateOutcome.fail(null, "higher-tf rsi unavailable");
    }
    double v = rsi.doubleValue();
    boolean ok = side == OptionType.CE ? v < ceCap.doubleValue() : v > peFloor.doubleValue();
    String want = side == OptionType.CE ? "CE rsi < " + ceCap : "PE rsi > " + peFloor;
    return new GateOutcome(ok, rsi, ok ? want + " ok" : want + " (overbought/oversold cap)");
  }

  /**
   * Per-strategy RSI band override (§3.6/§3.9/§4.2). Same shape as {@link #rsiBand} but the bounds are
   * supplied (e.g. CE 50–75 for Golden-Crossover, CE cap 75 for Morning Trade). null FAILS.
   */
  public static GateOutcome rsiBandCustom(
      BigDecimal rsi, OptionType side, BigDecimal ceLo, BigDecimal ceHi, BigDecimal peLo, BigDecimal peHi) {
    if (rsi == null) {
      return GateOutcome.fail(null, "rsi unavailable");
    }
    double v = rsi.doubleValue();
    boolean ok = side == OptionType.CE
        ? (v > ceLo.doubleValue() && v < ceHi.doubleValue())
        : (v > peLo.doubleValue() && v < peHi.doubleValue());
    String want = side == OptionType.CE ? "CE wants " + ceLo + "-" + ceHi : "PE wants " + peLo + "-" + peHi;
    return new GateOutcome(ok, rsi, ok ? want + " ok" : want + " (out of band)");
  }
```

The cool-off (§3.6) and post-vertical recovery (§3.7) need **prior-bar** state, so they are NOT pure
single-bar functions — they live in a small stateless detector that reads the `BarValues`/series
(§3.6/§3.7 below), not in `ScalperGates`.

---

### 3.2 `multi-timeframe-rsi` + `multi-tf-rsi-crosscheck` — 5m + daily overbought caps  [P]

The two packages are the **same mechanism** (a higher-TF cap) on two timeframes; implement once.

**Data flow.** YAML declares two new indicators on the signal future:
```yaml
indicators:
  - { name: RSI, alias: rsi5m,    timeframe: 5m, params: { period: 14 } }
  - { name: RSI, alias: rsiDaily, timeframe: 1d, params: { period: 14 } }
```
`SignalEngine.reload()` warms the 5m + 1d series automatically (`SignalEngine.java:220-232`, §2.3).
`IndicatorBank` cross-maps them to the 3m primary bar (`IndicatorBank.java:130-141`, §2.2).

**Seam read.** Extend `ScalperConfluenceGate.chart(...)` (`:304-316`) and the `Chart` record
(`ScalperGateContext.java:21-28`) with two nullable fields `rsi5m`, `rsiDaily`, read via
`bank.valueAt("rsi5m", index)` / `bank.valueAt("rsiDaily", index)`. (When the YAML omits the alias,
`bank.bound(alias)` throws — so guard with a `try` returning null, or, cleaner, only read the alias
when the cap tag is armed. **Recommended:** a `boolean has(String alias)` helper on `IndicatorBank`
that returns `byAlias.containsKey(alias)`; read the alias only when present, else null. This is a tiny
additive engine method, parity-neutral.)

**Gate.** New `requireRsi5mCap` + `requireRsiDailyCap` flags. Insert AFTER the existing RSI rail
(`ScalperConfluenceGate.java:161-163`), before the two-candle stop:

```java
    // multi-timeframe-rsi: a 5m overbought cap on top of the 3m band/floor (§3.2/§3.10/§4.2). CE must
    // not be overbought on the 5m TF; PE not oversold. Armed via the `rsi-5m-cap` tag; default OFF.
    if (cfg.requireRsi5mCap()
        && !ScalperGates.rsiHigherTfCap(chart.rsi5m(), side, oiProps.rsi5mCeCap(), oiProps.rsi5mPeFloor()).pass()) {
      return Optional.empty();
    }
    // daily-rsi-crosscheck: CE RSI(D) < 75 / PE RSI(D) > 25 (§4.2). Armed via `rsi-daily-cap`; default OFF.
    if (cfg.requireRsiDailyCap()
        && !ScalperGates.rsiHigherTfCap(chart.rsiDaily(), side, oiProps.rsiDailyCeCap(), oiProps.rsiDailyPeFloor()).pass()) {
      return Optional.empty();
    }
```

`ScalperOiProps` new fields + defaults (`§4.2` values): `rsi5mCeCap=75`, `rsi5mPeFloor=25`,
`rsiDailyCeCap=75`, `rsiDailyPeFloor=25`. (The §3.2 open-high text says "5m < 75/80" — the 80 variant
is owner-tunable via the prop; default to the tighter 75. See §8 Open Point 2.)

This closes `multi-timeframe-rsi` (3 gaps: the connect-the-dots 5m/daily cross-check, the open-high 5m
cap + daily cap, the session-additions daily screen) and `multi-tf-rsi-crosscheck` (the two-candle
5m+daily confirmation) — all four audit rows want exactly "5m < 75/80 AND daily < 75 for CE, mirror PE."

### 3.3 `daily-rsi-crosscheck` — index daily RSI < 75 (CE) / > 25 (PE)  [P]

Folded into 3.2 via `rsi-daily-cap` + `rsiDaily` alias (`disposition/indicators-oi-vix-iv.md:18`:
"add an `RSI@1d` indicator + a CE<75 / PE>25 gate"). No separate code — it is the daily half of the
`rsiHigherTfCap` insertion above. Counted as its own gap because its audit home is a distinct row.

### 3.4 `daily-rsi-hard-block` — never carry a fresh position with daily RSI > 75  [P]

`disposition/btst-stbt.md:31`: index variants codable now, stock universe deferred. Semantically this
is the **CE branch of the daily cap** (a fresh long blocked when daily RSI > 75). Two options:

- **(a) Reuse `rsi-daily-cap`** (§3.2) — a BTST/index strategy that arms `rsi-daily-cap` already gets
  "CE blocked when daily RSI ≥ 75." This is the daily-RSI hard block. **Recommended** — no new gate.
- (b) A dedicated `rsi-daily-hard-block` tag with a fixed 75 ceiling regardless of side (a pure
  "no fresh long if overbought" rail). Only needed if the owner wants the block independent of the
  CE/PE cap semantics.

Recommend (a); record (b) as Open Point 3. **Caveat:** the BTST path routes through the pre-close
clock and (per `btst-stbt.md:52`) the confluence gate is currently **unreachable for `style: btst`**
until the `btst-route-through-gate` package lands — so arming this on a real BTST variant depends on
that package. On the **index intraday** scalpers the gate is reachable today.

### 3.5 `rsi-band-per-strategy` — per-strategy CE/PE band override  [P]

`disposition/golden-crossover.md:21` (Golden-Cross bull at RSI 50–60 blocked by the shared 60–80;
"add a per-strategy RSI override; the `requireOpenHighLow`-style override pattern already exists") and
`disposition/morning-trade.md:17` (code caps CE at 80, doc says > 75 → lower the CE cap).

**Mechanism.** A nullable per-strategy band on `ScalperConfig`, read from new optional YAML keys, that
**replaces** the shared 60–80 band when present. Mirrors how `requireOpenHighLow` already swaps
`rsiBand` for `rsiAbove` at `ScalperConfluenceGate.java:157-160`:

```java
    boolean rsiOk;
    if (cfg.requireOpenHighLow()) {
      rsiOk = ScalperGates.rsiAbove(chart.rsi14(), oiProps.openHighRsiFloor()).pass();
    } else if (cfg.rsiBand() != null) {                                   // NEW: per-strategy override
      ScalperConfig.RsiBand b = cfg.rsiBand();
      rsiOk = ScalperGates.rsiBandCustom(chart.rsi14(), side, b.ceLo(), b.ceHi(), b.peLo(), b.peHi()).pass();
    } else {
      rsiOk = ScalperGates.rsiBand(chart.rsi14(), side).pass();           // unchanged default
    }
```

`ScalperConfig` adds a nullable `RsiBand rsiBand` record field; `from(...)` reads an OPTIONAL
`universe`-sibling or `risk`-sibling block — but `from(...)` currently receives the full `config`
node (`ScalperConfig.java:101`), so read a new top-level optional key, e.g.:
```yaml
rsi_band:            # OPTIONAL — absent ⇒ the shared §4.2 60-80/20-40 band (byte-identical)
  ce: { lo: 50, hi: 75 }
  pe: { lo: 25, hi: 50 }
```
`from(...)`: `JsonNode rb = config.path("rsi_band"); RsiBand band = rb.isMissingNode() ? null : RsiBand.parse(rb);`.
**Absent ⇒ null ⇒ the existing band fires byte-identically** (parity-safe-additive). This needs no new
*tag* — the presence of the `rsi_band` block IS the opt-in (like an indicator param), but it is still
`[P]` because it alters emission when present, so it ships default-absent and gets a golden variant
only if armed (§4).

The completeness-sweep S24 "RSI>85 → cool to ~75" row (`disposition/completeness-sweep.md:19`) is the
**cool-off** behaviour, NOT a static band — implemented in §3.6, not here.

### 3.6 `rsi-cooloff-pullback-entry` — RSI>80/85 cool-off + pullback-candle re-entry  [P]

`disposition/two-candle.md:14`: "RSI series available; add cool-off + pullback-candle re-entry." The
rule (§3.1 S21(f)/S24(a)): if the 3m RSI was overbought (> ~80, hard > 85) when the setup formed, do
NOT enter on that bar — **wait for a red/pullback candle** (RSI cooled to ~75) and enter then.

**Mechanism — a stateless 2-bar detector** (no cross-bar mutable field; reads the future series the
seam already holds). The seam receives `EngineSeries future` + `index` (`ScalperConfluenceGate.java:100-107`),
so the detector reads the prior bar's RSI via `bank.previousValueAt("rsi14", index)`
(`IndicatorBank.java:113-117`) and the current bar's candle colour:

```java
  /**
   * §3.1 S21(f)/S24(a) cool-off: if the PRIOR bar's RSI was overbought (>= {@code hotLevel}, default
   * 80) the CE entry must wait for THIS bar to be a pullback/red candle whose RSI has cooled to <=
   * {@code coolLevel} (default 75). PASS only on a cooled pullback candle; FAIL while still hot. PE
   * mirrors (prior oversold, wait for a green pullback). null RSI on either bar FAILS (data required).
   */
  static GateOutcome rsiCoolOff(BigDecimal prevRsi, BigDecimal rsi, EngineCandle bar, OptionType side,
      BigDecimal hotLevel, BigDecimal coolLevel) { ... }
```

Insert in the seam after the band rail, gated by `requireRsiCoolOff` (tag `rsi-cooloff`). `ScalperOiProps`
fields `rsiHotLevel=80`, `rsiCoolLevel=75` (and a strict `rsiHardHotLevel=85` if the owner wants the
two-tier 80/85 — see Open Point 4). Because the cool-off only fires when the prior bar was hot, on a
calm chart it is inert (PASS), so it never blocks a normal entry — it only delays the overbought ones.

This single mechanism closes BOTH `rsi-cooloff-pullback-entry` (two-candle S21f/S24a) and the
`rsi-band-per-strategy` completeness-sweep S24 row (`disposition/completeness-sweep.md:19`) — they are
the same "overbought → cool → pullback candle" rule cited under two dimensions.

### 3.7 `post-vertical-rsi-recovery` — after a vertical fall, wait for RSI recovery toward ~40 + a level  [P]

`disposition/trend-change.md:36`: "oversold-recovery sequencing after a vertical fall; automatable,
niche." This is the **PE/oversold mirror** of the cool-off, but for a reversal: after a vertical DROP
(RSI crashed oversold), do not take the reversal long until RSI **recovers toward ~40** and a defined
level prints.

**Mechanism.** A stateless detector reading the recent future bars (the same `future`/`index` the
TrendChangeGate already consumes, `ScalperConfluenceGate.java:204-211`): detect a recent oversold
trough (RSI < ~20 within the last N bars), then require the current bar's RSI to have recovered to
`>= recoveryLevel` (default 40). The "a defined level prints" leg reuses the structure-break /
swing-pivot already computed by `TrendChangeGate` (it returns a `stopLevel`); the recovery detector
only adds the RSI-sequence precondition. Gated by `requireRsiRecovery` (tag `rsi-recovery`),
`ScalperOiProps.rsiRecoveryLevel=40`, `rsiOversoldTrough=20`, lookback `rsiRecoveryLookback=10` bars.

Because it is **niche and trend-change-specific**, the recommended arming target is the trend-change
family only (and only its future PE-reversal variant, since the recovery-to-40 is a long-after-fall
read — see §2.5 on CE-only reality and Open Point 5).

---

### 3.8 Summary: new config surface

`ScalperConfig` new fields (all default OFF/null → byte-identical when unset):
`requireRsi5mCap`, `requireRsiDailyCap`, `requireRsiCoolOff`, `requireRsiRecovery` (booleans),
`RsiBand rsiBand` (nullable record). Parsed from tags `rsi-5m-cap`, `rsi-daily-cap`, `rsi-cooloff`,
`rsi-recovery` and the optional `rsi_band` block.

`ScalperGateContext.Chart` new nullable fields: `rsi5m`, `rsiDaily`.

`ScalperOiProps` new fields (defaults): `rsi5mCeCap=75`, `rsi5mPeFloor=25`, `rsiDailyCeCap=75`,
`rsiDailyPeFloor=25`, `rsiHotLevel=80`, `rsiCoolLevel=75`, `rsiRecoveryLevel=40`,
`rsiOversoldTrough=20`, `rsiRecoveryLookback=10`.

`IndicatorBank` new method `boolean has(String alias)` (additive, parity-neutral) so the seam reads a
higher-TF alias only when the YAML declares it.

---

## 4. PARITY classification

| Change | Class | Why | Tag + golden-variant plan |
|---|:--:|---|---|
| `rsiHigherTfCap` / `rsiBandCustom` / `rsiCoolOff` / `rsiRecovery` gate functions (`ScalperGates`) | **[S]** | New pure functions, **never called** unless a tag arms them; unit-tested in isolation. No emission change. | Unit tests only (§5.1). |
| 5m + daily RSI seam reads + gates (`requireRsi5mCap` / `requireRsiDailyCap`) | **[P]** | Alters which signals fire when armed. | Tags `rsi-5m-cap` / `rsi-daily-cap`, **default OFF**, absent from all 36 YAMLs in the infra PRs. New golden variant only when armed on a real YAML (PR-5). |
| Per-strategy band override (`rsi_band` block) | **[P]** | Replaces the 60–80 band when present. | Opt-in via the optional `rsi_band` block; **absent ⇒ byte-identical**. New golden variant only when a YAML adds the block (PR-5). |
| Cool-off (`requireRsiCoolOff`) | **[P]** | Delays/blocks overbought entries when armed. | Tag `rsi-cooloff`, default OFF. Golden variant on arming. |
| Post-vertical recovery (`requireRsiRecovery`) | **[P]** | Adds a reversal precondition when armed. | Tag `rsi-recovery`, default OFF. Golden variant on arming. |
| `IndicatorBank.has(alias)` (engine) | **[S]** | Read-only `Map.containsKey`; changes no value, no emission. | Engine unit test; the existing 5 goldens stay byte-identical (no scalper YAML in FEATURES). |
| `Chart` record new nullable fields | **[S]** | Additive record fields; the seam populates them, the scorer ignores them (scorer reads `rsi14` only). | Compiles; no scorer-denominator change. |
| `ScalperConfig` / `ScalperOiProps` new fields | **[S]** | In-memory config; flags default OFF, props default to §4.2 values; no persistence, no schema, no contract. | `ScalperStrategyLoadTest` OFF/absent assertions (§5.3). |

**Net parity guarantee (identical to FU2 §2.6 / §5.4).** Every behavioural change is behind a
default-OFF tag or an absent optional block; **no shipped YAML carries any new tag/block in the infra
PRs**; the seam is LIVE-only and never on the deterministic replay path; the engine goldens
(`GoldenDeterminismTest.FEATURES`, `BacktestParityTest.FEATURES`) contain no scalper strategy. So
`GoldenDeterminismTest` + `BacktestParityTest` stay **byte-identical with no regeneration**. A new
golden variant is created ONLY in PR-5 when a tag/block is armed on a real strategy — and even then
the existing 5 frozen goldens are untouched; the new variant is **additive** (a new FEATURES entry +
new committed `expected/*.signals.json`, generate-once), never a mutation of a frozen fixture.

> Important nuance vs FU2: FU2 deliberately ships NO scalper golden at all (its §5.6 punts positive
> deterministic coverage as out of scope, because the LIVE-only gate cannot be driven through
> `TickwiseGoldenRunner`). This stream **inherits that limitation** — the RSI gates are LIVE-only too.
> Therefore the "new golden variant per [P] change" obligation is satisfied by (a) the seam **unit**
> tests giving pass/block/unaffected coverage (§5.2), and (b) the standing rule that arming a tag on a
> real YAML in PR-5 must re-run both golden harnesses to prove byte-identity (they will pass, since no
> golden YAML is a scalper). A true positive scalper golden remains the same large, separate effort
> FU2 deferred (Open Point 6). **Do not regenerate any frozen golden in this stream.**

---

## 5. Tests

### 5.1 Unit — gate functions (`ScalperGatesTest.java`)
Add focused tests next to the existing `rsiBand` / `rsiAbove` tests:
- `rsiHigherTfCapBlocksOverboughtCeAndOversoldPe` — CE rsi 78 vs cap 75 → fail; CE rsi 70 → pass;
  PE rsi 22 vs floor 25 → fail; PE rsi 30 → pass; null → fail.
- `rsiBandCustomHonoursSuppliedBounds` — CE 55 in 50–75 → pass; CE 78 in 50–75 → fail; null → fail.
- `rsiCoolOffWaitsForPullbackAfterHotBar` — prior 82 / current 76 on a red candle → pass; prior 82 /
  current 81 → fail; prior 65 (not hot) → pass (inert); null on either bar → fail.
- `rsiRecoveryRequiresReboundToFortyAfterTrough` — trough 18 in lookback + current 42 → pass;
  current 35 → fail; no trough in window → pass (inert).

### 5.2 Unit — seam wiring (`ScalperConfluenceGateTest.java`)
This is the load-bearing coverage. The `ScalperConfig` arity change forces updating **all 8 existing
`new ScalperConfig(...)` literals** (`ScalperConfluenceGateTest.java:44,49,56,62,68,74,81,87`) — append
the new booleans (`false`) + the nullable `rsiBand` (`null`) in positional order to each. Then, per
new mechanism, add a CFG literal + the FU2 triple (pass / block / non-armed-unaffected):
- `RSI_5M_CAP_CFG`: arm `requireRsi5mCap`. Tests: a `bullBank()` clone with `rsi5m=70` PASSES; with
  `rsi5m=78` BLOCKS; the bare `CFG` (cap off) with `rsi5m=78` still PASSES (cap never consulted).
  Add a `bullBank()` variant exposing `rsi5m`/`rsiDaily` aliases (clone `:115-135`, add the keys).
- `RSI_DAILY_CAP_CFG`: same triple with `rsiDaily`.
- `RSI_BAND_CFG`: `rsiBand = new RsiBand(50,75,25,50)`. Test rsi 55 (in custom, OUT of shared 60–80)
  PASSES under `RSI_BAND_CFG` and BLOCKS under bare `CFG` — proving the override widens/narrows.
- `RSI_COOLOFF_CFG`: needs a 2-bar `future` series (the helpers `futureSeries(...)`/`strongGreen`
  already exist, `:93-107`) where the prior bar's RSI is hot — drive via `previousValueAt`. Triple:
  hot-prior + cooled-red-current PASSES; hot-prior + still-hot BLOCKS; calm-prior PASSES under bare CFG.
- `RSI_RECOVERY_CFG`: a `future` with an oversold trough then a recovered bar PASSES; no recovery BLOCKS.

Mirror the #5 oi-cross template (`ScalperConfluenceGateTest.java` around the OI_CROSS triple) and the
"non-Xxx unaffected" template exactly. Watch the FU2 §5.2 nuance: the **gate** reads the bank-derived
`chart`, while the **soft `rsi` dot** reads the mocked `ctx.chart()` — so flipping `rsi5m`/`rsiDaily`
in the bank does NOT touch the scorer aggregate (those aren't scored dots), and the cool-off/band
fixtures only need the bare aggregate to stay ≥ 0.6 in the non-armed PASS case (use the same
`bullContext()` that already clears it).

### 5.3 Load test (`ScalperStrategyLoadTest.java`)
After the existing per-strategy assertions, add the regression tripwire that the infra PRs arm
nothing (mirror FU2 §5.3):
```java
  assertThat(cfg.requireRsi5mCap()).as(id + " rsi-5m-cap off").isFalse();
  assertThat(cfg.requireRsiDailyCap()).as(id + " rsi-daily-cap off").isFalse();
  assertThat(cfg.requireRsiCoolOff()).as(id + " rsi-cooloff off").isFalse();
  assertThat(cfg.requireRsiRecovery()).as(id + " rsi-recovery off").isFalse();
  assertThat(cfg.rsiBand()).as(id + " rsi_band absent").isNull();
```
(If PR-5 arms a tag/block, flip the matching id to a per-id expectation, like the existing
`requireCallPutDeltaFilter` / `requireStraddle` per-family assertions.)

### 5.4 Engine unit (`libs/strategy-engine`)
- `IndicatorBank.has(alias)` — one test: `has("rsi5m")` true after declaring it, false otherwise.
- Optional: a multi-timeframe RSI mapping test (declare `RSI@5m` + `RSI@1d`, assert `valueAt` returns
  the last completed higher-TF bar at a 3m primary index) — proves the §2.2 cross-map for RSI
  specifically. The existing `IndicatorBank` multi-TF tests cover the mechanism; add an RSI case only
  if not already present.

### 5.5 Golden / parity tripwires (MUST stay byte-identical — no regeneration)
- `GoldenDeterminismTest` (`libs/strategy-engine`) — re-run; assert byte-identical green. **Do NOT
  pass `-Dgolden.generate=true`.**
- `BacktestParityTest` (`services/backtest-service`) — re-run; assert the three byte-match asserts stay
  green. The new `IndicatorBank.has` + `Chart` fields are read-only/additive and no FEATURES YAML is a
  scalper, so both pass unchanged.

### 5.6 e2e
No scalper-specific e2e exists and the infra PRs are default-OFF, so `e2e/tests/signals.spec.ts` must
stay green as a regression check (no new behaviour). If PR-5 arms a tag on a published strategy, add an
e2e assertion there that the confluence chip row still renders for a gated signal (FU2 §5.5 pattern) —
not in the infra PRs.

---

## 6. Dependencies & sequencing

1. **Engine first:** `IndicatorBank.has(alias)` (PR-1) is a prerequisite for the seam to safely read a
   higher-TF alias that may be absent. Tiny, parity-neutral, ship it first (rides upstream via `-am`).
2. **`ScalperGates` gate functions (PR-2)** before the seam wiring (the seam calls them).
3. **Seam + config wiring (PR-3/PR-4)** depends on 1–2. The `ScalperConfig` arity change touches the 8
   CFG literals — do it once in the first config-touching PR; later PRs in this stream extend the same
   literals.
4. **5m/daily caps need the YAML to declare `rsi5m`/`rsiDaily` indicators** to have any effect — but
   the indicator declaration is itself parity-safe-additive (a new soft indicator the scorer doesn't
   read changes nothing) and can ship in the same PR-5 that arms the tag. In the infra PRs, the seam
   reads `null` (alias absent) and the gate is OFF anyway.
5. **`daily-rsi-hard-block` on a real BTST variant** is blocked on `btst-route-through-gate`
   (`disposition/btst-stbt.md:52` — the confluence gate is unreachable for `style: btst` today). The
   **mechanism** ships here; **arming it on BTST** waits on that package. On index intraday scalpers it
   is reachable immediately.
6. **PE-side caps/recovery are unreachable until a `direction: short` / `[PE]` variant exists**
   (`bearish-side-seeding` package, §2.5). The PE branches are coded + unit-tested for correctness but
   only exercise live once a short variant is seeded.
7. **No dependency on the equity universe or SPAN** — this stream is index-only and buy-only
   (the `per-stock-daily-rsi` Market-Movers gap is explicitly NOT here, §1).

---

## 7. Effort (S/M/L) + suggested PR breakdown

Overall: **M** (mechanically a set of FU2-shaped early-return gates + two small stateful detectors +
config/props plumbing; no migration, no contract, no FE, no engine-core change beyond a one-line
`has`).

- **PR-1 `feat(strategy-engine): IndicatorBank.has(alias) presence check`** — **S.** The additive
  engine method + unit test + re-run the two goldens (byte-identical). Rides upstream via `-am`.
- **PR-2 `feat(strategy-signal): RSI multi-tf + band + cooloff gate functions (uncalled)`** — **S.**
  `ScalperGates` four new pure functions + `ScalperGatesTest`. Behaviourally inert (nothing calls them
  yet) — pure [S].
- **PR-3 `feat(strategy-signal): 5m/daily RSI caps + daily hard-block (tag-gated, default-off)`** —
  **M.** `ScalperConfig` arity (8 CFG literals) + `requireRsi5mCap`/`requireRsiDailyCap` + `ScalperOiProps`
  cap fields + `Chart` `rsi5m`/`rsiDaily` + seam reads/gates + seam-test triples + load-test OFF
  assertions. Closes `multi-timeframe-rsi`, `multi-tf-rsi-crosscheck`, `daily-rsi-crosscheck`,
  `daily-rsi-hard-block`.
- **PR-4 `feat(strategy-signal): per-strategy RSI band + cooloff + post-vertical recovery (default-off)`**
  — **M.** `rsi_band` block + `requireRsiCoolOff` + `requireRsiRecovery` + the two stateless detectors +
  props + seam wiring + tests + load-test absent/OFF assertions. Closes `rsi-band-per-strategy`,
  `rsi-cooloff-pullback-entry`, `post-vertical-rsi-recovery`.
- **PR-5 (DEFERRED, owner-driven) `feat(strategy-signal): arm <rsi tag/band> on <variant(s)>`** — **S
  per variant.** Append the tag / `rsi_band` block + the `rsi5m`/`rsiDaily` indicators to chosen YAMLs
  (the audit recommends: `rsi-daily-cap` on open-high-low + connect-the-dots; `rsi_band` 50–75 on
  golden-crossover; CE cap 75 on morning-trade). Flip the matching load-test assertion to a per-id
  expectation; re-run goldens (green). Per "tune on live, not backtest" (MEMORY: scalper-tuning), this
  is a forward-paper A/B the owner opts into — NOT part of the infra delivery.

Each PR: short-lived `feat/` branch, Conventional Commit scoped `strategy-signal` (or
`strategy-engine` for PR-1), squash-merge, single final PR. Build with the full reactor + `-am`
(`-pl services/strategy-signal-service -am verify`). Backout = revert the squash commit; everything is
additive + default-off, nothing persisted, no migration/contract/fixture touched.

---

## 8. Open Points

1. **Default CE/PE band value (the §4.2-vs-§3.x conflict).** The shared band stays §4.2 CE 60–80 / PE
   20–40 (the existing `rsiBand`, unchanged). The per-strategy override (§3.5) lets a strategy opt into
   50–75 etc. But which value is the *default* for a strategy that arms NO override is the standing
   `UNCERTAIN_OWNER` conflict (`GAP-DISPOSITION.md:246`). **Options:** (a) leave the default at §4.2
   60–80 and require each doc-50–75 strategy to set its own `rsi_band` block (recommended — no change to
   existing emission, explicit per strategy); (b) change the global default to 50–75 (would alter every
   live scalper's emission and needs a golden re-baseline + owner sign-off). **Recommended default: (a).**

2. **5m cap: 75 vs 80.** §3.2 open-high says "RSI5m < 75/80" (`disposition/open-high-low.md:16`).
   **Options:** (a) default `rsi5mCeCap=75` (tighter, recommended), (b) 80 (looser). It is a tunable
   prop either way; recommend (a) and let the owner widen via config.

3. **`daily-rsi-hard-block`: reuse `rsi-daily-cap` vs a dedicated side-agnostic block (§3.4).**
   **Options:** (a) reuse the daily cap's CE branch (recommended — no new gate); (b) a dedicated
   `rsi-daily-hard-block` tag that blocks any fresh long at daily RSI > 75 regardless of the CE/PE
   side decision (a pure "no fresh long if overbought" rail). Recommend (a); add (b) only if the owner
   wants the block decoupled from the cap semantics.

4. **Cool-off two-tier 80/85 (§3.6).** The doc says "don't enter while RSI > 80; if > 85 wait for
   ~70–80" (`disposition/two-candle.md:14,26`). **Options:** (a) a single `rsiHotLevel=80` /
   `rsiCoolLevel=75` (recommended for v1 — simpler, captures the intent), (b) a strict tier where > 85
   forces a longer cool-off to ≤ 80. Recommend (a); (b) is a follow-up prop if 80 proves too eager.

5. **Post-vertical recovery scope + reachability (§3.7).** The recovery-to-40 is a *long-after-a-fall*
   read; on the CE-only YAMLs the side is CE (long), so it IS reachable — but the rule is written as a
   reversal after a vertical DROP, which is naturally a PE→CE flip context. **Options:** (a) arm it
   only on the trend-change family as a CE-reversal-after-fall precondition (recommended — matches the
   §3.12 Day-07 source); (b) defer entirely until a `direction: short` reversal variant exists.
   Recommend (a) — the mechanism is index-computable and trend-change-specific.

6. **No positive scalper golden (inherited FU2 limitation).** The RSI gates are LIVE-only and cannot
   be driven through `TickwiseGoldenRunner` today, so there is no deterministic positive golden for
   them — coverage is the seam unit tests (§5.2). **Options:** (a) rely on the seam unit tests
   (recommended — pass/block/unaffected coverage, same posture as FU2 §5.6); (b) invest in a scalper
   golden harness (large, separate effort, out of scope). Recommend (a).

7. **Reading an absent higher-TF alias.** `IndicatorBank.valueAt` throws on an unknown alias
   (`IndicatorBank.java:91-95`). **Options:** (a) the new `IndicatorBank.has(alias)` presence check
   (recommended, PR-1) so the seam reads `rsi5m`/`rsiDaily` only when declared; (b) a `try/catch`
   around the read in the seam (uglier, hides real wiring bugs). Recommend (a).

8. **Where to read the per-strategy `rsi_band` block.** `ScalperConfig.from` receives the full
   `config` node, so a **top-level `rsi_band`** key is the least-intrusive home (§3.5). **Options:**
   (a) a top-level `rsi_band` block (recommended — `from` already has `config`); (b) nest it under
   `risk` or `universe` (would need threading a sub-node, more churn). Recommend (a). Confirm the
   strategy JSON-schema (`strategy-schema/v1`) tolerates an unknown top-level key (the schema is
   advisory per CLAUDE.md, but verify the validator does not reject it; if it does, add the optional
   key to the schema — a non-breaking additive schema change, no springdoc/contract impact).
