# Indicators: multi-TF Supertrend, volume MA, pattern arming, trendline (backlog stream)

> **ARCHIVED (2026-07-03 doc sweep):** historical planning doc — the work here is delivered, superseded, or consciously parked. Anything still open lives in `../2026-07-02-remaining-items.md` (ledger) or `../2026-07-03-10x-value-roadmap.md`. Do not mine this file for TODOs.


Status: PLAN (implementation-ready). Owner: single-owner. Date: 2026-06-27.
Target services: `services/strategy-signal-service` (scalper confluence seam + gates) ·
`libs/strategy-engine` (indicators) · the `scalper-strategies/*.yaml` set. No market-data
analytics change is required for this stream (every datum these packages need is already on the
per-bar `BarValues`/`EngineSeries` the seam reads; the OI/VIX/IV side is owned by other streams).

> Read order for the executor: this plan is self-contained, but the **load-bearing precedents** are
> (a) the FU2 plan (`2026-06-27-followup2-soft-dots-to-hard-gates.md`) — the canonical
> "tag-gated, default-OFF, early-return hard gate, no scorer-signature change, no golden touched"
> shape; and (b) the `#5 oi-cross-filter` wiring in `ScalperConfig`/`ScalperConfluenceGate`. Every
> `[P]` change here copies that exact shape. CLAUDE.md "parity-safe-additive" is the governing rule.

---

## 1. Goal & the packages/gaps this stream closes

This stream hardens the **chart-indicator + volume** half of the scalper confluence (the OI/VIX/IV
half is owned by the `directional-vix-gate` / `iv-per-strike` / `intraday-positional-oi` streams).
All gaps are `AUTOMATE_PKG` rows from `docs/strategy-audit/GAP-DISPOSITION.md` §3.

All file:line citations below are to the per-page **disposition** files under
`docs/strategy-audit/disposition/` (the "Work-package / note" column), re-verified 2026-06-27 against
the actual line numbers (the pass-1 audit corrected several stale rows — see the findings block at the
end). `GAP-DISPOSITION` line cites are to the §3c single-gap list in `docs/strategy-audit/GAP-DISPOSITION.md`.

| Package | gaps | Disposition row(s) — file:line | Doc § | P/S |
|---|---:|---|---|:--:|
| `multi-timeframe-supertrend` | 3 | disposition/connect-the-dots.md L17 (ST 15m variant); disposition/session-additions-and-manual-coverage.md L25 (15m-vs-60m OI separation), L33 (PSAR-distance durability) | 3.10 §6.10 / 4.14.6 / 4.15.3 | **[P]** |
| `volume-ma-indicator` | 1 | disposition/market-movers.md L22 | 3.3 | **[P]** (new dot) / **[S]** (declare-only) — see §3.2 |
| `indicator-param-pinning` | 1 | disposition/connect-the-dots.md L20 (single-gap pkg, GAP-DISPOSITION L161) | 3.10 Setup 2 | **[S]** |
| `two-candle-pattern-arming` | 1 | disposition/connect-the-dots.md L21 (GAP-DISPOSITION L161) | 3.10 Entry 2/4 | **[P]** |
| `pullback-entry-trigger` | 1 | disposition/connect-the-dots.md L32 (GAP-DISPOSITION L162) | 3.10 Exit-Scaling / Edge | **[P]** |
| `trendline-break-detector` | 1 | disposition/trend-change.md L18 (Setup 2-3 / Entry b.1); L34 (held-pivot KEEP_MANUAL) (GAP-DISPOSITION L164, effort M) | 3.12 Setup 3 / Entry b.1 | **[P]** |
| `rising-volume-confirm` | 1 | GAP-DISPOSITION L164 (single-gap; the per-page row folds into `trade-management-targets-trailing`) | 3.x volume-confirm | **[P]** |
| `vwap-break-volume-qualified` | 1 | disposition/connect-the-dots.md L30 (per-page row → `trade-management-targets-trailing` theme); GAP-DISPOSITION L165 | 3.10 Exit / 3.1 exit | **[P]** — but NOT YAML-only; see §3.8 audit correction |
| `volume-floor-per-index` | 1 | disposition/golden-crossover.md L20,L51 (GAP-DISPOSITION L154) | 3.6 setup-4 | **[P]** |
| `two-candle-volume-substitution` | 1 | disposition/two-candle.md L12,L55 (GAP-DISPOSITION L155) | 3.1 S21a / 5.1 | **[P]** |
| `pct-price-move-gate` | 1 | disposition/market-movers.md L32 (GAP-DISPOSITION L170) | 3.3 Entry Bull/Bear 5 | **[P]** |

**Total: 13 gaps across 11 packages.** Net `[P]` parity-sensitive: 9 packages (each needs a NEW
default-OFF tag + a NEW golden variant or seam-test triple, existing goldens byte-identical). Net
`[S]` safe: 2 (`indicator-param-pinning` is a YAML constant change that is a NO-OP because it pins
the engine's existing default; `volume-ma-indicator` is `[S]` if shipped as declare-only, `[P]` if
shipped as a new scored dot — recommended split in §3.2).

**Stream boundary note.** `pct-price-move-gate` lives in the Market-Movers per-stock sub-epic, which
is gated on `equity-fno-universe-screener` (the foundational equity capture). Its **index-level
>1%-move gate** is buildable today and in-scope here; its ΔOI leg and per-stock series are NOT (they
depend on the equity universe — see §6 Dependencies). We ship the index-level price-move gate as a
reusable primitive that the equity stream later re-keys per-stock.

---

## 2. Current state (verified file:line)

All line numbers opened and confirmed against the working tree on 2026-06-27.

### 2.1 The confluence seam — `ScalperConfluenceGate.java`
- `@Component`, **LIVE-only** (class javadoc L21-33 — the OI/macro/chain reads are current
  snapshots, never run on deterministic replay; the picked option + confluence persist at entry via
  the V009 side-channel). This is the parity firewall.
- `evaluate(...)` L100-280 runs the §0B pre-flight then the per-strategy hard gates, each behind a
  `ScalperConfig` flag. The **side decision** is L149-152 (CE when `close >= vwap`, else PE).
- The bank-derived `Chart chart` is built L124 via `chart(bank,index)` (the private method L304-316,
  reading `SUPERTREND`/`VWMA`/`PSAR`/`RSI` aliases + `close`/`vwap`/`volume` builtins off the
  `BarValues`). The `bias60m` read is L318-321 (`bank.valueAt(BIAS_60M, index).signum()`).
- The §0B volume + RSI rails are L157-163 (`ScalperGates.volume(cfg.signalIndex(), chart.volume())`
  + `rsiBand`/`rsiAbove`). **The volume floor is keyed off `cfg.signalIndex()`** (the signal
  future's index), and reads the FUTURE bar's volume, not a per-strike/per-stock or crossover-candle
  volume — this is exactly the `volume-floor-per-index` gap.
- `structuralStop(...)` L288-302 anchors the entry-time stop (two-candle 1st-candle, entry-candle,
  first-session-candle). The trend-change gate anchors on the broken swing pivot (L204-211).
- `#5 oi-cross-filter` is L196-199 — **the canonical hard-pre-gate insertion template** for a `[P]`
  gate behind a `cfg.requireXxx()` flag.

### 2.2 The scorer — `ConnectTheDotsScorer.java`
- 18 soft dots added L74-98 (`vwap@2.5, futures_oi@1.5, iv_rank@0.8, iv_pair@0.8`, + 14 unit `W=1.0`
  dots). Denominator Σweights = **19.6**. Aggregate = weighted-supports / 19.6, scale 4 HALF_UP
  (L100-109). **Adding a new scored dot changes this denominator for EVERY bar** — that is why a new
  scored dot is `[P]` and (if it ever rode a golden) would re-baseline it.
- `supertrend` dot L75 reads `c.supertrendDir()` (the 3m ST direction only). `volume` dot L79 reads
  `ScalperGates.volume(...)` (the floor, not a Volume-MA). `psar` dot L77 reads `c.psar()` (the
  level, no distance interpretation). **No 15m ST, no Volume-MA, no PSAR-distance, no pullback dot.**
- Validity L114-115: `valid = (!vwapHardGate || vwapSide) && biasAligned && !standAside && aggregate >= threshold`.

### 2.3 The hard-gate + chart records
- `ScalperGateContext.Chart` (record, `ScalperGateContext.java` L21-28) carries `close, vwap, vwma20,
  psar, supertrendDir(int), rsi14, volume`. **No `supertrend15m`, no `psarDistance`, no `volumeMa`,
  no prior-bar volume** — extending the confluence to read those needs a new Chart field + a new
  `chart(...)` read off the bank.
- `ScalperGates.volume(String underlying, BigDecimal volume)` L64-68 — `VOL_FLOOR = Map.of("NIFTY 50",
  125000)`, default 50000 (`ScalperGates.java` L27-30). Keyed by underlying NAME.
- `ScalperGates.indicatorAlignment` L101-118 (PSAR+VWMA+ST+VWAP all on side). **PASS-2 NOTE: this
  fn is WRITTEN + unit-tested but NOT called in `ScalperConfluenceGate.evaluate` on the current tree —
  FU2 (the plan that wires it as a hard `indicator-alignment` gate) is UNMERGED. So the references
  below to grouping new early-returns "with the FU2 indicator-alignment block" describe a code block
  that does not yet exist in `evaluate`; treat them as placement HINTS, not a precondition. The
  copy-the-#5-template shape needs only the live `oi-cross-filter` precedent (which IS merged), so this
  stream does NOT require FU2 to land first — see PASS-2 finding 1.**
- `TwoCandleGate.detect` (`TwoCandleGate.java` L41-59): requires BOTH 1st AND 2nd candle ≥ floor
  (L52); strong 2nd body (L55). **No 1st+3rd substitution fallback** (the `two-candle-volume-substitution`
  gap). The `two-candle-pattern` tag exists but is **absent from every connect-the-dots YAML**
  (the `two-candle-pattern-arming` gap).
- `MarketStructure.detect` (`MarketStructure.java` L41-61): a **3-bar fractal swing-pivot** break;
  the broken pivot is the SL anchor. **No diagonal/horizontal trendline engine** (the
  `trendline-break-detector` gap). Consumed only by `TrendChangeGate` (L86).

### 2.4 The engine indicator registry — `IndicatorRegistry.java`
- `SUPERTREND` registered L56-64 (`period`/`multiplier`, default 10/3). `VWMA` L77-80 (`period`, default
  20). `PSAR` L81-88 (`step`/`max`, defaults `0.02`/`0.2`). `VOLUME_RATIO` L65-68 (`lookback`, default
  20 — current-volume / mean-of-prior-lookback; `SessionIndicators.volumeRatio` L172-187). **A
  Volume-MA already exists as `VOLUME_RATIO`** (a ratio form; the raw Volume-SMA is a trivial new
  factory or reuse-as-ratio — see §3.2). Multi-TF is native: an indicator declares its own
  `timeframe`, so a 15m Supertrend is just another `indicators:` entry (the YAML already wires `[1h]`
  ST as `bias60m`).

### 2.5 The YAML set + tag wiring — `ScalperConfig.java`
- `from(JsonNode config, List<String> tags)` L101-157 maps tags → flags: `two-candle-pattern` (L119),
  `gap-theory` (L121), `trend-change` (L123), `open-high-low` (L125), `opening-tick` (L128),
  `hero-zero` (L131), `straddle` (L135), `entry-candle-stop` (L149), `oi-cross-filter` (L153, the #5
  template). The constructor returns all flags L154-156. **Constructor arity is coupled** to every
  `new ScalperConfig(...)` literal in `ScalperConfluenceGateTest` (8 literals per the FU2 audit) — a
  new flag forces a compile-time update to all 8 (a fan-out, not a parity risk).
- 36 YAMLs (12 strategies × {nifty, sensex-niftyoi, sensex-sensexoi}); the gate-arming field is the
  top-level `tags:` list. `scalp-connect-the-dots-nifty.yaml` L27-34 declares the 3m VWMA/PSAR/RSI/ST
  indicators + the 1h `bias60m` ST(7,3); its exit (L44-46) is `signal_exit {rule:"close < vwap"}` +
  `time_stop`. **PSAR carries NO `params:` block** (L29) — it relies on the engine default 0.02/0.2
  (the `indicator-param-pinning` gap).

### 2.6 The exit path — `ExitEvaluator.signalExit` (`libs/strategy-engine`)
- L330-338: `signal_exit` compiles `rule.params().get("rule")` as a **YAML gate-grammar boolean
  expression** via `StrategyCompiler.compileLeafText` and evaluates it against the `IndicatorBank`.
  So `vwap-break-volume-qualified` and `rising-volume-confirm` are expressible **directly in the YAML
  exit/gate string** (e.g. `close < vwap and volume > 125000`) — they alter trade emission, so they
  are `[P]` (a new golden variant of the affected family), but they need NO new Java gate.

### 2.7 The parity firewall — golden / parity harnesses
- `GoldenSignalsJson.write()` serializes only `timestamp/exchange/tradingsymbol/direction/composite/
  breakdown`. `SignalEvent.stopLoss/takeProfit` ride a non-serialized side-channel.
- `GoldenDeterminismTest.FEATURES` + `BacktestParityTest.FEATURES` = the 5 pure-engine goldens, **no
  scalper**. Neither harness instantiates `ScalperConfluenceGate`/`ConnectTheDotsScorer`. Therefore a
  tag-gated hard gate **cannot perturb the goldens** provided no golden/parity YAML carries a new tag
  (none can — the FEATURES arrays are fixed and contain no scalper strategy). This is the FU2 result,
  re-confirmed for this stream.

---

## 3. Design — per package

Two delivery vehicles, both parity-safe:
- **Vehicle A (gate / dot, in the seam):** a new `requireXxx` flag on `ScalperConfig` + a new tag +
  an early-return in `ScalperConfluenceGate.evaluate` (the #5 template) and/or a new field on
  `ScalperGateContext.Chart` read by a new soft dot. Used when the rule is a confluence precondition.
- **Vehicle B (YAML-only, in the engine grammar):** a new exit/gate string in a NEW tagged YAML
  variant. Used when `ExitEvaluator`/`GateEvaluator` already expresses the rule (volume-qualified
  exits, the >1% move). No Java change; the parity safety is "new variant ⇒ new golden, old YAMLs
  untouched".

### 3.1 `indicator-param-pinning` — pin PSAR (0.02/0.2) [S]
- **File:** every `scalp-*-*.yaml` that declares `{ name: PSAR, alias: psar, timeframe: 3m }` (no
  `params:`), e.g. `scalp-connect-the-dots-nifty.yaml` L29.
- **Change:** add the explicit params block:
  ```yaml
  - { name: PSAR, alias: psar, timeframe: 3m, params: { step: 0.02, max: 0.2 } }
  ```
- **Data flow:** `StrategyCompiler` → `IndicatorRegistry.create("PSAR", …, {step,max})`
  (`IndicatorRegistry.java` L81-88). Since `0.02`/`0.2` ARE the registry defaults (L87-88), the
  compiled indicator is **bit-identical** — pinning makes the config self-documenting and tuning-safe
  (the optimizer can now sweep `params.step`/`params.max`) without changing today's output.
- **PARITY [S]:** byte-identical because the pinned values equal the defaults. Verify with a unit
  assertion that `psar` valueAt is unchanged before/after (or simply that the YAML still parses + the
  load test passes). **No new tag, no golden variant.** This is the one genuinely free win — do it
  first across all PSAR-declaring YAMLs. (Optionally also pin `RSI period:14` where omitted, same
  no-op logic — but every shipped YAML already pins RSI/VWMA/ST params; only PSAR is bare.)

### 3.2 `volume-ma-indicator` — declare a Volume-20 MA [S] declare-only / [P] as-a-dot
The doc's §3.3 "Volume 20" indicator is absent; only the static floor is gated. Two options:

- **Option (a) — declare-only [S] (recommended for this stream).** Register a raw Volume-SMA in the
  engine and declare it in the Market-Movers YAML so it is computed + available to the optimizer /
  side-channel, WITHOUT wiring it into the scorer aggregate.
  - **Engine:** reuse the existing `VOLUME_RATIO` (`IndicatorRegistry.java` L65-68 — current vs
    mean-of-prior-lookback) OR add a sibling `VOLUME_SMA` factory (`SessionIndicators.volumeSma`,
    a 5-line trailing-mean-of-volume mirroring `vwma` L47-62 but summing raw volume). Recommend
    **reusing `VOLUME_RATIO`** (a Volume-MA *relationship* is what §3.3 means by "Volume 20" — "is
    this bar's volume above its 20-bar average"); no engine change at all then.
  - **YAML:** add to `scalp-market-movers-*.yaml`:
    ```yaml
    - { name: VOLUME_RATIO, alias: vol_ratio20, timeframe: 3m, params: { lookback: 20 } }
    ```
  - **PARITY [S]:** a declared-but-unscored indicator does NOT enter `ConnectTheDotsScorer` (the dot
    list is hard-coded L74-98, not YAML-driven) and does NOT change the scorer denominator. The
    engine `IndicatorBank` computes it; nothing reads it into a signal decision ⇒ emitted signals
    byte-identical. It rides the side-channel/optimizer only. **No new golden.**
    **VERIFIED (pass 1):** the Market-Movers family ALREADY EXISTS and is SEEDED —
    `scalp-market-movers-{nifty,sensex-niftyoi,sensex-sensexoi}.yaml`, no scalper golden (no scalper
    YAML rides `GoldenDeterminismTest`/`BacktestParityTest` — both FEATURES arrays are the 5 pure-engine
    vectors). So this is an ADDITIVE edit to existing seeded YAMLs, not a new family file. Adding an
    unscored `vol_ratio20` indicator does not change emission, BUT it DOES change the seeded YAML's
    `indicators[]` count — confirm `ScalperStrategyLoadTest` does not assert an exact indicator count
    for the Market-Movers id (it asserts the per-family `requireXxx` flags, not the indicator list, so
    this is expected to be fine — but re-run the load test as the tripwire).
- **Option (b) — scored dot [P] (deferred).** Add a `volumeMa`/`volRatio` field to
  `ScalperGateContext.Chart`, a `chart(...)` read, and a 19th soft dot in the scorer. This changes
  the denominator (19.6 → 20.6) for every bar ⇒ a hard `[P]` re-baseline of any scalper that ever
  rides a golden, and a behavioural shift live. **Recommend deferring (b)** to a dedicated, owner-opted
  scorer-extension PR (same shape as the FU2 "Dow dot" open point) — it is NOT free like (a).

**Recommendation:** ship **(a)** in this stream; record (b) as an Open Point.

### 3.3 `multi-timeframe-supertrend` — 15m ST variant + PSAR-distance durability [P]

> **AUDIT-ADDED (pass 1) — record-constructor fan-outs (two of them) that the rest of §3 inherits.**
> 1. **`ScalperGateContext.Chart` is a record with 7 positional fields** (`close, vwap, vwma20, psar,
>    supertrendDir(int), rsi14, volume`). There are **17 `new Chart(...)` call sites** (1 production —
>    `ScalperConfluenceGate.chart()` L308-315 — and 16 across `ConnectTheDotsScorerTest`,
>    `ScalperGatesTest`, `ScalperConfluenceGateTest`). EVERY new Chart field this stream adds
>    (`supertrend15mDir`, `psarDistancePct`, and any §3.7 `prevVolume`) forces a positional update to
>    ALL 17 literals (a compile-time fan-out, same shape as the `ScalperConfig` flag fan-out the plan
>    already flags — NOT a parity risk, but it is a real per-PR step the executor must do or the module
>    will not compile). **Add all of this stream's Chart fields in ONE PR (PR-2) to amortise the
>    17-site churn**, rather than re-touching the 17 sites across PR-2/PR-3/PR-4.
> 2. **`ScalperOiProps` is a `@ConfigurationProperties` record with 11 positional fields** + a compact
>    constructor defaulting block + a `defaults()` factory passing 11 `null`s. EVERY new tunable this
>    stream adds (`psarDistanceFloorPct`, `pullbackTolerancePct`, `pctPriceMoveFloor`) requires: the new
>    record component, a new `DEFAULT_*` constant + its line in the compact constructor, the extra `null`
>    in `defaults()`, AND every other `new ScalperOiProps(...)` test literal. **Batch the props additions
>    in PR-2 too.** (`ScalperGates` gate fns that take an `EngineSeries` — §3.11 `pctPriceMove` — also
>    add an `EngineSeries` import to `ScalperGates`, which today imports only `Chart`/`Oi`/`Macro`;
>    trivial but note it.)

Three sub-gaps. The clean, parity-safe carve:
- **(i) Selectable 15m ST(7,3) confirmation dot.** Multi-TF is native (an indicator declares its own
  `timeframe`). Add a 15m ST alias to a NEW tagged YAML variant and a NEW soft dot OR a NEW hard
  gate. **Recommended as a hard gate** (mirrors `bias60m` being a hard `biasAligned` term, but on
  15m and tag-gated):
  - `ScalperGateContext.Chart`: add `int supertrend15mDir`.
  - `ScalperConfluenceGate.chart(...)` (L304-316): read `bank.valueAt("supertrend15m", index).signum()`
    (a new alias the YAML declares on `timeframe: 15m`).
  - `ScalperGates`: add a pure `supertrend15mAlign(int dir15m, OptionType side)` (CE wants `>0`, PE
    `<0`; **dir 0 ⇒ unknown ⇒ PASS**, the `bias60m`/VIX fail-open convention so a missing 15m series
    never blocks).
  - `ScalperConfig`: `boolean requireSupertrend15m` + `tags.contains("supertrend-15m")` + constructor.
  - `ScalperConfluenceGate.evaluate`: early-return after the side decision (needs only `chart`+`side`),
    placed with the other chart-precondition early-returns (next to the FU2 indicator-alignment block
    IF FU2 has merged; otherwise just after the §0B volume/RSI rails — the block is independent of FU2):
    ```java
    if (cfg.requireSupertrend15m()
        && !ScalperGates.supertrend15mAlign(chart.supertrend15mDir(), side).pass()) {
      return Optional.empty();
    }
    ```
  - **NEW YAML variant** `scalp-connect-the-dots-nifty-st15m.yaml` (or a `supertrend-15m` tag on a new
    family member): `tags: [..., supertrend-15m]` + an indicator
    `- { name: SUPERTREND, alias: supertrend15m, timeframe: 15m, params: { period: 7, multiplier: 3.0 } }`
    + `timeframes.additional: [15m, 1h]`.
- **(ii) 15m-vs-60m OI separation (session-additions L25).** This is the OI-page multi-TF read; the
  scalper-signal slice of it is the same 15m-ST-vs-60m-ST agreement above (the 60m is `bias60m`, the
  15m is the new dot). Mark the OI-page-window half (`/options/trending` 5–15m vs 60m bucketing)
  as belonging to the `intraday-positional-oi` / `trending-oi-window-fidelity` streams — **not this
  one** (record in Open Points so it is not double-counted).
- **(iii) PSAR-distance durability (disposition/session-additions-and-manual-coverage.md L33;
  disposition/trend-change.md L34 "deep PSAR bounce" cue, KEEP_MANUAL).**
  The doc: PSAR dots close to price ⇒ short-lived; wide gap ⇒ lasting. Add a derived distance:
  - `ScalperGateContext.Chart`: add `BigDecimal psarDistancePct` (= `|close - psar| / close * 100`,
    computed in `chart(...)` from the existing `close`/`psar` — **no new bank read**, pure arithmetic).
  - **As a tag-gated hard gate** `psar-durability`: `ScalperGates.psarDurable(BigDecimal distPct,
    BigDecimal floorPct)` (PASS when `distPct >= floor`; **null ⇒ PASS** fail-open). Floor lives on
    `ScalperOiProps` (new `psarDistanceFloorPct`, DB-tunable, default e.g. `0.05`% — a placeholder,
    tune on live). `ScalperConfig.requirePsarDurable` + `tags.contains("psar-durability")` + the
    early-return.
- **PARITY [P]:** 15m-ST dot/gate and PSAR-durability gate are NEW preconditions ⇒ each behind a NEW
  default-OFF tag (`supertrend-15m`, `psar-durability`) on a NEW YAML variant with a NEW golden (or,
  since the gate is LIVE-only and no scalper golden exists, the seam-test triple per §5). Existing 36
  YAMLs carry neither tag ⇒ byte-identical. The `psarDistancePct` Chart field is computed but only
  *read* by the gated path, so an unarmed strategy never consults it.

### 3.4 `two-candle-pattern-arming` — arm `two-candle-pattern` on connect-the-dots [P]
- **File:** a NEW tagged variant of the connect-the-dots family (do NOT mutate the shipped
  `scalp-connect-the-dots-nifty.yaml` — that would alter its emitted signals and break its identity).
  Create `scalp-connect-the-dots-nifty-2candle.yaml` with `tags: [scalper, options, intraday, nifty,
  two-candle-pattern]`.
- **Change:** none in Java — `ScalperConfig.from` already maps `two-candle-pattern` → `requireTwoCandle`
  (L119) → the hard `TwoCandleGate` gate (L167-169) + the 1st-candle SL anchor. Arming is a one-token
  YAML addition on the new variant.
- **Data flow:** `ScalperStrategySeeder` loads the new YAML → `requireTwoCandle=true` → the seam
  requires the 2-green/2-red strong-2nd formation before firing.
- **PARITY [P]:** the new variant fires a DIFFERENT (stricter) signal set than the base ⇒ it is a new
  strategy with no existing golden, and the base's golden is untouched. Verify via the seam test
  (`requireTwoCandle` blocks without the formation) + `ScalperStrategyLoadTest` asserting the new
  variant has the tag and the base still does not.

### 3.5 `pullback-entry-trigger` — retrace-to-ST/VWAP/VWMA entry [P]
The defining "enter at the ST level on a pullback; take retraces near VWMA/ST/VWAP, don't chase"
discipline. Today the seam fires on any `close > vwap`.
- **`ScalperGates`:** add `pullbackProximity(Chart c, OptionType side, BigDecimal tolPct)` — PASS when
  the bar's `close` is within `tolPct` of at least one of `vwap`/`vwma20`/`psar` on the correct side
  (CE: price just reclaimed/sits near the level from above; PE mirror). Concretely: `min(|close-vwap|,
  |close-vwma20|, |close-psar|) / close * 100 <= tolPct` AND the side is correct (still above VWAP for
  CE). **Null operands ⇒ FAIL** (a pullback can't be confirmed without the levels — this is a
  precondition, not a confirm-or-pass; matches `indicatorAlignment`'s strict-on-null behaviour).
- **`ScalperConfig`:** `boolean requirePullback` + `tags.contains("pullback-entry")` + constructor.
  Tolerance on `ScalperOiProps` (new `pullbackTolerancePct`, default e.g. `0.10`%, DB-tunable).
- **`ScalperConfluenceGate.evaluate`:** early-return after the side decision (needs `chart`+`side`),
  placed with the 15m-ST early-return (and the FU2 indicator-alignment block IF that has merged —
  independent of it otherwise).
- **PARITY [P]:** NEW `pullback-entry` tag, default-OFF, NEW variant + seam-test triple. The base
  YAMLs never chase-vs-pullback differently ⇒ byte-identical.

### 3.6 `trendline-break-detector` — diagonal/horizontal trendline break [P] (effort M)
Today only a 3-bar fractal swing-pivot break (`MarketStructure.detect`) feeds `TrendChangeGate`. Add
a trendline-break primitive as an ADDITIONAL, tag-gated alternative trigger (NOT a replacement —
swing-pivot stays the default).
- **NEW class `TrendlineBreak.java`** (sibling of `MarketStructure`, same package, pure +
  deterministic over closed bars):
  - Fit a session-bounded **trendline** from the two most recent confirmed fractal pivots on the
    relevant side (for a CE up-break: the two latest swing-HIGHS define a descending resistance line;
    the break fires when the deploy bar's `close` prints above the line's value extrapolated to the
    deploy index). PE mirror on swing-LOWS / ascending support.
  - Return `record Break(boolean broke, BigDecimal level)` (the line value at the break bar = the SL
    anchor). NOTE (pass 1): `MarketStructure.Break`'s second field is named `pivot` (not `level`) —
    `MarketStructure.java` L30 `record Break(boolean broke, BigDecimal pivot)`; `TrendChangeGate` reads
    `structure.pivot()` at L90. The new record's field name is free to choose, but the "mirrors
    `MarketStructure.Break`" phrasing should not imply an identical field name.
  - Reuse `MarketStructure`'s `isSwingHigh`/`isSwingLow` fractal definition (extract them to
    package-visible helpers or duplicate the 4-line predicate — recommend extracting to avoid drift).
- **Wiring:** a NEW tag `trendline-break` arms it as an alternative inside the trend-change path:
  `TrendChangeGate.evaluate` gains an optional `useTrendline` parameter (or the gate consults
  `TrendlineBreak.detect(...)` when the structure-pivot break fails AND the tag is set). Cleanest:
  `ScalperConfig.requireTrendlineBreak` + thread a boolean into `TrendChangeGate.evaluate` so leg (a)
  accepts EITHER a fractal-pivot break OR a trendline break. The broken line anchors the SL.
- **PARITY [P]:** NEW `trendline-break` tag, default-OFF (only the new trend-change variant carries it);
  the shipped trend-change YAMLs keep the pivot-only path ⇒ byte-identical. Effort **M** (a real new
  geometric primitive + its unit tests).
- **Scope note:** the doc's "held prior-day trendline pivot" cue (disposition/trend-change.md L34,
  KEEP_MANUAL) stays a manual check — only the intraday session-bounded break is automated here.

### 3.7 `rising-volume-confirm` — volume rising into the break [P]
"A break is only real if volume is rising" (S24 volume-confirmation, already a HARD floor at
`ScalperConfluenceGate` L161 — but only an ABSOLUTE floor, not a RISING check).
- **Vehicle B (YAML grammar) preferred** for the *entry* form: the engine gate grammar reads
  `volume` (builtin) and `VOLUME_RATIO` (declared indicator). A NEW tagged variant adds to the entry
  `gate.all`:
  ```yaml
  - "vol_ratio20 > 1.0"     # this bar's volume above its 20-bar mean (rising)
  ```
  (with `vol_ratio20` declared per §3.2). This is a pure engine-gate add, no Java change.
- **Vehicle A (seam) alternative** if a typed gate is preferred: add `ScalperGates.risingVolume(Chart
  c, BigDecimal prevVolume, BigDecimal ratioFloor)` reading a NEW `Chart.prevVolume` field — but the
  YAML grammar already does it, so **prefer Vehicle B**.
- **PARITY [P]:** NEW `rising-volume` tagged variant (or just the new gate line on a new variant) ⇒
  new golden / new strategy identity; base YAMLs untouched.
- **AUDIT NOTE (pass 1) — §3.7 Vehicle B IS sound (unlike §3.8).** A single ENTRY `gate.all` line
  `"vol_ratio20 > 1.0"` is a SINGLE-LEAF expression inside a STRUCTURED `all` list — the schema's
  `entryRules.gate` `all` array takes one leaf string per item and the conjunction is the `all` node,
  not inline `and`. `vol_ratio20` is a declared indicator alias (not a builtin) but `GateEvaluator.operand`
  falls through to `bank.valueAt(alias,...)` for non-builtins, so it resolves. This compiles + validates.
  The §3.8 failure is specific to wanting a CONJUNCTION inside ONE `signal_exit` rule string — entry
  `gate.all` does not have that problem.

### 3.8 `vwap-break-volume-qualified` — VWAP-break exit only with volume [P]
Today `signal_exit {rule:"close < vwap"}` exits on ANY VWAP break (`ExitEvaluator.signalExit`
L330-338 evaluates the rule string as a gate expression). The doc: break WITH volume = real exit;
WITHOUT volume = fake, don't chase.

> **AUDIT CORRECTION (pass 1) — the conjunction is NOT expressible in the closed grammar; "no Java
> change" is WRONG.** A `signal_exit` rule string of `"close < vwap and volume > 125000"` is rejected
> at TWO layers:
> 1. **JSON-Schema validation at save/publish** — `strategy-schema-v1.json` L437-440 pins
>    `signal_exit.params.rule` to a `pattern` that allows ONLY a single `crossover()/crossunder()`
>    call OR a single `<operand> <comparator> <operand|number>` expression. There is NO `and`/`or`
>    alternation in the regex, so the conjunction fails schema validation before it reaches the engine.
> 2. **`StrategyCompiler.compileLeafText` (L155-173)** — a single-leaf compiler: it matches the
>    `CROSS_CALL` regex OR the single-comparison `EXPRESSION` regex (`^...$`), else THROWS
>    `IllegalArgumentException("expression outside the closed grammar: ...")`. The `and` conjunction
>    lives ONLY at the structured JSON tree level (`compileGate` reads `{all:[...]}` nodes), and
>    `signalExit` calls `compileLeafText(text)` on the raw string — it never sees a structured node.
>
> Two `signal_exit` rules do NOT express the conjunction either: within a type, `ExitEvaluator.evaluate`
> fires on the FIRST matching rule (an OR), so `[close<vwap, volume>125000]` exits on EITHER, the
> opposite of the wanted AND.

**Corrected approach (pick one, all are real work — this is NOT a free YAML edit):**
- **Option A — typed seam/engine primitive (recommended).** Add a volume-qualified VWAP-break exit
  as a typed rule the seam (or a small `ExitEvaluator` extension) evaluates as a conjunction. This is a
  Vehicle-A change (`ScalperGates`/engine), tag-gated + default-OFF, NEW variant.
- **Option B — extend the engine grammar to accept a structured `all`-node in `signal_exit`.** Teach
  `signalExit` to accept a structured rule (`compileGate` instead of `compileLeafText`) AND widen the
  schema `signal_exit.params.rule` to permit an object form. This is an engine + schema change (a
  springdoc/contract surface change too) — heavier, and itself parity-sensitive (it must not alter any
  existing single-leaf `signal_exit` — confirm `GoldenDeterminismTest`'s `exit-intrabar`/other vectors
  use only single-leaf rules; they do, so a strictly-additive object form stays byte-identical).
- **Option C — single-leaf volume-only exit (lossy but legal today).** A NEW variant whose exit is the
  single legal leaf `volume > 125000` (or `vol_ratio20 > 1.0`) — this exits on a volume spike alone, NOT
  "VWAP break WITH volume". It is doc-INFAITHFUL (drops the VWAP-break leg) so it is a fallback only,
  not the intended rule. **Do not ship C as the faithful gap-closer.**
- **PARITY [P]:** whichever option, a NEW `vwap-vol-exit` tagged variant + a NEW seam/grammar test; the
  shipped YAMLs keep `close < vwap` and stay byte-identical. The `volume` builtin DOES exist
  (`BarValues.isBuiltin` L21-23 recognises `close`/`volume`/`vwap`), so a single-leaf `volume > <floor>`
  compiles — it is only the CONJUNCTION that the grammar/schema reject.
- **Effort:** re-rate this package **S→M** (Option A) or **M** (Option B), NOT the "S–M, YAML-only" of
  the original §7 table. PR-3 must carry Java/engine work.

### 3.9 `volume-floor-per-index` — re-key the floor to the traded index [P]
`ScalperGates.volume` is called with `cfg.signalIndex()` (L161) — for a SENSEX-options variant whose
`signal_underlying` is `NIFTY-FUT-CONT`, this gates on **NIFTY 125k**, not a SENSEX 50k floor, and on
the FUTURE bar's volume, not the traded option/crossover candle (golden-crossover disposition L20/L51).
- **The decoupling subtlety (CLAUDE.md ADR-0003).** The volume floor is *deliberately* keyed off the
  SIGNAL future's index today (so the NIFTY 125k floor applies to SENSEX variants that signal on the
  NIFTY future — `scalp-connect-the-dots-nifty.yaml` comment L159 in connect-the-dots.md confirms this
  is intentional for the signal-spine). The gap is the doc-faithful reading that the floor should key
  off the **option-execution root** (the traded index) for the *breakout-candle* check.
- **Change (tag-gated, so the default stays the signal-index behaviour):**
  - `ScalperConfig.requireTradedIndexVolumeFloor` + `tags.contains("traded-index-volume")`.
  - In `evaluate` L161, when the flag is set, key the floor off `cfg.oiIndex()`/`cfg.underlying()`
    (the traded index) instead of `cfg.signalIndex()`:
    ```java
    String volIndex = cfg.requireTradedIndexVolumeFloor() ? cfg.underlying() : cfg.signalIndex();
    if (!ScalperGates.volume(volIndex, chart.volume()).pass() || !rsiOk) return Optional.empty();
    ```
  - (The "crossover-candle's own volume vs the future bar's volume" half is a golden-crossover-specific
    refinement — record it as a sub-item; the index re-keying is the load-bearing fix.)
- **PARITY [P]:** default-OFF tag ⇒ the shipped SENSEX variants keep the NIFTY-floor behaviour
  (byte-identical); only a NEW `traded-index-volume` variant gates on the SENSEX 50k floor. Seam test:
  a SENSEX-underlying CFG with the flag passes at 60k volume (≥ 50k) where the base CFG would block
  (< 125k), and the base CFG is unaffected.

### 3.10 `two-candle-volume-substitution` — 1st+3rd fallback for a light 2nd candle [P]
`TwoCandleGate.detect` (L52) hard-requires BOTH 1st AND 2nd candle ≥ floor; the §3.1 S21(a)
relaxation (a light 2nd candle may be substituted by the 1st+3rd candle making the floor jointly) is
not encoded.
- **`TwoCandleGate`:** add a NEW overload `detect(future, entryIndex, side, underlying, boolean
  allowSubstitution)`. When `allowSubstitution` and the 2nd candle is below the floor, accept if the
  **3rd (deploy) candle** clears the floor and is the side's colour (the S21(a) "1st+3rd substitute
  for a weak 2nd"). The existing `detect(future, entryIndex, side, underlying)` (the ONLY current
  overload — there is no 2-arg form; PASS-2 correction) keeps the strict BOTH-≥-floor behaviour as the
  default for every shipped variant. NOTE (pass 2): today's `detect` never inspects the 3rd (deploy)
  candle at all — it checks only the 1st+2nd colour/floor and the 2nd's strong body — so reading
  `future.candle(entryIndex)` for the substitution leg is a genuinely new read (the data is in scope;
  no new bank wiring), not a re-use of an existing check.
- **`ScalperConfig.requireTwoCandleSubstitution`** + `tags.contains("two-candle-substitution")`; the
  seam passes the flag into `TwoCandleGate.detect`. (This only matters when `requireTwoCandle` is
  also set, so it is naturally a refinement of the two-candle family.)
- **PARITY [P]:** default-OFF; the strict path is unchanged ⇒ byte-identical. A NEW
  `two-candle-substitution` variant accepts a strictly WIDER formation set ⇒ new golden / new identity.
  Unit test on `TwoCandleGate` directly (strict blocks a light-2nd; substitution accepts it when 1st+3rd qualify).

### 3.11 `pct-price-move-gate` — >1% intraday price-move alternative entry [P] (index-level only)
Market-Movers §3.3: an alternative entry on a considerable ΔOI AND a >1% intraday price change. The
**index-level >1%-move** half is buildable today (a session-open-to-now percent move off the future
series); the ΔOI leg + per-stock series are gated on the equity universe (§6).
- **`ScalperGates`:** add `pctPriceMove(EngineSeries future, int index, BigDecimal floorPct,
  OptionType side)` — compute `(close - sessionOpen) / sessionOpen * 100` (session-open via
  `future.candle(future.sessionStart(index)).open()`) and PASS when the move is ≥ `floorPct` in the
  side's direction (CE: `>= +1%`; PE: `<= -1%`). Pure + deterministic.
- **`ScalperConfig.requirePctPriceMove`** + `tags.contains("pct-price-move")`; floor on `ScalperOiProps`
  (`pctPriceMoveFloor`, default `1.0`). Early-return in `evaluate` after the side decision (needs
  `future`+`index`+`side` — all in scope).
- **PARITY [P]:** default-OFF tag, NEW variant + seam-test triple; base YAMLs untouched ⇒ byte-identical.
- **Reuse note:** ship it as a generic index-level primitive so the `equity-fno-universe-screener`
  stream later re-points it per-stock (the same gate fn, a per-stock series) — avoids a rewrite.

---

## 4. PARITY classification (summary table)

Every change is either **[S]** (no emitted-signal change today) or **[P]** (alters emission ⇒ NEW
default-OFF tag + NEW golden variant / seam-test triple; the 36 shipped YAMLs + 5 engine goldens stay
byte-identical because no shipped/golden YAML carries the new tag — the FU2 result).

| Package | Class | New tag (default-OFF) | Golden/parity plan |
|---|:--:|---|---|
| `indicator-param-pinning` | **[S]** | — (no tag) | Pinned values == engine defaults ⇒ byte-identical; load-test + a value-equality unit assert. NO new golden. |
| `volume-ma-indicator` (declare-only) | **[S]** | — (declared indicator, unscored) | Not read by the scorer ⇒ denominator unchanged ⇒ emitted signals identical. NO new golden. |
| `volume-ma-indicator` (scored dot) | **[P]** | (deferred — Open Point) | Would change scorer denominator 19.6→20.6; needs a deliberate scorer-extension PR + re-baseline. OUT of this stream. |
| `multi-timeframe-supertrend` (15m dot) | **[P]** | `supertrend-15m` | NEW variant YAML + seam triple (LIVE-only gate; no scalper golden exists). Base byte-identical. |
| `multi-timeframe-supertrend` (PSAR-distance) | **[P]** | `psar-durability` | NEW variant + seam triple. |
| `two-candle-pattern-arming` | **[P]** | `two-candle-pattern` (existing tag, newly armed on a NEW variant) | NEW connect-the-dots variant; base untouched. Seam test + load-test tag assertion. |
| `pullback-entry-trigger` | **[P]** | `pullback-entry` | NEW variant + seam triple. |
| `trendline-break-detector` | **[P]** | `trendline-break` | NEW trend-change variant + `TrendlineBreak` unit tests + seam test. |
| `rising-volume-confirm` | **[P]** | `rising-volume` | NEW variant (YAML gate line); base keeps the bare floor. |
| `vwap-break-volume-qualified` | **[P]** | `vwap-vol-exit` | NEW variant. **NOT YAML-only (pass 1):** the conjunction `close<vwap AND volume>floor` is rejected by both the `signal_exit.rule` schema pattern AND `compileLeafText`; needs a typed seam primitive or an engine+schema grammar extension. Base keeps `close < vwap`. |
| `volume-floor-per-index` | **[P]** | `traded-index-volume` | NEW SENSEX variant gating on the 50k floor; base keeps the signal-index floor. Seam test. |
| `two-candle-volume-substitution` | **[P]** | `two-candle-substitution` | `TwoCandleGate` overload; NEW variant; strict path unchanged. Unit test on the gate. |
| `pct-price-move-gate` | **[P]** | `pct-price-move` | NEW variant + seam triple. |

**The byte-identity invariant (must hold after every PR):** `GoldenDeterminismTest` +
`BacktestParityTest` re-run green WITHOUT regenerating any fixture; `ScalperStrategyLoadTest` asserts
every NEW `requireXxx` flag is OFF for all 36 shipped YAMLs.

---

## 5. Tests

### 5.1 Unit — gate functions (`ScalperGatesTest.java`)
Add one focused test per new pure gate fn (mirroring `indicatorAlignmentNeedsAllOnTheCorrectSide`):
- `supertrend15mAlignPassesOnSideBlocksAgainstAndPassesOnUnknown` (dir 0 ⇒ pass).
- `psarDurablePassesWhenDistanceAtOrAboveFloorFailOpenOnNull`.
- `pullbackProximityPassesNearALevelBlocksWhenChasingAndOnNullOperand`.
- `risingVolume…` (only if Vehicle A is chosen; else skip — Vehicle B is engine-grammar tested).
- `tradedIndexVolumeFloorUsesSensex50kVsNifty125k`.
- `pctPriceMoveOnePercentInSideDirection`.

### 5.2 Unit — engine primitives
- **`TrendlineBreakTest.java`** (`services/strategy-signal-service` test, sibling of
  `MarketStructureTest`): a descending two-swing-high line broken to the upside fires CE with the
  line value as the anchor; an un-broken line does not; PE mirror; session-bounded (a pivot from a
  prior session is ignored).
- **`TwoCandleGateTest.java`** (extend): the strict path blocks a light-2nd-candle formation; the
  substitution overload accepts it when the 1st+3rd clear the floor (and still blocks when they don't).
- **`SessionIndicatorsTest`** / `IndicatorRegistryTest`: if a new `VOLUME_SMA` factory is added
  (Option b of §3.2), a trailing-mean assertion. (Skipped if `VOLUME_RATIO` is reused.)

### 5.3 Unit — seam wiring (`ScalperConfluenceGateTest.java`)
The constructor-arity change forces updating **all 8** existing `new ScalperConfig(...)` literals
(append the new booleans positionally, `false` for every existing literal — none arm the new gates).
For EACH new `[P]` gate add a CFG literal + the #5 triple (pass / block / non-Xxx-unaffected):
- `supertrend-15m`: a bank with `supertrend15m=-1` blocks CE; base CFG fires anyway.
- `psar-durability`: a chart with `psar` within the floor distance blocks; base CFG fires.
- `two-candle-pattern` (arming): the new variant blocks without the 2-candle formation; base fires.
- `pullback-entry`: a chart far from every level blocks (chasing); near a level fires; base fires.
- `pct-price-move`: a future with a <1% session move blocks; ≥1% fires; base fires.
- `traded-index-volume`: a SENSEX CFG fires at 60k (≥ 50k) where it would block on the 125k signal floor.
- `trendline-break`: a trend-change CFG with the tag fires on a trendline break that is NOT a fractal
  pivot break (and the pivot-only base trend-change CFG does not).

### 5.4 Load test (`ScalperStrategyLoadTest.java`)
- Add an OFF-assertion for EVERY new `requireXxx` flag, asserted across all seeded strategies
  (the regression tripwire that no shipped YAML silently arms a new gate) — EXCEPT for the
  deliberately-armed NEW variants, which assert ON for their own id (mirroring the existing
  `requireCallPutDeltaFilter`/`requireStraddle` per-family assertions, verified `ScalperStrategyLoadTest`
  L149-159). Confirm the new variant YAMLs parse + seed.
- **AUDIT-ADDED (pass 1) — schema validation of the new variant YAMLs.** Every NEW variant's added
  entry `gate.all` line / `signal_exit` rule / `vol_ratio20` indicator must pass `strategy-schema/v1`
  validation at seed/publish (the seeder validates against the JSON Schema). For the `vwap-vol-exit`
  variant specifically, a single-leaf `signal_exit` rule validates but the CONJUNCTION does not (§3.8) —
  so if Option B (grammar extension) is chosen, the schema change ships in the SAME PR and a
  `ContractCaptureTest` re-capture may be needed (the `/v3/api-docs` spec does not enumerate the
  rule-string regex, but the strategy-schema resource is validated separately — confirm whether a
  schema-resource test guards the `signal_exit.rule` pattern before widening it).

### 5.5 Golden / parity tripwires (MUST stay byte-identical — do NOT regenerate)
- `GoldenDeterminismTest` (`libs/strategy-engine`) — FEATURES carry no scalper ⇒ change invisible.
  Re-run; assert byte-identical green.
- `BacktestParityTest` (`services/backtest-service`) — same. Re-run; assert the three byte-match
  asserts stay green.
- The `indicator-param-pinning` change is the one to watch: a value-equality unit assert (PSAR
  before == after) proves the no-op, AND the two harnesses staying green proves the engine output is
  untouched (no golden carries the pinned YAML anyway).

### 5.6 e2e
No scalper-specific e2e exists; the shipped variants are default-OFF unchanged, so `signals.spec.ts`
must stay green as a regression check. If a NEW variant is published (not required by this stream —
the variants can ship as drafts), add a smoke assertion that the confluence-chip row renders for the
new variant's signal. Do NOT add e2e for default-OFF infra.

---

## 6. Dependencies & sequencing

1. **`indicator-param-pinning` first (free, [S]).** Unblocks the optimizer sweeping PSAR params and
   is a zero-risk no-op; land it as a tiny standalone PR.
2. **`volume-ma-indicator` (declare-only) second ([S]).** Declaring `VOLUME_RATIO`/`vol_ratio20` in
   the Market-Movers YAML is a precondition for `rising-volume-confirm` (Vehicle B uses
   `vol_ratio20`) and for the volume-qualified exit's fallback form.
3. **15m-ST + PSAR-distance + pullback + pct-move gates ([P]).** All four are the same shape (new
   Chart field optional + new pure gate + new flag + new tag + early-return); batch them. They need
   only `chart`/`future`/`side` (in scope at the side decision) — no feed wiring.
4. **`vwap-break-volume-qualified` + `rising-volume-confirm` ([P]).** `rising-volume-confirm` is
   YAML-only (a single-leaf entry `gate.all` line). **`vwap-break-volume-qualified` is NOT YAML-only**
   (pass-1 correction §3.8): the closed grammar + JSON-Schema reject a conjunction inside a `signal_exit`
   rule string, so it needs a typed seam primitive (Option A) or an engine+schema grammar extension
   (Option B). Both depend on §2 (`vol_ratio20`) only for the volume-qualifier's robust form. Sequence
   `rising-volume-confirm` first (free), then the typed `vwap-vol-exit` primitive.
5. **`two-candle-pattern-arming` + `two-candle-volume-substitution` ([P]).** Arming is a YAML token;
   substitution is the `TwoCandleGate` overload. Ship together (both touch the two-candle family).
6. **`trendline-break-detector` ([P], effort M).** The one new geometric primitive — its own PR after
   the cheaper wins, reusing `MarketStructure`'s fractal predicate.
7. **`volume-floor-per-index` ([P]).** Independent; a small SENSEX-variant re-keying.

**Cross-stream gates:**
- **No feed wiring is needed for any package in this stream** — unlike the OI/VIX/IV streams, every
  datum (close/vwap/vwma/psar/supertrend-multi-TF/volume/volume-ratio/session-open) is already on the
  engine `BarValues`/`EngineSeries` the seam reads. This stream is therefore independent of the
  `directional-vix-gate` / `intraday-positional-oi` feed work.
- **`pct-price-move-gate`'s ΔOI leg + per-stock form** are gated on `equity-fno-universe-screener`
  (the Market-Movers equity capture). Only the index-level >1%-move primitive ships here; record the
  per-stock re-key as a follow-up owned by the equity stream.
- **SPAN does not gate anything here** (no sell legs in this stream).

---

## 7. Effort + suggested PR breakdown

| PR | Packages | Effort |
|---|---|:--:|
| **PR-1** `chore(strategy-signal): pin PSAR params + declare Volume-MA` | `indicator-param-pinning`, `volume-ma-indicator` (declare-only) | **S** |
| **PR-2** `feat(strategy-signal): 15m-ST / PSAR-durability / pullback / pct-move chart gates (tag-gated, default-off)` | `multi-timeframe-supertrend` (i)+(iii), `pullback-entry-trigger`, `pct-price-move-gate` | **M** |
| **PR-3** `feat(strategy-signal): volume-qualified VWAP-break exit + rising-volume confirm (new variants)` | `vwap-break-volume-qualified`, `rising-volume-confirm` | **M** (re-rated pass 1 — `vwap-break-volume-qualified` is NOT YAML-only: the closed grammar + schema reject a conjunction in a `signal_exit` rule, so it needs a typed seam primitive or an engine+schema grammar extension; only `rising-volume-confirm`'s entry `gate.all` line is YAML-only) |
| **PR-4** `feat(strategy-signal): two-candle arming + 1st/3rd volume substitution` | `two-candle-pattern-arming`, `two-candle-volume-substitution` | **M** |
| **PR-5** `feat(strategy-signal): trendline-break alternative trigger for trend-change` | `trendline-break-detector` | **M** |
| **PR-6** `feat(strategy-signal): traded-index volume floor (tag-gated)` | `volume-floor-per-index` | **S** |

Each PR: short-lived `feat/|chore/` branch, Conventional Commit scoped `strategy-signal` (or
`strategy-engine` for any registry change), squash-merge, single final PR. Build with the full
reactor + `-am` (`-pl services/strategy-signal-service -am verify`). Every PR re-runs the two
golden/parity tripwires (§5.5) and the load-test OFF-assertions (§5.4). **Overall stream effort: M**
(one new geometric primitive in PR-5; the rest are the proven FU2 tag-gate shape).

---

## 8. Open Points

1. **`volume-ma-indicator` as a scored dot vs declare-only.** Option (a) declare-only is `[S]` and
   recommended here; option (b) a 19th scored dot is `[P]` and changes the scorer denominator
   19.6→20.6 for every bar (a live re-baseline). **Recommended default: ship (a); defer (b)** to a
   deliberate scorer-extension PR the owner opts into (same posture as FU2's Dow-dot open point).

2. **15m-vs-60m OI separation (session-additions L25) ownership.** The OI-page `/options/trending`
   5–15m-vs-60m bucketing is an `intraday-positional-oi` / `trending-oi-window-fidelity` concern, not
   a chart-indicator one. **Recommended: scope only the 15m-ST-vs-60m-ST agreement to THIS stream**
   (the `supertrend-15m` dot) and leave the OI-window half to the OI stream — avoids double-counting
   the gap. Owner to confirm the split.

3. **`rising-volume-confirm` / `vwap-break-volume-qualified` — exit/gate grammar. RESOLVED (pass 1).**
   - `volume` IS a recognised builtin (`BarValues.isBuiltin` L21-23: `close`/`volume`/`vwap`), so a
     SINGLE-LEAF `volume > 125000` compiles + validates in both an entry `gate.all` line AND a
     `signal_exit` rule string.
   - **But `and` is NOT in the grammar.** `StrategyCompiler.compileLeafText` (L155-173) matches ONLY a
     single `crossover()/crossunder()` call OR a single comparison; the `signal_exit.rule` JSON-Schema
     `pattern` (`strategy-schema-v1.json` L437-440) likewise forbids any `and`/`or`. So
     `"close < vwap and volume > 125000"` FAILS at schema-validation AND at compile — the original §3.8
     plan is unbuildable as written. See the §3.8 audit-correction block: `vwap-break-volume-qualified`
     needs a typed seam primitive or an engine+schema grammar extension, NOT a YAML-only edit.
   - `rising-volume-confirm` (a single entry `gate.all` line `vol_ratio20 > 1.0`) is unaffected — it is
     a single leaf inside the structured `all` node, which IS the conjunction. Ship it YAML-only.

4. **`volume-floor-per-index` — re-key by `underlying` vs a per-side option-volume read.** The doc
   wants the *traded index's* floor (50k for SENSEX) AND ideally the *option/crossover candle's* own
   volume, not the future bar's. The index re-key is buildable now (tag-gated); the per-strike option
   volume needs the live chain volume in the seam (a bigger read). **Recommended: ship the index
   re-key in this stream; record the per-strike-option-volume read as a follow-up** (it overlaps the
   `strike-premium-band-backtest` / chain-volume work). Owner to confirm that the SENSEX 50k floor (vs
   the deliberate NIFTY-signal-floor of ADR-0003) is the intended faithful reading.

5. **`trendline-break-detector` — line definition (2-pivot vs regression).** A two-most-recent-pivot
   line is the simplest faithful reading of the doc's diagonal trendline; a least-squares fit over N
   pivots is more robust but less doc-literal and harder to make deterministic-stable. **Recommended
   default: the 2-pivot extrapolated line** (deterministic, reuses the fractal predicate); revisit a
   regression fit only if the 2-pivot line proves noisy on live. Also: the "held prior-day trendline
   pivot" cue stays a manual check (KEEP_MANUAL in the disposition), not part of this primitive.

6. **`pullback-entry-trigger` — which levels + tolerance.** The doc names ST/VWAP/VWMA as the
   pullback anchors. **Recommended: proximity to ANY of the three within a DB-tunable `pullbackTolerancePct`
   (default 0.10%)** on the correct side. Owner to confirm whether "pullback" should also require the
   prior bar to have been further from the level (a true retrace) vs merely "near a level now" — the
   v1 default is the simpler "near a level now, side correct". Recorded for sign-off.

7. **`psar-durability` / `pct-price-move` thresholds.** `psarDistanceFloorPct` and `pctPriceMoveFloor`
   are placeholders on `ScalperOiProps` (defaults `0.05`% and `1.0%`). Per the MEMORY scalper-tuning
   finding, these are **tune-on-live, not backtest** knobs — ship conservative defaults and a YAML/DB
   override; do NOT calibrate them on a (muted-OI) historical backtest.

8. **Whether to PUBLISH any new variant (vs ship as a draft).** Every `[P]` package adds a NEW
   tagged YAML variant. **Recommended default: seed them as DRAFTS** (so they exist for forward-paper
   A/B but do not change the published set), consistent with "tune on live". Publishing is an
   owner-driven follow-up per variant (mirrors the FU2 PR-3 posture). This keeps every PR in this
   stream behaviourally inert on the shipped/published surface until the owner opts a variant in.

9. **`vwap-break-volume-qualified` delivery vehicle (pass-1 escalation).** The faithful "VWAP break
   WITH volume" exit needs a CONJUNCTION that the closed `signal_exit` grammar + JSON-Schema both
   reject (§3.8). Owner to pick: **Option A** typed seam primitive (smaller, scalper-local) vs **Option
   B** engine+schema grammar extension (reusable across all strategies, but a schema/contract surface
   change). Recommended default: **Option A** for this stream (keeps the engine grammar frozen); revisit
   B if other streams also want conjunction exits.

10. **`pctPriceMove` session-open source.** §3.11 computes the move off
    `future.candle(future.sessionStart(index)).open()`. Confirm the `*-FUT-CONT` continuous-future
    series exposes a clean per-day `sessionStart` open at the live bar (the stitched continuous series'
    roll-day boundary could place a synthetic open). If the continuous-future open is unreliable at a
    roll boundary, fall back to the live front-future's own session open. Low-risk (the gate fail-opens
    on a null open) but verify the open is the index's true 09:15 open, not a stitched artifact.

---

## Audit pass 1 findings

**Verdict: sound-with-open-points.** The plan's core architecture is correct and parity-safe: the
FU2 tag-gated / default-OFF / early-return shape is faithfully applied; the parity firewall reasoning
holds (both `GoldenDeterminismTest.FEATURES` and `BacktestParityTest.FEATURES` are the SAME 5
pure-engine vectors `{ema-crossover, optional-indicator-activation, btst-preclose, exit-intrabar,
context-series}` — NO scalper — so no tag-gated scalper change can perturb a golden; re-confirmed
byte-for-byte). One package (`vwap-break-volume-qualified`) was mis-scoped as YAML-only and is
corrected; several citation line-numbers were stale and are fixed; two record-constructor fan-outs
were undocumented and are added.

### Citations — verified CORRECT (opened, line-checked)
- `ScalperConfluenceGate`: side decision L149-152 ✓, §0B rails L157-163 ✓, `#5 oi-cross-filter` L196-199 ✓
  (the canonical hard-pre-gate template), `chart(...)` L304-316 ✓, `bias60m` L318-321 ✓, `structuralStop`
  L288-302 ✓, LIVE-only class javadoc L21-33 ✓.
- `ConnectTheDotsScorer`: **18 soft dots, denominator Σ=19.6** ✓ (counted: vwap 2.5 + futures_oi 1.5 +
  iv_rank 0.8 + iv_pair 0.8 + 14×1.0 = 19.6); validity L114-115 ✓; `supertrend` reads `c.supertrendDir()`,
  `psar` reads the level (no distance), `volume` reads the floor (no MA) — all ✓.
- `ScalperGateContext.Chart` record = 7 fields `(close,vwap,vwma20,psar,supertrendDir:int,rsi14,volume)` ✓.
- `ScalperGates.volume` `VOL_FLOOR=Map.of("NIFTY 50",125000)` default 50000, L27-30/L63-68 ✓;
  `indicatorAlignment` L101-118 ✓.
- `TwoCandleGate.detect` requires BOTH 1st+2nd ≥ floor (L52) + strong 2nd body (L55); no 1st+3rd
  fallback ✓.
- `MarketStructure.detect` 3-bar fractal swing-pivot break L41-61 ✓; consumed only by `TrendChangeGate`
  L86 ✓; `isSwingHigh/Low` are PRIVATE (L64/L72) — so the §3.6 "extract to package-visible" is needed ✓.
- `IndicatorRegistry`: SUPERTREND L56-64 (10/3) ✓, VOLUME_RATIO L65-68 (lookback 20) ✓, VWMA L77-80
  (period 20) ✓, PSAR L81-88 (0.02/0.2) ✓; `SessionIndicators.volumeRatio` L172-187 (current vs mean of
  PRIOR lookback, EXCLUDING the current bar) ✓.
- `ScalperConfig.from` tag→flag map: two-candle L119, gap-theory L121, trend-change L123, open-high-low
  L125, opening-tick L128, hero-zero L131, straddle L135, entry-candle-stop L149, oi-cross-filter L153 ✓;
  constructor L154-156 ✓. **8 `new ScalperConfig(...)` literals in `ScalperConfluenceGateTest`** (counted) ✓
  — the arity fan-out is real and positional.
- `ExitEvaluator.signalExit` L330-338 compiles `params.get("rule")` via `compileLeafText` ✓.
- `scalp-connect-the-dots-nifty.yaml` PSAR is bare (`L29 { name: PSAR, alias: psar, timeframe: 3m }`,
  no `params:`) ✓; 36 seeded YAMLs ✓; Market-Movers family already seeded (3 variants) ✓.
- §3.1 PSAR-pinning IS byte-identical: `Params.decimalValue` routes a YAML `0.02` Number through
  `new BigDecimal(n.toString())` → `"0.02"`, equal to the registry default `new BigDecimal("0.02")` (NOT
  the lossy double constructor). Verified — the [S] no-op claim holds.

### Citations — CORRECTED (were stale/wrong; fixed in §1 + §3)
- **§1 gap-source line cites pointed at count/theme lines, not the real disposition rows.** All gap rows
  live in `docs/strategy-audit/disposition/*.md`; re-verified line numbers:
  - `two-candle-volume-substitution`: real row is **two-candle.md L12** (file is only 67 lines) — plan
    said L69/L92-94 (past/near EOF). Fixed.
  - `trendline-break-detector`: real rows **trend-change.md L18 + L34** (file 57 lines) — plan said
    L40/L52. Fixed.
  - `vwap-break-volume-qualified` / `pullback`: real rows **connect-the-dots.md L30 / L32** — plan cited
    L42 (the "## Counts" header). Fixed.
  - GAP-DISPOSITION §3c offsets were off-by-one for param-pinning/two-candle-arming (L161 not L162) and
    pullback (L162 not L163); vwap-break is L165 not L164. Fixed. Doc-§ refs were all correct.
  - Package-name note: the per-page disposition files fold `vwap-break-volume-qualified` and
    `rising-volume-confirm` into the `trade-management-targets-trailing` THEME; the canonical single-gap
    names the plan uses come from the master `GAP-DISPOSITION.md §3c` — both exist; not an error, noted.

### Soundness issues
- **CRITICAL — §3.8 `vwap-break-volume-qualified` is NOT YAML-only (mis-marked as "no Java change").**
  A `signal_exit` rule `"close < vwap and volume > 125000"` is rejected at BOTH (1) JSON-Schema
  validation (`strategy-schema-v1.json` L437-440 pins `signal_exit.params.rule` to a single-leaf/cross
  `pattern`, no `and`) and (2) `StrategyCompiler.compileLeafText` (L155-173, single-leaf compiler — else
  it THROWS). Two `signal_exit` rules don't help (within-type = OR, not AND). Corrected to a typed seam
  primitive (Option A) or an engine+schema grammar extension (Option B); PR-3 re-rated S–M → M, §6 step 4
  + §7 + §4 table + Open Point 9 updated. `rising-volume-confirm`'s single-leaf entry `gate.all` line is
  fine.
- Otherwise the seam wiring is sound: every proposed `requireXxx` flag + `tags.contains(...)` + the
  early-return-after-the-side-decision pattern matches the live `#5 oi-cross-filter` template exactly
  (`chart`/`future`/`side` are all in scope at the side decision); new soft dots would sum in
  `ConnectTheDotsScorer` only if hard-coded into the L73-98 dot list (which is why a scored
  `volume-ma` dot IS `[P]` and a declared-only indicator is `[S]` — confirmed: the dot list is NOT
  YAML-driven).

### Parity
- All [P] packages are correctly behind a NEW default-OFF tag + a NEW variant/seam-test; no [P] is
  mis-marked [S], and no [S] actually moves signals (re-checked `indicator-param-pinning` = registry-default
  no-op; `volume-ma-indicator` declare-only = unscored indicator, denominator unchanged).
- `volume-ma-indicator` declare-only [S] now notes the seeded-YAML indicator-count change (load-test
  tripwire), and the scored-dot [P] denominator shift 19.6→20.6 is correctly deferred.
- `GoldenDeterminismTest` + `BacktestParityTest` stay byte-identical for every PR (no scalper in either
  FEATURES array) — invariant re-confirmed.

### Completeness gaps added
- **Record-constructor fan-outs** (new §3.3 audit block): 17 `new Chart(...)` call sites (1 prod + 16
  test) must be updated for every new `Chart` field; `ScalperOiProps` is an 11-field record (component +
  `DEFAULT_*` const + compact-ctor line + `defaults()` null + test literals) for every new tunable. Batch
  both in PR-2. `ScalperGates` gains an `EngineSeries` import for §3.11 `pctPriceMove`.
- **Schema validation of new variant YAMLs** added to §5.4 (the seeder validates against
  `strategy-schema/v1`; the `vwap-vol-exit` grammar widening, if Option B, ships its schema change +
  possible contract re-capture in the same PR).
- `pctPriceMove` continuous-future session-open reliability added as Open Point 10.

### Dependency sequencing — verified
- "No feed wiring needed" is correct: every datum (close/vwap/vwma/psar/multi-TF ST via a declared
  alias/volume/volume-ratio/session-open) is on the engine `BarValues`/`EngineSeries` the seam reads;
  multi-TF is native (`bias60m` already proves a 1h ST alias works, so a 15m alias is just another
  `indicators:` entry). SPAN gates nothing here (no sell legs). The `pct-price-move` ΔOI/per-stock leg
  is correctly gated on `equity-fno-universe-screener`. Sequencing (param-pin → declare vol-MA → batch
  chart-gates → volume-exit/rising-volume → two-candle → trendline → volume-floor) is sound, with the
  one correction that PR-3 is no longer pure-YAML.

---

## Audit pass 2 findings

**Verdict: sound-with-open-points.** An independent re-verification (not trusting pass 1) re-opened
the working tree for every load-bearing citation and the parity firewall end-to-end. The plan's
architecture is correct and parity-safe; pass-1's headline correction (§3.8 `vwap-break-volume-qualified`
is NOT YAML-only) is itself verified correct against the actual schema pattern + compiler; pass-1's
citation fixes are accurate and introduced no new error. Two real issues both passes missed are
corrected in place (an unmerged-FU2 placement reference, and a non-existent `detect` overload). None is
a blocker — all are placement/wording precision, not parity or buildability defects.

### Re-verified INDEPENDENTLY (opened the source, not trusting pass 1)
- **Parity firewall — byte-confirmed.** `GoldenDeterminismTest.FEATURES` (L33-36) and
  `BacktestParityTest.FEATURES` (L35-38) are the IDENTICAL 5-vector array `{ema-crossover,
  optional-indicator-activation, btst-preclose, exit-intrabar, context-series}` — NO scalper in either.
  Neither harness instantiates `ScalperConfluenceGate`/`ConnectTheDotsScorer`. A tag-gated scalper gate
  therefore cannot perturb a golden. ✓
- **The §3.8 CRITICAL correction is RIGHT.** `strategy-schema-v1.json` `signal_exit.params.rule`
  `pattern` (L437-440) is a single anchored alternation: one `crossover|crossunder(...)` OR one
  `<operand> <cmp> <operand|number>` — **no `and`/`or`**. `StrategyCompiler.compileLeafText` (L155-173)
  matches only `CROSS_CALL` or `EXPRESSION` (L22-24, single comparison) else THROWS (L164). So a
  conjunction `signal_exit` rule fails at BOTH layers — pass-1's "needs a typed seam primitive or an
  engine+schema grammar extension" is correct, and §3.7's single-leaf `vol_ratio20 > 1.0` entry
  `gate.all` line DOES compile (`GateEvaluator.operand` L115-117 falls through to `bank.valueAt(alias)`
  for non-builtins; `vol_ratio20` matches the EXPRESSION regex). ✓
- **§3.1 PSAR-pinning [S] no-op confirmed at the bytecode level.** `IndicatorRegistry` PSAR defaults are
  `new BigDecimal("0.02")`/`new BigDecimal("0.2")` (L87-88, string ctors). `Params.decimalValue` routes a
  YAML number through `new BigDecimal(n.toString())` (L40), NOT the lossy `double` ctor — so a pinned
  `0.02`/`0.2` is `compareTo`-equal to the default ⇒ the compiled indicator is bit-identical ⇒ emission
  unchanged. `scalp-connect-the-dots-nifty.yaml` L29 PSAR is indeed bare (no `params:`); only PSAR is
  bare (VWMA/RSI/ST all pin params). ✓
- **Citations spot-checked against the tree (all exact):** `ScalperConfluenceGate` side decision
  L149-152, §0B rails L157-163 (volume floor keyed off `cfg.signalIndex()` L161), `#5` L196-199,
  `chart()` L304-316, `bias60m` L318-321, `structuralStop` L288-302; `ConnectTheDotsScorer` 18 dots
  Σ=19.6 (re-counted: 2.5+1.5+0.8+0.8+14×1.0), validity L114-115; `ScalperGateContext.Chart` 7 fields
  L21-28; `ScalperGates.volume` L64-68 + `VOL_FLOOR=Map.of("NIFTY 50",125000)` default 50000 L27-30;
  `ScalperConfig` 16-component ctor L36-52, tag→flag map L119-153, canonical `new ScalperConfig(...)`
  L154-156, exactly **8** `new ScalperConfig(...)` test literals (L44/49/56/62/68/74/81/87); exactly
  **17** `new Chart(...)` call sites (1 prod + 4 `ConnectTheDotsScorerTest` + 8 `ScalperConfluenceGateTest`
  + 4 `ScalperGatesTest`); `ScalperOiProps` 11-field record + 11-null `defaults()` (L18-78);
  `MarketStructure.Break(boolean broke, BigDecimal pivot)` L30 (field is `pivot`, consumed by
  `TrendChangeGate` L90; `MarketStructure.detect` called L86), `isSwingHigh/Low` PRIVATE L64/L72;
  `IndicatorRegistry` SUPERTREND L56-64 / VOLUME_RATIO L65-68 / VWMA L77-80 / PSAR L81-88; `ExitEvaluator.signalExit`
  L330-338; `BarValues.isBuiltin` L21-23; `ScalperStrategyLoadTest` per-family flag asserts L150-159
  (`containsAll(SEAM_ALIASES)` L146 — NOT an exact indicator count, so an added `vol_ratio20` is safe);
  Market-Movers family seeded (3 variants). Disposition rows (`two-candle.md` L12, `trend-change.md`
  L18/L20/L34, `connect-the-dots.md` L17/L20/L21/L30/L32, `market-movers.md` L22/L32, `golden-crossover.md`
  L20/L51) and GAP-DISPOSITION §3c offsets (param-pin L161, two-candle-arm L161, pullback L162,
  trendline/rising-volume L164, vwap-break L165, vol-floor L154, two-candle-sub L155, pct-move L170,
  vol-ma L171) — **all confirmed exact.** Pass-1's corrections stand and added no new error.

### NEW issues this pass found (both corrected in place)
1. **`indicatorAlignment` is NOT wired in `evaluate` on the current tree — FU2 is UNMERGED (placement
   references were misleading).** The plan repeatedly tells the executor to group new early-returns
   "with the FU2 indicator-alignment block" (§2.3 L95, §3.3 (i), §3.5). But `git log` on
   `ScalperConfluenceGate.java` ends at #233 (the 2c decoupling); there is no `requireIndicatorAlignment`
   flag and no `indicator-alignment` string anywhere in `src/main` — `ScalperGates.indicatorAlignment`
   (L101-118) is written + unit-tested but **never called** in `evaluate` (the disposition files confirm
   "confirmed NOT called"). FU2 is a sibling PLAN, not merged code. **Impact: LOW — not a blocker.** The
   tag-gated early-return shape this stream copies needs only the LIVE `oi-cross-filter` (#5) precedent
   at L196-199, which IS merged; the new gates are independent of `indicatorAlignment`. **Corrected:**
   §2.3, §3.3, §3.5 now say "placed with the chart-precondition early-returns (next to the FU2 block IF
   it has merged, else after the §0B rails — independent of FU2)." The executor must NOT assume that
   block exists; if FU2 lands first, its flag also consumes one ctor slot, so coordinate the §5.3
   "append positionally" step (FU2's flag + this stream's flags interleave — same fan-out, just more
   slots). No GAP-DISPOSITION dependency is violated: §3c says `[P]` needs "the FU2 default-OFF
   tag-gating" *pattern*, which `oi-cross-filter` already instantiates.
2. **§3.10 cited a non-existent 2-arg `TwoCandleGate.detect`.** The only current overload is
   `detect(future, entryIndex, side, underlying)` (4-arg, L41-59); there is no 2-arg form. **Corrected**
   §3.10 to name the real signature, and ADDED that today's `detect` never inspects the 3rd (deploy)
   candle (it checks only 1st+2nd colour/floor + the 2nd's strong body), so the substitution leg reading
   `future.candle(entryIndex)` is a genuinely NEW read (data in scope; no new bank wiring) — the design
   is feasible but is not a re-use of an existing check.

### Minor / non-blocking (noted, not corrected — they don't change buildability)
- **`ScalperOiProps` test-literal fan-out is small but real.** The pass-1 §3.3 block says every new
  `ScalperOiProps` tunable also touches "every other `new ScalperOiProps(...)` test literal." In fact
  most tests call `ScalperOiProps.defaults()`; only `ScalperOiPropsTest` constructs the record directly
  (4 `ScalperOiProps.` refs). So the per-tunable fan-out is: record component + `DEFAULT_*` const +
  compact-ctor line + the `defaults()` null + the `ScalperOiPropsTest` literals — still a real positional
  fan-out, just fewer sites than "every test." Accurate enough as written; flagged for the executor.
- **`rising-volume-confirm` per-page provenance.** §1 says the per-page row "folds into
  `trade-management-targets-trailing`"; the actual `trend-change.md` L20 row's work-package is literally
  named `rising-volume-confirm` (its own package), while `connect-the-dots.md`/`two-candle.md` fold the
  VWAP-break-with-volume row into the trailing theme. Cosmetic; the canonical single-gap name the plan
  uses (GAP-DISPOSITION L164) is correct.
- **Cross-stream constructor-arity collision (coordination, not a defect).** Eleven sibling backlog plans
  (`vwap-and-sizing`, `iv-fidelity`, `oi-fidelity-gates`, `macro-vix-global-fii`, `rsi-multi-timeframe`,
  …) each baseline "8 `new ScalperConfig(...)` literals" and each appends new flags + `Chart`/`Oi`
  fields positionally. The "8" baseline is correct AS OF the current tree, but whichever stream lands
  second must re-baseline its "append positionally" instructions against the then-current arity. Not an
  error in THIS plan; an owner-level merge-ordering note.
- **§3.11 `pctPriceMove` session-open** is already captured as Open Point 10 (continuous-future roll-day
  open reliability) — re-confirmed `EngineSeries.sessionStart`/`EngineCandle.open()` are both in use in
  the tree (`structuralStop` L298, `TwoCandleGate` L63), so the data-flow is in scope as claimed.

### Parity re-check (every signal-affecting change)
Every `[P]` package is behind a NEW default-OFF tag on a NEW variant + a NEW golden/seam-test; no `[P]`
is mis-marked `[S]`. The two `[S]` items genuinely don't move emission (param-pin = registry-default
no-op, verified at the BigDecimal level; vol-MA declare-only = an unscored indicator, and the scorer
dot list is HARD-CODED L74-98 not YAML-driven, so the 19.6 denominator is untouched). The scored-dot
`volume-ma` option (b) correctly stays deferred (it WOULD shift 19.6→20.6 for every bar). The
byte-identity invariant (both FEATURES arrays green without regen; load-test OFF-asserts) holds for
every PR. **No parity gap found.**

### Readiness verdict
**Implementation-ready, with the two corrections applied and the Open Points (esp. #9 vwap-vol-exit
vehicle, #1 vol-ma scored-dot defer, and the new FU2-ordering note) carried to the owner for sign-off.**
The one thing the executor must internalise beyond the text: do NOT assume the FU2 indicator-alignment
block exists in `evaluate` — anchor new early-returns to the merged `#5 oi-cross-filter` template, and
re-count the `ScalperConfig`/`Chart` arity against the live tree at PR time (other streams may have
landed first).
