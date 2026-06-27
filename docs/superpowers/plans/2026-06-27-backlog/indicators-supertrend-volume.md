# Indicators: multi-TF Supertrend, volume MA, pattern arming, trendline (backlog stream)

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

| Package | gaps | Disposition row(s) — file:line | Doc § | P/S |
|---|---:|---|---|:--:|
| `multi-timeframe-supertrend` | 3 | connect-the-dots.md L17 (ST 15m variant); session-additions.md L25 (15m-vs-60m OI separation), L33 (PSAR-distance durability) | 3.10 §6.10 / 4.14.6 / 4.15.3 | **[P]** |
| `volume-ma-indicator` | 1 | market-movers.md L22; README §3.3 (L184 NEW) | 3.3 | **[P]** (new dot) / **[S]** (declare-only) — see §3.2 |
| `indicator-param-pinning` | 1 | connect-the-dots.md L20 (single-gap pkg, GAP-DISPOSITION L162) | 3.10 Setup 2 | **[S]** |
| `two-candle-pattern-arming` | 1 | connect-the-dots.md L21 (GAP-DISPOSITION L162) | 3.10 Entry 2/4 | **[P]** |
| `pullback-entry-trigger` | 1 | connect-the-dots.md L32 (GAP-DISPOSITION L163) | 3.10 Exit-Scaling / Edge | **[P]** |
| `trendline-break-detector` | 1 | trend-change.md L40,L52 (GAP-DISPOSITION L164, effort M) | 3.12 Setup 3 / Entry b.1 | **[P]** |
| `rising-volume-confirm` | 1 | GAP-DISPOSITION L164 (single-gap) | 3.x volume-confirm | **[P]** |
| `vwap-break-volume-qualified` | 1 | connect-the-dots.md L30,L42; two-candle.md L44,L65; README §3.1 exit (L146 NEW) | 3.10 Exit / 3.1 exit | **[P]** |
| `volume-floor-per-index` | 1 | golden-crossover.md disposition L20,L51 (GAP-DISPOSITION L154) | 3.6 setup-4 | **[P]** |
| `two-candle-volume-substitution` | 1 | two-candle.md L69,L92-94 (GAP-DISPOSITION L155) | 3.1 S21a / 5.1 | **[P]** |
| `pct-price-move-gate` | 1 | market-movers.md L32 (GAP-DISPOSITION L170) | 3.3 Entry Bull/Bear 5 | **[P]** |

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
- `ScalperGates.indicatorAlignment` L102-118 (PSAR+VWMA+ST+VWAP all on side; promoted by FU2).
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
    byte-identical. It rides the side-channel/optimizer only. **No new golden** (new YAML variant if
    Market-Movers is a new family file, else additive on the existing one — confirm whether the
    Market-Movers YAML already exists with a golden; it ships as a paper draft so likely none).
- **Option (b) — scored dot [P] (deferred).** Add a `volumeMa`/`volRatio` field to
  `ScalperGateContext.Chart`, a `chart(...)` read, and a 19th soft dot in the scorer. This changes
  the denominator (19.6 → 20.6) for every bar ⇒ a hard `[P]` re-baseline of any scalper that ever
  rides a golden, and a behavioural shift live. **Recommend deferring (b)** to a dedicated, owner-opted
  scorer-extension PR (same shape as the FU2 "Dow dot" open point) — it is NOT free like (a).

**Recommendation:** ship **(a)** in this stream; record (b) as an Open Point.

### 3.3 `multi-timeframe-supertrend` — 15m ST variant + PSAR-distance durability [P]
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
    grouped with the FU2 indicator-alignment block:
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
- **(iii) PSAR-distance durability (session-additions L33; trend-change L40 "deep PSAR bounce").**
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
  grouped with the indicator-alignment / 15m-ST block.
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
    anchor, mirroring `MarketStructure.Break`).
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
- **Scope note:** the doc's "held prior-day trendline pivot" cue (trend-change L40, KEEP_MANUAL in the
  disposition) stays a manual check — only the intraday session-bounded break is automated here.

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

### 3.8 `vwap-break-volume-qualified` — VWAP-break exit only with volume [P]
Today `signal_exit {rule:"close < vwap"}` exits on ANY VWAP break (`ExitEvaluator.signalExit`
L330-338 evaluates the rule string as a gate expression). The doc: break WITH volume = real exit;
WITHOUT volume = fake, don't chase.
- **Vehicle B (YAML grammar), the clean fit.** A NEW tagged variant changes the exit rule to the
  conjunction:
  ```yaml
  exit_rules:
    - { type: signal_exit, params: { rule: "close < vwap and volume > 125000" } }   # CE variant
    - { type: time_stop,   params: { max_bars: 10 } }
  ```
  (the volume floor mirrors §0B — NIFTY 125k / index 50k; for a SENSEX variant use `50000`). For the
  PE/short side the mirror is `close > vwap and volume > <floor>`.
- **PARITY [P]:** the exit fires on a strictly NARROWER condition ⇒ different trade exits ⇒ a NEW
  golden variant of the affected family; the shipped YAMLs keep `close < vwap` and stay byte-identical.
  No Java change (the grammar + `ExitEvaluator` already support it).
- **Note:** confirm the gate grammar supports `and` + a numeric literal RHS (it does — `GateEvaluator`
  compiles `compileLeafText`; the existing entry gates use `close > vwap`). If `volume` is not a
  recognised builtin in the exit context, fall back to `vol_ratio20 > 1.0` (the declared indicator)
  as the volume qualifier — both express "the break carried volume".

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
  for a weak 2nd"). The existing 2-arg/4-arg `detect` keeps the strict BOTH-≥-floor behaviour
  (the default for every shipped variant).
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
| `vwap-break-volume-qualified` | **[P]** | `vwap-vol-exit` | NEW variant (YAML exit string); base keeps `close < vwap`. |
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
  `requireCallPutDeltaFilter`/`requireStraddle` per-family assertions). Confirm the new variant YAMLs
  parse + seed.

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
4. **`vwap-break-volume-qualified` + `rising-volume-confirm` ([P], Vehicle B).** YAML-only on new
   variants; depend on §2 (the declared `vol_ratio20`) for the volume qualifier's robust form.
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
| **PR-3** `feat(strategy-signal): volume-qualified VWAP-break exit + rising-volume confirm (new variants)` | `vwap-break-volume-qualified`, `rising-volume-confirm` | **S–M** |
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

3. **`rising-volume-confirm` / `vwap-break-volume-qualified` — `volume` builtin in the exit/gate
   grammar.** Verify `GateEvaluator`/`StrategyCompiler.compileLeafText` recognises the `volume`
   builtin and `and` + a numeric literal in an exit-rule string (entry gates use `close > vwap`; the
   exit path compiles the same grammar). **If `volume` is not an exit-context builtin, use the
   declared `vol_ratio20 > 1.0`** as the qualifier (functionally equivalent — "the break carried
   above-average volume"). Recommended default: try the raw `volume` floor first; fall back to
   `vol_ratio20`. Confirm before authoring PR-3.

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
