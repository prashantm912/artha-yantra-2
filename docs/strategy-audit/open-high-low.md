## Open=High / Open=Low (O=H / O=L) — automation audit

**Scope.** Audits the Siva #2 Open=High / Open=Low scalper (doc §3.2 narrative + §6.2 JSON spec) against its
automation: the three `scalp-open-high-low-*.yaml` (NIFTY + SENSEX×niftyoi/sensexoi), the `OpenHighLowGate` /
`OpenHighLow` engine pre-gate, its wiring in `ScalperConfluenceGate`, the `ScalperOiProps` thresholds, the
`StrikePicker` strike/delta selection, the `ConnectTheDotsScorer` dots (VIX/IV/breadth), and the
`ScalperManualChecks` checklist. The OH/OL gate IS unusually complete — the source-faithful Table-1/Table-2
per-strike footprint, the ≤50% spurt reject, the VWAP stop and the 1st-half cutoff are all genuinely encoded.
The gaps are: the ≥90% OI-Pulse AI badge (admitted unavailable), the precise ideal 9:15–10:00 window, the
S22-operative premium bands, exit management (target/trail/~5pt-inside), the 30%-capital and ≥50K/125K
adverse-volume risk rules, and the VIX/IV "go down for longs" confirmation (present only as soft confluence
dots that degrade to NEUTRAL on derived history).

| Rule | Doc § | Status | Evidence (file:line / yaml key) | Gap / manual-check |
|---|---|---|---|---|
| OH on Futures (3-min) — open == running session high (mirror OL) | §3.2 L441,L451 / §6.2 setup[0] | FULL | `OpenHighLow.marks` (OpenHighLow.java:78-100), 1-pt tolerance L68; wired ScalperConfluenceGate.java:219 | — |
| ≥3 strikes above/below ATM matching OH (CE) / OL (PE); "very high" at 4-5 | §3.2 L442,L452 setup[1] | FULL | `OpenHighLow.tier` minStrikes (OpenHighLow.java:151-160); `openHighMinStrikes` default 3 (ScalperOiProps.java:45); per-strike via `openHighStats` (MarketOiClient.java:123) | Per-strike OHLC is 5-min snapshot resolution (yaml `oi_confluence_gate.interval:"5m"`), not native 3-min — manual: eyeball the OH/OL strike count on the live chain |
| Probability tiers: OH-Fut+OH-Call+OL-Put=HIGH; few=MILD; both-OH=stand-aside | §3.2 L489 / §6.2 edge_cases[2] | FULL | `OpenHighLow.tier` (OpenHighLow.java:112-174); only HIGH fires (OpenHighLowGate.java:103-105) | — |
| Restrict to ATM and ITM strikes, ATM ±3; avoid OTM / deep ITM | §3.2 L443,L483 setup[2] | PARTIAL | yaml `strikes:{selector:atm_window,width:3}`; window via `openHighWindow` default 3 (ScalperOiProps.java:52) | Footprint window is symmetric ATM±3; doc says ATM/ITM-only — OTM legs are NOT excluded from selection. Manual: confirm the picked strike is ATM/ITM, not OTM. Automatable: true |
| Identified-strike premium not fallen >50% from prev close (mirror PE not risen >50%) | §3.2 L444,L456,L472 setup[4] | FULL | `exceedsPrevCloseFall` → Tier.LOW (OpenHighLow.java:223-226); `openHighMaxPrevCloseFallPct` default 50 (ScalperOiProps.java:50); plus spurt reject OpenHighLowGate.java:108 | Null value does not block (degrade-around). Per-strike prevclose-fall via strike-session-stats |
| Change in OI on identified strike not increased >50% (>50% = opposite player) | §3.2 L445,L456,L472 setup[5] | PARTIAL | spurt reject reuses Tier-1 `spurtOiPct` magnitude (OpenHighLowGate.java:108, ScalperOiProps.java:42) | Reject uses the chain-wide OI-spurt %, NOT the per-strike OI-change; a null magnitude does NOT block. Manual: check the identified strike's own ΔOI% < 50. Automatable: true (needs per-strike ΔOI in strike-session-stats) |
| Table-2 modifier: OH strike fell on ≥50K (BN)/125K (N) volume → downgrade probability | §3.2 L490 / §6.2 edge_cases[3] | FULL | `fellOnHeavyVolume` → Tier.LOW (OpenHighLow.java:205-217); `openHighFallVolumeFloor` default 50000 (ScalperOiProps.java:48) | Floor is a single 50000, not the per-index 50K-BN / 125K-N split |
| Momentum up: RSI > 50 and moving above 50 (RSI5m <75/80, RSI-D <75) | §3.2 L454,L479 entry[3] | PARTIAL | RSI>50 floor: `rsiAbove(openHighRsiFloor)` default 50 (ScalperConfluenceGate.java:157-160, ScalperOiProps.java:54); yaml `rsi14` 3m | Only the >50 floor on the 3-min RSI is gated. The <75/80 overbought cap and the daily-RSI<75 cap are NOT enforced. Manual: confirm RSI5m<75/80 and RSI(D)<75. Automatable: true |
| All indicators (VWAP/Supertrend 10,2/VWMA) below price for a CE (above for PE) | §3.2 L454,L484 entry[3] | PARTIAL | hard VWAP gate (yaml `gate: close > vwap`; ScalperConfluenceGate.java:149-152); supertrend/vwma/psar are SOFT dots (ConnectTheDotsScorer.java:75-77) | Only VWAP is a hard gate; ST/VWMA/PSAR alignment is weighed in the aggregate, not required. Manual: confirm price is above ALL three. Automatable: true |
| OI build-up Call OI declining / Put OI increasing (bullish) | §3.2 L456,L480 entry[5] filters | PARTIAL | `underlying_oi` / `trending_cross` / `futures_oi` soft dots (ConnectTheDotsScorer.java:80-83) | Folded into the confluence aggregate (threshold 0.2 yaml), not a hard OH-specific requirement; degrades to NEUTRAL on derived history. Manual: confirm the OI build-up direction on the live chain |
| VWAP is the stop-loss on a live OH momentum scalp | §3.2 L434(d) S22 / §6.2 stop_loss | FULL | structural stop = front-future VWAP (OpenHighLowGate.java:228, .Verdict stopLevel); yaml `exit_rules: signal_exit close < vwap` | — |
| 1st-half preference; avoid INITIATING in 2nd half (time-value erosion) | §3.2 L446,L471 setup[6] | FULL | `FIRST_HALF_CUTOFF` 12:00 (OpenHighLowGate.java:72,97-99); yaml session `window:{from:09:45,to:12:00}` | — |
| Trade in the ideal 9:15–10:00 window; ~90% of OH hit before 10:30 | §3.2 L451,L477; S22 L434(a) | NONE | yaml session window is `09:45`–`12:00`; the general ≥09:45 pre-flight (ScalperGates.timeWindow) applies, no 9:15–10:00 narrowing, no 10:30 freshness cut | The doc's IDEAL window (9:15–10:00) and the "if not hit by 10:30 it's low-probability" rule are not encoded. Manual: prefer entries 9:15–10:00; deprioritise after 10:30. Automatable: true |
| OI Pulse probability ≥90% WITH badge (red dot); do not chase below 90% | §3.2 L448,L453,L462; §6.2 entry bullish[2] | NONE | NOT automated — explicitly an unavailable Phase-4 OiPulse-parity model, "OPTIONAL, currently-unavailable, NEVER required" (yaml header L8-11; OpenHighLow.java class doc) | The single hardest doc gate (≥90% badge) is degraded around. Manual: read the OI-Pulse AI badge ≥90% (red dot) on oipulse before entering. Automatable: false (no parity model / external feed) |
| Strike selection: prefer 0.6–0.7 delta | §3.2 L455,L483; §6.2 entry[4] | FULL | `DELTA_LO 0.6 / DELTA_HI 0.7` (ScalperConfig.java:82-83); `StrikePicker.pick` selects nearest band midpoint (StrikePicker.java:99-105) | Backtest selector ignores delta band (picks nearest-strike-to-spot); live only |
| Premium bands (S22-operative): Nifty 150–350 avoid <130/>380; BN 250–550 avoid >600/<200 | §3.2 L455; §6.2 entry[4] | PARTIAL | `NIFTY 50` band hardcoded **100–250** (the older/general slide band, ScalperConfig.java:93,96); SENSEX 300–800 | The S22-OPERATIVE bands (Nifty 150–350, BN 250–550) are NOT used — the older 100–250 is. Backtest ignores the band entirely. Manual: confirm the strike premium sits in 150–350 (Nifty). Automatable: true |
| Choose strike whose premium is nearest its target | §3.2 L457,L469; §6.2 exit | NONE | StrikePicker selects on delta-nearest-midpoint, not "premium nearest target" (the OH magnet); no target premium computed | The "premium nearest the OH target" selection is not modelled. Manual: pick the strike closest to its OH level. Automatable: true (once per-strike OH levels are read) |
| Target: small scalps 30–50 pts; target the OH/OL extreme but NEVER beyond; exit ~5 pts inside | §3.2 L468; §6.2 target | NONE | No engine target — yaml exits are `signal_exit close<vwap` + `time_stop max_bars:20`; the ~5-pt-inside / 30–50pt target is documented live-management only (OpenHighLowGate.java:48-51) | The profit target is unautomated. Manual: set the target ~5 pts inside the OH (CE) / OL (PE); take 30–50 pts. Automatable: partial (needs the per-strike OH level as a live target) |
| Always trail SL once in profit; trail up from the OH number | §3.2 L470; S21 L432(b); §6.2 scaling | NONE | No trailing-stop in engine; only the static VWAP `signal_exit` | Manual: trail the stop once in profit. Automatable: true (a trailing-stop exit rule) |
| Abort/exit if premium falls >50% AND/OR strike ΔOI >50% (bigger opposite player) | §3.2 L472; §6.2 stop_loss | PARTIAL | Encoded as an ENTRY reject (spurt + prev-close-fall, OpenHighLowGate.java:108, OpenHighLow.java:170) | The >50% rule is a pre-entry block only; there is no in-trade abort/exit monitor. Manual: exit if the premium falls >50% or strike ΔOI crosses 50% after entry. Automatable: true |
| Adverse move on >50K (BN)/125K (N) volume candle = exit; low-volume drift tolerable | §3.2 L474,L478; §6.2 risk[1] | NONE | The 50K floor exists only as the Table-2 ENTRY downgrade (OpenHighLow.java:216); no in-trade adverse-volume exit | Manual: exit if an adverse candle prints >50K (BN)/125K (N) volume. Automatable: true |
| Never deploy more than 30% of capital on this trade (highly risky) | §3.2 L474; §6.2 risk[0] | NONE | yaml `position_sizing: premium_budget budget_inr:15000`, `max_daily_loss_pct:2.0`, `max_positions:1` — no 30%-of-capital cap | Manual: keep this trade ≤30% of capital. Automatable: true (a per-strategy capital-fraction cap) |
| VIX down (bull) / up (bear) supportive | §3.2 L456,L482; §6.2 filters | PARTIAL / MANUAL_COVERED | `vix` SOFT dot (ConnectTheDotsScorer.java:92, ScalperGates.vix); checklist `vix_normal` (ScalperManualChecks.java:46-50, §4.5) | VIX direction is a weighed dot, not a hard OH gate, and degrades to NEUTRAL on derived history. Manual `vix_normal` covers spike-avoidance, not the directional down/up confirmation. Automatable: true (already a live feed) |
| IV rising (bull) / falling (bear) in that strike; IV-rank low = cheap premium | §3.2 L484; §6.2 indicators/filters | PARTIAL | `iv_rank` + `iv_pair` SOFT dots (ConnectTheDotsScorer.java:94-98); `ivPairMinGap`/`ivBothHighFloor` (ScalperOiProps.java:38-40) | Per-strike IV direction (rising for the bought strike) is not gated; IV pair/rank are aggregate dots that degrade to NEUTRAL on derived history. Manual: confirm IV is rising in the bought strike. Automatable: true |
| Volume floor confirmation (50K BN / 125K N) for the breakout entry | §3.2 L457,L478; §6.2 filters | PARTIAL | `ScalperGates.volume(signalIndex, volume)` hard gate (ScalperConfluenceGate.java:161) | A signal-index volume floor is enforced, but it is the index-future volume, not the per-strike option breakout volume the doc means. Manual: confirm the breakout candle has volume. Automatable: true |
| Two-sided OH+OL both Call & Put = sideways, stand aside | §3.2 L487; §6.2 edge_cases[0] | FULL | both-sides OH footprint → Tier.STAND_ASIDE (OpenHighLow.java:147-148) | — |
| Confirm OH on Futures with **Long Build-up (preferred) or Short Covering** (mirror PE: Short Build-up / Long Unwinding) | §3.2 L451,L460 entry[0]; §6.2 entry bullish[0]/bearish[0] | PARTIAL | NOT in the OH/OL hard pre-gate — `OpenHighLow.tier` grades only the per-strike footprint × the bare futures OH/OL mark (OpenHighLow.java:112-160), it does **not** read the futures OI quadrant. The LB/SC (CE) / SB/LU (PE) build-up survives only as the SOFT `futures_oi` dot (ConnectTheDotsScorer.java:80 → `ScalperGates.oiQuadrant` ScalperGates.java:121-125) folded into the threshold-0.2 aggregate; degrades to NEUTRAL on derived history | The futures-side build-up is the doc's explicit OH-confirmation but is only a weighed dot, not a hard OH gate. Manual: confirm the futures OH carries Long Build-up / Short Covering (CE). Automatable: true (promote the `futures_oi` quadrant to a hard OH leg) |
| Do not jump straight to buying on seeing OH on CE/PE — time the entry to confirmed probability/momentum | §3.2 L492; §6.2 edge_cases[5] | MANUAL_COVERED | discipline rule — no engine equivalent; the momentum rails (RSI>50 + VWAP hard gate, ScalperConfluenceGate.java:157-163) plus the checklist `not_parabolic` / `clean_setup` (ScalperManualChecks.java:36-44,56-60) cover "don't chase, wait for confirmation" | Manual: do not buy on the bare OH sighting — wait for the probability/momentum confirmation |
| Trend alignment: OH on the side WITH the market trend = high-prob; opposite trend = low-prob | §3.2 L447; setup[7] | PARTIAL | optional 60-min `bias60m` SUPERTREND must agree (ConnectTheDotsScorer.java:111,114; yaml `bias60m` 1h) | The 60-min bias is the only trend filter and only when present (absent ⇒ never blocks). Manual: confirm the OH side aligns with the day's trend. Automatable: true |
| No market-moving news against the trade (news overrides data) | §2.13 (cross-strategy) | MANUAL_COVERED | checklist `news_clear` (ScalperManualChecks.java:27-30) | Manual: scan news/economic calendar before entry |
| Global cues not against the trade (DOW futures, Asian indices, crude, USD) | §4.7 (cross-strategy) | MANUAL_COVERED | checklist `global_cues_ok` (ScalperManualChecks.java:51-55) | Manual: check DOW futures + Asian index direction |
| Regime suits the setup (trending, not choppy); not parabolic; clean one-good-trade | §3.1,§3.10 (cross-strategy) | MANUAL_COVERED | checklist `regime_ok` / `not_parabolic` / `clean_setup` (ScalperManualChecks.java:36-60) | Manual: confirm regime + no chase + clean setup |

### Not automated (gaps)

- **OI-Pulse AI badge ≥90% (red dot)** — the doc's single hardest entry gate; explicitly an unavailable
  Phase-4 parity model, degraded around (never required). MANUAL, not automatable without an external feed.
- **Ideal 9:15–10:00 window + 10:30 freshness cut** — only the general ≥09:45 pre-flight runs (session
  window 09:45–12:00); the doc's ideal/freshness timing is not encoded. Automatable.
- **S22-operative premium bands** — `NIFTY 50` is hardcoded to the older 100–250, not the S22 150–350
  (avoid <130/>380); the backtest selector ignores the band entirely. Automatable.
- **Exit/target management** — 30–50pt target, "target the OH/OL but never beyond it / exit ~5 pts inside",
  "premium nearest target" strike choice, and "always trail the SL once in profit" are all documented
  live-management only; the engine carries only the VWAP stop + a 20-bar time stop. Automatable (target/trail
  need the per-strike OH level).
- **In-trade abort/exit rules** — the >50% premium-fall / >50% strike-ΔOI abort and the >50K(BN)/125K(N)
  adverse-volume exit exist only as ENTRY downgrades; there is no in-trade monitor. Automatable.
- **30%-of-capital cap** — not encoded (only `budget_inr:15000`, `max_daily_loss_pct:2.0`). Automatable.
- **VIX-direction and per-strike-IV-rising confirmations** — present only as soft confluence dots that
  degrade to NEUTRAL on derived history, not hard OH gates; `vix_normal` covers spike-avoidance only.
  Automatable (live feeds exist).
- **Overbought caps + full-indicator alignment** — RSI5m<75/80, RSI(D)<75, and "all indicators below price"
  are not gated (only RSI>50 and VWAP are hard); the rest are soft dots. Automatable.
- **Per-strike granularity caveats** — the per-strike footprint is 5-min snapshot resolution (not native
  3-min), the OI-change reject is chain-wide (not per-strike), and OTM legs are not excluded from selection.
- **Futures OI build-up (LB/SC for CE, SB/LU for PE)** — the doc's explicit OH confirmation on the futures
  leg; the OH/OL hard pre-gate dropped the quadrant, so it survives only as the soft `futures_oi` dot.
  Automatable (promote to a hard OH leg).

### v2 review notes

Independent second-pass review (fresh-derived doc §3.2 + §6.2, then diffed against v1). **The v1 audit is
high-quality**: all 30 original rows were re-verified against the cited code and every `file:line` / yaml
key was confirmed accurate (`OpenHighLow.java`, `OpenHighLowGate.java`, `ScalperOiProps.java`,
`ScalperConfig.java`, `StrikePicker.java`, `ScalperConfluenceGate.java`, `ConnectTheDotsScorer.java`,
`ScalperManualChecks.java`). **No false-coverage, no false-gap, no wrong-cite, no invented figures** were
found, and the README "Audit-quality flags" tail raises no item for this dimension. Every doc number the v1
rows quote (50% reject, ATM±3, 30%-capital, 50K/125K volume, 0.6–0.7 delta, 100–250 / 150–350 premium bands,
9:15–10:00 window, ≥90% badge, 30–50 pt target, ~5 pt-inside) matches the doc verbatim.

**Two MISSED rules added** (real doc rules with no v1 row):
1. *Confirm OH on Futures with Long Build-up (preferred) or Short Covering* (§3.2 L451; mirror SB/LU for PE
   L460; §6.2 entry bullish[0]/bearish[0]). v1's existing "Call OI declining / Put OI increasing" row covers
   the **option-side** build-up; the doc separately makes the **futures-side** OI quadrant an explicit OH
   confirmation. The source-faithful refactor of `OpenHighLow.tier` grades only the per-strike footprint ×
   the bare futures OH/OL mark (OpenHighLow.java:112-160) and does **not** read the futures OI quadrant in the
   OH/OL hard pre-gate — so this leg is only the soft `futures_oi` dot (ConnectTheDotsScorer.java:80 →
   ScalperGates.java:121-125). Status PARTIAL.
2. *Do not jump straight to buying on seeing OH; time the entry to confirmed probability/momentum* (§3.2 L492;
   §6.2 edge_cases[5]) — a discrete discipline edge-case. Status MANUAL_COVERED (`not_parabolic` /
   `clean_setup` + the RSI>50 / hard-VWAP momentum rails).

No rows were deleted or re-statused; the diff is purely additive (28→30 doc rows in the body table).
