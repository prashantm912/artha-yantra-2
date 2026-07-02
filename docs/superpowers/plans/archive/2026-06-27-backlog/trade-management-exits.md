# Trade management: targets, trailing, SL alternates, exits

Status: PLAN (implementation-ready). Owner: single-owner. Date: 2026-06-27.
Target modules: `libs/strategy-engine` (the `ExitEvaluator` + golden harness), `libs/strategy-schema`
(the `exit_rules` grammar), `services/strategy-signal-service` (the scalper seam + the 36 YAMLs +
`ScalperConfig`), `services/backtest-service` (`OptionsPremiumReplay` / `PremiumExitEvaluator`).

> Read order for the executor: this plan is self-contained but assumes the CLAUDE.md
> "parity-safe-additive" convention and the **FU2 plan** (`2026-06-27-followup2-soft-dots-to-hard-gates.md`)
> as the load-bearing precedents for every `[P]` change. The `oi-cross-filter` (#5) tag and the FU2
> `indicator-alignment` tag are the exact shapes copied here for a new opt-in default-OFF gate;
> the engine `take_profit`/`trailing_stop` exit types are the precedents copied for the new exit legs.

---

## 1. Goal & the packages/gaps this stream closes

This stream automates **exit / trade-management** discipline: profit targets, trailing stops,
alternate stop-loss anchors, conditional exits, deferred-feature switches and laddered sizing — the
"what happens AFTER entry" half of the Siva method that the engine today under-models (most scalpers
exit only on `close < vwap` signal_exit + a coarse `time_stop`).

| Package | gap count | Source disposition rows (file:line) | Doc-§ |
|---|---:|---|---|
| **`trade-management-targets-trailing`** | 31 | two-candle.md L25-30; open-high-low.md L23-26; gap-theory.md L28-29; trending-oi.md L27,L29; golden-crossover.md L26; hero-zero.md L19,L26; btst-stbt.md L38; morning-trade.md L15,L31-32; connect-the-dots.md L28-30; straddle.md L25,L27; trend-change.md L25; market-movers.md L26; completeness-sweep.md L20-21 | §3.1/3.2/3.4/3.5/3.6/3.9/3.10/3.11/3.12, §5.7, §6.x exit/scaling |
| **`sr-levels-targets-stops`** | 11 | risk-framework.md L16,L50; gap-theory.md L20; open-high-low.md L22; morning-trade.md L16; gates-strike-sr-fiidii.md L23-24; completeness-sweep.md L16-17,L27; session-additions.md L51 | §2.1/2.12, §3.4/4.10/4.11, §5.6/5.7 |
| **`supertrend-level-stop`** | 2 | gap-theory.md L18; golden-crossover.md L25 (+ completeness-sweep.md L17) | §3.4 L619; §3.6/5.6 |
| **`volume-conditional-exit`** | 1 | market-movers.md L30 | §3.3 Edge cases |
| **`oi-confirmed-sl-leeway`** | 1 | trend-change.md L24 | §3.12 Stop-Loss/Risk |
| **`structural-stop-arming`** | 1 | connect-the-dots.md L31 | §3.10 Exit |
| **`profit-slice-sizing`** | 1 | hero-zero.md L27 | §5.7 S24(a); §2.12-58 |
| **`gap-fill-deadline-switch`** | 1 | gap-theory.md L19 | §3.4 L622 (S24) |
| **`scale-in-ladder`** | 1 (+1 ACCEPT) | risk-framework.md L20 | §2.2 r7 / §2.11 r47 |

Stream total: **50 AUTOMATE_PKG gap rows** (`31 + 11 + 2 + 1×6`). Several rows in
`trade-management-targets-trailing` / `sr-levels-targets-stops` overlap on the SAME engine feature (a
take-profit leg, a trailing-stop leg), so the *code surface* is much smaller than 50 — the count is
gaps-closed, not files-touched.

> **⚠ STALE GAP-SOURCE LINE NUMBERS (audit pass 1).** Every `docs/strategy-audit/*.md` line number in
> the table above and in §3.x below is **stale** — the audit `.md` files were expanded with v2/v3 rows
> (look for the inline `[v2 doc-§ fix]` / `v3-added MISS` / "INACCURATE —" annotations), shifting every
> exit/target/SL row ~10-25 lines DOWN from where the plan captured them. The cited line numbers now
> point at the OI/VIX block, not the exit/target/stop rows. The CONTENT→feature mapping is correct;
> only the line numbers are wrong. **Executor: locate gap-source rows by their ROW TEXT (the
> "Target:…" / "Trail…" / "Stop-loss…" / "VWAP…with volume…" wording), not by the line number.** The
> corrected line numbers for the load-bearing rows are in the Audit-pass-1 block at the end; the rest
> are findable by text. Also note `session-additions.md` is really
> `session-additions-and-manual-coverage.md` (its spot-OI-bar S/R row is L57, not L51).

**Key architectural finding (verified, drives the whole design).** The engine `ExitEvaluator` ALREADY
implements `take_profit` (basis `premium_pct`/`atr_multiple`/`r_multiple`) and `trailing_stop`
(`premium_pct` with `activate_at`/`trail_by`, or `atr_multiple`) — `ExitEvaluator.java:180-303`. The
backtest premium-replay path ALSO already consumes `take_profit`/`trailing_stop`/`time_stop` premium-pct
thresholds — `OptionsPremiumReplay.exitRules` (L262-296) → `PremiumExitEvaluator`. **No scalper YAML uses
either** (grep over the 36 files: every `exit_rules` block is `signal_exit` + `time_stop`, plus a
`premium_pct` stop on hero-zero/straddle/btst). So the **largest, lowest-risk slice of this stream is
wiring existing exit types into YAMLs** (each a NEW default-OFF strategy *variant*, never an edit of a
shipped config). The genuinely-new engine work is narrow: a volume-qualified `signal_exit`, an
ATR/structure target source, and the laddered sizing.

---

## 2. Current state (verified by opening each file)

### 2.1 The engine exit model — `libs/strategy-engine/.../eval/ExitEvaluator.java`
- Precedence FIXED (class javadoc L17-22): `stop_loss → trailing_stop → take_profit → time_stop →
  signal_exit`; protective stops win a tie. Evaluated at primary bar close (`evaluate`, L164-193) and
  intrabar at the 1m floor for level exits when `exit_intrabar` is on (`evaluateIntrabarLevels`, L86-121).
- `take_profit` works TODAY: `level(...)` (L195-222) + `levelDistance(...)` (L225-248) handle bases
  `premium_pct` (L236-237), `atr_multiple` (L238-241, ATR-at-ENTRY), `r_multiple` (L242-245, off the
  first non-r-multiple `stop_loss`). **Nothing reads an S/R-level or an absolute-points basis.**
- `trailing_stop` works TODAY: `trailing(...)` (L250-305) — `premium_pct` with optional `activate_at`
  gate (L266-277) + `trail_by`/`value`; `atr_multiple` (L288-303). Peak = `favorableExtreme` (L340-351,
  bar highs/lows since entry). **No fixed-points trail offset and no "5 pt below the gap reference".**
- `signal_exit` (L330-338) compiles the rule string via `StrategyCompiler.compileLeafText` and runs it
  through `GateEvaluator`. **The closed grammar is `alias <op> alias|literal` or `crossover()/
  crossunder()` only** — it CANNOT express "close < vwap AND volume > X" (no conjunction in a leaf).
- `time_stop` (L307-328): `max_bars` (intraday) or `max_holding_days` (btst). No "time-conditional
  MTM-flat" (e.g. "exit at 15:10 if flat") and no "abandon at ~40 min, switch setup".
- `entryLevels(...)` (L50-56) is the PARITY-SAFE side-channel: it returns the first stop_loss / first
  take_profit absolute prices at entry; the golden writer ignores them (see §2.5). **This is the seam a
  new target/stop must ride to stay byte-identical.**

### 2.2 The schema — `libs/strategy-schema/.../strategy-schema-v1.json`
- `exitRule` `oneOf` (L362-446): `stop_loss`/`take_profit` → `levelParams` (L447-456, basis enum
  `premium_pct|atr_multiple|r_multiple`); `trailing_stop` (L382-405, basis enum `premium_pct|
  atr_multiple`, `activate_at`/`trail_by`/`value`); `time_stop` (L406-425, `max_bars` XOR
  `max_holding_days`); `signal_exit` (L426-444, the regex-pinned closed-grammar `rule`).
- `additionalProperties:false` everywhere → **any new exit type, basis, or param requires a schema edit**
  (and the schema is the upstream validator + the springdoc/contract source).
- The optimize-path regex (L715) already whitelists `exit_rules[type=...].params.*` for tuning.

### 2.3 The scalper seam + structural stop — `services/strategy-signal-service/.../scalper/`
- `ScalperConfig.java`: `record ScalperConfig(...)` (L36-52) with per-strategy flags; `from(JsonNode,
  List<String> tags)` (L101-157) maps tags → flags; the `StructuralStop` enum (L55-64) anchors the
  entry-time stop: `TWO_CANDLE_FIRST`, `ENTRY_CANDLE`, `GAP_TREND`, `SWING_BREAK`, `VWAP`,
  `FIRST_CANDLE`, `OPPOSITE_EXTREME`, `NONE`. The chained tag→anchor select is L136-151.
- `ScalperConfluenceGate.java`: `Decision` record carries `structuralStop` (L71-76); the seam computes
  it per-tag (L166, L178, L210, L228, L244) and returns it. **LIVE-only** (class javadoc — the OI/macro
  reads never run on deterministic replay → the parity firewall).
- `ScalperRisk.hasBoundingExit` (L20-24): a scalper MUST carry a `stop_loss` OR `time_stop`. Asserted in
  `SignalEngine` L199.

### 2.4 How the stop becomes a live exit — `services/strategy-signal-service/.../signals/SignalEngine.java`
- At entry (`emitEntry`, L573-604): `stopLoss = levelFromRules(definition, entryPrice, "stop_loss")`
  (the premium_pct level, L809-825) is OVERRIDDEN by `decision.structuralStop()` when present (L587-589);
  `target = levelFromRules(..., "take_profit")` (L590); both persisted on the signal row.
- On the next bars (`evaluateScalper`-style block, L398-425): a touched **structural stop exits FIRST**
  via `structuralStopHit` (L802-807, low≤stop long / high≥stop short), then the engine `ExitEvaluator`
  runs (L414-421). The structural-stop branch is the live-only protective seam.
- **Implication for `take_profit`:** wiring a `take_profit` into a scalper YAML works on BOTH paths —
  `levelFromRules` persists the target live AND `ExitEvaluator.evaluate` fires it bar-close live AND
  `OptionsPremiumReplay` fires it on the premium leg in backtest. Zero new engine code for a premium-pct
  target.

### 2.5 The parity firewall — golden / parity harnesses
- `GoldenSignalsJson.write()` serializes ONLY `timestamp/exchange/tradingsymbol/direction/composite/
  breakdown`; `stopLoss/takeProfit` are a NON-serialized side-channel (the parity-safe-additive
  precedent — CLAUDE.md "Extend engine records parity-safely").
- `TickwiseGoldenRunner` (`golden/TickwiseGoldenRunner.java`) drives the engine exit path: it calls
  `ExitEvaluator.evaluate` (L150, L220), `evaluateIntrabarLevels` (L181), and stamps `entryLevels`
  onto the entry event (L166, L206, L235; the `entryLevels` helper itself is L281-287, not L266-273 —
  audit cite fix). The runner rolls 1m → 3m/5m/15m/1h primaries (`intervalDuration`, L354-363).
  **The golden FEATURES are pure-engine strategy YAMLs (NOT scalper configs)** — the new
  `signal-exit-volume`/`trailing-points`/`trailing-indicator`/`stop-points` fixtures must each be a
  minimal NON-scalper YAML declaring the new exit type on the NIFTY50 1m fixtures, so the harness can
  evaluate them without the live OI/macro seam (which never runs on the replay). For `trailing-indicator`
  the fixture's `alias` must be an indicator the YAML actually declares (e.g. a VWMA the runner can read
  via `valueAt`), since `supertrend` is direction-only (see §3.3).
- `GoldenDeterminismTest.FEATURES` = `{ema-crossover, optional-indicator-activation, btst-preclose,
  exit-intrabar, context-series}` (L33-36) — **no scalper, no new exit type**. `BacktestParityTest`
  mirrors these (FU2 §2.6). A new parity-sensitive exit primitive needs a NEW FEATURES entry + a
  generate-once `expected/<name>.signals.json`; the 5 frozen goldens stay byte-identical.

### 2.6 The 36 scalper YAMLs — `services/.../scalper-strategies/`
- 12 strategies × {nifty, sensex-niftyoi, sensex-sensexoi}. Each `exit_rules` (verified grep):
  - **signal_exit + time_stop only:** two-candle, golden-crossover, gap-theory, trend-change,
    open-high-low, market-movers, morning-trade, trending-oi, connect-the-dots.
  - **premium_pct stop_loss + time_stop (+ signal_exit):** hero-zero (L74-76), straddle (L84-85),
    btst-stbt (L96-97, `max_holding_days:1`).
- Every YAML already carries an `optimize` block tuning `exit_rules[type=time_stop].params.max_bars`.

---

## 3. Design — per package

Legend for the per-package PARITY tag in §4: **[P]** = alters emitted signals (new exit/target changes
which bars EXIT) → behind a NEW opt-in default-OFF strategy tag/variant + a NEW golden variant; **[S]** =
read-only / management-leg / backtest-only / brand-new variant with no existing golden.

### 3.1 `trade-management-targets-trailing` (31) — the core exit toolkit

This package is **four sub-features**, each a thin addition. The 31 gaps map onto them:

**(A) Premium-pct take-profit leg (no engine code — YAML-only variant).** Closes the "% target / 1-2%
RR / point-target" rows (connect-the-dots L28, gap-theory L28, golden-crossover L26, trending-oi L27,
open-high-low L23, market-movers L26, two-candle L28, completeness-sweep L27, risk-framework L17). The
engine + premium-replay already fire `take_profit premium_pct`. **Change:** add a NEW per-strategy
*variant* YAML (e.g. `scalp-connect-the-dots-nifty-tp.yaml`) that appends:

```yaml
exit_rules:
  - { type: signal_exit, params: { rule: "close < vwap" } }
  - { type: take_profit, params: { basis: premium_pct, value: 40 } }   # ~1-2% index ≈ 40% premium scalp target
  - { type: time_stop,   params: { max_bars: 10 } }
```

The doc point-targets (Nifty 50-70 / BN 100-150 pt) are STRUCTURAL on the index, not on the premium leg
— map them to a premium-% band (the StrikePicker delta makes ~50 Nifty pts ≈ 30-45% premium) and tune
via the existing `optimize` block (add `exit_rules[type=take_profit].params.value` to the sweep).
Data flow: YAML → `StrategyCompiler.exitRules` → `ExitEvaluator.level` (live) + `PremiumExitEvaluator`
(backtest). **No code.**

**(B) Premium-pct trailing-stop leg (no engine code — YAML-only variant).** Closes the trailing rows
(open-high-low L24, morning-trade L31 trail-to-breakeven, two-candle L30 PSAR/ST trail, trending-oi L29
RSI-proximity trail, gap-theory L29 "trail 5 pts once in profit"). The engine `trailing_stop premium_pct`
with `activate_at`/`trail_by` already does activate-then-trail. **Change:** a NEW variant appends:

```yaml
  - { type: trailing_stop, params: { basis: premium_pct, activate_at: 15, trail_by: 10 } }  # arm at +15%, trail 10%
```

`activate_at` = the "once in profit / trail-to-breakeven" trigger; `trail_by` = the trail width. The
"trail 5 pts below the gap reference" and "PSAR-then-Supertrend trail" are STRUCTURAL — see (C)/(D).
**No code.**

**(C) Volume-qualified VWAP-break exit (NEW engine code) [P].** Closes the "VWAP break WITH volume =
exit; without = fake, don't chase" rows (connect-the-dots L30, two-candle L29, completeness-sweep
implied; market-movers L30 is the separate `volume-conditional-exit` pkg, §3.4). The closed leaf grammar
cannot express a conjunction. **Change — `ExitEvaluator.signalExit` gains a `min_volume` param:**

```java
// ExitEvaluator.signalExit (extend, L330-338): after the gate passes, require the bar's volume to
// clear an optional floor so a no-volume "fake" VWAP break does NOT exit.
private static Optional<ExitDecision> signalExit(
    IndicatorBank bank, int index, StrategyDefinition.ExitRuleSpec rule) {
  String text = String.valueOf(rule.params().get("rule"));
  GateNode node = StrategyCompiler.compileLeafText(text);
  ScoreBreakdown.GateResult result = GateEvaluator.evaluate(node, bank, index);
  if (!result.passed()) return Optional.empty();
  Object minVol = rule.params().get("min_volume");           // NEW (optional)
  if (minVol != null) {
    BigDecimal vol = bank.builtin("volume", index);          // bar volume as BigDecimal
    if (vol == null || vol.compareTo(decimal(minVol)) < 0) return Optional.empty();
  }
  return Optional.of(new ExitDecision("signal_exit", text));
}
```

> **[audit pass 1 — soundness fix]** `EngineCandle.volume()` is a **`long`**, not a `BigDecimal`
> (`EngineCandle.java:17`), so the original `bank.primarySeries().candle(index).volume()` would NOT
> compile against `.compareTo(...)`. The correct accessor is `bank.builtin("volume", index)`, which
> returns `BigDecimal.valueOf(candle.volume())` (`IndicatorBank.java:124`). `signalExit` already
> receives the `IndicatorBank bank` (`ExitEvaluator.java:330-331`), so no signature change is needed
> for the volume floor.

Schema: add `min_volume` (optional number) to the `signal_exit` params (`exitRule` L432-442). YAML
(new variant): `{ type: signal_exit, params: { rule: "close < vwap", min_volume: 125000 } }`.
**[P]** — a signal_exit that NO LONGER fires on a low-volume break changes the emitted EXIT bar →
new default-OFF variant YAML + a new golden FEATURE (`signal-exit-volume`).

**(D) Structural-trail exit types (NEW engine code) [P]** — the "trail 5 pts", "PSAR/Supertrend trail",
"hourly-new-high erosion exit" (completeness-sweep L21), "ride-to-VWAP" (trend-change L25), "RSI-cross
exit" (morning-trade L32). These need a non-premium-pct trailing basis. **Change — add `points` +
`indicator` bases to `trailing_stop`:**

```java
// ExitEvaluator.trailing (extend, L250-305): two new bases.
case "points" -> {                       // fixed-offset trail: peak ± N points (no bank needed)
  BigDecimal off = decimal(params.get("value"));
  if (off == null) yield Optional.empty();
  boolean hit = isLong ? close.compareTo(peak.subtract(off)) <= 0
                       : close.compareTo(peak.add(off)) >= 0;
  yield hit ? Optional.of(new ExitDecision("trailing_stop", "trailed " + off + "pts off " + peak))
            : Optional.empty();
}
case "indicator" -> {                    // trail to a named indicator level (e.g. psar, supertrend, vwap)
  String alias = String.valueOf(params.get("alias"));
  BigDecimal level = indicatorLevel(bank, alias, index);   // see resolution note below
  if (level == null) yield Optional.empty();
  boolean hit = isLong ? close.compareTo(level) <= 0 : close.compareTo(level) >= 0;
  yield hit ? Optional.of(new ExitDecision("trailing_stop", "trailed to " + alias))
            : Optional.empty();
}
```

> **[audit pass 1 — soundness fixes]**
> 1. **No `bank.builtinOrAlias(...)` method exists.** `IndicatorBank` exposes `valueAt(String alias, int)`
>    for declared aliases (`IndicatorBank.java:104-109`) and `builtin(String name, int)` for the three
>    built-ins `close|volume|vwap` (`IndicatorBank.java:120-128`). The resolver must try `builtin` for
>    `vwap`/`close`, else `valueAt` for a declared alias (`psar`, `supertrend`, …) — guarding the
>    `IllegalArgumentException` `valueAt` throws on an unknown alias. Call this helper `indicatorLevel`.
>    (`BarValues.isBuiltin(name)` `:20-23` is the ready-made discriminator for the `builtin`-vs-`valueAt`
>    branch.)
> 2. **The `indicator` basis needs the bank, but `trailing(...)` does NOT receive it — on EITHER path.**
>    **[audit pass 2 — signature correction]** Pass-1 framed this as only an INTRABAR problem; it is also a
>    PRIMARY-path problem. `trailing(...)` is declared `(EngineSeries series, Position position, int index,
>    ExitRuleSpec rule, BigDecimal close)` (`ExitEvaluator.java:250-255`) — **no `bank` parameter** — and
>    `evaluate` calls it `trailing(series, position, primaryIndex, rule, close)` (`:182`). So the proposed
>    `indicator` snippet that references `bank` will NOT compile until `trailing(...)`'s OWN signature gains
>    the bank (and the L182 call site passes it). `evaluate` HAS the bank in scope, so this is a one-line
>    call-site change PLUS the method-signature widen — small, but it is a signature change, not a pure
>    in-body addition. The INTRABAR reuse (`trailingOn` ← `evaluateIntrabarLevels`, `:133-161`) is the
>    SEPARATE harder problem: that path has only raw `EngineSeries` and no bank anywhere in scope.
>    **Decision required (Open Point #9):** (a) thread the bank through `trailing` + the intrabar path
>    (`evaluateIntrabarLevels`/`trailingOn`) too, or (b) widen ONLY `trailing` (primary) for the bank and
>    declare `indicator` trailing **bar-close-ONLY** (skip it in `trailingOn`), documenting that
>    `exit_intrabar` does not apply to it. `points` has no such problem (peak/close only, no bank) and works
>    on both paths.
> 3. **`SUPERTREND` alias returns +1/-1, not the line price** (`ScalperConfluenceGate.chart` reads
>    `bank.valueAt(SUPERTREND, index).signum()`, `:305-307`). So `indicator: supertrend` would trail to
>    ±1, which is meaningless. A real Supertrend-line trail needs a NEW indicator that exposes the ST
>    LINE value (ta4j computes it; the registry only binds the direction today). Same caveat for any
>    direction-only indicator. **This blocks §3.3's "indicator-trail for backtest" claim** — see §3.3.

The "RSI-cross exit" + "ride-to-VWAP" are
better modelled as additional **`signal_exit` rules** (`rsi14 < 30`, `close < vwap`) — no new code,
fold into (A)-style variants. The "hourly-new-high erosion" is a genuinely new detector; recommend
**DEFER** to a manual check (no clean primitive; record in Open Points).
**[P]** for `points`/`indicator` trailing → new tag `struct-trail` is NOT needed (it's expressed by the
exit rule itself in a new variant YAML) + new golden FEATURE (`trailing-points`, `trailing-indicator`).
**Schema:** `trailing_stop` basis enum (`exitRule` L393) must gain `points` + `indicator`, and the
params block must accept `alias` (string) for the `indicator` basis — today the `trailing_stop` params
(L390-403) allow only `basis|value|activate_at|trail_by|atr_period` under `additionalProperties:false`,
so an `alias` key REJECTS until added (and the `anyOf` value/activate_at requirement must be widened so
a `points` rule with `value` and an `indicator` rule with `alias` both validate).

**Sequencing within (A-D):** (A) and (B) are pure YAML variants (ship first, zero engine risk). (C) and
(D) are the only engine edits and each needs a golden variant.

### 3.2 `sr-levels-targets-stops` (11) — S/R-zone primitive + index-point stop band [P]

Two distinct capabilities:

**(i) Index-scaled point stop / target band (lower-risk).** Closes risk-framework L16,L50;
gap-theory L20; hero-zero L26 (via this stream); completeness-sweep L16,L27. **Change — add a `points`
basis to `levelParams`** (schema L447-456 → `enum [premium_pct, atr_multiple, r_multiple, points]`) and
to `ExitEvaluator.levelDistance` (L225-248):

```java
case "points" -> value;   // absolute index points (e.g. Nifty 30, Sensex 100) — distance IS the value
```

`stop_loss`/`take_profit` with `basis: points` then clamps the stop to a per-index band. Because the
scalper trades the PREMIUM leg, a `points` basis on the index future is only meaningful when the signal
is on the future — which it is (signal_underlying = NIFTY-FUT-CONT). For the live premium-leg stop,
`levelFromRules` (SignalEngine L809-825) must also learn `points` (today it only reads `premium_pct`).
**[P]** new golden FEATURE `stop-points`.

**(ii) True S/R-zone engine (the L-effort, highest-value, shared primitive).** Closes
gates-strike-sr-fiidii L23-24; open-high-low L22; morning-trade L16; session-additions L51;
completeness-sweep L17 (the ST-level form rides `supertrend-level-stop`, §3.3). This is a NEW
market-data analytics service: pivot/zone detection on the 1d + 15m series (+ the spot-OI-bar S/R from
`session-additions L51` — largest call-OI bar = resistance, put-OI bar = support). It belongs in
`services/market-data-service/.../marketdata` (alongside `ConnectingDotsService`), exposed as a read-only
`/api/v1/market/sr-levels` endpoint, then consumed by the seam as the target/stop reference.
**This is a large sub-epic** — recommend splitting it OUT to its own plan (it gates targets across
multiple strategies and needs its own grilling). This stream ships (i) now and STUBS the (ii) consumer
behind a `sr-target` tag that is inert until the feed lands (Open Point).
**[P]** when the seam consumes S/R for entries/targets; the analytics endpoint itself is **[S]** (read-only).

### 3.3 `supertrend-level-stop` (2) — expose the ST band price as a stop anchor [P]

Closes gap-theory ST-level-stop row (correct line ~L24/L49; NOT L18), golden-crossover S21 support-form
SL, completeness-sweep ST-level row. The `SUPERTREND` indicator is direction-only (+1/-1) in the scoring
path (`ScalperConfluenceGate.chart` → `valueAt(SUPERTREND).signum()`, `:305-307`); ta4j CAN compute the
ST LINE price but the registry does NOT bind it today. **So the FIRST step is a NEW indicator/alias that
exposes the Supertrend LINE value** (a price), distinct from the existing direction-only `supertrend`
alias. Without it there is no ST price to anchor to. **Then:** add a new `StructuralStop.SUPERTREND_LEVEL`
enum value (`ScalperConfig.java:55-64`) armed by a `supertrend-level-stop` tag (a new branch in the
`from(...)` chain, L136-151), and set the anchor in `ScalperConfluenceGate.evaluate`.

> **[audit pass 1 — soundness fix]** The private `structuralStop(cfg, future, index, side)` method
> (`ScalperConfluenceGate.java:288-302`) does **NOT** receive the `bank`/`BarValues` that holds the ST
> line value — it only has the raw future `EngineSeries`. So the ST-line anchor must either (a) be set
> inline in `evaluate(...)` (which has `bank` in scope, e.g. right after the side decision, reusing the
> `chart(bank,index)` read), or (b) thread the bank/ST-line value into `structuralStop(...)`. Prefer
> (a) to avoid widening the private helper's signature. Update the `structuralStop` javadoc accordingly.

Because the structural stop is LIVE-only (the parity firewall), this is **[P] but parity-safe-by-firewall**
— it never touches the golden replay; still requires the seam-test triple (pass/anchor/non-armed
unaffected) like every existing structural stop. **The "also works in the premium replay" alternative is
NOT free:** `OptionsPremiumReplay.exitRules` (`:262-296`) only parses `premium_pct` levels +
`activate_at`/`trail_by` trailing — it would SILENTLY IGNORE a `basis: indicator` trail, AND a
`points`/`indicator` index-level has no meaning on the OPTION-premium leg anyway (different instrument).
So an indicator-trail does NOT give backtest fidelity for free; treat the ST-level stop as **live-seam +
golden-harness only** and DROP the "recommend BOTH for backtest" claim (record the premium-replay
extension as an Open Point if backtest application of structural trails is ever wanted).
Tag: `supertrend-level-stop`. No new GOLDEN FEATURE for the live anchor (firewall); if the engine
`indicator` trailing basis (§3.1-D) is also shipped, it carries its own `trailing-indicator` FEATURE.

### 3.4 `volume-conditional-exit` (1) — adverse-move volume-conditional exit [S, equity-gated]

Closes market-movers L30. Identical mechanism to §3.1-C (the `min_volume` signal_exit), but it needs the
STOCK's volume series → **gated on the equity universe** (`equity-fno-universe-screener`, a different
stream). Until that lands, this is unreachable for per-stock Market-Movers. **Recommendation:** implement
the `min_volume` primitive in §3.1-C now (index variant), and DEFER the per-stock wiring to the equity
sub-epic. **[S]** (no existing per-stock golden; a brand-new path).

### 3.5 `oi-confirmed-sl-leeway` (1) — pad the pivot stop when OI confirms [P, owner-gated]

Closes trend-change L24. The trend-change YAML header (L18-21) DELIBERATELY defers the ~10-20pt
benefit-of-doubt allowance to live SL-management "so the persisted stop stays pure/deterministic". So
this is an **owner decision**, not a clear automate. If automated: in
`ScalperConfluenceGate.structuralStop` for the `SWING_BREAK` anchor, when the TrendChangeGate's OI
confirmation is "convincing" (a threshold on the signed dOI magnitude already computed in the gate), pad
the swing-pivot stop by a configurable `sl_leeway_points`. **[P]** (changes the live stop level → live
behaviour change; parity-safe by firewall but a deliberate strategy change). **Recommended default:
keep deferred** (record in Open Points); if armed, behind a `sl-leeway` tag, forward-paper only.

### 3.6 `structural-stop-arming` (1) — arm the 1st-candle stop on connect-the-dots [S→P]

Closes connect-the-dots stop-loss row (correct line ~L43 "Stop-loss = 1st candle low/high; gap-trail
5pts"; NOT L31). The `StructuralStop` machinery EXISTS **and the `entry-candle-stop` tag is ALREADY
fully wired** — `ScalperConfig.from` already maps `tags.contains("entry-candle-stop")` →
`StructuralStop.ENTRY_CANDLE` (`ScalperConfig.java:149-150`), and `ScalperConfluenceGate.structuralStop`
already returns `future.candle(index).low()/.high()` for it (`:293-295`). connect-the-dots YAMLs simply
don't carry the tag (they resolve to `StructuralStop.NONE`). **Change: ZERO Java — purely add the
existing `entry-candle-stop` tag to a NEW connect-the-dots *variant* YAML** so the entry-candle extreme
becomes the persisted stop. (`two-candle-pattern` is the OTHER existing tag, but it ALSO runs the
TwoCandleGate as a HARD entry gate — pick `entry-candle-stop` if only the stop anchor is wanted, not the
2-candle entry filter.) **[S]** — new variant, no existing golden; the structural stop is live-only so
even arming it is parity-inert on the harness. If instead armed on the SHIPPED connect-the-dots YAMLs it
becomes **[P]** (changes live emission) → keep it a new variant, default-OFF.

### 3.7 `profit-slice-sizing` (1) — size hero-zero off realised PnL [S]

Closes hero-zero "10%-of-profits / ₹1-2k" row (correct line ~L43; NOT L27). Today hero-zero sizes off a
flat `budget_inr` (`premium_budget` method, `PositionSizer.java:36-44`; hero-zero YAML `budget_inr:2000`).
**Change:** a new `position_sizing.method: profit_slice` (schema `risk.position_sizing` method enum
L468 + a params shape; `PositionSizer.size` switch, `PositionSizer.java:28-61`) that caps the premium
budget at `pct_of_profit` (e.g. 10%) of running realised PnL, floored at a min and capped at a max (the
₹1-2k band). Sizing is ADVISORY (`suggested_qty`) — it does NOT change WHICH signal fires, so **[S]**
(parity-neutral, same class as `probability-graded-sizing` in the sibling stream).

> **[audit pass 1 — completeness fixes]** Two concrete steps the plan omitted:
> 1. **`PositionSizer.Inputs` carries NO PnL field** — it is `record Inputs(BigDecimal equity, BigDecimal
>    price, BigDecimal stopDistance, long lotSize)` (`PositionSizer.java:18-19`). `profit_slice` needs the
>    running realised `dayPnl`, so a NEW field must be added to `Inputs` AND populated at the call site.
> 2. **The LIVE sizing call site is `PaperEmissionGuard.suggestedQty(...)`** (the impl of the
>    `EmissionGuard` SPI **interface** `EmissionGuard.java:31-36` — not the interface itself; reached from
>    `SignalEngine.java:608-610`, live-only — guarded by `emissionGuard.isPresent()`). `dayPnl` lives in the
>    paper layer (`PaperAccountService` / `RiskService`); thread it into the `EmissionGuard.suggestedQty`
>    SIGNATURE (so every impl + the `SignalEngine` caller change), into `PaperEmissionGuard` (`:54-55`), and
>    on into `PositionSizer.Inputs`. Because `suggestedQty` is stamped ONLY on the live path and is NOT part
>    of the golden side-channel, feeding live `dayPnl` into the deterministic `libs/strategy-engine` sizer
>    stays **parity-safe** (the golden harness never calls `EmissionGuard`).
> 3. **[audit pass 2 — call-site fan-out correction]** Pass-1 said "the only call site" — WRONG.
>    `PositionSizer.Inputs` has a SECOND production constructor at **`ReplayEngine.size`
>    (`ReplayEngine.java:283-287`)** — the DETERMINISTIC backtest path — plus **6** test constructors in
>    `ExitEvaluatorTest` (`:317,321,326,332,338,344,350`). Adding a 5th `Inputs` field is a
>    constructor-ARITY break that MUST also update `ReplayEngine` (pass `null`/zero `dayPnl` — `profit_slice`
>    is never selected in a backtest config, so every existing method stays inert and parity holds) AND the
>    6 `ExitEvaluatorTest` literals AND any `PositionSizerTest` literal. Enumerate ALL of these — the
>    `ReplayEngine` site is the parity-relevant one (it compiles on the golden/parity path).

Ship as a new hero-zero variant.

### 3.8 `gap-fill-deadline-switch` (1) — abandon an unfilled gap at ~40 min [P]

Closes gap-theory L19. A PRE-ENTRY counter: if the gap is unfilled (on volume) by ~40 min, drop the gap
setup and revert to the trend trade. This lives in the seam's gap path (`GapTheoryGate` / the seam's
`gap-theory` branch, ScalperConfluenceGate L173-179), not the exit evaluator — it's an entry-gating
switch. **Change:** add a `gap_fill_deadline_min` knob (held in `ScalperConfig`, like the OPENING_* and
VWAP_ACTIONABLE_FROM constants L72-76) and in the seam, when `barTime - sessionOpen > deadline` AND the
gap is still open, fall through to the plain trend gate instead of the gap pre-gate. The "on volume"
qualifier reuses the §0B volume floor. **[P]** (changes which entries fire) → new tag
`gap-deadline-switch`, forward-paper variant; seam-test triple. (Live-only seam → parity-safe by
firewall, but it IS a behaviour change so treat as [P] and tag-gate it.)

### 3.9 `scale-in-ladder` (1) — smallest-first laddered deployment [S, L-effort]

Closes risk-framework L20. The engine emits ONE full-qty entry (`max_positions:1`); there is no
multi-leg entry primitive. A true scale-in (deploy smallest first, add nearer VWAP/ST/S-R) needs:
(a) a `position_sizing.method: ladder` with N rungs + per-rung trigger levels, (b) multi-entry support in
the paper engine (relax the single-active-entry invariant for laddered adds), (c) "add only at
ST/VWAP/S-R, never after SL breach" guards. This is a **large, cross-cutting engine change** that touches
the paper-position invariant the no-averaging rail depends on (risk-framework L34 ACCEPT_BY_DESIGN relies
on single-entry). **Recommendation: DEFER to its own plan** — it is the heaviest item in the stream and
orthogonal to the exit toolkit. The averaging-ladder cousin (two-candle L31, morning-trade L33) is
KEEP_MANUAL_NEW, reinforcing that scale-in is not a v1 automate. **[S]** if/when built (new sizing method,
new variant). Record as the stream's primary Open Point.

---

## 4. PARITY classification (per change) + tag + golden-variant plan

| Change | P/S | New tag / variant | New golden variant |
|---|:--:|---|---|
| §3.1-A premium-pct `take_profit` leg | **[S]** | new `*-tp` variant YAML (no tag) | none (new variant, no existing golden; engine type already golden-covered via exit-intrabar) |
| §3.1-B premium-pct `trailing_stop` leg | **[S]** | new `*-trail` variant YAML | none (engine type pre-existing) |
| §3.1-C volume-qualified `signal_exit` (`min_volume`) | **[P]** | new variant; schema `min_volume` param | **`signal-exit-volume`** FEATURES entry + `expected/signal-exit-volume.signals.json` (generate-once) |
| §3.1-D `trailing_stop` `points` / `indicator` bases | **[P]** | new variant | **`trailing-points`**, **`trailing-indicator`** FEATURES entries |
| §3.2-i `stop_loss`/`take_profit` `points` basis | **[P]** | new variant | **`stop-points`** FEATURES entry |
| §3.2-ii S/R-zone analytics endpoint | **[S]** | (read-only `/sr-levels`) | none (analytics, no signal) |
| §3.2-ii seam consumes S/R targets | **[P]** | `sr-target` tag (inert until feed) | rides `stop-points` pattern when armed |
| §3.3 `SUPERTREND_LEVEL` structural anchor | **[P]**¹ | `supertrend-level-stop` tag (needs a NEW ST-LINE indicator first — Open Point #10) | none live (firewall); NO backtest representation (premium-replay ignores it — Open Point #11) |
| §3.4 per-stock volume exit | **[S]** | (equity-gated, deferred) | none (new path) |
| §3.5 OI-confirmed SL leeway | **[P]**¹ | `sl-leeway` tag (owner-gated, default keep-deferred) | none live (firewall) |
| §3.6 arm structural stop on connect-the-dots | **[S]** | `entry-candle-stop` on a NEW variant | none (structural stop live-only) |
| §3.7 `profit_slice` sizing | **[S]** | `profit_slice` method on a new hero-zero variant | none (advisory sizing) |
| §3.8 gap-fill deadline switch | **[P]**¹ | `gap-deadline-switch` tag | none live (firewall); seam-test triple |
| §3.9 scale-in ladder | **[S]** | (DEFERRED — own plan) | none |

¹ Live-seam-only changes are **parity-safe by the firewall** (`ScalperConfluenceGate` never runs on the
golden/parity harness — FU2 §2.6), so they cannot perturb the 5 frozen goldens. They are still labelled
**[P]** because they change *emitted live signals* → MUST be tag-gated default-OFF (a new variant), proven
by a seam-test triple (pass / behaviour / non-armed-unaffected) mirroring the #5 `oi-cross-filter` and
FU2 `indicator-alignment` templates, and never armed on a shipped YAML in the infra PR.

**Hard parity rule for the ENGINE changes (§3.1-C/D, §3.2-i):** these touch `ExitEvaluator` /
`StrategyCompiler` / the schema, which DO run on the golden harness. The 5 existing goldens
(`GoldenDeterminismTest.FEATURES`) MUST stay byte-identical — they declare none of the new exit
types/bases, so adding a `switch` case / optional param is inert for them (re-run, assert byte-identity,
do NOT regenerate). Each new type/basis gets its OWN new FEATURES entry + generate-once fixture so it has
positive deterministic coverage without mutating a frozen vector.

---

## 5. Tests

### 5.1 Engine unit (`libs/strategy-engine/src/test/java/.../eval/`)
- `ExitEvaluatorTest` (extend or add): `signalExitHonoursMinVolumeFloor` (passes only when bar volume ≥
  floor; below floor → no exit); `trailingPointsTrailsByFixedOffset`; `trailingIndicatorExitsAtLevel`;
  `levelDistancePointsBasisIsAbsolute`. Each is a pure `(series, definition, position) → Optional` assert
  — no Spring, no DB.
- `StrategyCompilerTest`: a YAML with `signal_exit min_volume`, `trailing_stop basis: points`,
  `stop_loss basis: points` compiles to the expected `ExitRuleSpec` params.

### 5.2 Schema (`libs/strategy-schema/src/test/`)
- `StrategySchemaValidationTest`: the new params/bases VALIDATE; an out-of-range / unknown basis still
  REJECTS (`additionalProperties:false` is preserved).

### 5.3 Golden / parity (the load-bearing byte-identity proof)
- `GoldenDeterminismTest` (`libs/strategy-engine`): re-run — the 5 frozen FEATURES stay byte-identical
  (they use none of the new types). Then ADD `signal-exit-volume`, `trailing-points`,
  `trailing-indicator`, `stop-points` to `FEATURES` (L33-36) with new
  `strategies/<name>.yaml` + generate-once `expected/<name>.signals.json` (`-Dgolden.generate=true`,
  then freeze).
- `BacktestParityTest` (`services/backtest-service`): re-run the existing FEATURES byte-match; if a new
  exit-type fixture is added to the parity set, prove two replays + replay==frozen byte-identity.
- `PremiumExitEvaluatorTest` / `OptionsPremiumReplayTest`: a config with `take_profit premium_pct` and
  `trailing_stop activate_at/trail_by` produces the expected premium-leg exit bar (the §3.1-A/B path is
  already consumed — add the coverage if absent).

### 5.4 Seam unit (`services/.../scalper/ScalperConfluenceGateTest.java`)
- For each live-only tag (§3.3 `supertrend-level-stop`, §3.8 `gap-deadline-switch`, §3.6
  `entry-candle-stop` on connect-the-dots, §3.5 `sl-leeway` if built): the 3-test triple — a CFG with the
  tag set anchors/switches as designed; a CFG without it is unaffected; the structural stop / decision is
  the expected level. Update ALL `new ScalperConfig(...)` literals for any added record field
  (constructor-arity fan-out, exactly as FU2 §2.4 documents — 8 literals in this test).
- `ScalperConfig`-level: a `supertrendLevelStopTagAnchorsOnStLine` test.

### 5.5 Load test (`ScalperStrategyLoadTest.java`)
- Assert the new tags are OFF for every SHIPPED strategy (the regression tripwire that the infra PRs arm
  nothing — same shape as FU2 §5.3). When a new variant YAML is added it gets a per-id expectation.

### 5.6 e2e (`e2e/tests/signals.spec.ts`)
- No behaviour change in the infra PRs (default-OFF) → re-run as a regression check. When a forward-paper
  variant is published (the arming PR), add an assertion that a signal carrying a `take_profit` target
  renders the target on the signal card (the `stopLoss`/`takeProfit` already ride the row).

---

## 6. Dependencies & sequencing

1. **Schema FIRST.** `min_volume`, `points` basis (level + trailing), `indicator` basis + `alias`, then
   the compiler reads them — nothing downstream validates without the schema change. **[audit pass 1 —
   correction]** `StrategyCompiler` itself needs **NO code change**: it builds `ExitRuleSpec(type,
   paramsMap(params))` generically with NO param allowlist (`StrategyCompiler.java:58-62`), so a new
   param/basis flows into `rule.params()` automatically once the schema admits it — only the schema +
   the consuming `ExitEvaluator`/`levelFromRules` code change (and a `StrategyCompilerTest` to lock the
   round-trip).
   **[audit pass 1 — correction]** The strategy JSON-schema is **NOT** part of the springdoc `/v3/api-docs`
   contract: `/schema/v1` returns the schema as an opaque `String` (`RegistryController.java:156-159`) and
   `/validate` takes an untyped `JsonNode`/`Map` body (`:150-153`) — no DTO mirrors `exitRule`/`ExitRuleSpec`,
   so per CLAUDE.md "generic `Map<String,Object>`/string returns are NOT enumerated", **adding an exit-rule
   param/basis does NOT drift the springdoc spec and needs NO `ContractCaptureTest` re-capture.** (There IS
   a separate frozen-schema *byte-identity* acceptance — the schema doc is served byte-for-byte — but that
   is satisfied by editing the schema resource normally, not a springdoc concern.) Also confirm
   `StrategyDocuments`/`StrategySchemaV1` reloads the edited schema (it is read from the classpath resource).
2. **Engine exit types** (§3.1-C/D, §3.2-i) before any YAML can use them; each lands with its golden
   FEATURE in the SAME PR (generate-once, freeze).
3. **YAML-only variants** (§3.1-A/B, §3.6, §3.7) can ship in parallel once the engine types exist (A/B
   need no new type — they can ship FIRST of all).
4. **The S/R-zone feed (§3.2-ii)** must be wired in market-data BEFORE the seam can consume S/R targets;
   ship the analytics endpoint, then the `sr-target` consumer. **Split to its own plan.**
5. **The equity universe** (`equity-fno-universe-screener`, separate stream) gates §3.4 per-stock volume
   exit — DEFER until it lands.
6. **SPAN (#47 margin appliance)** gates any SELL-leg exit management (short straddle / BTST-STBT sell
   legs) — none in this stream's v1 (long-premium only), but the trailing/target legs are written so a
   future sell path inherits them.
7. **`scale-in-ladder` (§3.9)** depends on relaxing the single-active-entry paper invariant — DEFER to
   its own plan (it interacts with the no-averaging ACCEPT_BY_DESIGN rail).
8. **Owner sign-off** gates §3.5 (`oi-confirmed-sl-leeway`, deliberately deferred today) and the doc point
   values (per-index SL/target points — see Open Points).
9. **[audit pass 1] A NEW Supertrend-LINE indicator** (a price, distinct from the direction-only
   `supertrend` alias) must be added to `IndicatorRegistry` BEFORE §3.3 (`SUPERTREND_LEVEL` stop) and
   before any `indicator: supertrend` trail can resolve to a meaningful level — see Open Point #10. Put
   it at the head of PR-3 (or drop §3.3's ST-level form from v1 if deferred).

---

## 7. Effort + suggested PR breakdown

Overall effort: **L** (the package is L per the disposition; but front-loaded with cheap YAML wins).

- **PR-1 (S) — premium-pct target/trail variants (no engine code).** Add `*-tp` / `*-trail` variant YAMLs
  for the directional families (connect-the-dots, two-candle, golden-crossover, trending-oi,
  open-high-low, market-movers, gap-theory) + the `optimize` rows for the new params + `ScalperStrategyLoadTest`
  per-id expectations. Closes the bulk of §3.1-A/B (the largest gap slice) with zero parity risk.
  `feat(strategy-signal): premium-pct take-profit + trailing variants`.
- **PR-2 (M) — engine: volume-qualified signal_exit + points basis.** Schema (`min_volume`, `points`) +
  `StrategyCompiler` + `ExitEvaluator` (`signalExit` floor, `levelDistance` points) + 4 new golden
  FEATURES (`signal-exit-volume`, `stop-points`) + `SignalEngine.levelFromRules` points + contract
  re-capture. `feat(strategy-engine): volume-qualified exit + points stop/target basis`.
- **PR-3 (M) — engine: structural trailing bases + ST-level anchor.** A NEW Supertrend-LINE indicator
  (prerequisite, dep #9) + `trailing_stop` `points`/`indicator` bases (`points` works on both paths;
  `indicator` is bar-close-only OR threads the bank through the intrabar path — Open Point #9) + golden
  FEATURES (`trailing-points`, `trailing-indicator` — the `indicator` fixture uses a value-bearing alias,
  not the direction-only `supertrend`) + the `SUPERTREND_LEVEL` structural anchor (set inline in
  `evaluate`, not the private `structuralStop` helper) + `supertrend-level-stop` tag + seam triple.
  `feat(strategy-engine): structural trailing + supertrend-level stop`.
- **PR-4 (S) — profit_slice sizing + connect-the-dots structural-stop variant.** `profit_slice`
  `PositionSizer` method + schema + new hero-zero variant; `entry-candle-stop` connect-the-dots variant.
  `feat(strategy-signal): profit-slice sizing + connect-the-dots structural stop`.
- **PR-5 (M, gated) — gap-fill deadline switch (forward-paper).** `gap-deadline-switch` tag + seam knob +
  triple + a forward-paper variant. `feat(strategy-signal): gap-fill deadline switch (tag-gated)`.
- **SEPARATE PLANS (DEFER):** S/R-zone analytics primitive (§3.2-ii, L), scale-in ladder (§3.9, L),
  per-stock volume exit (§3.4, equity-gated).

Each PR: short-lived `feat/`/`fix/` branch, Conventional Commit scoped `strategy-engine`/`strategy-signal`,
squash-merge, build with the full reactor + `-am` (`-pl <svc> -am verify`). Golden/parity tripwires green
on every PR.

---

## Open Points

1. **S/R-zone engine — own plan or in-stream?** The `sr-levels-targets-stops` (ii) primitive (1d+15m
   pivot/zone + spot-OI-bar S/R) is L-effort, gates targets across 5+ strategies, and needs its own
   grilling. **Options:** (a) split to a dedicated plan, ship a `points`-basis stop/target now and a
   `sr-target` inert tag (recommended); (b) build it inline (bloats this stream, delays the cheap exit
   wins). **Recommended default: (a).**

2. **Per-index SL/target point values.** The doc gives Nifty ~30/50-60, BankNifty ~75, Sensex
   ~50-100/200-250 pt (risk-framework **L73** — the v2-corrected row with the verbatim numbers; the
   earlier "L50" cite is stale, and L73 explicitly removed an invented "~80 pt" Sensex figure) — but
   these are INDEX points and the scalper trades the PREMIUM
   leg. **Options:** (a) map to a tuned premium-% band per index and let the optimizer find it
   (recommended — consistent with "tune on live, not backtest"); (b) hardcode a `points` basis on the
   index future and convert to premium at fill. **Recommended default: (a)**; expose the `points` basis
   anyway for the future-leg use. Owner to confirm the starting band.

3. **`oi-confirmed-sl-leeway` (§3.5) — automate or keep deferred?** The trend-change YAML deliberately
   defers the 10-20pt allowance to live SL-management to keep the persisted stop deterministic.
   **Options:** (a) keep deferred / manual (recommended — matches the existing design decision);
   (b) automate behind a forward-paper `sl-leeway` tag. **Recommended default: (a)** — record as an
   owner decision, do not build unless asked.

4. **`scale-in-ladder` (§3.9) — defer.** A true ladder breaks the single-active-entry invariant the
   no-averaging rail (risk-framework L34 ACCEPT_BY_DESIGN) relies on. **Options:** (a) DEFER to its own
   plan (recommended); (b) attempt a constrained 2-rung add. **Recommended default: (a)** — it is the
   heaviest, most invasive item and orthogonal to the exit toolkit.

5. **Volume-qualified exit threshold value.** §3.1-C/§3.4 need a volume floor; the entry-side §0B volume
   floor (Nifty 125K / others 50K) is the obvious reuse, but an EXIT floor may want a different value.
   **Options:** (a) reuse the §0B entry floor (recommended for v1, one source of truth); (b) a separate
   `min_volume` per exit rule (already supported by the param — let the optimizer tune it). **Recommended
   default: (a) as the YAML default, (b) available for tuning.**

6. **Hourly-new-high erosion exit (completeness-sweep, erosion row — verify by text) + RSI-laddered
   partial scale-out (connect-the-dots **L41** "RSI profit-booking ladder", NOT L29 which is Trending-OI
   cross).** These need a partial-exit / multi-bucket-scale-out primitive the engine
   lacks (it exits the WHOLE position). **Options:** (a) DEFER both to a "partial exits" follow-up (the
   RSI-book-90%/10% ladder is explicitly a partial scale-out); (b) approximate erosion with a wider
   `time_stop` + tighter trail. **Recommended default: (a) defer the partials**, approximate with trail in
   the interim. (The full RSI-booking ladder is a distinct engine feature — flag for the owner.)

7. **Per-stock volume exit (§3.4) is equity-gated.** It cannot ship until `equity-fno-universe-screener`
   lands. **Options:** (a) implement the `min_volume` primitive now (index path) and wire per-stock later
   (recommended); (b) wait. **Recommended default: (a).**

8. **`take_profit` on the premium leg vs the index leg.** §3.1-A maps a structural index-point target to a
   premium-%. The premium-% target fires on BOTH the live engine (index future close) and the backtest
   premium replay (option premium) — but those are DIFFERENT instruments, so the same `value: 40` means
   "40% of the index-future close move" live and "40% of the option premium" in backtest. **Options:**
   (a) accept the dual interpretation (the live signal is on the future; the backtest trades premium —
   this is the existing `premium_pct` stop behaviour, already shipped on hero-zero/straddle, so it's
   consistent); (b) add a premium-only target basis. **Recommended default: (a)** — it matches the
   existing premium-pct stop semantics; document the dual meaning in the variant YAML header.

9. **[audit pass 1; sharpened pass 2] `indicator` trailing basis — bank threading.** The `indicator`
   basis (§3.1-D) needs the `IndicatorBank`, but `trailing(...)` does not receive it on EITHER path —
   the PRIMARY `trailing(...)` (`:250-255`) takes only `EngineSeries`, and the intrabar
   `evaluateIntrabarLevels`/`trailingOn` (`:133-161`) has no bank anywhere in scope. So EVEN the
   bar-close-only option requires widening the primary `trailing(...)` signature + its `:182` call site
   (cheap — `evaluate` has the bank). **Options:** (a) thread the bank through `trailing` AND the
   intrabar path (wider change, `indicator` works intrabar); (b) widen `trailing` (primary) only and
   make `indicator` trailing **bar-close-ONLY** (skip it under `exit_intrabar`), documenting the
   limitation. **Recommended default: (b)** for v1 (the `points` basis works intrabar with no bank;
   `indicator` is the rarer case). Decide before PR-3.

10. **[audit pass 1] Supertrend-LINE indicator is unbuilt.** The `supertrend` alias is direction-only
    (±1); §3.3 + the `indicator: supertrend` trail both need a NEW registry indicator exposing the ST
    LINE PRICE. **Options:** (a) add an `SUPERTREND_LINE` (or parameterised `output: line`) indicator
    in `IndicatorRegistry`/ta4j and bind it; (b) drop the ST-level stop and ST-trail from v1 and keep
    only `points`/premium trailing. **Recommended default: (a)** if the ST-level stop is wanted (it is
    the §3.3 core), else (b). This is the gating dependency for §3.3 and the `trailing-indicator`
    golden if `supertrend` is the chosen alias.

11. **[audit pass 1] Structural trails in the premium-replay backtest.** `OptionsPremiumReplay.exitRules`
    (`:262-296`) parses only `premium_pct` levels + `activate_at`/`trail_by` trailing; the new
    `points`/`indicator` bases (and any index-level structural stop) are silently ignored there AND are
    meaningless on the option-premium leg anyway. So structural trails are **live-seam + golden-harness
    only** — they have NO backtest representation. **Options:** (a) accept (treat structural trails as
    forward-paper-validated, not backtested — consistent with "tune on live"); (b) extend
    `PremiumExitEvaluator` to apply a points/indicator trail on the PREMIUM series itself (a different
    semantic — a premium-points trail, not an index trail). **Recommended default: (a).**

---

## Audit pass 1 findings

Auditor opened every cited source file. **Verdict: sound-with-open-points.** The architecture is
correct — the engine already implements `take_profit`/`trailing_stop` and the premium-replay already
consumes them, so the "YAML-variant first, narrow engine work" framing holds — and the PARITY design is
right (engine changes get new golden FEATURES; live-seam changes are parity-safe-by-firewall and must be
tag-gated default-OFF; the 5 frozen goldens stay byte-identical because they declare none of the new
types). But there is one **systematic citation defect** plus several **soundness/completeness gaps in the
proposed code** that would each fail a developer at first contact. All are corrected in place above.

### A. Engine/seam/schema/golden citations — VERIFIED CORRECT
- `ExitEvaluator`: `evaluate` L164-193, `evaluateIntrabarLevels` L86-121, `level` L195-222,
  `levelDistance` L225-248 (bases `premium_pct`/`atr_multiple`/`r_multiple` at L236-245), `trailing`
  L250-305 (premium_pct activate_at L266-277; atr_multiple L288-303), `signalExit` L330-338, `timeStop`
  L307-328, `entryLevels` L50-56, `favorableExtreme` L340-351 — **all correct.**
- Schema `exitRule` L362-446; `trailing_stop` L382-405 (basis enum L393); `time_stop` L406-425;
  `signal_exit` L426-444; `levelParams` L447-456; optimize-path regex L715; `position_sizing` method
  enum L468 — **all correct.**
- `ScalperConfig`: `record` L36-52, `StructuralStop` enum L55-64, `from` L101-157, tag→anchor chain
  L136-151 — **all correct.** (`entry-candle-stop`→`ENTRY_CANDLE` is ALREADY wired at L149-150.)
- `ScalperConfluenceGate`: `Decision` L71-87 (`structuralStop` L76), per-tag stop set at L166/178/210/
  228/244, private `structuralStop` L288-302, LIVE-only firewall (class javadoc L29-32) — **all correct.**
- `SignalEngine`: `emitEntry` L573-604, `levelFromRules` L809-825 (premium_pct-ONLY, confirmed),
  structuralStop override L587-589, `target` L590, scalper exit block L398-425 (structuralStopHit
  L405-411, ExitEvaluator L414-421), `hasBoundingExit` assert L199, suggestedQty L605-613 — **all correct.**
- `ScalperRisk.hasBoundingExit` L20-24; `GoldenSignalsJson.write` serializes only timestamp/exchange/
  tradingsymbol/direction/composite/breakdown (stopLoss/takeProfit side-channel) L52-65; `GoldenDeterminismTest.FEATURES`
  = exactly 5, no scalper, L33-36; `OptionsPremiumReplay.exitRules` L262-296; **8** `new ScalperConfig(...)`
  literals in `ScalperConfluenceGateTest` — **all correct.** FU2 precedents (`indicator-alignment` tag,
  parity firewall, 8-literal fan-out) all real.
- Minor cite drift only: `TickwiseGoldenRunner.entryLevels` HELPER is L281-287 (plan said L266-273); the
  STAMP sites L166/206/235 are correct. Fixed.

### B. Citation defect — STALE GAP-SOURCE LINE NUMBERS (the one systemic problem)
Every `docs/strategy-audit/*.md` line number is **stale**, pointing ~10-25 lines above the actual
exit/target/SL row (the audit `.md` files were expanded with v2/v3 rows after the plan captured them).
The content→feature mapping is correct; the numbers are not. A prominent warning was added under §1 and
the load-bearing cites were corrected. Spot-checked corrections (find the rest by ROW TEXT):
- two-candle.md: Target **L43** (not L28); VWAP-with-volume exit **L44** (not L29); PSAR/ST trail **L45**
  (not L30). connect-the-dots.md: Target **L40** (not L28); VWAP-with-volume exit **L42** (not L30);
  structural-stop row **L43** (not L31); RSI-book ladder **L41** (not L29).
- gap-theory.md: ST-level SL **L24/L49** (not L18); targets **L38/L50** (not L20/L28); 5-pt trail
  **L39/L50** (not L29); §3.8 "abandon unfilled gap ~40 min on volume" **L51** (not L19).
- hero-zero.md: index-point SL **L25/L42** (not L26); 10%-of-profits/₹1-2k **L43** (not L19/L27).
- risk-framework.md: scale-in/ladder **L11/L60** (not L20); RR/no-take_profit **L55** (not L17);
  per-index point values **L73** (not L50). market-movers.md: per-stock target **L32** (not L30).
- trend-change.md: L24/L25 are timing/morning-print rows, NOT the SL-leeway/ride-to-VWAP rows (find by text).
- session-additions: file is `session-additions-and-manual-coverage.md`; spot-OI-bar S/R row **L57** (not L51).

### C. Soundness gaps in the proposed CODE (each would not compile / not work) — CORRECTED
1. **§3.1-C volume accessor** used `candle(index).volume()` (a **`long`**, `EngineCandle.java:17`) with
   `.compareTo(...)` → won't compile. Fixed to `bank.builtin("volume", index)` (returns BigDecimal,
   `IndicatorBank.java:124`); `signalExit` already has the bank, no signature change.
2. **§3.1-D `bank.builtinOrAlias(...)` does not exist.** `IndicatorBank` has `valueAt(alias,i)` +
   `builtin(name,i)` only. Replaced with an `indicatorLevel` resolver note.
3. **§3.1-D `indicator` trail + the intrabar path.** `trailing(...)` is also reached via `trailingOn`
   from `evaluateIntrabarLevels`, which has NO bank → the "signature widen confined to one method" claim
   is wrong. Flagged + Open Point #9 (bar-close-only vs thread-the-bank).
4. **§3.3 SUPERTREND_LEVEL.** (a) the private `structuralStop(...)` lacks the bank → set the anchor inline
   in `evaluate`; (b) the `supertrend` alias is **direction-only (±1)**, not a price → a NEW ST-LINE
   indicator is the gating prerequisite (Open Point #10); (c) the "also works in premium replay /
   recommend BOTH" claim is false — `OptionsPremiumReplay.exitRules` ignores non-premium-pct trails and
   the index level is meaningless on the premium leg (Open Point #11). §3.3 rewritten.

### D. Completeness gaps — ADDED
5. **§3.6** is **zero-Java** — the `entry-candle-stop` tag is already fully wired; only a new variant YAML
   is needed. The plan implied machinery work. Corrected.
6. **§3.7 profit_slice** omitted two concrete steps: `PositionSizer.Inputs` has NO PnL field (must add
   one) and the only call site is `EmissionGuard.suggestedQty` (live-only) — thread `dayPnl` from the
   paper layer through it; enumerate the constructor-arity fan-out. Parity-safe (live-only). Added.
7. **§6.1 contract-capture is overcautious.** The strategy JSON-schema is NOT a springdoc DTO (`/schema/v1`
   returns a `String`, `/validate` takes an untyped body) → adding an exit-rule param/basis does NOT drift
   `/v3/api-docs` and needs no `ContractCaptureTest` re-capture. Corrected.
8. **`StrategyCompiler` needs no code change** — it copies all exit params generically (L58-62). The plan
   listed a compiler change; clarified it's schema + consumer + test only.
9. **Schema for §3.1-D/§3.2-i** must add the `trailing_stop` `points`/`indicator` enum values AND an
   `alias` param (today `additionalProperties:false` rejects `alias`), and widen the `anyOf`. Added to §3.1-D.

### E. Parity assessment — PASS
- The [P]/[S] classification is correct after the fixes. §3.1-C/D and §3.2-i touch `ExitEvaluator`/schema
  (run on the golden harness) → correctly [P] with new FEATURES; the 5 frozen goldens declare none of the
  new types so they stay byte-identical (re-run, assert, do NOT regenerate). §3.3/§3.5/§3.6/§3.8 are
  live-seam-only → parity-safe by the firewall, correctly tag-gated default-OFF + seam-test triple.
  §3.1-A/B/§3.7 are [S] (pre-existing engine types / advisory sizing, parity-neutral). `GoldenDeterminismTest`
  + `BacktestParityTest` would still pass. **No [S] mis-marked as signal-moving; no [P] missing its tag/golden.**
- One clarification added to §2.5: the new golden FEATURE fixtures must be **pure-engine (non-scalper)**
  YAMLs, and the `trailing-indicator` fixture's `alias` must be a declared, value-bearing indicator
  (not `supertrend`, which is direction-only).

### F. Sequencing — SOUND
Schema → engine types (with goldens) → YAML variants is the right order; S/R feed before its consumer;
equity universe before per-stock volume exit; SPAN before any sell-leg (none in v1). The Supertrend-LINE
indicator (Open Point #10) is a NEW prerequisite that must precede §3.3 — added to the dependency set.

---

## Audit pass 2 findings

INDEPENDENT re-audit. I re-opened every load-bearing source file myself (not trusting pass-1's
transcript) and re-derived the citations, re-checked the parity firewall end-to-end, confirmed the pass-1
corrections, and hunted for what both the author and pass-1 missed. **Verdict: sound-with-open-points** —
ready to execute once the two NEW gaps below and the four pre-existing Open Points are folded into the
PR-3/PR-4 dev notes (no design rework; both new gaps are within already-flagged areas).

### A. Re-verified citations (opened the files, line-by-line) — ALL CORRECT
- `ExitEvaluator.java`: `entryLevels` L50-56, `evaluateIntrabarLevels` L86-121, `trailingOn` L133-161,
  `evaluate` L164-193, `level` L195-222, `levelDistance` L225-248 (bases L235-246), `trailing` L250-305
  (signature `(EngineSeries,Position,int,ExitRuleSpec,BigDecimal)` — **no bank**, L250-255; premium_pct
  activate_at L266-277; atr_multiple L288-303), `timeStop` L307-328, `signalExit` L330-338 (already takes
  `IndicatorBank bank`), `favorableExtreme` L340-351 — **all exact.**
- `IndicatorBank.java`: `valueAt` L104-109 (throws on unknown alias via `bound()` L92-94), `builtin`
  L120-128 (`volume` → `BigDecimal.valueOf(candle.volume())` L124) — **exact.** `BarValues.java`:
  `valueAt`/`builtin` ports + `isBuiltin(name)` L20-23. `EngineCandle.volume()` is **`long`** (L17) —
  confirms pass-1 fix C.1.
- Schema `strategy-schema-v1.json`: `exitRule` L362-446, `trailing_stop` L382-405 (basis enum L393
  `[premium_pct, atr_multiple]`, anyOf L399-402, `additionalProperties:false` L390), `time_stop` L406-425,
  `signal_exit` L426-444 (params `additionalProperties:false` L434, `required:[rule]` L435), `levelParams`
  L447-456 (basis enum L452), `position_sizing` method enum L468, optimize regex L715 (admits
  `exit_rules[type=...].params.<lc>`) — **all exact.**
- `ScalperConfig.java`: record L36-52, `StructuralStop` enum L55-64 (8 values), `from` L101-157, anchor
  chain L136-151, **`entry-candle-stop`→`ENTRY_CANDLE` already wired L149-150**, OPENING_*/VWAP consts
  L72-76 — **exact** (confirms §3.6 zero-Java).
- `ScalperConfluenceGate.java`: LIVE-only javadoc L29-32, `Decision` L71-87 (`structuralStop` L76),
  `evaluate` L100-280, per-tag stop set L166/178/210/228/244, private `structuralStop` L288-302 (**no
  bank**), ENTRY_CANDLE returns `candle(index).low()/.high()` L293-295, `chart` reads
  `valueAt(SUPERTREND).signum()` L305-307 (direction-only) — **exact** (confirms pass-1 C.4).
- `SignalEngine.java`: `emitEntry` L573-604, structuralStop override L587-589, `target` L590, suggestedQty
  L605-613 (call L608-610). `EmissionGuard` is an **interface** (SPI), `suggestedQty` sig L31-36.
  `OptionsPremiumReplay.exitRules` L262-296 (trailing reads `activate_at`/`trail_by` only). `PositionSizer`
  switch L28-61, `premium_budget` L36-44, `Inputs` L18-19 (no PnL). `GoldenDeterminismTest.FEATURES` =
  exactly 5 L33-36. `TickwiseGoldenRunner.entryLevels` helper L281, `intervalDuration` L354. 8
  `new ScalperConfig(...)` literals in the seam test — **all exact.**
- Gap-source staleness re-verified by ROW TEXT: `two-candle.md` Target **L43**, VWAP-with-volume **L44**,
  PSAR/ST-trail **L45**; `connect-the-dots.md` Target **L40**, RSI-book ladder **L41**, VWAP-with-volume
  **L42**, structural-stop **L43** (the row itself says "add `entry-candle-stop`/`two-candle-pattern`
  tag" — confirms §3.6). `session-additions-and-manual-coverage.md` is the real filename. Pass-1's
  corrected numbers match; the original plan numbers are ~10-13 lines high. **Defect is real and pass-1's
  "find by row text" remedy is correct.**

### B. Pass-1 corrections — CONFIRMED RIGHT, no new error introduced
All four soundness fixes (C.1 volume accessor, C.2 no-`builtinOrAlias`, C.3 intrabar bank, C.4 SUPERTREND
direction-only + private-helper-lacks-bank + premium-replay-ignores) and all four completeness fixes (D.5
zero-Java §3.6, D.6 profit_slice Inputs+call-site, D.7 no springdoc drift, D.8 no StrategyCompiler change)
are each grounded in the actual code. The springdoc claim is correct (the schema is served as an opaque
`String`/untyped body, not a DTO). The `StrategyCompiler` "copies params generically" claim is consistent
with how `levelDistance`/`signalExit` read `params.get(...)` reflectively. **No pass-1 fix is wrong.**

### C. NEW gaps pass-1 AND the author both missed (corrected in place)
1. **§3.7 call-site fan-out understated (real, moderate).** Pass-1 D.6.2 wrote "the only call site is
   `EmissionGuard.suggestedQty`". That is wrong twice: (a) the LIVE sizing call is in `PaperEmissionGuard`
   (the impl), not the `EmissionGuard` interface; (b) there is a SECOND production `PositionSizer.Inputs`
   constructor — **`ReplayEngine.size` (`ReplayEngine.java:283-287`), the deterministic backtest path** —
   plus 6 `ExitEvaluatorTest` literals. Adding a 5th `Inputs` field is a constructor-ARITY break that MUST
   update `ReplayEngine` too. Parity still holds (ReplayEngine passes null/zero `dayPnl`; `profit_slice`
   is never a backtest config), but the fan-out enumeration in §3.7 was incomplete. **Corrected** in the
   §3.7 pass-1 note (added item 3).
2. **§3.1-D `trailing(...)` lacks the bank on the PRIMARY path too (real, compile-blocking).** Pass-1
   Open Point #9 framed the missing bank as ONLY an intrabar problem. But the primary `trailing(...)`
   signature itself (`ExitEvaluator.java:250-255`) takes no `bank` — so the proposed `indicator` case that
   references `bank` will not compile until `trailing`'s own signature is widened and the `:182` call site
   passes the bank. It's a small change (`evaluate` has the bank), but it IS a signature change, not a
   pure in-body addition. **Corrected** the §3.1-D soundness note (item 2) and Open Point #9.

### D. Parity re-assessment — PASS (independently re-derived)
Every signal-affecting change is correctly handled: §3.1-C (`min_volume`), §3.1-D (`points`/`indicator`
trailing) and §3.2-i (`points` level) are **[P]**, touch `ExitEvaluator`/schema (which DO run on the golden
harness), and each carries a NEW generate-once FEATURES fixture — the 5 frozen goldens declare none of the
new types/bases so a new `switch` case / optional param is inert (re-run, assert byte-identity, never
regenerate). §3.3/§3.5/§3.6/§3.8 are live-seam-only (`ScalperConfluenceGate` never runs on the
golden/parity harness — the firewall) → correctly **[P] tag-gated default-OFF + seam-test triple**, can't
perturb the goldens. §3.1-A/B/§3.7 are **[S]** (pre-existing premium-pct engine types already golden-
covered; advisory sizing stamped outside the frozen breakdown). **The profit_slice `Inputs` field (new
gap C.1) is the only thing that newly touches the deterministic `ReplayEngine` — and it is parity-safe
precisely because the new field defaults null/zero there and `profit_slice` is never a backtest method.**
No [S] is secretly signal-moving; no [P] is missing its tag/golden. `GoldenDeterminismTest` +
`BacktestParityTest` stay green.

### E. Sequencing & automatability — SOUND
The Open-Point deferrals are right-sized: §3.2-ii S/R engine (own plan), §3.9 scale-in ladder (breaks the
single-active-entry invariant — own plan), §3.4 per-stock volume exit (equity-gated), the RSI-book /
hourly-erosion partials (need a partial-exit primitive the engine lacks — `ExitEvaluator` exits the WHOLE
position, confirmed). The ST-LINE indicator (Open Point #10) is correctly the gating prerequisite for §3.3
and the `trailing-indicator` golden. No dependency is mis-ordered; no item is over-claimed as automatable
(every "Automatable: true" in the gap-source rows I spot-checked maps to a real, in-scope primitive).

### F. Final readiness verdict
**sound-with-open-points — implementation-ready.** The architecture, the parity design, and the PR
breakdown are correct and the citations (after pass-1's staleness warning + the two pass-2 corrections)
resolve to real code. Before coding: (1) fold the §3.7 `ReplayEngine`/`PaperEmissionGuard` fan-out and the
§3.1-D primary-path bank-widen into the PR-4/PR-3 dev notes; (2) resolve Open Points #9 (bank threading
scope), #10 (ST-LINE indicator: build or drop §3.3 from v1), #11 (no backtest rep for structural trails),
plus the owner-gated #2/#3/#5 (point bands, SL-leeway, exit volume floor). None require design changes —
they are build-time decisions already enumerated.
