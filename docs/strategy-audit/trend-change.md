## Trend Change (Siva #12) — automation audit

**Scope:** Audits the §3.12 / §6.12 Trend-Change reversal-capture strategy against its automation: the three
`scalp-trend-change-*.yaml` configs, the `trend-change`-tag-armed `TrendChangeGate` (+ its `MarketStructure`
and `TwoCandleGate` primitives), the shared `ScalperConfluenceGate`/`ScalperGates`/`ConnectTheDotsScorer`
rails, and the OI/VIX/IV inputs (`ScalperGateContext` ← `MarketOiClient` / `ConnectingDotsService`). Judgement
is by code presence, not backtest behaviour; the derived-history caveat (OI/Dow/IV degrade to NEUTRAL on
backtests, so the gate effectively never fires on history) is noted where relevant. **Three structural
facts shape every row: (1) all three YAMLs are `direction: long` / `option_types: [CE]`, so only the
up-reversal half is ever exercised; the bearish down-reversal rules below are dead in these configs. (2) The
`TrendChangeGate` is a LIVE-only seam — it never runs on the deterministic replay/backtest path. (3) `side`
is decided purely by price-vs-VWAP in `ScalperConfluenceGate.evaluate` (L149-152), not by the doc's
swing/OI-defined reversal direction.**

| Rule | Doc § | Status | Evidence (file:line / yaml key) | Gap / manual-check |
|------|-------|--------|----------------------------------|--------------------|
| Identify prevailing trend (up/down/sideways) first via trend lines / price-action swings; reversal only meaningful vs a defined prior trend | 3.12 Setup 1 | PARTIAL | no full trend-classification/trendline engine, but the 1h `bias60m` = `SUPERTREND@1h (7,3.0)` IS a hard live validity AND-term: `ScalperConfluenceGate` L252 passes `bias60m(bank,index)` → `ConnectTheDotsScorer` L111 (`biasAligned`) + L114-115 (in `valid`). All 3 trend-change YAMLs declare it (e.g. `scalp-trend-change-nifty.yaml` L55). v2: this is the same live-only `bias60m` gate the README §5 false-coverage flag #1 corrected for gap-theory. | The 1h-Supertrend prevailing-trend bias IS gated live (unknown ⇒ never blocks; the **backtest** lacks it). What is NOT automated: explicit up/down/sideways classification + trendline structure. Manually classify the prevailing trend. Automatable: partly |
| Swing-structure break: HH/HL→LH/LL (down) or LL/LH→HL/HH (up) **OR** a trendline break in the reversal direction | 3.12 Setup 2-3, Entry b.1 | PARTIAL | `MarketStructure.detect` L41-61 (3-bar fractal swing-high/low taken out by the close); armed via `TrendChangeGate.evaluate` L86-89 | Swing-break is automated; the **trendline-break alternative is NOT** (no diagonal/horizontal trendline engine). Manually confirm a trendline break if no fractal-pivot break printed. Automatable: trendline detection is hard but feasible |
| Trending-OI momentum shift, **≥50% quantified** (CE: call-OI falling + put-OI rising; PE mirror) — the primary confirmation | 3.12 Entry b.2, Filters; 4.3.2 / Day-12; 6.12 | FULL | `TrendChangeGate.oiShift` L98-110 + `MIN_SHIFT_PCT="50"` L47; reads `Oi.ceOiDelta/peOiDelta/callPutDeltaImbalancePct` (`ScalperGateContext` L39-52) | Direction + ≥50% imbalance encoded; null deltas block (L102-104). v3 doc-cite fix: the DIRECTION (call-OI falling/put-OI rising) is §3.12 Entry b.2 (doc L1222), but the literal "**50%**" number is NOT in §3.12 Entry b.2 — it lives in §4.3.2 OI-Spurts (doc L1338-1339, "50% increase in OI") + the §3.12 Day-12 source-ref ">50% call/put OI demarcation confirms direction" (doc L1278). Derived-history caveat: degrades to NEUTRAL on backtests so it never fires there. |
| RSI **above 60** for up-reversal (CE); **below ~40** for down-reversal (PE); 40-60 no-trade band | 3.12 Entry b.3 / r.3, Filters; 4.2 | FULL (CE) / dead (PE) | `ScalperGates.rsiBand` L76-84 (CE 60-80, PE 20-40), wired at `ScalperConfluenceGate` L160 (non-#2 path uses the band). PE branch never reached: YAMLs are CE-only | RSI band is encoded but the gate uses the §4.2 60-80/20-40 band, not a bare ">60"; the PE <40 path is unreachable in these CE-only YAMLs. No manual check needed for CE. |
| **Chart indicators** — Supertrend (10,2), VWMA, Parabolic SAR (0.02,0.2) alignment relative to VWAP/price (the §4.2 "5 Chart Dots") | 6.12 indicators; 3.12 Instruments/Filters; 4.2 | PARTIAL | v2 MISSED-by-v1 row. All 3 YAMLs declare the set on the 3m future: `SUPERTREND (10,2.0)` + `VWMA (20)` + `PSAR` (e.g. `scalp-trend-change-nifty.yaml` L49-54). Each is a SOFT confluence dot in `ConnectTheDotsScorer` — `supertrend` L75, `vwma` L76, `psar` L77 — weighed into the aggregate threshold, not hard gates. (VWAP is the one decisive/hard chart term, L114-115.) | The named §4.2 indicators ARE computed + scored as soft dots, but the doc's "all indicators below/above price" Golden-Cross **alignment** is NOT a hard gate here (`ScalperGates.indicatorAlignment` exists L101-118 but is unused on this path). Manually confirm ST/VWMA/PSAR sit on the correct side. |
| Confirm with **volume increase + follow-up bars** (50K BN / 125K N) on the break | 3.12 Entry b.4 / r.4; 4.2 | PARTIAL | `TwoCandleGate.detect` L52 requires BOTH prior bars over the floor; `ScalperGates.volume` L64-68 (NIFTY 125k / others 50k); also a soft `volume` dot in `ConnectTheDotsScorer` L79 | Per-bar floor on the 2 confirmation candles is enforced, but "**increase** in volume + follow-up bars" (a rising-volume sequence) is NOT modelled — only an absolute floor. Manually confirm volume is expanding, not just above floor. Automatable: yes |
| **2-candle (true-candle) confirmation — enter on the 3rd candle** | 3.12 Entry b.5 / r.5; 3.1 | FULL | `TwoCandleGate.detect` L41-59 (two same-colour bars over floor + strong 2nd body, enter on 3rd) called by `TrendChangeGate.evaluate` L82-84 | Encoded as a hard leg of the gate. |
| Timing window **09:45-14:30** (can print any time 09:45-2:30; avoid morning prints) | 3.12 Setup 6, Filters; 6.12 | PARTIAL | YAML `risk.session.window {from: "09:45", to: "14:30"}`; but the live gate uses `ScalperGates.timeWindow` L33-44 (≥09:45, **blocks 11:00-13:00**, no fresh entry after 15:30) | The live gate's hard 09:45 floor + **11:00-13:00 midday block** + 15:30 cap are STRICTER and differ from the doc's continuous 09:45-2:30 window; a valid ~11:00-13:00 OI-flip reversal (the doc's own Day-10 ~11:00 example!) would be blocked. Manually note: a midday reversal will not auto-fire. |
| Avoid a fresh **DOWN-reversal after ~14:30** ("if after 2.33, avoid it"); after 14:30 both OIs falling = action over | 3.12 Entry r.4, Exits, Filters | FULL (but unreachable) | `TrendChangeGate` `DOWN_REVERSAL_CAP=14:30` L49 + bearish-only check L73-75 | Encoded, but **bearish-only** (`!ce`) and the YAMLs are CE-only, so this cap never engages in the shipped configs. |
| Avoid morning prints / wait for intraday trend (a naked wrong entry can lose 50-70% in 2-3 candles) | 3.12 Setup 6, Risk | MANUAL_COVERED | `ScalperManualChecks` `not_parabolic` (L37-40, §3.1) + the 09:45 floor | Covered by the not-parabolic manual check + the time floor. |
| **VWAP is the patience/defend line:** hold to VWAP; exit only on a VWAP break, and only a **volume-backed** VWAP break invalidates | 3.12 Exits, Stop-Loss, Filters; 6.12 | PARTIAL | Entry `gate.all: ["close > vwap"]`; exit `signal_exit {rule: "close < vwap"}` (all 3 YAMLs); VWAP is the decisive hard gate in `ConnectTheDotsScorer` L114-115 | Exit fires on ANY close<VWAP; the doc's "**VWAP break WITH volume**" qualifier is NOT modelled — a no-volume VWAP wick exits prematurely. Manually judge whether a VWAP break carried volume before honouring it. Automatable: yes |
| **Stop-loss:** no numeric SL; structural — broken swing pivot / 1st-candle extreme / VWAP per Global Risk | 3.12 Stop-Loss; 6.12 | FULL | `TrendChangeGate.evaluate` returns `structure.pivot()` L90; wired as `structuralStop` at `ScalperConfluenceGate` L210; `ScalperConfig` `StructuralStop.SWING_BREAK` L141-142 | Broken-swing-pivot SL anchor encoded (the documented, bar-derivable anchor). |
| **~10-20-pt benefit-of-doubt** SL leeway, ONLY when OI convincingly confirms | 3.12 Stop-Loss, Risk | NONE | not in code; YAML header L19-21 explicitly defers it to "live SL-management" | The persisted stop is the raw pivot; the ±10-20pt pad is deliberately unencoded. Manually apply leeway only with convincing OI. Automatable: yes but intentionally deferred |
| **Target:** ride to VWAP / "exit once it moves in favour"; no fixed point target (~400pt is example only) | 3.12 Exits; 6.12 (uncertain) | PARTIAL | exit = `signal_exit close<vwap` + `time_stop max_bars: 30` (all 3 YAMLs) | No profit target by design (doc gives none); exit is VWAP-break or a 30-bar time-stop. Manually manage the ride-to-VWAP target; the time-stop is a crude proxy. |
| Trending-OI **crossover** marks the shift; **both OI graphs climbing together = strict AVOID** as a buyer | 3.12 Filters; 6.12 | PARTIAL | `TrendChangeGate.oiShift` requires `ceDelta<0 && peDelta>0` (CE) L105-109 — both-rising fails the directional test → blocks. Soft `trending_cross` dot in scorer L83/L125-134 | The directional sign-test implicitly rejects "both climbing" (both-positive can't satisfy `ceDelta<0`), so the AVOID is effectively enforced for the gate; not a separately-named "both-up = avoid" rule. Adequate. |
| **VIX rising into the reversal direction supports it**; flat VIX warns | 3.12 Filters; 6.12 | PARTIAL | `ScalperGates.vix` L136-143 (soft dot, `vixRising` from `Macro`); fed by `ConnectingDotsService.vixFactor` L263-270; manual `vix_normal` check (`ScalperManualChecks` §4.5) | VIX direction is a **soft confluence dot only** — it nudges the aggregate, it is NOT a trend-change-specific hard gate, and unknown direction never blocks (L137-138). Manually confirm VIX is moving with the reversal. Automatable: already a dot |
| **India VIX not abnormally spiking** (gap/whipsaw risk) | 3.12 Risk (regime) | MANUAL_COVERED | `ScalperManualChecks` `vix_normal` (§4.5) | Covered by the manual checklist. |
| **Index-contribution / heavyweights** (Reliance, Infosys, TCS, banks) must support the new direction | 3.12 Filters, Edge-cases; 6.12 | NONE | no heavyweight/index-contribution analytics anywhere in the scalper path | Manually check that the index heavyweights support the reversal direction. Automatable: yes (per-constituent contribution feed) but not built |
| **Support/Resistance from seller OI** (max call OI = resistance, max put OI = support) defines the range that must break | 3.12 Filters; 6.12 | PARTIAL | `ScalperManualChecks` `level_respected` (§4.11); `StrikePicker`/`HeroZeroStrikeSelector` read `strikeOi` but not for an S/R-range gate on trend-change | The max-OI S/R range is NOT computed as a gate for trend-change; only the generic manual `level_respected` covers it. Manually mark max-CE/PE-OI S/R. Automatable: yes |
| **Intraday-bearish but positionally-bullish** (or mirror) precondition; flip confirms when BOTH intraday + positional OI rotate the same way | 3.12 Setup 5; 6.12 | NONE | the gate reads a single windowed OI delta snapshot (`Oi`), no intraday-vs-positional split | Manually confirm intraday AND positional OI have both rotated. Automatable: partly (needs a positional/day-cumulative OI series alongside the intraday window) |
| **News overrides data** on gap/event/war days — trade smaller / confirm post-open | 3.12 Edge-cases; 6.12 | MANUAL_COVERED | `ScalperManualChecks` `news_clear` (§2.13) | Covered by the manual checklist. |
| Data leads price by **~15-30 min** — prepare on the OI but still wait for volume + 2-candle confirm | 3.12 Exec notes; 6.12 | FULL (implicit) | the gate requires the OI shift AND the 2-candle/structure price confirm together (`TrendChangeGate.evaluate` L78-89) | The "wait for price confirmation" discipline is structurally enforced (both halves required). |
| Failed-attempt (1-2-3) reversal; confirm down-reversal with 2-3 consecutive red bars >125K | 3.12 Edge-cases (Day 03) | NONE | no multi-failed-attempt detector; `TwoCandleGate` checks only the 2 bars before entry | Manually spot the 1-2-3 failed-attempt pattern. Automatable: yes but niche |
| Trendline/structure pivot held + deep PSAR bounce cue (Day 12) | 3.12 Edge-cases | PARTIAL | PSAR is a soft `psar` dot (`ConnectTheDotsScorer` L77); no "held prior-day trendline" detector | PSAR position is scored; the held-trendline pivot is not. Manually note a held prior-day pivot. |
| Consolidation: OI added on BOTH sides after a flip = pause (not continuation); genuine reversal needs hourly unwinding-volume beaten + LB/SC | 3.12 Edge-cases (Day 07) | NONE | no both-sides-building / hourly-unwinding-volume detector | Manually check OI is not building on both sides (consolidation). Automatable: partly |
| Post-vertical bounce caution: after a vertical fall, don't reverse until **RSI recovers toward ~40** and a defined level prints | 3.12 Edge-cases (Day 07) | NONE | the CE RSI band (60-80) is unrelated; no oversold-recovery sequencing | Manually wait for RSI recovery toward ~40 + a level after a vertical fall. Automatable: yes |
| Don't chase a side when **premiums are higher on that side with no positive cues** | 3.12 Risk; 6.12 | NONE | no per-side-premium-skew warning in the trend-change path (IV-pair dot L97 is a different signal) | Manually avoid chasing into a higher-premium side without cues. Automatable: yes (per-side premium/IV skew) |
| **Strong-trend / late entry is hard to catch — wait for pullbacks to support, do NOT chase** (once you miss the move) | 3.12 Exec notes; 6.12 edge-cases | MANUAL_COVERED | v3 still-missing add. No "missed-the-move ⇒ wait for a pullback" detector in the scalper path; the discretion is covered by `ScalperManualChecks` `not_parabolic` (§3.1) + `clean_setup` (§3.1) | Doc §3.12 Exec-notes (doc L1270) / §6.12 edge_cases (doc L2916): on a strong reversal day, once the move is missed, wait for a pullback to support — don't chase. Covered only by the not-parabolic / clean-setup manual checks. Manually wait for a pullback rather than chasing a strong-trend entry. |
| Scale expectations to regime (low-VIX expiry: a 10-15pt move is "a big hit") | 3.12 Risk; 6.12 | MANUAL_COVERED | `ScalperManualChecks` `regime_ok` (§3.10) + `vix_normal` (§4.5) | Covered by the regime/VIX manual checks. |
| Global risk: sizing + daily-loss cap | 3.12 Risk (Global §2) | PARTIAL | YAML `risk.position_sizing {premium_budget 15000}` IS read (`StrategyCompiler` L66-69 → `SizingSpec`). v2 correction: `max_daily_loss_pct: 2.0` + `max_positions: 1` / `max_positions_per_underlying: 1` are **DEAD YAML keys** — `StrategyCompiler` reads ONLY `position_sizing.method`/`params` from the risk block (L65-69); no read of `max_positions*` or `max_daily_loss_pct` anywhere in `strategy-engine` / `strategy-signal-service`. The only daily-loss mechanism is the separate paper-runtime `RiskService.DAILY_LOSS = "daily_loss_limit"` setting (off by default), not this YAML key. | Position-SIZING is encoded; the **daily-loss cap + max-positions are not enforced from the YAML** (dead keys, per README §4). Manually rely on the paper-trade `daily_loss_limit` runtime setting for a daily cap. Automatable: yes (wire the keys) |
| Instruments: buy CE / sell PE / buy futures (up); buy PE / sell CE / sell futures (down) | 3.12 Entry b.6 / r.6; 6.12 | PARTIAL | YAMLs are `direction: long`, `option_types: [CE]` (long-premium CE only) | Only the **buy-CE** up-reversal leg is automated; sell-PE, buy/sell-futures, and the entire **down-reversal (buy-PE)** are NOT shipped. Manually trade the bearish side / futures legs if desired. Automatable: yes (a PE-direction YAML) |

### Not automated (gaps)

- **Whole bearish down-reversal half is dead in shipped configs** — all three YAMLs are CE/long-only, so the doc's PE entry rules, the PE RSI<40 trigger, and the bearish-only 14:30 cap never engage (the code exists but is unreachable). A `direction: short` / PE trend-change YAML is missing.
- **Trendline-break alternative trigger** (§3.12 Setup 3 / Entry b.1) — only the fractal swing-pivot break is automated; no diagonal/horizontal trendline engine.
- **Index-contribution / heavyweight support** (§3.12 Filters) — no constituent-contribution analytics in the scalper path at all.
- **Intraday-vs-positional OI rotation precondition** (§3.12 Setup 5) — the gate reads one windowed OI delta, not the dual intraday+positional confirmation.
- **Volume must be *increasing* + follow-up bars** (§3.12 Entry b.4) — only an absolute per-bar floor is enforced, not a rising-volume sequence.
- **VWAP break must carry volume to invalidate** (§3.12 Exits) — the `close < vwap` exit fires on any break, ignoring the volume qualifier.
- **~10-20-pt benefit-of-doubt SL leeway** (§3.12 Stop-Loss) — deliberately deferred to live SL-management; the persisted stop is the raw pivot.
- **Max-OI S/R range as a hard gate** (§3.12 Filters) — only the generic `level_respected` manual check covers it.
- **Edge-case detectors**: 1-2-3 failed-attempt reversal, both-sides-building consolidation / hourly-unwinding-volume, post-vertical RSI-recovery sequencing, and the higher-premium-no-cues warning — none are automated.
- **Midday-window mismatch**: the live `ScalperGates.timeWindow` blocks 11:00-13:00, contradicting the doc's continuous 09:45-2:30 window — the doc's own Day-10 ~11:00 reversal example would be blocked. (Not a gap to fill, but a documented behaviour divergence the trader must know.)
- **Daily-loss cap + max-positions are dead YAML keys** (v2): `max_daily_loss_pct: 2.0`, `max_positions: 1`, `max_positions_per_underlying: 1` are present in all 3 YAMLs but read by NO compiler/engine code (`StrategyCompiler` reads only `position_sizing`). The only live daily-loss cap is the paper-runtime `daily_loss_limit` setting (off by default). Wire the YAML keys, or rely on the runtime setting.
- **Golden-Cross indicator alignment as a hard gate** (v2): the §4.2 "all indicators below/above price" alignment exists as `ScalperGates.indicatorAlignment` but is UNUSED on the scalper confluence path; ST/VWMA/PSAR are only soft dots. Promoting alignment to a hard AND-term would match the doc's "all soldiers on the far side" framing.

### v2 review notes

Independent second-pass review of the §3.12 / §6.12 Trend-Change audit. v1 was strong: the 4-leg
`TrendChangeGate` (structure break + ≥50% OI shift + 2-candle + 14:30 down-cap), the CE-only / live-only /
VWAP-decides-side structural facts, and all spot-checked file:line citations in v1 (`TrendChangeGate`,
`MarketStructure`, `TwoCandleGate`, `ConnectTheDotsScorer` L74-97/L114-115, `ScalperGates.vix` L136-143,
the `ScalperManualChecks` codes) were re-verified as accurate. Changes made:

- **MISSED — added 1 row:** the §4.2 / §6.12 **core chart-indicator set (Supertrend 10,2 / VWMA / PSAR)**.
  v1 listed PSAR only inside a Day-12 edge-case row but never credited the named indicator family that
  §6.12's `indicators` array calls out and that all 3 YAMLs declare + the scorer scores (soft dots, L75-77).
  Marked PARTIAL: computed/scored as soft dots, but the doc's "all indicators on the far side" alignment is
  not a hard gate (`indicatorAlignment` exists but is unused on this path).

- **INACCURATE (false-gap) — "Identify prevailing trend first" NONE → PARTIAL.** v1 missed that the 1h
  `bias60m = SUPERTREND@1h` IS a hard live validity AND-term (`ScalperConfluenceGate` L252 →
  `ConnectTheDotsScorer` L111,L114-115), declared by all 3 YAMLs. This is the SAME live-only `bias60m`
  automation the audit README §5 false-coverage flag #1 corrected for gap-theory; it applies identically
  here (a directional-trend bias the doc's Setup-1 asks for). The trendline/structure-classification half
  remains unautomated, hence PARTIAL not FULL. (Backtest still lacks `bias60m`.)

- **INACCURATE (false-coverage) — "Global risk: sizing + daily-loss cap" FULL → PARTIAL.** v1 cited
  `max_daily_loss_pct: 2.0` + `max_positions: 1` as encoded. Verified against `StrategyCompiler` L65-69:
  only `position_sizing.method`/`params` is read from the risk block; `max_daily_loss_pct` and
  `max_positions*` are **dead YAML keys** (no read anywhere in `strategy-engine` / `strategy-signal-service`;
  the only daily-loss cap is the separate paper-runtime `RiskService.DAILY_LOSS = "daily_loss_limit"`,
  off by default). Matches the audit README §4 dead-key note. Position-sizing alone is genuinely encoded.

No v1 rows were deleted; the two corrected rows keep their original doc-§ and now carry corrected
status + evidence. Two gap-list bullets were added (dead daily-loss/max-positions keys; unused
Golden-Cross alignment). All claims trace to a re-read file:line or YAML key.

### v3 review notes

Third-pass citation-validation. Every row's `file:line` / `yaml key` / `doc-§` was re-opened and confirmed.
The v2 state was strong; the body is **stable / converged**.

**Citation validation — all code/YAML citations re-opened and confirmed accurate** (line numbers were
verified, not assumed):
- `TrendChangeGate`: `oiShift` L98-110, `MIN_SHIFT_PCT="50"` L47, null-delta block L102-104, the CE
  `peDelta>0 && ceDelta<0` test L105-109, `DOWN_REVERSAL_CAP=14:30` L49 + bearish-only `!ce` cap L73-75,
  `evaluate` legs L78-89, `structure.pivot()` returned L90 — all present and accurate.
- `MarketStructure.detect` L41-61 (3-bar fractal swing, close beyond recent opposing pivot) ✓.
- `TwoCandleGate.detect` L41-59, both-bars floor `||` at L52, called from `TrendChangeGate` L82-84 ✓.
- `ScalperConfluenceGate`: VWAP-decides-side L149-152, `rsiBand` wired L160, trend-change branch +
  `structuralStop = tc.stopLevel()` L210, `bias60m(bank,index)` → scorer L252, `indicatorAlignment`
  exists but **unused on this path** (confirmed: the only `requireXxx` branches in `evaluate` are
  twoCandle/gapFill/trendChange/openHighLow/heroZero — `indicatorAlignment` is never called) ✓.
- `ConnectTheDotsScorer`: soft dots `supertrend` L75, `vwma` L76, `psar` L77, `volume` L79,
  `trending_cross` L83 (method L125-134), `iv_pair` L97; `biasAligned` L111; `valid` (hard VWAP + bias +
  threshold) L114-115 — all accurate.
- `ScalperGates`: `timeWindow` ≥09:45 / 11:00-13:00 block / 15:30 cap L33-44, `volume` L64-68,
  `rsiBand` 60-80/20-40 L76-84, `indicatorAlignment` L101-118, `vix` (unknown never blocks) L136-143 ✓.
- `ScalperGateContext` Oi record (`ceOiDelta`/`peOiDelta`/`callPutDeltaImbalancePct`) L39-52 ✓.
- `StrategyCompiler` L65-69 reads ONLY `risk.position_sizing.method`/`params` (+ `risk.session` L82);
  re-confirmed **no read** of `max_positions*` / `max_daily_loss_pct` — the v2 dead-key finding holds.
- `ConnectingDotsService.vixFactor` L263-270 ✓.
- `ScalperManualChecks` codes: `news_clear` §2.13, `level_respected` §4.11, `not_parabolic` §3.1,
  `regime_ok` §3.10, `vix_normal` §4.5 — all present at the cited keys/§.
- All 3 YAMLs re-opened: `bias60m = SUPERTREND@1h(7,3.0)` (nifty L55), the §4.2 indicator set
  (nifty L49-54), `direction: long`/`option_types:[CE]`, `close > vwap` gate, `signal_exit close<vwap` +
  `time_stop max_bars:30`, the dead `max_positions`/`max_daily_loss_pct:2.0` keys, the SL-leeway header
  comment (nifty L19-21), and `position_sizing premium_budget budget_inr:15000` (L70) — all confirmed.
  (The two SENSEX YAMLs carry the same keys shifted +1 line by an extra `strike_reference` universe
  entry — the rows cite the named nifty file, so the line numbers are correct as written.)

**Changes made:**
- **Doc-cite fix (row "Trending-OI ≥50% shift")**: added `4.3.2 / Day-12` to the doc-§. The DIRECTION
  is §3.12 Entry b.2 (doc L1222), but the literal **"50%"** number is NOT in §3.12 Entry b.2 — it lives
  in §4.3.2 OI-Spurts (doc L1338-1339) + the §3.12 Day-12 source-ref ">50% call/put OI demarcation"
  (doc L1278). Status stays FULL (`MIN_SHIFT_PCT=50` is genuinely encoded); only the citation was made
  provable. No status overturned by any validation.
- **Still-missing rule added (1 new row)**: "**strong-trend / late entry — wait for pullbacks, don't
  chase**" (§3.12 Exec-notes doc L1270 / §6.12 edge_cases doc L2916). Both prior passes omitted it; it is
  a discretionary "missed-the-move ⇒ wait for a pullback" cue with no detector — marked MANUAL_COVERED
  (the `not_parabolic` + `clean_setup` manual checks carry the don't-chase discipline).

**Convergence:** stable. No status was overturned by citation validation; the v1/v2 body is accurate.
Only one doc-§ precision fix + one genuinely-still-missing discretionary rule. The remaining §3.12
doc rules are all represented.
