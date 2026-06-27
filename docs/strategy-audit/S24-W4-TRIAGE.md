# S24-W4-TRIAGE — disposition of the 95 S24-only adds

**Input:** the `S24-only` column of [S24-COMPARISON.md](S24-COMPARISON.md) — **95** rules across
18 scopes that the debloated S24 doc introduces or sharpens over the OLD consolidated concept.
**Already done upstream:** [W1](../../strategy-documents/options-scalper-siva-operative/Options_Scalper_Siva_Operative_Strategy.md)
folded **all 95** into the operative doc (the S24-only doc IS its base), and
[W3](../superpowers/plans/2026-06-27-w3-engine-drift-impl.md) wired the 6 ratified *drift*
fixes (RSI bands / delta floor / premium band / point-SLs / OIP-AI %/veto), all default-OFF.

So W4 is **not** "add doc content" (the doc already has it) and **not** the drift reconciliation
(W3 did that). W4 isolates, from the 95, the rules that imply a **concrete deterministic engine
behaviour not yet wired** — the forward-list of optional parity-safe tags the owner can build
*after* forward-paper — and confirms the rest are captured (DOC) or already live (DONE) or
deferred tooling (SKIP).

## Buckets

| Bucket | Meaning | Action |
|---|---|---|
| **DOC** | Judgment / narrative / worked-example / illustrative number — captured in the operative doc as a human read. | None. Already in operative doc. |
| **DONE** | A concrete threshold that is **already wired** (W3 tag, an existing `ScalperOiProps`/gate default, or a live factor). | None. Cross-ref noted. |
| **PARAM** | A concrete deterministic threshold/gate **not yet wired** — a future parity-safe default-OFF tag candidate. | Forward-list §2. Owner-gated, build after forward-paper. |
| **SKIP** | Tool-UI primitive or pure-educational context — deferred per the debloat philosophy (matches the 72 bloat-drops). | None. |

## Counts (95) — one primary bucket per rule

- **DOC: 51** — the dominant bucket; S24's "new" is mostly sharper *prose* (2025 numbers, named
  reads, worked examples), already faithfully in the operative doc.
- **DONE: 9** — already wired by W3 or pre-existing engine defaults/factors (§2 lists 11
  cross-refs because 2 of these rules are wired in two places).
- **PARAM: 27** — distinct deterministic gate/threshold candidates (12 high-value, 15 low/dup).
- **SKIP: 8** — tool-UI colour-codes + pure-educational anchors.

> The headline: **of 95 S24-only adds, only ~12 are high-value un-wired engine candidates.** The
> rest are doc-captured judgment (52), already live (11), low-value/duplicative params (12), or
> deferred tooling (8). S24 is overwhelmingly a *prose sharpening*, not a backlog of new code.

---

## 1. The high-value PARAM forward-list (12) — prioritized

Each is a deterministic threshold/gate the engine does **not** carry today, build-able as a
parity-safe default-OFF tag exactly like the W3 six (WORLD-2 scalper gate ⇒ goldens inert).
**All owner-gated; validate on forward-paper with real OI, never a derived-history backtest.**

| # | Candidate | Source rule(s) | Why it matters | Proposed tag | Effort |
|---|---|---|---|---|---|
| **1** | **Daily-loss cap reconcile 10-12% vs engine 2%** | Shared-S1-risk.1 (drift #36) | Live engine caps daily loss at 2% (YAML `2.0`); S24 doc says 10-12% single-day. A real divergence in a **risk** rail — must be reconciled by owner, not silently kept. | n/a (config value, owner ruling) | S |
| **2** | **Daily-RSI cool-off caps (>70 no-long / 80 book / 25-30 short-caution)** | S3.2 (drift #3) | Market-Movers daily-RSI caps, distinct from the intraday W3 bands; S24 tightens the OLD 75 cap → 70. Not wired. | `daily-rsi-caps` | M |
| **3** | **Hero-Zero per-side OI thresholds (CE≥50% / PE~70-78%+85%)** | S7.3 (Day-17) | Concrete per-side OI gates for expiry-day side selection; HeroZeroGate has no such threshold. | `herozero-side-oi` | M |
| **4** | **BTST carry-validity gate (no VWAP/ST breach + hold into close)** | S8.1 (drift #17) | Replaces the OLD close-at-day-high/OI-quadrant carry test with a price-action gate; engine BTST has the old form. | `btst-carry-validity` | M |
| **5** | **`indicators-far-from-candles = avoid` distance gate** | S10.8 (**genuinely NEW**, not re-emphasis) | A no-trade gate when price has run too far from VWAP/ST/EMA cluster; nothing equivalent today. | `indicator-distance-veto` | M |
| **6** | **ST↔VWAP no-trade zone** | S6.3 | Suppress entries while price sits between SuperTrend and VWAP (chop). Golden-Crossover-specific. | `st-vwap-no-trade` | S |
| **7** | **Intraday+positional OI must-agree (5cr / 10-12cr)** | S5.2, Shared-S2.2 | Dual-timeframe OI confirmation; engine reads one OI window. Concrete crore thresholds. | `oi-dual-timeframe` | M |
| **8** | **Fake-crossover exit + 2-3-crosses = avoid-day** | S5.4 | Trending-OI whipsaw guard: exit on a re-cross, stand aside after 2-3. | `oi-crossover-whipsaw` | M |
| **9** | **Combined-premium-VWAP whole-day directional gate (straddle)** | S11.3 (Day-17) | A straddle/strangle directional gate off the combined-premium vs its VWAP. | `combined-prem-vwap` | M |
| **10** | **Divergence counter-trend 125K-volume gate** | S12.1 (Day-21) | Trend-Change requires a ≥125K volume confirm before a counter-trend divergence entry. | `divergence-vol-gate` | S |
| **11** | **Intraday VWAP-anchor switch ~10:30** | S10.4 (rel. drift §305) | Switch the VWAP stop anchor from yesterday's to today's at ~10:30; engine anchors statically. | `vwap-anchor-1030` | M |
| **12** | **Trending-OI-gap exit >50-60K** | Shared-S1-risk.4 | Exit when the OI-gap confidence collapses below the 50-60K band; an exit rail, not entry. | `oi-gap-exit` | S |

**Low-value / duplicative PARAM (15, not individually prioritized):** S1.1 overbought-defer RSI>85
(HeroZero already caps at 80), S1.2 trailing-prev-candle SL mode, S2.1 3-recovery-candle count
(≥50K floor already wired), S2.2 Day-20 quadrant precondition, S2.3 round-strike weighting, S4.1
gap volume-direction validity, S5.1/Shared-S2.1 15-strike chain-read width, S8.2 profit-protection
exit (≥80-90% recovered), S9.2 gap-size side gate, Shared-S2.5 9:45-2:30 window reconcile (engine
keeps a midday block — drift #42), Shared-S5.5 Sensex participation gate, Shared-S6.3 FII LSR-primary
vs participant-matrix. Defer until a high-value tag motivates the surrounding work.

---

## 2. DONE — already wired (11)

| Rule | Where wired |
|---|---|
| S2.4 abort = >50% premium-fall + >50% OI-rise crossover | W3 PR-6 `open-high-oi-veto` (#256) |
| S2.6 Day-6 baseline ≥3-strikes confirm | `ScalperOiProps` `minStrikes=3` (default) |
| S4.3 ~50-60pt SL (the SL half) | W3 PR-4 `index_points` basis (#254) |
| S6.2 30-40pt SL (the SL half) | W3 PR-4 `index_points` basis (#254) |
| S10.1 RSI zone 50-75 buy / 40-50 no-trade | W3 PR-1 `rsi-s24-bands` (#251) |
| S10.1 OB80 / OS20 | HeroZero `RSI_OVERBOUGHT=80 / RSI_OVERSOLD=20` |
| S11.2 ~50% OI-gap sentiment | existing ≥50% ΔOI pre-gate (#5) |
| S12.2 monthly-expiry ignore-OI | `MarketCalendar.isMonthlyIndexExpiryDay` → `MarketOiClient` suppress |
| Shared-S4.1 Dow = primary US30 (live LTP) | `ConnectingDotsService` Dow factor (task #13) |
| S9.4 delta ~0.80 sizing read | within W3 PR-2 delta band 0.7-0.8 (#252) |
| Shared-S5.4 prefer-150+ premium | W3 PR-3 premium band N 150-350 (#253) |

---

## 3. SKIP — tool-UI / pure-educational (8)

S5.5 OI-sentiment colour code (UI), Shared-S4.9 VIX historical anchors (educational), Shared-S4.8
erosion-day 24,800 worked example (illustrative — borderline DOC), S3.3 2025 liquidity reads
Maxhealth/PayTM (illustrative), S4.4 99% fill-rate framing (illustrative), S8.5 hit-rate 6-7/10
(meta), S6.4 / S10.10 reused-deck provenance caveats (meta). All consistent with the 72 bloat-drops;
present in the operative doc only as context, never as a rail.

---

## 4. Full 95-row disposition

> Rule text abbreviated from S24-COMPARISON §4 `S24-only` lines; `[n]` = the COMPARISON source line.

### Per-strategy (12 scopes)

| Scope | Rule | Bucket | Note |
|---|---|---|---|
| S1 Two Candle [139] | Overbought DEFER RSI>85→cool 70-80→pullback | PARAM (low) | HeroZero caps 80 already; general two-candle un-wired |
| S1 [139] | Trader-type SL split (scalper trail prev-candle / positional 1st-candle) + deep-SL sizing | PARAM (low) | partly structuralStop; trailing-mode un-wired |
| S2 Open=High [151] | ≥50K on 3 consecutive recovery candles + 70-80-90% ladder | PARAM (low) | ≥50K floor wired; candle-count un-wired |
| S2 [151] | Day-20 directional-change precondition (quadrant) | PARAM (low) | OiQuadrant exists; precondition un-wired |
| S2 [151] | Round strikes weigh more | PARAM (low) | StrikePicker round-strike weight un-wired |
| S2 [151] | Abort = >50% premium-fall + >50% OI-rise crossover | **DONE** | W3 PR-6 veto |
| S2 [151] | ~90% reverse-on-tag | DOC | statistical read |
| S2 [151] | Day-6 baseline ≥3-strikes confirm | **DONE** | minStrikes=3 |
| S2 [151] | Day-14 positional-override worked example | DOC | worked example |
| S3 Market Movers [163] | Futures-only, strict NO stock options | DOC | strategy `universe` config, not engine |
| S3 [163] | Daily-RSI >70 / 67-68 / 80 / 25-30 | **PARAM #2** | daily-RSI caps, drift #3 |
| S3 [163] | 2025 liquidity reads (Maxhealth 150K / PayTM 100K) | SKIP | illustrative |
| S3 [163] | "3-4%+ already → 1-2% more" digest | DOC | judgment |
| S4 Gap [175] | Volume-DIRECTION validity (with-volume = valid) | PARAM (low) | gap volume-direction gate |
| S4 [175] | Bearish texture / fill-without-volume (Day 17) | DOC | judgment |
| S4 [175] | 30-60min time-box + ~50-60pt SL (Day 21) | **DONE** (SL) + PARAM (low, time-box) | SL = W3 PR-4; time-box = `time_stop` |
| S4 [175] | 99% live fill-rate framing | SKIP | illustrative |
| S5 Trending OI [187] | 15-strike (7+7+ATM) read | PARAM (low) | chain-read width |
| S5 [187] | Intraday+positional must agree (5cr / 10-12cr) | **PARAM #7** | dual-timeframe OI |
| S5 [187] | Crossover-not-required-on-wide-gap (Day 8) | DOC | gate relaxation read |
| S5 [187] | Fake-crossover exit + 2-3-crosses = avoid-day | **PARAM #8** | whipsaw guard |
| S5 [187] | OI-sentiment colour code (Day 15) | SKIP | UI |
| S5 [187] | Trending-down expiry read (11-12cr / 4-5cr) | DOC | illustrative |
| S5 [187] | New-series contradiction discipline (Day 20) | DOC | judgment |
| S6 Golden Crossover [199] | Clustered-indicators warning | DOC | judgment |
| S6 [199] | Dip-buy pyramiding 20%@ST / 80-90%@VWAP, SL 30-40pt | **DONE** (SL) + DOC (sizing) | SL = W3 PR-4; pyramiding = sizing policy |
| S6 [199] | No-trade-zone ST↔VWAP range | **PARAM #6** | chop suppression |
| S6 [199] | Reused-deck provenance caveat | SKIP | meta |
| S7 Hero-Zero [213] | Size ~10% of PROFITS never capital | DOC | sizing policy |
| S7 [213] | Direction by second-half flow | DOC | judgment |
| S7 [213] | Day-17 per-side OI thresholds (CE≥50% / PE~70-78%+85%) | **PARAM #3** | side-selection gates |
| S7 [213] | Premium-level low-vs-high ITM bifurcation | DOC | strike judgment |
| S7 [213] | VIX+OI two-sided framing (Day 21) | DOC | judgment |
| S8 BTST [225] | Validity gate (no-VWAP/ST-breach + hold into close) | **PARAM #4** | carry-validity, drift #17 |
| S8 [225] | Profit-protection override (≥80-90% recovered → square off) | PARAM (low) | exit override |
| S8 [225] | News-rally distrust (2-3 days) | DOC | judgment |
| S8 [225] | Near-expiry last-30-min trap | DOC | time judgment |
| S8 [225] | Hit-rate honesty 6-7/10 | SKIP | meta |
| S9 Morning [239] | Pre-market settle ~9:07-8 / ignore ±200pt | DOC | pre-market read |
| S9 [239] | Gap-read for side (300-400 no-put / 30-40 short-once) | PARAM (low) | gap-size side gate |
| S9 [239] | Pre-market heavyweights +2-4% / ~80-100 Nifty pts | DOC | breadth read |
| S9 [239] | Sizing ~10-20% + prev-day-profit-as-SL + delta~0.80 | DOC | sizing; delta within W3 0.7-0.8 |
| S9 [239] | Slight-ITM → rotate-higher rotation | DOC | strike rotation judgment |
| S9 [239] | Day-20 gap-up-overbought | DOC | read |
| S10 Connect-Dots [253] | Full RSI zone OB80 / OS20 / 40-50 no-trade / buy50-75 | **DONE** | W3 PR-1 + HeroZero OB/OS |
| S10 [253] | Hourly-new-high cadence 20→60→90→110pts | DOC | continuation read |
| S10 [253] | Support-strength tiering weak/strong/very-strong | DOC | S&R tiering judgment |
| S10 [253] | Intraday-VWAP switch ~10:30 | **PARAM #11** | VWAP-anchor switch |
| S10 [253] | Discount-premium read | DOC | judgment |
| S10 [253] | Breadth+heavyweight cluster | DOC | judgment |
| S10 [253] | Recycle-profit re-enter-lower | DOC | sizing/re-entry |
| S10 [253] | Indicators-far-from-candles = avoid (**NEW**) | **PARAM #5** | distance veto, genuinely new |
| S10 [253] | VIX live-clarification | DOC | judgment |
| S10 [253] | Reused-deck provenance guard | SKIP | meta |
| S11 Straddle [267] | Day-character "who is winning" read | DOC | judgment |
| S11 [267] | ~50% OI-gap as buy-the-dip sentiment (Day 5) | **DONE** | ≥50% ΔOI pre-gate |
| S11 [267] | Combined-premium-VWAP whole-day directional gate (Day 17) | **PARAM #9** | straddle directional gate |
| S12 Trend Change [279] | Divergence counter-trend 125K-volume gate (Day 21) | **PARAM #10** | volume confirm |
| S12 [279] | Monthly-expiry caveat (ignore OI) | **DONE** | MarketCalendar suppress |
| S12 [279] | Dual-confirmation crisp VWAP-broken-AND-OI-changed | DOC | dual-confirm read |

### Shared (6 scopes)

| Scope | Rule | Bucket | Note |
|---|---|---|---|
| Shared-S1-risk [293] | 10-12% single-day hard cap | **PARAM #1** | reconcile vs engine 2%, drift #36 |
| Shared-S1-risk [293] | Geometric 1/2/4/8→16 pyramiding | DOC | sizing policy |
| Shared-S1-risk [293] | Volatility sizing ~4pt / 100-200pt | DOC | sizing policy |
| Shared-S1-risk [293] | Trending-OI-gap confidence + >50-60K exit | **PARAM #12** | OI-gap exit rail |
| Shared-S1-risk [293] | Recycle-profit + never-contra-trade (RSI 20→9) | DOC | discipline |
| Shared-S2 [305] | 15-strike "beats 5/9/11" | PARAM (low) | dup S5 chain-read width |
| Shared-S2 [305] | Intraday+positional both-agree | PARAM (low) | dup PARAM #7 |
| Shared-S2 [305] | VIX ladder 18-20+ / 20-25+ | DOC | VIX bands (drift #32/#39/#44) |
| Shared-S2 [305] | Low-VIX 90%-bounce | DOC | VIX read (split from ladder row) |
| Shared-S2 [305] | Kingdom chess mnemonic | DOC | mnemonic |
| Shared-S2 [305] | Explicit 9:45-2:30 window | PARAM (low) | reconcile vs engine midday block, drift #42 |
| Shared-S3-oi [317] | Classify-by-close-in-range | DOC | candle classification |
| Shared-S3-oi [317] | Decode-from-ATM 5-6 strikes | DOC | chain-read width (judgment) |
| Shared-S3-oi [317] | OTM-penny-strike OI-decreasing | DOC | OI read |
| Shared-S3-oi [317] | Sellers-both-sides = pin + writer-creates-position | DOC | OI interpretation |
| Shared-S4 [331] | Dow = primary US30 + European-from-12:30 | **DONE** (Dow) + DOC (Euro timing) | Dow factor wired |
| Shared-S4 [331] | Crude $60/$70-80/$80-100 + DXY <100/92-93 + USD-INR 88.5-88.8 | DOC | global-cue bands (drift #45) |
| Shared-S4 [331] | Don't-act-on-Gift-Nifty + open-recheck | DOC | discipline |
| Shared-S4 [331] | VIX positional deploy ladder | DOC | VIX read |
| Shared-S4 [331] | Low-VIX 90%-bounce + vertical-climb test | DOC | VIX read |
| Shared-S4 [331] | FII-no-covering-during-rally caution | DOC | FII read |
| Shared-S4 [331] | Expiry-IV-crush 2nd-half | DOC | IV read |
| Shared-S4 [331] | Erosion-day 24,800 example + 50/50-60/60 ladder | SKIP | illustrative |
| Shared-S4 [331] | VIX historical anchors | SKIP | educational |
| Shared-S5 [343] | S&R from volume-turns + 2-3mo/6mo lines | DOC | S&R method judgment |
| Shared-S5 [343] | Max-call-OI = resistance / max-put-OI = support + spot-OI bars | DOC | standard OI S&R |
| Shared-S5 [343] | Time refinement hourly-high / 1:30-book / 2:30-stay-away | PARAM (low) | time rails (dup window reconcile) |
| Shared-S5 [343] | Prefer-fewer-writers + avoid-120-130-prefer-150+ | **DONE** | W3 PR-3 premium band |
| Shared-S5 [343] | Sensex participation/volume gate (36/59L vs 10/18cr) + Thu/Tue + HFT-arb | PARAM (low) | Sensex liquidity gate |
| Shared-S5 [343] | Avoid-OTM-for-momentum (Day 20) | DOC | judgment |
| Shared-S6 [355] | VIX vertical-15min-climb test | DOC | dup Shared-S4 |
| Shared-S6 [355] | Low-VIX 90%-bounce 2:00-3:30 | DOC | dup Shared-S4 |
| Shared-S6 [355] | FII L/S ratio primary (drops participant matrix) | PARAM (low) | LSR-primary reconcile |
| Shared-S6 [355] | Global cues sharpened with 2025 levels | DOC | illustrative |
| Shared-S6 [355] | S&R-from-volume-turns refinements | DOC | dup Shared-S5 |

---

## 5. Disposition summary & recommendation

- **No new doc work** — the operative doc already carries all 95 as human reads; this triage adds
  no prose to it.
- **No new W3-style code yet** — the 12 high-value PARAM candidates are *optional* forward builds,
  each owner-gated and forward-paper-validated. **PARAM #1 (daily-loss cap 10-12% vs 2%) is the one
  that needs an owner ruling before anything**, because it is a live **risk** rail divergence, not a
  feature gate.
- **Recommended order if/when built:** #1 (risk reconcile — owner ruling) → #5 / #6 / #10 (small,
  self-contained, genuinely new) → #3 / #4 / #7 / #8 (medium OI/BTST gates) → #2 / #9 / #11 / #12.
  Each ships as a parity-safe default-OFF tag, same recipe as the W3 six.
- **Everything stays inert** until the owner arms a tag on a YAML after forward-paper.

This closes the S24-incorporation chain: **COMPARISON → RATIFICATION-PACK → W1 operative doc +
OIP-AI model → W2 backlog prune → W3 (6 engine drifts) → W4 (95-add triage)**.
