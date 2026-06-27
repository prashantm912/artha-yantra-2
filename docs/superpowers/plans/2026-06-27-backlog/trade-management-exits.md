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
  onto the entry event (L166, L206, L235, L266-273). The runner rolls 1m → 3m/5m/15m/1h primaries
  (`intervalDuration`, L354-363).
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
    BigDecimal vol = bank.primarySeries().candle(index).volume()...; // bar volume
    if (vol == null || vol.compareTo(decimal(minVol)) < 0) return Optional.empty();
  }
  return Optional.of(new ExitDecision("signal_exit", text));
}
```

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
case "points" -> {                       // fixed-offset trail: peak ± N points
  BigDecimal off = decimal(params.get("value"));
  yield hit(isLong, close, peak, off) ? ... : Optional.empty();
}
case "indicator" -> {                    // trail to a named indicator level (e.g. psar, supertrend, vwap)
  BigDecimal level = bank.builtinOrAlias(String.valueOf(params.get("alias")), index);
  yield (isLong ? close.compareTo(level) <= 0 : close.compareTo(level) >= 0) ? ... : Optional.empty();
}
```

(The `indicator` basis needs the `IndicatorBank`, so `trailing(...)` must take `bank`, not just
`series` — a signature widen confined to `ExitEvaluator`.) The "RSI-cross exit" + "ride-to-VWAP" are
better modelled as additional **`signal_exit` rules** (`rsi14 < 30`, `close < vwap`) — no new code,
fold into (A)-style variants. The "hourly-new-high erosion" is a genuinely new detector; recommend
**DEFER** to a manual check (no clean primitive; record in Open Points).
**[P]** for `points`/`indicator` trailing → new tag `struct-trail` is NOT needed (it's expressed by the
exit rule itself in a new variant YAML) + new golden FEATURE (`trailing-points`, `trailing-indicator`).

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

Closes gap-theory L18, golden-crossover L25 (S21 support-form SL), completeness-sweep L17. The
`SUPERTREND` indicator is direction-only (+1/-1) in the scoring path, but ta4j computes the ST LINE
price. **Change:** add a new `StructuralStop.SUPERTREND_LEVEL` enum value (`ScalperConfig.java:55-64`)
armed by a `supertrend-level-stop` tag (parse at L149-ish), and in `ScalperConfluenceGate.structuralStop`
(L288+) read the ST line value at the entry bar from the bank and set it as `decision.structuralStop`.
Because the structural stop is LIVE-only (the parity firewall), this is **[P] but parity-safe-by-firewall**
— it never touches the golden replay; still requires the seam-test triple (pass/anchor/non-armed
unaffected) like every existing structural stop. **Alternatively** (and cleaner for backtest fidelity):
add a `trailing_stop basis: indicator, alias: supertrend` rule (§3.1-D) so the ST-trail also works in the
premium replay — recommend BOTH (the structural anchor live + the indicator-trail for backtest).
Tag: `supertrend-level-stop`. New golden FEATURE rides §3.1-D's `trailing-indicator`.

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

Closes connect-the-dots L31. The `StructuralStop` machinery EXISTS; connect-the-dots YAMLs just don't
carry a stop tag (they set `StructuralStop.NONE`). **Change:** add the `entry-candle-stop` (or a new
`two-candle-pattern`-style) tag to a NEW connect-the-dots *variant* YAML so the 1st-candle low (bull) /
high (bear) becomes the persisted stop. This is purely a YAML tag add on a new variant — **[S]** (new
variant, no existing golden; the structural stop is live-only so even the existing config is
parity-inert). If instead armed on the SHIPPED connect-the-dots YAMLs it becomes **[P]** (changes live
emission) → keep it a new variant, default-OFF.

### 3.7 `profit-slice-sizing` (1) — size hero-zero off realised PnL [S]

Closes hero-zero L27 ("deploy ~10% of profits / ₹1-2k"). Today hero-zero sizes off a flat
`budget_inr`. **Change:** a new `position_sizing.method: profit_slice` (schema `risk.position_sizing` +
`PositionSizer` in `libs/strategy-engine/.../eval/PositionSizer.java`) that reads the running realised
PnL (`dayPnl` from the emission guard / RiskService) and caps the premium budget at `pct_of_profit` (e.g.
10%), floored at a min and capped at a max (the ₹1-2k band). Sizing is ADVISORY (`suggested_qty`) — it
does NOT change WHICH signal fires, so **[S]** (parity-neutral, same class as `probability-graded-sizing`
in the sibling stream). Depends on a realised-PnL feed being threaded into the sizer (it exists for the
A12 `suggestedQty` path, SignalEngine L605-609). Ship as a new hero-zero variant.

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
| §3.3 `SUPERTREND_LEVEL` structural anchor | **[P]**¹ | `supertrend-level-stop` tag | none live (firewall); backtest rides `trailing-indicator` |
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

1. **Schema FIRST.** `min_volume`, `points` basis (level + trailing), then the compiler reads them —
   nothing downstream compiles without the schema + `StrategyCompiler` change. (Schema is also the
   springdoc/contract source; confirm `ContractCaptureTest` — `exit_rules` params are inside the request
   body, so a new enum value / param is a contract change → re-capture per CLAUDE.md.)
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
- **PR-3 (M) — engine: structural trailing bases + ST-level anchor.** `trailing_stop` `points`/`indicator`
  bases (signature widen to pass the bank) + golden FEATURES (`trailing-points`, `trailing-indicator`) +
  the `SUPERTREND_LEVEL` structural anchor + `supertrend-level-stop` tag + seam triple.
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
   ~50-100/200-250 pt (risk-framework L50) — but these are INDEX points and the scalper trades the PREMIUM
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

6. **Hourly-new-high erosion exit (completeness-sweep L21) + RSI-laddered partial scale-out
   (connect-the-dots L29).** These need a partial-exit / multi-bucket-scale-out primitive the engine
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
