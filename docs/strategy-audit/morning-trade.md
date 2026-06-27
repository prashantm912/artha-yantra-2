## Morning Trade (Opening Trade Strategy) — automation audit

**Scope.** Audits the automation of Siva strategy #9 "Morning Trade" (doc §3.9 prose + §6.9 JSON
spec) against the three `scalp-morning-trade-*.yaml` variants
(`services/strategy-signal-service/src/main/resources/scalper-strategies/scalp-morning-trade-nifty.yaml`,
`-sensex-niftyoi.yaml`, `-sensex-sensexoi.yaml`) and the live scalper confluence seam
(`ScalperConfluenceGate`, `ScalperConfig`, `ScalperGates`, `ConnectTheDotsScorer`,
`ScalperGateContext`, `MarketOiClient`, `ScalperManualChecks`) plus the strategy-engine indicators/exits.
The morning trade is an opening-tick scalp: the directional view is FORMED the prior evening from EOD
Futures positioning + Trending OI + Sentiment + FII/DII + global cues, then EXECUTED on the opening tick
(~09:16–09:18) when price action (a rejection wick on a gap) confirms. The automation runs only the
chart + OI confluence at the open; almost all of the EOD-view-formation half is human work and is only
partially covered by the generic checklist. Derived-history caveat applies to the OI/VIX/IV dots (they
degrade to NEUTRAL on backtests), but status below is judged by CODE PRESENCE, not backtest behaviour.

| Rule | Doc § | Status | Evidence (file:line / yaml key) | Gap / manual-check |
|------|-------|--------|---------------------------------|--------------------|
| Opening-tick window — trade executed in the first candle(s) at ~09:16; the general "after 09:45" rule does NOT apply | 3.9 §Time-of-day (L1025); §6.9 timeframe | FULL | `tags:[...opening-tick]` → `ScalperConfig.from` L128; `OPENING_FROM=09:15`/`OPENING_TO=09:30` `ScalperConfig.java:72-73`; `ScalperConfluenceGate.java:112-115` swaps to `ScalperGates.timeWindow(ist,from,to)` | — |
| SL = first session candle's low (CE) / high (PE) | 3.9 Stop-loss (L1008); §6.9 stop_loss | FULL | `StructuralStop.FIRST_CANDLE` `ScalperConfig.java:145-146`; `ScalperConfluenceGate.structuralStop` L297-300 (`future.sessionStart`); enforced as the protective stop `SignalEngine.java:587-588,405-408` | — |
| Time exit / scalping-only — finish once target/SL hit; can live inside the first 3-min candle; no carry | 3.9 Time exit (L1009); §6.9 time_exit | PARTIAL | `exit_rules[type=time_stop].max_bars:10`; `session.style:intraday` + `square_off:15:15` (YAML); `ScalperRisk` requires a bounding exit | `max_bars:10` (~10 primary bars) is a coarse proxy, NOT the doc's "close inside the first 3-min candle / 250 pts in 2 min". Manual: book/scratch the scalp within the first candles; do not let it ride to bar 10. Automatable: true (tighten `max_bars` or add an opening-window forced square-off). |
| Target = next resistance (CE) / next support (PE), a defined Futures level | 3.9 Target (L1007); §6.9 target | NONE | YAML has only `signal_exit:"close < vwap"` + `time_stop`; no S/R-level target. `level_respected` checklist item (`ScalperManualChecks` L31-35, §4.11) covers entry zone, not the exit target | Manual: set the profit target at the next mapped Futures S/R level and exit there. Automatable: partial (S/R levels are not currently computed for exits). |
| Primary timeframe 3m, direction on the index-future chart | 3.9 Instruments (L981); §6.9 timeframe | FULL | `timeframes.primary:3m`; `signal_underlying:{NFO,"NIFTY-FUT-CONT"}` (YAML); chart dots read off the signal future `ScalperConfluenceGate.chart` L304-316 | — |
| RSI 14 (band 80:20): CE wants 60+ (not overbought >75), PE wants 40 and below; 40–60 no-trade | 3.9 Entry/Filters (L995,L1004,L1023); §6.9 filters | PARTIAL | `RSI 14` indicator (YAML); `ScalperGates.rsiBand` `ScalperGates.java:76-84` (CE 60–80, PE 20–40, hard gate `ScalperConfluenceGate.java:157-163`) | Code caps CE at 80 / PE at 20, not the doc's "overbought >75". Variants are CE-only (`option_types:[CE]`) so PE rules never fire. Manual: confirm RSI is not >75 on a CE before firing. Automatable: true (lower the CE cap to 75). |
| Gap-down + already-oversold RSI: do NOT chase the fall; wait for RSI to cool back to resistance | 3.9 Entry-Bearish 4 (L1003); §6.9 entry bearish edge case | NONE | No oversold-cool-off logic on the morning/opening-tick path (the oversold cap exists only in `HeroZeroGate.java:120-122`, a different strategy #7). Variants are CE-only anyway | Manual (PE side): on a gap-down open with RSI already oversold, wait for RSI to recover to resistance before taking a PE. Automatable: true (an RSI-floor cool-off gate on the opening-tick PE path). |
| Rejection-wick entry on the failed attempt at prior-day close (the gap-rejection trigger) | 3.9 Entry 3 (L995,L1002); §6.9 entry | NONE | No rejection-wick / prior-day-close trigger on the opening-tick path. The only wick logic is `TwoCandleGate` (#3.1, not tagged here) and `GapState` (#3.4 gap-fill). Gate fires on `close > vwma20` + confluence, not a wick rejection | Manual: confirm a rejection wick has formed at/around the prior-day close before entering. Automatable: true (a candle-shape rejection check on the first 1m/3m bars). |
| "2nd candle breaks the 1st" — read the 1-min candle, watch the 2nd-candle break direction | 3.9 Instruments/Entry 2 (L981,L994,L1001); §6.9 indicators | NONE | Not modelled; the gate evaluates a single closed primary bar, no 1m→2nd-candle-break formation for the opening tick | Manual: watch how the 2nd candle breaks the 1st and only fire on alignment. Automatable: true (a 2-bar opening-formation check on the 1m series). |
| Previous-day VWAP is the reference/defended level; do NOT use the morning VWAP before 10:30 | 3.9 S21/S22 update (L975,L977), Filters 6 (L1024); §6.9 indicators/filters | PARTIAL | VWAP-before-10:30 degrade is automated: `VWAP_ACTIONABLE_FROM=10:30` `ScalperConfig.java:76`; `vwapHardGate=false` before 10:30 `ScalperConfluenceGate.java:249`; VWAP stays a soft dot `ConnectTheDotsScorer.java:113-115`. BUT no *previous-day* VWAP level is computed/used | The "yesterday's VWAP as the defended level/target" refinement is NOT automated (only the current-VWAP suppression is). Manual: use prior-day VWAP as the level the trade respects/targets. Automatable: true (prior-session VWAP is derivable). |
| OIP/AI direction must match pre-market direction | 3.9 Setup 5 / Filters 1 (L988,L1019); §6.9 filters | NONE | No OIP-AI signal source and no pre-market-direction input wired into the scalper context. `ScalperGateContext.Macro` has no AI/pre-market field; no pre-open feed consumed | Manual: confirm the OI-Pulse AI direction and the pre-market (pre-open) direction agree before firing. Automatable: partial (pre-open data feed noted in §4.14 but not wired into the gate; no AI-direction source exists). |
| Global cues match direction (Dow/Dow30 futures, Dollar index, Asian markets, Oil) | 3.9 Setup 5 / Filters 2 (L988,L1020); §6.9 filters | MANUAL_COVERED | `ScalperManualChecks` `global_cues_ok` L51-55 (doc_ref 4.7): "Global cues are not against the trade (DOW futures, Asian indices, crude, USD)". NOT a scored dot — `ConnectTheDotsScorer` has no Dow/global field; `Macro` has none | Manual: check DOW futures + Asian index + crude + USD direction (checklist item). Automatable: partial (Dow live-LTP is wired in market-data `ConnectingDotsService`, task #13, but is NOT fed to the scalper scorer; Dollar/Asian/Oil are not ingested). |
| Breadth — Nifty advance/decline must match (adv>32 = CE, dec>32 = PE) | 3.9 Setup 5 / Filters 3 (L988,L1021); §6.9 filters | FULL | `ScalperGates.breadth` `ScalperGates.java:127-133` (>32); scored dot `ConnectTheDotsScorer.java:91`; fed by `/api/v1/market/breadth` `MarketOiClient.java:368-373` (EOD bhavcopy date) | EOD-sourced (today's date 422s until post-close bhavcopy → degrades to 0/0). Soft dot, never a hard block. |
| OI confluence — Futures OI + Option OI (Trending OI + Sentiment) confirmed at the prior-day 3:20 PM OI-Pulse | 3.9 Setup 6 / Filters 4 (L989,L1022); §6.9 setup/filters | PARTIAL | Live OI quadrant/sentiment/trending-cross dots ARE scored (`ConnectTheDotsScorer.java:80-90`; `ScalperGates.oiQuadrant`; `MarketOiClient.oi`). BUT the read is the CURRENT bar's OI, NOT a prior-day-3:20-PM snapshot | The specific "prior-day 3:20 PM OI-Pulse alignment" check is not modelled as a point-in-time gate. Manual: at 3:20 PM the prior session confirm Futures OI + Option OI align with tomorrow's intended direction. Automatable: true (snapshot the 3:20 PM OI state and gate the next open). |
| FII/DII activity feeds the EOD morning view | 3.9 Setup 3 / data points (L986); §6.9 setup | PARTIAL | `fiiLongPct` is READ into `Macro` (`MarketOiClient.java:375-383`) but is NOT scored — no `fii` reference in `ConnectTheDotsScorer`; it never influences the signal | FII is fetched but unused by the confluence. Manual: review FII/DII positioning in the prior-evening view. Automatable: true (add an FII dot to the scorer). |
| India VIX not abnormally spiking (gap/whipsaw risk) | (Common/§4.5 via checklist) | MANUAL_COVERED + PARTIAL | `ScalperManualChecks` `vix_normal` L46-50 (doc_ref 4.5). VIX dot exists in the scorer (`ConnectTheDotsScorer.java:92`) but `MarketOiClient.macro` returns `null` VIX level+direction (`MarketOiClient.java:394-397`) → the VIX gate always degrades to pass | Manual: glance at India VIX vs recent sessions (checklist). Automatable: true (wire a VIX endpoint — flagged a v1 gap in code). |
| EOD data must be CONVINCING and the market must have closed at the day's HIGH or LOW (inside/near-open close = no trade) | 3.9 Setup 4 (L987); §6.9 setup_preconditions | NONE | No prior-day "closed at high/low" convincing-close gate anywhere in the scalper package (grep: no `dayHigh`/`convincing`/`closed at` logic on the morning path) | Manual: only take the trade if the prior session closed at its high (for CE) or low (for PE); skip an inside/near-open close. Automatable: true (prior-day OHLC is available). |
| Stand aside if post-close news invalidates the EOD positioning | 3.9 Setup 2 / Filters 8 (L985,L1026); §6.9 filters | MANUAL_COVERED | `ScalperManualChecks` `news_clear` L26-30 (doc_ref 2.13): "No market-moving news or event against this trade (news overrides the data)" | Manual: scan news + the economic calendar; if post-close news invalidates the EOD view, stand aside (checklist). Automatable: false. |
| Strike & delta selection — AI-suggested strike, ATM±3, delta 0.6–0.7, premium band (Nifty 100–250) | 3.9 Setup 7 (L990); §6.9 setup | FULL | `strikes:{selector:atm_window,width:3}` (YAML); `StrikePicker` delta band 0.6–0.7 + premium band `ScalperConfig.java:82-98`; `StrikePicker.pick` `ScalperConfluenceGate.java:271-276` | Doc marks the premium/ATM/delta values UNCERTAIN (§6.9 uncertain[2]); the code uses the verified §0B band. SENSEX band 300–800 is grill-locked, not in the §3.9 doc. |
| Small position size / profits-only (deploy only a portion of profits, never core capital) | 3.9 Risk 2 / S21 (L975,L1013); §6.9 risk | PARTIAL | `position_sizing:{premium_budget, budget_inr:15000}` + `max_positions:1` + `max_daily_loss_pct:2.0` (YAML). A fixed budget, not a "% of profits" rule | The "deploy only a portion of profits, never core capital" discipline is NOT enforced (YAML comment L9 admits "not enforced here"). Manual: size off profits only. Automatable: partial (no realized-profit-pool sizing method exists). |
| Take EVERY signal but modulate the lot to risk-reward — normal lot when the signal aligns with the overall market, reduced lot when it opposes or is neutral (S21 refinement) | 3.9 S21 update (d) (L975) | NONE | No align/oppose lot-modulation on the morning path: `position_sizing:{method:premium_budget, budget_inr:15000}` is a FIXED budget (YAML); the confluence emits a single uniform-size draft and nothing scales the lot down on an opposing/neutral read (`ConnectTheDotsScorer` returns only a validity verdict, no size multiplier) | Manual: take the signal but size it to the conviction — full lot when it aligns with the broader market, reduced lot when it opposes or is neutral. Automatable: partial (a confluence-strength→size multiplier would need a sizing primitive that does not exist; budget is fixed). |
| Profit-trail-to-breakeven once price runs in your favour (S22 refinement) | 3.9 S22 update (b) (L977) | NONE | No trailing-stop / breakeven-move on the morning path; the stop is the static first-candle level | Manual: once in profit, trail the stop to your buy price. Automatable: true (a trailing/breakeven exit rule). |
| RSI secondary exit confirmation — in the worked short, RSI dropping below 30 was a reason to exit | 3.9 Time exit (L1009) | NONE | No RSI-based exit on the morning/opening-tick path. The morning YAMLs declare `exit_rules:[signal_exit:"close < vwap", time_stop:max_bars:10]` only (no RSI exit); RSI(14) is used as an ENTRY band/dot (`ScalperGates.rsiBand`), never as an exit trigger | Manual: on a CE/long use RSI re-crossing back overbought→down (and for the worked PE, RSI<30) as a secondary exit confirmation alongside target/SL. Automatable: true (add an RSI-cross exit rule to `exit_rules`). |
| Add to the position ONLY around the previous-day close, nowhere else (S22 refinement) | 3.9 S22 update (d) (L977) | NONE | No scale-in / averaging logic on the morning path at all: `max_positions_per_underlying:1` (YAML) + the no-averaging rule (`ScalperRisk.java:13`) preclude any add. The doc's "add only around prev-day close" location rule is therefore moot in the automation (single-entry only) | Manual: if averaging in, do so only around the previous-day close. Automatable: partial (would need a scale-in engine primitive, which does not exist — single-entry is enforced). |
| Open=High doubles as exit-trigger + hedge (S22 refinement) | 3.9 S22 update (e) (L977) | NONE (separate strategy) | The `OpenHighLowGate` (#2) is a DISTINCT strategy, not wired into the opening-tick path; no opposite-side OH exit/hedge here | Manual: if price hits the opposite-side Open=High against you, exit (and optionally hedge). Automatable: partial. |
| >50% change in OI direction needed for a convincing same-day view (S22) | 3.9 S22 update (g) (L977) | PARTIAL | The ≥50% call/put dOI imbalance exists as `ScalperGates.callPutDeltaFilter` (`ScalperGates.java:151-161`) but is gated behind the `oi-cross-filter` tag (#5), which the morning-trade YAMLs do NOT carry | Not active for morning trade (no `oi-cross-filter` tag). Manual: confirm a >50% OI-direction change supports the view. Automatable: true (add the tag, or make it intrinsic to the morning view). |
| Experienced-traders-only / clean "one good trade" discipline | 3.9 Risk 1 (L1012); §6.9 risk | MANUAL_COVERED | `ScalperManualChecks` `clean_setup` L56-60 (§3.1) + `not_parabolic` L36-40 + `regime_ok` L41-45 | Manual: skip forced/marginal entries (checklist). Automatable: false. |
| Entry gate actually fired by the YAML — `close > vwma20` momentum lean | (automation-specific, not a doc rule) | n/a | `entry_rules.gate.all:["close > vwma20"]` + `scoring.threshold:0.2` (YAML); chart `EntryEvaluator` runs THEN the confluence seam | Noted: the YAML's chart gate is a thin momentum lean; the doc's real opening-tick trigger (rejection wick + 2nd-candle break + prev-close fail) is NOT what fires the signal — the confluence carries it. |

### Not automated (gaps)

- **Convincing-close-at-day-high/low precondition (§3.9 Setup 4)** — the core EOD qualifier ("must close at the day's high or low; inside/near-open close = no trade") has no gate. Pure manual. Automatable.
- **Rejection-wick-on-gap entry trigger + "2nd candle breaks the 1st" (§3.9 Entry 2/3)** — the doc's actual opening-tick trigger is not modelled; the signal fires on `close > vwma20` + confluence instead. Manual to confirm. Automatable.
- **OIP/AI direction ⟷ pre-market direction match (§3.9 Setup 5 / Filter 1)** — neither an AI-direction source nor a pre-market/pre-open direction is wired into the scalper gate. Manual. Partly automatable.
- **Global cues (Dow/Dollar/Asian/Oil) (§3.9 Filter 2)** — checklist-only (`global_cues_ok`), NOT a scored dot; Dow live-LTP exists in market-data but is not fed to the scalper scorer, and Dollar/Asian/Oil are not ingested.
- **Gap-down already-oversold RSI cool-off (§3.9 Entry-Bearish 4)** — no oversold-wait gate on the morning/PE path (and the variants are CE-only). Manual. Automatable.
- **Prior-day 3:20 PM OI-Pulse point-in-time alignment (§3.9 Setup 6)** — OI dots are scored on the CURRENT bar, not a prior-day-3:20 snapshot. Manual. Automatable.
- **FII/DII not scored (§3.9 Setup 3)** — `fiiLongPct` is fetched but never influences the confluence. Automatable (add a dot).
- **India VIX inert (§4.5 / checklist)** — VIX level+direction are hard-null in `MarketOiClient.macro` (no endpoint), so the VIX dot always passes; only the manual `vix_normal` check covers it. Automatable.
- **Previous-day VWAP as the defended level (§3.9 S21/S22)** — only the current-VWAP-before-10:30 suppression is automated; prior-day VWAP is not computed. Automatable.
- **S/R-level profit target (§3.9 Target)** — exit is `close < vwap` + a 10-bar time stop, not the next Futures S/R level. Partly automatable.
- **Tight "inside the first 3-min candle" exit (§3.9 Time exit)** — approximated by `max_bars:10`, coarser than the doc's 2-minute scalp. Automatable (tighten).
- **Profits-only sizing + trail-to-breakeven + opposite-side Open=High exit/hedge (§3.9 S21/S22 refinements)** — none enforced. Partly automatable.
- **RSI secondary exit confirmation (§3.9 Time exit L1009)** — RSI is an entry band/dot only; no RSI-cross exit rule in `exit_rules`. Automatable.
- **"Add only around the previous-day close" (§3.9 S22 update d)** — no scale-in/averaging exists (`max_positions_per_underlying:1`, no-averaging enforced); the location rule is moot under single-entry. Partly automatable.
- **Align/oppose lot-modulation (§3.9 S21 update d, L975)** — "normal lot when the signal aligns with the overall market, reduced lot when it opposes or is neutral"; the YAML sizing is a fixed `budget_inr:15000` and the confluence emits a single uniform-size draft. Partly automatable (no confluence-strength→size primitive exists).

### v2 review notes

Independent second-pass review (fresh-derived §3.9 prose + §6.9 JSON, then diffed against the v1 table
and verified each cited file:line). Changes:

- **MISSED → added (2 rows).**
  - *RSI secondary exit confirmation* (§3.9 Time exit L1009: "RSI dropping below 30 was a reason to
    exit"; §6.9 `exit_conditions`). v1 enumerated the time-stop and target/SL exits but not the doc's
    RSI exit confirmation. Status **NONE** — the morning YAMLs' `exit_rules` are `signal_exit:"close <
    vwap"` + `time_stop:max_bars:10` only; RSI(14) drives the entry band/dot (`ScalperGates.rsiBand`),
    never an exit.
  - *"Add only around the previous-day close, nowhere else"* (§3.9 S22 update (d), L977). v1 omitted
    this S22 add-location rule. Status **NONE** — there is no scale-in/averaging on the morning path
    (`max_positions_per_underlying:1` + the no-averaging rule at `ScalperRisk.java:13`), so the
    location rule is moot under single-entry.

- **INACCURATE → none.** No false-coverage, false-gap, wrong-cite, or invented-figure rows found. Spot-
  checked the load-bearing FULL/PARTIAL cites: the first-candle SL **is** enforced as a protective
  touch-exit (`SignalEngine.java:400-410` exit-first, captured at entry `:587-589`) — v1's `:587-588,
  405-408` cite is correct; the opening-tick window swap (`ScalperConfluenceGate.java:112-115`,
  `OPENING_FROM/TO` `ScalperConfig.java:72-73`), the VWAP-before-10:30 soft-degrade
  (`ScalperConfig.java:76`, `ScalperConfluenceGate.java:249`, `ConnectTheDotsScorer.java:113-115`), the
  `>32` breadth dot (`ScalperGates.java:127-133`, fed by `/api/v1/market/breadth`
  `MarketOiClient.java:368-373`), the RSI band CE 60–80 / PE 20–40 (`ScalperGates.java:76-84`), and the
  null-VIX degrade (`MarketOiClient.java:396` — VIX level+dir are the `null,null` args to `new Macro`)
  all verify exactly as v1 states.

- **README audit-quality flags.** The README tail lists NO false-coverage/invented-claim flag for
  morning-trade. The one cross-reference that touches this dimension — btst-stbt's note that the `>32`
  breadth threshold "actually originates in Morning-Trade §6.9" — confirms (not contradicts) the
  morning-trade breadth row's placement of `>32` on the code/§6.9. No change required here.

- **CONFIRMED.** All pre-existing v1 rows (incl. the `n/a` automation-note row) stand as written; v1
  quality is high.

### v3 review notes

Third-pass = citation re-validation (opened EVERY cited file:line / yaml key / doc line) + a fresh
convergence read of §3.9 + §6.9. Nearly clean — the table converged after two passes. Two changes:

- **BAD CITATION fixed (1).** Row *"FII/DII activity feeds the EOD morning view"* cited `3.9 Setup 3 /
  data points (L986,L987)`. The FII/DII rule lives WHOLLY at **L986** (Setup 3 "Data points to study:
  … (d) FII/DII activity"); **L987 is Setup 4** (the convincing-close "closed at day's high/low" rule —
  a DIFFERENT rule, already audited as its own row at §3.9 Setup 4). The stray `L987` did not contain
  the cited FII/DII rule. Corrected the cite to `(L986)`. Status (PARTIAL) unaffected — `fiiLongPct` is
  still read into `Macro` (`MarketOiClient.java:375-383`) and never scored, exactly as stated.

- **STILL-MISSING rule added (1).** §3.9 S21 update (d), L975: *"take **every** signal but size to
  risk-reward — normal lot when the signal aligns with the overall market, **reduced lot** when it
  opposes or is neutral."* Two passes captured "profits-only small size" (row, L975/L1013) but not this
  distinct align/oppose **lot-modulation** directive. Added as a new **NONE** row: the YAML sizing is a
  fixed `position_sizing:{method:premium_budget, budget_inr:15000}` and `ConnectTheDotsScorer` returns
  only a validity verdict (no confluence-strength→size multiplier), so nothing reduces the lot on an
  opposing/neutral read. Automatable: partial (needs a sizing primitive that does not exist).

- **VALIDATED, no change (everything else).** Re-opened and confirmed accurate, at the cited lines:
  - YAML keys across all three variants (`-nifty`, `-sensex-niftyoi`, `-sensex-sensexoi` — read the
    first two; the third mirrors): `tags:[…opening-tick]`, `option_types:[CE]`, `signal_underlying:{NFO,
    NIFTY-FUT-CONT}`, `strikes:{selector:atm_window,width:3}`, `entry_rules.gate.all:["close > vwma20"]`
    + `scoring.threshold:0.2`, `exit_rules:[signal_exit "close < vwap", time_stop max_bars:10]`,
    `position_sizing budget_inr:15000`, `max_positions(_per_underlying):1`, `max_daily_loss_pct:2.0`,
    `oi_confluence_gate.enabled:false`, `session.style:intraday`/`square_off:15:15`.
  - `ScalperConfig.java`: `OPENING_FROM/TO 09:15/09:30` (72-73), `VWAP_ACTIONABLE_FROM 10:30` (76),
    delta+premium band (82-98), `openingTick` parse (128), `FIRST_CANDLE` anchor (145-146).
  - `ScalperConfluenceGate.java`: opening-tick window swap (112-115), RSI hard gate (157-163), VWAP
    soft-degrade `vwapHardGate=false` (249), `StrikePicker.pick` (271-276), `structuralStop` first-candle
    (297-300), `chart(...)` dots (304-316).
  - `ScalperGates.java`: `rsiBand` CE 60-80 / PE 20-40 (76-84), `breadth >32` (127-133),
    `callPutDeltaFilter` (151-161).
  - `ConnectTheDotsScorer.java`: OI/sentiment/trending-cross dots (80-90), `breadth` dot (91), `vix`
    dot (92), VWAP soft-dot `valid` calc (113-115).
  - `MarketOiClient.java`: `/api/v1/market/breadth` fetch (368-373), `fiiLongPct` into `Macro`
    (375-383), null VIX level+dir in the `new Macro(...)` (394-397).
  - `ScalperManualChecks.java`: `news_clear` 26-30 (2.13), `level_respected` 31-35 (4.11),
    `not_parabolic` 36-40, `regime_ok` 41-45, `vix_normal` 46-50 (4.5), `global_cues_ok` 51-55 (4.7),
    `clean_setup` 56-60 (3.1) — every doc_ref matches the row.
  - `ScalperRisk.java:13` (no-averaging javadoc), `HeroZeroGate.java:120-122` (oversold cap — a
    DIFFERENT strategy, as the row states), `SignalEngine.java:405-408` (structural-stop exit-first) +
    `:587-588` (structuralStop captured at entry) — note SignalEngine lives under `…/signals/`, the bare
    filename cite is still unambiguous.
  - Cross-checks: §4.14.5 (L1572) DOES carry the pre-open data feed row-28 references; `ConnectingDotsService`
    DOES wire a live-LTP Dow factor (`DOWJONES@GLOBAL_INDEX`, L58/L167) that is NOT fed to the scalper
    scorer (row 29) — both confirmed.
  - All other §3.9 / §6.9 doc-line cites (L975/L977/L981/L988-990/L994-995/L1001-1004/L1007-1009/L1012-1013/
    L1019-1026) land on the quoted rule.

- **CONVERGENCE: stable.** After three passes the only residual was the one S21 lot-modulation rule
  (now added) and one stray doc line (now fixed); no false-coverage, no invented figure, no wrong code
  cite. The table is converged.
