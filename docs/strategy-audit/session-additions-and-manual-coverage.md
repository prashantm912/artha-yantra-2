# Session-21..24 additions (§4.14–4.17), open questions (§7), and ScalperManualChecks coverage

**Scope.** Audits the shared-input refinements added across Sessions 21–24 (doc §4.14 VIX bands /
futures basis / Q1-Q2 gate / constituent contribution / time-of-day weighting / OI timeframes / strike-delta;
§4.15 Trending-OI+PA / straddle chart / VWMA-20 / PSAR-distance / IV 7-10 / IV>40 / Open=High bands;
§4.16 Sensex value & 3x point-scaling / Nifty-chart proxy / options-not-futures / strike-SL scaling;
§4.17 Sensex participation gate / Trending-OI 15-strike / FII Long-Short ratio / IV refinements / OI-bar-on-spot)
plus the §7 open-questions list, against the scalper automation
(`ScalperConfig`, `ScalperConfluenceGate`, `ScalperGates`, `ConnectTheDotsScorer`, `ScalperOiProps`,
`MarketOiClient`, the 36 `scalper-strategies/*.yaml`) and the `ScalperManualChecks` 7-item checklist.
It also asks the cross-cutting question: **does the current 7-item checklist cover the manual-only rules
across ALL sections?** Derived-history caveat applies to every OI/VIX/IV/Dow factor (degrades to NEUTRAL on
backtests) — judged here by code presence, not backtest behaviour.

| Rule | Doc § | Status | Evidence (file:line / yaml key) | Gap / manual-check |
|---|---|---|---|---|
| India VIX **absolute regime bands** (10–11 Lower bullish / 12–14 Medium / 15–16 seller-favoured / 17+ Higher) | 4.14.1 | NONE | No band logic anywhere; `MarketOiClient.macro` returns `null` VIX level+direction (`MarketOiClient.java:396-397`) | Manually read India VIX absolute level and map to the band before trading. **Automatable** (INDIA VIX candles already exist — `ConnectingDotsService.vixByBucket` reads them for the OI page). |
| India VIX **direction** dot (falling=CE / rising=PE) | 4.14.1 | PARTIAL | Dot + gate exist (`ConnectTheDotsScorer.java:92`, `ScalperGates.vix` :136) BUT the scalper feed never populates VIX — `macro()` hard-codes `null,null` (`MarketOiClient.java:397`); gate degrades to "unknown → pass". Wired only on the OI page (`ConnectingDotsService.vixFactor` :263) | The scalper signal ignores VIX entirely; confirm VIX direction by eye. **Automatable** — wire the existing INDIA VIX candle read into `macro()`. |
| VIX-vs-price grid (price up & VIX down = bullish, etc.); compare to prev-day close; "erratic intraday VIX → ignore" | 4.14.1 | NONE | Not encoded (no VIX in scalper macro) | Manually apply the 4-cell VIX/price grid. Partly **automatable** (direction yes; prev-day-close compare yes; "erratic → ignore" is judgement). |
| Futures **basis** (premium=bullish/CE, discount=bearish/PE) | 4.14.2 | FULL | `ScalperGates.futuresBasis` (`ScalperGates.java:164`), dot `basis` (`ConnectTheDotsScorer.java:93`), fed by `MarketOiClient.futuresBasis` (:339) | — (present-series-discount + next-series-premium nuance not modelled; front-contract basis only — minor). |
| **Q1/Q2 buyer gate**: require BOTH >50% price change AND >50% OI change on the correct quadrant | 4.14.3 | FULL | `ScalperGates.callPutDeltaFilter` (#5 hard pre-gate, `ScalperGates.java:151`) + OI-spurt dot `oiSpurt` requires both OI% and price% past 50 floors (`ConnectTheDotsScorer.java:159-167`, `ScalperOiProps` `spurtOiPct`/`spurtPricePct`=50) | Hard pre-gate only on `oi-cross-filter`-tagged strategies; spurt dot is soft elsewhere. |
| Price-move-per-OI demand read (big move on small OI = stronger) | 4.14.3 | NONE | Not encoded | Eyeball the price-impact-per-OI ratio. **Automatable** but unbuilt. |
| **Index-constituent contribution** (top movers; BankNifty top3 ≈ 60%; HDFC = 29.46% of NiftyBank; crude→BankNifty adverse) | 4.14.4 | NONE | No constituent/weightage gate (the only "constituent" hit is a comment in `scalp-market-movers-nifty.yaml:6`) | Manually read top movers / sector sync to confirm index direction. **Automatable** (would need a constituent-weight + per-stock quote feed; not present). |
| Lot sizes (BN 25 / Nifty 50 / FinNifty 40); weekly Thu / monthly last-Thu expiry | 4.14.4 | PARTIAL | Expiry calendar automated (`MarketCalendar.isWeeklyIndexExpiryDay`/`isMonthlyIndexExpiryDay`, used in `HeroZeroGate` + OI suppression `MarketOiClient.java:268`). Lot sizes not in scalper code | Lot size handled at order/margin layer, not the signal. No manual check needed for expiry. |
| **Time-of-day data weighting** (prev-day data + prev-day VWAP until 11 AM, intraday + current VWAP after 11 AM) | 4.14.5 | PARTIAL | The 11:00–13:00 midday block is encoded (`ScalperGates.java:23-24,37`), and `eodDate` passes the prior session for breadth/FII. But there is NO "weight prev-day vs current VWAP by 11 AM" switch — a single current-bar VWAP is always used (`ConnectTheDotsScorer.java:71-74`) | Manually weight prev-day data/VWAP before 11 AM. **Automatable** (prev-day VWAP series is computable). |
| **Pre-open (9:00–9:07) positioning + advances/declines** for the morning bias | 4.14.5 / 4.15.5 | NONE | No pre-open read; the opening-tick path is 09:15–09:30 (`ScalperConfig.OPENING_FROM/TO` :72-73), not 9:00–9:07 pre-open | Manually read pre-open A/D + positioning to set the day bias. **Automatable** if a pre-open snapshot feed exists (currently none). |
| OI interval reads (5/15/30/60/120/240 min); Futures-OI 3–60 min; **15-min major crossover, 60-min longer view** | 4.14.6 | PARTIAL | Scorer reads a single trending window (`SERIES_WINDOW=20` buckets, `MarketOiClient.java:49`) + a 60m bias indicator (`bias60m`/`BIAS_60M`); there is no explicit 15-min-crossover vs 60-min-view separation | Manually corroborate the 15-min major-crossover TF. **Automatable** but the multi-TF OI separation is unbuilt. |
| Refresh Trending-OI strikes to ATM±7 once move >1% | 4.14.6 | NONE | No 1%-move strike-refresh trigger in scalper code | Manual housekeeping. **Automatable** but unbuilt (UI/OI-page concern). |
| **Strike/delta by expiry phase** (0.7–0.8 near weekly-end; ~0.5 first day) and VIX (low→lower-premium / high→higher) | 4.14.7 / 4.15.4 | PARTIAL | Fixed delta band 0.6–0.7 only (`ScalperConfig.java:82-83`); the expiry-phase + VIX-conditional delta/premium are DEFERRED by explicit code comment (`ScalperConfig.java:78-81`) | Manually shift delta toward 0.7–0.8 near weekly-end / 0.5 on day 1; manually choose premium by VIX. **Automatable** (expiry clock + VIX feed) — doc-sanctioned v1 simplification, not a gap per se. |
| Read IV only at ~3 LTPs around ATM; ignore deep ITM/OTM IV | 4.14.7 | FULL | `deriveIvPair` uses exactly the 3-above + 3-below ATM strikes (`MarketOiClient.java:567-617`) | — |
| **Options selling / hedging** (never naked; short straddle/strangle; SL = straddle VWAP +10–15pt) | 4.14.8 | NONE (SPAN-deferred) | `StraddleLegPicker` only ever returns BUY legs (`scalp-straddle-nifty.yaml:18-21`); short premium SPAN-gated (#47, dormant) | Short-side selling is not automated at all; manual only until SPAN appliance live. **Automatable** post-SPAN. |
| **Scalping cadence & discipline** (hold seconds–~3 min; SL always small + cut immediately; a missed/delayed entry is **let go, not chased**; multi-lot for small per-trade targets) | 4.14.9 | PARTIAL | The 3-min hold maps to `primary: 3m` + `time_stop max_bars` (`scalp-connect-the-dots-nifty.yaml:24,47`); a scalp signal is per-bar so there is no "chase" path (a missed bar simply does not emit). Small-SL-cut-immediately = the structural stops; the **multi-lot / per-trade-target sizing is NOT encoded** (flat `premium_budget`, `:49`) | Cadence/no-chase are implicit in the per-bar engine; the multi-lot small-target sizing is manual. **Automatable** (sizing) but unbuilt. |
| **Account size & order mechanics** (1% rule, ~5–6 lakh for consistency, trade from withdrawn profits; **basket orders** to punch large qty; recommended small-capital set = OSPL/Trending-OI/Open=High/2-Candle) | 4.14.9 | NONE | No capital-tier sizing, no basket-order grouping, no "recommended-set" gating in the scalper engine | Account sizing + basket orders are an account/order-layer concern; the recommended-set is owner judgement. Not a signal gate. |
| **Trending-OI + PA** (LTP change beside ΔOI; "LTP not moving = premium erosion not a real move") | 4.15.1 | PARTIAL | Trending cross requires real ΔOI signs (`trendingCross`, `ConnectTheDotsScorer.java:125`) and OI-spurt requires a price% move (:159) — so a flat-LTP/erosion case is non-confirming. But the explicit "gradual drop in negative LTP-change flags a buyer" PA read is NOT modelled | Manually confirm LTP follow-through on a Trending-OI signal. **Automatable** (LTP-change series exists on the OI page). |
| **Straddle chart** = combined Call+Put premium vs its own VWAP, entry = VWAP break **with volume**; one-leg management | 4.15.2 / 3.11 | NONE (LIVE-deferred) | Explicitly NOT enforced — the combined-premium-vs-VWAP entry + low-IV gate are "LIVE market-data the deterministic seam cannot recompute" (`ScalperConfluenceGate.java:128-131`, `scalp-straddle-nifty.yaml:24-31,86`); v1 emits a two-leg draft only | Manually time the straddle entry off the combined-premium VWAP break and manage one-leg. **Automatable** on a live (non-replay) seam only. |
| **VWMA period = 20** ("Pawn = VWMA(20)") | 4.15.3 | FULL | YAML `indicators VWMA params.period: 20` (`scalp-straddle-nifty.yaml:67`, all scalper YAMLs) + alias `vwma20` (`ScalperConfluenceGate.java:38`) | — |
| PSAR distance read (dots close = short-lived / wide gap = lasting) | 4.15.3 | PARTIAL | PSAR side dot only (price vs PSAR, `ConnectTheDotsScorer.java:77`); the dot-distance-to-candle interpretation is NOT modelled | Manually judge PSAR dot distance for move durability. **Automatable** (PSAR value vs price distance is computable). |
| OSPL volume colour-coding (>50K BN / >125K N dark green/red) | 4.15.3 | PARTIAL | Volume floors encoded (`ScalperGates.java:27-30`: NIFTY 125k, others 50k) but no bull-vs-bear volume-attribution colour | Volume floor automated; the green/red attribution is a manual/UI read. |
| **VWAP "most important indicator"** + decisive + max-quantity-nearest-VWAP sizing | 4.15.3 | PARTIAL | VWAP is the highest-weight decisive hard gate (`ConnectTheDotsScorer.java:32 W_VWAP=2.5`, :71,114) — qualitative rule FULL; the "deploy max quantity nearest VWAP" sizing is NOT in the signal (sizing is `premium_budget`, `scalp-straddle-nifty.yaml:89`) | VWAP-proximity position sizing is manual. **Automatable** but unbuilt. |
| Buyer delta up to 0.9 / seller ~0.4 (wider band) | 4.15.4 | PARTIAL | Fixed 0.6–0.7 (`ScalperConfig.java:82-83`); wider 0.9/0.4 band deferred (same comment :78-81) | Manually widen delta for strong buys/sells. **Automatable**. |
| **IV trending-difference band = 7–10 pts** (CE-vs-PE, higher on trending side) | 4.15.4 / 4.17.5 | PARTIAL | `ivPair` dot requires a ≥0.10 (10-pt) CE-vs-PE gap favouring the side (`ConnectTheDotsScorer.java:173-180`, `ivPairMinGap`=0.10). Encodes the **10-pt** edge but NOT the 7–10 **band** nor the §4.17.5 "~8–10-pt" examples (16/8, 15/8, 25/15) | Manually confirm a 7–10 pt IV gap on the trending side. Threshold is single-valued (10), not a band — tunable via `artha.scalper.oi.ivPairMinGap`. |
| **IV above 40 → stay away as a buyer**; IV ~20/20·40/40·50/50 = erosion/avoid; IV>40–50 favours sellers | 4.15.4 / 4.17.5 | PARTIAL | The 40/40 **both-high + narrow-gap** stand-aside is encoded (`ivBothHighStandAside`, `ConnectTheDotsScorer.java:186-195`, `ivBothHighFloor`=0.40). It does NOT enforce the **unilateral** "buyer avoids when his side's IV >40" — a CE buyer with CE-IV 0.45 / PE-IV 0.20 (wide gap) still PASSES | Manually skip a buy when the bought side's IV is >40. **Automatable** (add a per-side IV>40 buyer cap; the 6-strike IV averages are already computed). |
| **Open=High premium bands** (BN 250–550, Nifty 150–350; buyers near-ATM, sellers OTM) [RESOLVED in S22] | 4.15.4 / 3.2 / 7 | PARTIAL | Live `StrikePicker` premium bands are NIFTY **100–250** / BN 250–400 / SENSEX 300–800 (`ScalperConfig.java:93-98`) — these are the S22 "older/general-case" slide bands, NOT the resolved wider daily-note bands (Nifty 150–350) the doc says are now **operative** | Manually apply the wider operative O=H premium bands; the encoded NIFTY band (100–250) is the superseded one. **Automatable** (constant change). |
| Trending day = new high/low ~every 45–60 min | 4.15.5 | NONE | Not encoded | Manual regime read (closest proxy = the manual `regime_ok` check). **Automatable** but unbuilt. |
| Stock daily-RSI screen: not crossed RSI 75 (bull) / 40 (bear) on daily | 4.15.5 | PARTIAL | Index intraday RSI band encoded (`ScalperGates.rsiBand` 60–80/20–40, :76); the **stock daily-RSI 75/40 BTST screen** is not a scalper-engine gate (Market Movers is stock-universe, separate) | Manually check daily RSI on the stock before a Movers/BTST trade. **Automatable** (daily RSI computable). |
| Pre-open data available after 9:07 (in 9:08) | 4.15.5 | NONE | No pre-open snapshot consumed | See pre-open row above. |
| **Sensex value & ~3× point-scaling** (Nifty 0.5%≈125 → Sensex 375–400; 1%≈250 → 800) | 4.16.1 / 4.17.2 | PARTIAL | Sizing/SL is structural + premium-band based (`StructuralStop`, SENSEX premium 300–800 `ScalperConfig.java:98`); there is no explicit 3× point multiplier — the structural-stop approach scales implicitly with the Sensex future's own swing | Manually widen point-SL ~3× for Sensex (structural stop handles this implicitly). |
| **Trade Sensex via Nifty charts** (30 Sensex stocks all in Nifty ~80%; study Banking ~23.71% / IT ~19.35%) | 4.16.2 | FULL (mechanism) | The signal/option-root decoupling lets a SENSEX scalper signal on the NIFTY front future: `signal_underlying: NIFTY-FUT-CONT` (`scalp-...-sensex-*.yaml`), `ScalperConfig.signalIndex` (:176-184) maps it to NIFTY 50 | The Nifty-chart proxy IS automated via decoupling; the sector-study (Banking/IT) is manual context. |
| **Sensex options not futures** (futures illiquid, ~418 vol) | 4.16.3 | FULL | `mode: options_of_underlying` always trades options; option-execution root resolves to NFO/BFO via `expired_contracts.underlying_symbol` | — |
| Sensex strike & SL ladder near VWAP; point-SL scales ~3× | 4.16.4 | PARTIAL | Structural VWAP/swing stops scale with the instrument; no explicit 3× ladder or multi-level pyramiding in the signal (single position, `max_positions_per_underlying: 1`) | Manually ladder quantity across levels for Sensex. **Automatable** but the no-averaging rule is intentional. |
| **Sensex participation / volume gate** (skip thin Sensex, prefer Nifty; pick by nearer expiry/richer premium) | 4.17.2 | NONE | No Sensex-vs-Nifty participation comparator; the niftyoi/sensexoi variants are a static A/B (`ScalperStrategySeeder` :38-73), not a runtime "skip Sensex when thin" switch | Manually skip Sensex on thin-volume days, prefer Nifty. **Automatable** (compare both chains' OI/volume at runtime). |
| Monitor Nifty AND Sensex on a Sensex expiry; pre-open NSE-vs-BSE gap = HFT arb not retail | 4.17.2 | NONE | Not encoded | Manual cross-index alignment + HFT-gap judgement. Partly **automatable** (spread compute), partly judgement. |
| **Trending-OI 15-strike read** (7 above + ATM + 7 below; tested vs 5/9/11) | 4.17.3 | PARTIAL | The trending/active-strikes reads use server-side windows (`active-strikes?buckets`, `MarketOiClient.java:301`); the strike-window count (15 vs 5–7) is set by the market-data endpoint, not a scalper-config knob | Confirm the OI dashboard is on the 15-strike window. **Automatable** (window param) but not surfaced as a scalper knob. |
| **Intraday vs positional OI must agree** (>50% call-vs-put gap on BOTH; PCR 1.2→1.5→2; ~5cr call vs 10–12cr put) | 4.17.3 | PARTIAL | Single intraday trending/imbalance read (`callPutDeltaFilter` ≥50%, `imbalancePct`); there is NO intraday-vs-positional (today vs yesterday+today) two-window agreement, no PCR-level (1.2/1.5/2) ladder, no absolute cr-OI compare | Manually cross-check positional (yesterday+today) OI agreement + PCR progression. **Automatable** (positional series is derivable). |
| **FII futures Long/Short-ratio gate** (~87–94% short = sell every level; crossing ~50% = short-covering trigger; DII-buy-alone may not lift) | 4.17.4 | NONE (plumbed-but-dead) | `fiiLongPct` is FETCHED and carried in `Macro` (`MarketOiClient.java:375-383,397`) but **never consumed by any confluence dot or gate** — no reference in `ConnectTheDotsScorer`/`ScalperGates`. No ~50% crossover trigger, no 87–94% short read, no DII compare | Manually read the FII L/S ratio + the ~50% crossover. **Automatable** — the value is already fetched; only a dot/gate consuming it is missing. |
| IV crashes 2nd-half of expiry day (call IVs fall); IV crashes post-event; CE-vs-PE TV diff demand-driven (10–20%, up to 40%) | 4.17.5 | NONE | Not modelled (no time-of-day/expiry IV-decay logic) | Manually expect IV crush late on expiry / post-event. **Automatable** in part (expiry-day + IV series) but unbuilt. |
| **OI bars on Nifty SPOT** for S/R (largest call-OI bar=resistance, put-OI bar=support; shrinking put-bar on fall=reversal); S/R from volume turning-points not OI | 4.17.6 / 4.11 | NONE | No spot-OI-bar S/R derivation in the scalper engine; S/R is a manual pre-market mark | Manually mark spot-OI S/R bars + volume-turning-point S/R. **Automatable** (spot-OI bars computable) but unbuilt — covered weakly by `level_respected` manual check. |
| "Kingdom" chess mnemonic (Queen=OI, Rook=VWAP, Knight=ST(10,2), Pawn=VWMA(20), Bishop=PSAR(0.02/0.2), Territory=RSI(14)) | 4.17.1 | FULL (no new rule) | All six map to encoded indicators: ST(10,2) (`scalp-straddle-nifty.yaml:71`), VWMA(20) (:67), PSAR (:68), RSI(14) (:69), VWAP decisive (`ConnectTheDotsScorer.java:32`), OI scored | Mnemonic only — no new mechanics to automate. |
| **§7 open-questions / [RESOLVED] status the engine should reflect** — the S21/S22 resolutions: Open=High operative premium bands [RESOLVED S22], Hero-Zero numeric SL (BN ~75 / N ~30 + 3:10 no-move exit + deploy ≤10%) [RESOLVED S22], Golden-Crossover support-form SL = Supertrend [RESOLVED S21], BTST/STBT LU = Quadrant 4 [RESOLVED S21] | 7 | PARTIAL | These RESOLVED numerics are mostly carried by OTHER dimension files (`completeness-sweep.md`, `hero-zero.md`, `golden-crossover.md`, `btst-stbt.md`). For THIS dimension: the O=H bands are the **superseded** set in `ScalperConfig.java:93-98` (see the Open=High row above); the Hero-Zero gate fires after 14:30 not the doc's ~14:00 and lacks the explicit ~75/~30 point SL + 3:10 no-move exit (`HeroZeroGate`); the still-OPEN ambiguities (RSI 75-vs-80, 3m-vs-5m primary, exact targets, partial-booking ladders) are resolved one-way by the §4.2 conflict rule with no on-card alternate-reading note | The engine picked one reading per the doc's conflict rule; verify the S22-resolved O=H bands + Hero-Zero SL are intentionally still superseded, and that the open ambiguities are owner-confirmed. Mostly **doc-sanctioned choices**, not gaps. |

### ScalperManualChecks coverage (the 7-item checklist vs ALL manual-only rules)

The checklist (`ScalperManualChecks.java:24-60`) ships 7 items: `news_clear` (§2.13), `level_respected`
(§4.11), `not_parabolic` (§3.1), `regime_ok` (§3.10), `vix_normal` (§4.5), `global_cues_ok` (§4.7),
`clean_setup` (§3.1). Mapping the manual-only / NONE / PARTIAL rules above against it:

| Manual-only rule | Covered by a checklist item? |
|---|---|
| India VIX abnormal spike | YES — `vix_normal` (§4.5) — but it only covers "abnormal spike", NOT the §4.14.1 regime bands or the VIX-vs-price grid |
| Global cues (DOW/Asian/crude/USD) | YES — `global_cues_ok` (§4.7) |
| S/R level respected (incl. spot-OI S/R §4.17.6) | PARTIAL — `level_respected` (§4.11) covers the zone read, not the spot-OI-bar mechanic |
| Regime / trending-vs-choppy (§4.15.5 45–60min) | PARTIAL — `regime_ok` (§3.10) is VWAP-crossover-count, not the 45–60min new-high cadence |
| News/event override | YES — `news_clear` (§2.13) |
| Not parabolic / clean one-good-trade | YES — `not_parabolic`, `clean_setup` |
| **Index-constituent contribution** (§4.14.4) | **NO** |
| **FII Long/Short-ratio** (§4.17.4) | **NO** |
| **Pre-open positioning + A/D** (§4.14.5/§4.15.5) | **NO** |
| **Sensex participation / prefer-Nifty-when-thin** (§4.17.2) | **NO** |
| **Intraday-vs-positional OI agreement / PCR ladder** (§4.17.3) | **NO** |
| **Expiry-day / post-event IV crush** (§4.17.5) | **NO** |
| **Straddle combined-premium VWAP entry + one-leg mgmt** (§4.15.2) | **NO** (documented in YAML comments, not in the checklist) |
| **Time-of-day prev-day-vs-current VWAP weighting before 11AM** (§4.14.5) | **NO** |

**Verdict:** the 7-item checklist covers the *generic* discretionary guardrails (news, global cues, VIX
spike, S/R, regime, parabolic) but does **NOT** enumerate the Session-21..24 manual-only inputs — at least
8 distinct manual rules (constituent contribution, FII L/S ratio, pre-open A/D, Sensex participation,
intraday-vs-positional OI agreement, expiry/event IV crush, straddle-VWAP timing, time-of-day VWAP
weighting) have no checklist item. The trader gets no on-card reminder for any of them.

## Not automated (gaps)

- **VIX is unwired in the scalper signal** (§4.14.1): `MarketOiClient.macro()` hard-codes `null` VIX
  level+direction, so the vix dot always degrades to pass. Neither the absolute regime bands (10-11/12-14/
  15-16/17+) nor the VIX-vs-price grid are encoded — and `vix_normal` only covers "abnormal spike". The
  INDIA VIX candle feed already exists (used by the OI page), so this is automatable.
- **FII Long/Short-ratio gate is plumbed-but-dead** (§4.17.4): `fiiLongPct` is fetched into the `Macro`
  record but no dot/gate ever reads it. No ~50% crossover short-covering trigger, no 87-94% short read.
  Not in the checklist either. Automatable — only the consumer is missing.
- **Index-constituent contribution** (§4.14.4): no top-mover / sector-weight gate, no checklist item.
- **Pre-open positioning + advances/declines** (§4.14.5, §4.15.5): no pre-open snapshot consumed; the
  opening-tick path is 09:15-09:30, not the 9:00-9:07 pre-open. No checklist item.
- **IV>40 unilateral buyer-avoid** (§4.15.4, §4.17.5): only the 40/40 both-high+narrow-gap stand-aside is
  encoded; a wide-gap buy with the bought side's IV >40 still passes. The 7-10 pt IV band is collapsed to a
  single 10-pt threshold.
- **Open=High premium bands are the superseded set** (§4.15.4/§7): the live StrikePicker uses NIFTY 100-250,
  not the S22-resolved operative Nifty 150-350.
- **Sensex participation gate** (§4.17.2): no runtime "skip thin Sensex, prefer Nifty" comparator; the
  niftyoi/sensexoi split is a static A/B only. No checklist item.
- **Intraday-vs-positional OI agreement + PCR ladder** (§4.17.3): only a single intraday imbalance read; no
  yesterday+today positional cross-check, no PCR 1.2→1.5→2 progression. No checklist item.
- **Straddle combined-premium VWAP entry + low-IV gate + one-leg management** (§4.15.2): explicitly
  LIVE-deferred (the deterministic seam can't recompute the combined-premium series); v1 emits a two-leg
  draft only. Documented in YAML comments but not in the manual checklist.
- **Spot-OI-bar S/R + volume-turning-point S/R** (§4.17.6): not derived in the engine; only weakly covered
  by the generic `level_respected` checklist item.
- **Time-of-day data weighting** (§4.14.5): the 11:00-13:00 block is encoded, but the "prev-day data/VWAP
  until 11 AM, current after" weighting switch is not; a single current-bar VWAP is always used.
- **Expiry-phase / VIX-conditional delta & premium** (§4.14.7, §4.15.4): deferred by design to the fixed
  0.6-0.7 band (doc-sanctioned simplification, not an oversight) — but a manual delta-shift reminder exists
  nowhere.
- **Most §7 open questions are unresolved-by-design**: the doc itself flags them as UNCERTAIN (RSI 75 vs 80,
  3m-vs-5m primary, exact targets, partial-booking ladders). The automation picked one interpretation
  (e.g. §4.2 RSI 60-80, 3m primary) per the doc's conflict rule; these are not "gaps" so much as documented
  choices, but the trader has no on-card note that an alternate reading exists.

## v2 review notes

Independent second-pass review (fresh-derived §4.14–§4.17 + §7 from the consolidated doc, then re-read
`ScalperConfig`/`ScalperConfluenceGate`/`ScalperGates`/`ConnectTheDotsScorer`/`ScalperOiProps`/`MarketOiClient`/
`ConnectingDotsService`/`ScalperManualChecks` and diffed against v1). v1 was strong: every spot-checked
file:line cited resolves and does what is claimed (VIX hard-null at `MarketOiClient.java:396-397`; `fiiLongPct`
fetched `:375-383` and consumed by NO dot/gate — confirmed by grep; the static niftyoi/sensexoi A/B is
`ScalperStrategySeeder.java:38-73`; straddle indicator lines `:67-71`; IV-pair gap 0.10 / 40-40 floor 0.40 in
`ScalperOiProps`). No false-coverage or invented figures found; the README "Audit-quality flags" tail raises
none for THIS dimension. Changes this pass:

1. **MISSED — added §4.14.9 (Scalping cadence & discipline)** as a new PARTIAL row. v1 had no row for §4.14.9
   at all. The 3-min hold maps to `primary: 3m` + `time_stop`, the no-chase behaviour is implicit in the
   per-bar engine, but the multi-lot small-per-trade-target sizing is unencoded (flat `premium_budget`).
2. **MISSED — added §4.14.9 (Account size & order mechanics / basket orders / recommended-set)** as a new NONE
   row. Account/order-layer + owner-judgement concern, not a signal gate — but a discrete doc rule v1 omitted.
3. **MISSED — added a consolidated §7 row (PARTIAL)** tracking the [RESOLVED in S21/S22] status the engine
   should reflect (O=H operative bands, Hero-Zero numeric SL, Golden-Crossover Supertrend SL, BTST/STBT Q4)
   plus the still-open ambiguities. The dimension title names "open questions (§7)" yet v1 covered §7 only in a
   single closing prose bullet, never as a table row. Most §7 numerics are carried by other dimension files
   (`completeness-sweep.md` etc.), so this row is a pointer + the THIS-dimension-specific O=H/Hero-Zero notes.
4. **WRONG_CITE fixed** — the pre-open row cited "§4.14.5 / §4.15.6"; **§4.15.6 does not exist** (the doc's
   §4.15 runs 4.15.1–4.15.5; the pre-open "after 9:07 AM" line is §4.15.5, doc line 1572). Corrected to
   §4.14.5 / §4.15.5. The checklist-coverage prose (line ~74) keeps "§4.14.5/§4.15.5" — already correct there.

All v1 rows verified CONFIRMED otherwise (no status flips). The Q1/Q2 FULL row (§4.14.3) is borderline — the
doc frames "both >50% price AND OI" as a hard buyer-conviction gate, while the faithful encoding (the `oiSpurt`
dot needing both ≥50 floors) is *soft* (weighted) on non-`oi-cross-filter` strategies; v1 already caveats this
in its gap cell, so the status is left as FULL.
