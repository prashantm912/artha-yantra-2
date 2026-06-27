## Golden Crossover — automation audit

**Scope.** Audits Siva strategy #6 (Golden Crossover, doc §3.6 prose + §6.6 JSON spec) against its three
seeded YAMLs (`scalp-golden-crossover-{nifty, sensex-niftyoi, sensex-sensexoi}.yaml`), the shared scalper
confluence seam (`ScalperConfluenceGate`, `ScalperGates`, `ConnectTheDotsScorer`, `ScalperConfig`,
`StrikePicker`, `ScalperOiProps`, `ScalperManualChecks`), the strategy-engine indicator set
(`IndicatorRegistry`/`Ta4jIndicators`/`SessionIndicators`), and the OI/VIX/IV/breadth inputs from
market-data (`ConnectingDotsService` + `MarketOiClient` via `ScalperGateContext`). Golden Crossover carries
NO strategy-specific Java gate — it rides the *generic* scalper confluence path; its only bespoke knobs are
the chart-state gate in the YAML and the `entry-candle-stop` tag. Derived-history caveat applies (OI/Dow/IV
factors degrade to NEUTRAL on backtests — judged here by code presence, not backtest behaviour).

| Rule | Doc § | Status | Evidence (file:line / yaml key) | Gap / manual-check |
|---|---|---|---|---|
| Both ST AND VWMA cross VWAP together on the SAME candle (defining condition; single-indicator cross or no-body candle is NOT a Golden Crossover) | 3.6 setup-3 / 6.6 setup_preconditions[3] | PARTIAL | `scalp-golden-crossover-nifty.yaml` gate `all: ["vwma20 > vwap", "supertrend > 0"]` (lines 42-45); scored as dots `vwap`/`supertrend`/`vwma` in `ConnectTheDotsScorer.java:74-76` | Gate encodes the golden-cross **STATE** (VWMA above VWAP + ST bullish), NOT a same-candle *crossover event* — the YAML comment states the engine crossover op can't take VWAP as an operand. A position already in the bull state re-fires; "both pierce on the SAME candle" + "no-body candle = not a crossover" are not enforced. Manual: confirm the entry bar is the actual same-candle pierce with a real body, not a stale already-above state. Automatable: true (add a VWAP-aware crossover op + body check). |
| Volume mandatory on crossover candle: Bank Nifty 50K+, **Nifty 125K+** | 3.6 setup-4 / entry bull-4 / 6.6 setup_preconditions[4] | PARTIAL | `ScalperGates.volume()` floors NIFTY 50 = 125000, other indices = 50000 (`ScalperGates.java:27-30,64-68`); applied as a HARD rail in `ScalperConfluenceGate.java:161` keyed on `cfg.signalIndex()` | Floor is keyed to the **signal future's index** (`signalIndex`), which is `NIFTY 50` for ALL three variants (`signal_underlying: NIFTY-FUT-CONT`). So the SENSEX-options variants gate on the **NIFTY 125K** floor, not a SENSEX/BankNifty 50K floor — and the volume is the index-future bar's, not the doc's "crossover candle" on the traded chart. Manual: confirm the crossover candle's own volume clears the correct index floor. Automatable: true. |
| RSI(3-min, 14): bullish **< 75** (not overbought) | 3.6 entry bull-3 / 6.6 entry bullish[3] / filters | PARTIAL | `RSI` indicator declared (`scalp-golden-crossover-nifty.yaml:33`); HARD gate `ScalperGates.rsiBand()` requires CE **> 60 and < 80** (`ScalperGates.java:76-84`); also scored as `rsi` dot (`ConnectTheDotsScorer.java:78`) | Engine uses the shared §4.2 band **60–80** (with a 40–60 no-trade dead-zone), NOT the doc §3.6 card's plain **RSI < 75**. A valid Golden-Cross bull at RSI 50–60 (doc-eligible) is blocked. The class javadoc acknowledges §4.2 governs over §3.10's "buy 50–75". Manual: a Golden-Cross with RSI 50–60 is doc-eligible but the engine rejects it. Automatable: true (a per-strategy RSI override pattern already exists — `requireOpenHighLow`). |
| RSI(3-min): bearish **> 25** (not oversold) | 3.6 entry bear-3 / 6.6 entry bearish[3] | PARTIAL | `ScalperGates.rsiBand()` requires PE **> 20 and < 40** (`ScalperGates.java:81`) | Engine band 20–40 ≠ doc card "RSI > 25". Also the three YAMLs are `direction: long` / `option_types: [CE]` only — the **bearish (Buy PE / Sell CE) side is not seeded at all**. Manual: bearish Golden-Cross must be traded by hand; and the PE RSI gate differs. Doc itself flags this gate UNCERTAIN (card >25 vs a matrix row "<25"). Automatable: true. |
| OI confirmation: **drastic change in change-of-OI on BOTH CE and PE sides** (no drastic OI ⇒ small move, skip) | 3.6 setup-5 / entry-5 / 6.6 setup_preconditions[5] | PARTIAL | `drastic_oi` dot: both legs' \|dOI\| ≥ `drasticFloor` AND imbalance favours side (`ConnectTheDotsScorer.java:86,141-153`); `drasticFloor` default 50000 (`ScalperOiProps.java:36,59`) | The drastic-OI rule is a **soft weighted dot (weight 1.0)**, not a hard gate — confluence can reach threshold without it. Doc treats it as a near-mandatory confirmer ("no drastic OI ⇒ skip"). The `oi-cross-filter` HARD pre-gate (`ScalperConfig.java:153`) is NOT tagged on Golden Crossover. The 50000 floor is an admitted placeholder (`ScalperOiProps.java:33` — "the doc gives NO number"). Manual: confirm a genuine drastic two-sided dOI move before taking the trade. Automatable: true (tag `oi-cross-filter`, calibrate floor). |
| Trending OI across **5/7 strikes above and below ATM** (5–15 min window) | 3.6 setup-5 / filters / 6.6 indicators | PARTIAL | OI factors sourced from `ConnectingDotsService` / `MarketOiClient` active-strike + trending-cross dots (`ConnectTheDotsScorer.java:83-90`) | OI dots exist but the **specific 5/7-strike-around-ATM window** is not a configurable knob on this strategy (the active-strike service has its own window); no Golden-Cross-specific 5/7 setting is wired. Manual: read the Trending-OI dashboard over 5/7 strikes around ATM. Automatable: partial (window is service-level, not per-strategy). |
| Strike/delta: **0.6–0.7 delta**, strikes within **ATM ±3** | 3.6 risk-3 / 6.6 risk_management[4] | FULL | `atm_window` width 3 (`scalp-golden-crossover-nifty.yaml:23`); `StrikePicker.Params` DELTA_LO/HI 0.6/0.7 (`ScalperConfig.java:82-83`); `StrikePicker.pick()` selects |delta| in band nearest midpoint (`StrikePicker.java:74-109`) | — |
| Premium ranges: **Nifty 100–250, Bank Nifty 250–400** (SENSEX grill-locked 300–800) | 3.6 risk-3 / 6.6 risk_management[5] | FULL (live) | `PREMIUM` map (`ScalperConfig.java:93-98`), enforced in `StrikePicker.java:93` | Backtest selector ignores the band (picks nearest-strike-to-spot) per the §0B comment — live-only enforcement. Not a gap (doc-sanctioned). |
| Time filter: trade only after **9:45 am** (ideal 9:15–10:00); avoid sideways **11am–1pm**; no fresh entry after 3:30 | 3.6 setup-2 / filters / 6.6 filters[0] | FULL | `ScalperGates.timeWindow()` blocks <09:45, 11:00–13:00, ≥15:30 (`ScalperGates.java:22-44`); applied `ScalperConfluenceGate.java:112-118`; YAML session `from 09:45` (`scalp-golden-crossover-nifty.yaml:59`) | "Best/most-trending window 10–11 AM" (§3.6 S21) is NOT encoded as a preference. Manual: prefer 10–11 AM. Automatable: true (low value). |
| Broad-trend confirmation: **Supertrend (7,3) on the 15-min / 1-hour chart** must agree with the 3-min side (the "broad view" trend filter) | 4.2 (ST 7,3 = 15-min/1-hour broad view; §3.6-S22 line 124) / glossary §1 DOTS (60-min broad + smaller-TF entries) | FULL (live) | `bias60m` alias = `SUPERTREND@1h period:7 multiplier:3.0` (`scalp-golden-crossover-nifty.yaml:37`); read as `bias60mDir` (`ScalperConfluenceGate.java:318-321`) and applied as a **HARD AND-term** in the confluence — `biasAligned` must hold for a valid bull/bear (`ConnectTheDotsScorer.java:111,114-115`) | Wired and enforced on the **live** `SignalEngine` path only; the **backtest** premium-replay path does NOT invoke `ScalperConfluenceGate` (it reads only `oi_confluence_gate`, `OptionsPremiumReplay.java:158`), so the 60-min bias does not gate on history (same live-only class as the README §5 gap-theory `bias60m` false-coverage flag). `bias60mDir == 0` (unknown / null) never blocks. Manual: on a backtest, confirm the 1h ST(7,3) bias agrees. |
| Stop-loss: support-trade form SL = the **Supertrend level** (S21 Day 7); breakout form = crossover-candle extreme / VWAP reclaim | 3.6 exit / 5.6 / 6.6 exit_conditions.stop_loss | PARTIAL | `entry-candle-stop` tag ⇒ `StructuralStop.ENTRY_CANDLE` = crossover candle low(CE)/high(PE) (`scalp-golden-crossover-nifty.yaml:15`; `ScalperConfig.java:149-150`; `ScalperConfluenceGate.java:293-295`) | Only the **breakout-form** SL (crossover-candle extreme) is automated. The **Supertrend-level SL** (the support-trade form, the S21 resolution) is NOT — the engine's `SUPERTREND` indicator outputs only direction (+1/−1), not the ST price level (`Ta4jIndicators.java:49-62`), so the band level isn't available to size the stop. Manual: on the support-trade form, place SL at the Supertrend line. Automatable: true (expose the ST band level from the indicator). |
| Targets: BN ~100–150 pts (vol-backed ~200), Nifty ~50–70 pts; S21 clean ~200–300 BN | 3.6 exit / 6.6 exit_conditions.target | NONE | Exit is `signal_exit` (VWMA < VWAP) + `time_stop max_bars: 12` (`scalp-golden-crossover-nifty.yaml:49-50`) | No point-target / take-profit encoded; exits on cross-undone or 12-bar timeout. Manual: manage to the ~point targets. Automatable: partial (an index-point target on a premium leg is indirect). |
| RSI-exhaustion caveat: don't expect extension if RSI already overbought/oversold — wait for VWAP to hold | 3.6 S21(e) | NONE | Not encoded as a Golden-Cross rule | The 60–80/20–40 band partially caps exhaustion, but the "wait for VWAP to hold then trade the move" nuance is not. Manual: skip extension entries when RSI is already at the band edge. Automatable: false (judgment). |
| News overrides the data (no trade against market-moving news/event) | Common §2.13 | MANUAL_COVERED | `ScalperManualChecks.CHECKS` key `news_clear` (`ScalperManualChecks.java:26-30`) | Trader ticks the checklist item before "Take". |
| VIX not abnormally spiking | 3.6 filters (defer to Common) / §4.5 | MANUAL_COVERED | Manual key `vix_normal` (`ScalperManualChecks.java:46-50`); also a soft `vix` dot from INDIA VIX direction (`ConnectTheDotsScorer.java:92`; `ConnectingDotsService.java:263-270`) | VIX *direction* is a soft dot; "abnormal spike" magnitude is the manual check. §3.6 says VIX not specifically required by this block. |
| Global cues / DOW not against the trade | 3.6 filters / §4.7 | MANUAL_COVERED | Manual key `global_cues_ok` (`ScalperManualChecks.java:51-55`); Dow soft dot live-only (`ConnectingDotsService.java:310-326`, NEUTRAL on history) | — |
| Regime: choppy/range-bound stand-aside (>2–3 VWAP crossovers ⇒ choppy day) | 3.6 (implied) / §3.10 | MANUAL_COVERED | Manual key `regime_ok` (`ScalperManualChecks.java:41-45`) | The "no-body / no-volume crossover = trap" edge cases (§3.6 edge_cases) are not separately automated; the regime check is the closest manual proxy. |
| Not chasing a parabolic / forced entry; "one good trade" (rare ~3–4×/month) | 3.6 risk-1 / §3.1 | MANUAL_COVERED | Manual keys `not_parabolic`, `clean_setup` (`ScalperManualChecks.java:36-40,56-60`) | Covers the §3.6 "rare, do not force" discipline. |
| Bearish RSI gate ambiguity (card >25 vs matrix <25) | 3.6 entry bear-3 (UNCERTAIN) | UNCERTAIN | n/a — bearish side not seeded; engine band is 20–40 | Doc itself marks this UNCERTAIN. Resolve the operative bearish RSI rule with the owner before automating the PE side. |

### Not automated (gaps)
- **Same-candle crossover EVENT + body check** — the gate is a static *state* (`vwma20 > vwap` AND `supertrend > 0`), not the doc's defining "both ST and VWMA pierce VWAP on the same candle with a real body". (Automatable.)
- **Bearish (Buy PE / Sell CE) side entirely unseeded** — all three YAMLs are `direction: long` / `[CE]` only. The whole short half of §3.6 is manual. (Automatable.)
- **Drastic two-sided OI is only a soft dot, not a hard confirmer** — doc says "no drastic OI ⇒ skip", but `oi-cross-filter` is not tagged and the 50000 floor is an admitted placeholder. (Automatable.)
- **Supertrend-level stop-loss (support-trade form, the S21 resolution)** — only the breakout crossover-candle stop is automated; the ST line level is unavailable from the direction-only indicator. (Automatable with effort.)
- **RSI band mismatch** — engine 60–80 / 20–40 (§4.2) blocks doc-eligible Golden-Cross entries at RSI 50–60 / >25; doc §3.6 card says simply `< 75` / `> 25`. (Automatable — override pattern exists.)
- **Volume floor keyed to the NIFTY signal-future for SENSEX variants** — SENSEX-options variants gate on NIFTY 125K, not a SENSEX/BankNifty 50K floor, and on the future bar's volume not the crossover candle's. (Automatable.)
- **Point targets not encoded** — exits on VWMA-undone / 12-bar timeout, not the ~50–300 pt move expectations. (Partially automatable.)
- **10–11 AM "best window" preference + 5/7-strike-around-ATM Trending-OI window** — not strategy-specific knobs. (Low-value / service-level.)

### v2 review notes

Independent second-pass review (fresh-derived §3.6 + §6.6, then diffed vs v1). Summary: v1 is
**high-quality** — every FULL/PARTIAL/NONE row's cited `file:line` was re-traced and holds; no
false-coverage, no false-gap, no invented figure. One real doc rule was **missed**.

- **[MISSED → added FULL (live)]** *Broad-trend ST(7,3) 60-min/15-min confirmation.* The YAML declares
  `bias60m: SUPERTREND@1h period:7 multiplier:3.0` (`scalp-golden-crossover-nifty.yaml:37`) and it is a
  **hard AND-term** of the live confluence (`ScalperConfluenceGate.java:318-321` →
  `ConnectTheDotsScorer.java:111,114-115` — `biasAligned` must hold). This traces to the doc's §4.2
  indicator set ("ST 7,3 = 15-min / 1-hour broad view"; §3.6-S22 line 124) and the §1 DOTS glossary
  ("60-min broad + smaller-TF entries"). v1 listed the 3-min ST(10,2) leg but never the higher-TF bias
  filter, despite it being one of the few Golden-Crossover knobs that is a real hard gate. Added as a new
  row, marked **FULL (live)** with the backtest-inert caveat (the premium-replay path invokes only
  `oi_confluence_gate`, not `ScalperConfluenceGate` — same live-only class as the README §5 gap-theory
  `bias60m` false-coverage flag).

- **[CONFIRMED accurate]** Spot-checked the load-bearing v1 citations: `ScalperGates.rsiBand` PE band
  `>20 && <40` (`ScalperGates.java:81`) and NIFTY 125k / index 50k volume floors (`:27-30,64-68`);
  `drastic_oi` soft dot weight 1.0 + `drasticFloor` default 50000 placeholder (`ConnectTheDotsScorer.java:86,141-153`;
  `ScalperOiProps.java:36`); `entry-candle-stop`→`StructuralStop.ENTRY_CANDLE` crossover-candle extreme
  (`ScalperConfig.java:149-151`; `ScalperConfluenceGate.java:293-295`); `SUPERTREND` indicator is
  **direction-only** (+1/−1, no band price level → the support-form ST-level SL genuinely cannot be sized,
  `Ta4jIndicators.java:49-62`); StrikePicker delta/premium bands (`StrikePicker.java:74-109,93,99`;
  `ScalperConfig.java:82-83,93-98`); all six `ScalperManualChecks` keys + line ranges
  (`ScalperManualChecks.java:26-30/36-40/41-45/46-50/51-55/56-60`). Every one matched.

- **[CONFIRMED — bearish RSI UNCERTAIN flag kept]** v1's UNCERTAIN row (card RSI >25 vs matrix "<25") is
  faithful to the doc's own `uncertain[0]` (line 2381); the bearish PE side is genuinely unseeded
  (all three YAMLs `direction: long` / `[CE]`). No change.

- **[CONFIRMED — no invented claim]** The known v1 invented-claim flag does not apply to this section's
  retained rows: no §3.6 threshold is attributed to the doc that isn't in the cited line. The 50000
  `drasticFloor` is correctly placed on the **code** (an admitted placeholder, `ScalperOiProps.java:33-36`),
  not on the doc (the doc gives no number — v1 says so).

No v1 row was deleted; one row was added. All other rows stand as written.

### v3 review notes

Third-pass CITATION VALIDATION — every `file:line`, `yaml key`, and `doc §` in the table was
re-opened and confirmed against the live source. Convergence is **stable**: no new doc rule is
still-missing after v2 (the broad-trend ST(7,3) row was the last real gap and v2 caught it). All
statuses hold; two citation fixes were applied (right-file, drifted line number / loose doc ordinal).

- **[CHANGED — line drift]** Drastic-OI row: `ScalperOiProps.java:35` cited as "the doc gives NO
  number" is stale — that exact text is on **line 33** (line 35 is "once the live dOI distribution is
  observed…"). Fixed `:35` → `:33`. Also tightened the `drasticFloor` default cite from `:36,57` (line
  57 is the compact-constructor header) to `:36,59` (line 59 is the actual `drasticFloor` default-fill).
  Status PARTIAL unchanged — the dot is still a soft weight-1.0 dot, not a hard gate (re-confirmed at
  `ConnectTheDotsScorer.java:86,141-153`).

- **[CHANGED — doc ordinal]** Same drastic-OI row's doc pointer `6.6 setup_preconditions[4]` →
  `[5]`: in the §6.6 JSON the drastic-OI precondition is the **5th** item (line 2315); `[4]` (line
  2314) is the volume rule. (Rows 1 and 16 use 1-based ordinals consistently — cross=3rd item line
  2313, volume=4th item line 2314 — so they are left as-is.)

- **[VALIDATED — all code citations real and accurate]** Re-opened and confirmed every cited line:
  `ConnectTheDotsScorer.java` dots `vwap/supertrend/vwma` (`:74-76`), `rsi` (`:78`), `vix` (`:92`),
  OI dots (`:83-90`), `drastic_oi` + `drasticOi()` (`:86,141-153`), `biasAligned` hard AND-term
  (`:111,114-115`); `ScalperGates.java` time window (`:22-44`), volume floors NIFTY 125k / index 50k
  (`:27-30,64-68`), CE band 60-80 (`:76-84`), PE band 20-40 (`:81`); `ScalperConfluenceGate.java`
  volume rail keyed on `cfg.signalIndex()` (`:161`), timeOk block (`:112-118`), `bias60m()` (`:318-321`),
  ENTRY_CANDLE low/high (`:293-295`); `ScalperConfig.java` DELTA 0.6/0.7 (`:82-83`), PREMIUM map
  (`:93-98`), `entry-candle-stop`→ENTRY_CANDLE (`:149-150`), `oi-cross-filter` tag (`:153`);
  `StrikePicker.java` `pick()` delta-band-nearest-midpoint (`:74-109`), premium band enforce (`:93`);
  `Ta4jIndicators.java` SUPERTREND direction-only +1/−1, no band level (`:49-62` → return at line 62);
  `OptionsPremiumReplay.java:158` reads only `backtest.oi_confluence_gate` (confirms the backtest path
  never invokes `ScalperConfluenceGate`); `ConnectingDotsService.java` `vixFactor` (`:263-270`),
  `dowFactor` history→NEUTRAL (`:310-326`); all referenced `ScalperManualChecks` keys (`news_clear`
  `:26-30`, `not_parabolic` `:36-40`, `regime_ok` `:41-45`, `vix_normal` `:46-50`, `global_cues_ok`
  `:51-55`, `clean_setup` `:56-60`). Every one matched the row's claim.

- **[VALIDATED — YAML keys]** `scalp-golden-crossover-nifty.yaml`: `entry-candle-stop` tag (line 15),
  `signal_underlying: NIFTY-FUT-CONT` (line 20), `atm_window width: 3` (line 23), `RSI` indicator
  (line 33), `bias60m: SUPERTREND@1h period:7 multiplier:3.0` (line 37), gate `vwma20 > vwap` /
  `supertrend > 0` (lines 42-44), exits `signal_exit` + `time_stop max_bars:12` (lines 49-50),
  session `from "09:45"` (line 59). All present and accurate; `direction: long` / `option_types: [CE]`
  confirms the bearish side is genuinely unseeded.

- **[VALIDATED — doc numbers verbatim]** §3.6 / §6.6 re-read: setup-3 same-candle cross + no-body
  exclusion (line 746 / 2313); volume "Bank Nifty 50K+, Nifty 125K+" (line 747 / 2314); bull "RSI <
  75" (line 753 / 2321), bear "RSI > 25" + the UNCERTAIN matrix-row conflict (line 761 / 2381);
  drastic two-sided OI + "5/7 strikes above and below ATM (5-15 min)" (lines 748/755/778 / 2315);
  "0.6-0.7 delta, ATM ±3, premium Nifty 100-250, Bank Nifty 250-400" (line 772 / 2345-2346); time
  filter "after 9:45 / ideal 9:15-10:00 / avoid 11am-1pm" (lines 745/775 / 2350); targets "BN
  ~100-150, Nifty ~50-70" (line 767 / 2336); S21(e) RSI-exhaustion "wait for VWAP to hold" (line 732);
  §5.6 / §7 support-form SL = Supertrend level resolution (lines 1691/2975-2977). The ST(7,3)
  15-min/1-hour broad-view fact is at the §4.2 indicator table **line 124** (the row's
  "§3.6-S22 line 124" parenthetical is loose — line 124 sits in §4.2, not in the §3.6-S22 note at
  line 734 — but the primary doc-§ "4.2" is correct and the line number is real; left as-is, not a
  factual error).

Convergence signal: **stable**. No still-missing rule, no status overturned, 1 row corrected (2
citation fixes within it).
