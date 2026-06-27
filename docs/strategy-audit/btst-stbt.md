## BTST / STBT (Siva #8) — automation gap audit

**Scope.** Audits the BTST/STBT overnight-carry rules (doc §3.8 narrative + §6.8 JSON) against the three seeded
YAMLs (`scalp-btst-stbt-nifty.yaml`, `-sensex-niftyoi.yaml`, `-sensex-sensexoi.yaml`) and the strategy-signal
engine. **Headline finding:** these strategies run with `risk.session.style: btst`, and the live BTST path
(`SignalEngine.preCloseEvaluate`) calls `emitEntry(..., decision = null)` — it **does NOT route through
`ScalperConfluenceGate`/`ConnectTheDotsScorer`** the way the intraday scalpers do. So the OI-quadrant, VIX,
breadth, global-cue, sentiment and IV confluence — and even the StrikePicker delta/premium leg selection — are
*bypassed* for BTST. The only live BTST gate is the chart `EntryEvaluator` over the YAML `entry_rules.gate`
(`volume > 0`, `direction: both`, `scoring.threshold: 0.2`) at the 15:20 clock, plus the 50%-premium stop and
the 1-day time-stop. The YAML header comments assert the OI/quadrant/global-cue legs "ride the LIVE confluence
seam"; for `style: btst` that seam is unreachable, so most of §3.8's EOD decision logic is **not automated** and
not in `ScalperManualChecks`. (Derived-history caveat applies to the OI/Dow/IV factors generally, but here the
factors are not even consulted on the BTST path regardless of fidelity.)

| Rule | Doc § | Status | Evidence (file:line / yaml key) | Gap / manual-check |
|---|---|---|---|---|
| Carry decided EOD at 3:15–3:20pm, evaluated once/day | §3.8 setup 5–9; §6.8 timeframe | FULL | `risk.session.style: btst` + `pre_close_at: "15:20"`; `SignalEngine.java:510` cron `preCloseClock`, `:518` matches `pre_close_at`, `:522` once/day guard | — |
| Exit next morning (1-night carry; "not more than 1 night risk") | §3.8 exit; risk-mgmt | FULL | `exit_rules` `time_stop max_holding_days: 1`; `allow_overnight: true` | — |
| Stop loss 50% (option leg) | §3.8 exit "Stop loss 50%"; §6.8 stop_loss | FULL | `exit_rules` `stop_loss basis: premium_pct value: 50` | — |
| Strikes within ATM +/- 3 | §3.8 strike guidance; §6.8 instruments | PARTIAL | `universe.options.strikes {selector: atm_window, width: 3}` — but the live BTST path emits with `decision = null` (`SignalEngine.java:567-569`), so `StrikePicker` never runs at 15:20 | Manual: pick the ATM+/-3 strike yourself; the engine does not select the option leg on the BTST clock. Automatable: true (route `style: btst` through the gate). |
| Delta 0.6–0.7 for buys | §3.8 strike guidance; §6.8 instruments | PARTIAL | band constants in `ScalperConfig.java:82-83` (`DELTA_LO/HI`) — but `StrikePicker` is bypassed on the BTST path (null decision) | Manual: confirm the bought option is delta 0.6–0.7. Automatable: true. |
| Premium band (Nifty 100–250 / BankNifty 250–400) | §3.8 strike guidance | PARTIAL | `ScalperConfig.java:93-98` premium map — bypassed on BTST path (null decision); SENSEX uses 300–800 | Manual: confirm bought premium in band. Automatable: true. |
| Executable leg: BTST Buy CE / STBT Buy PE (long-premium); short legs (Sell PE / Sell CE) deferred to SPAN | §3.8 instruments; §6.8 instruments | PARTIAL | YAML header lines 10–19 seed only the long buy leg; `option_types: [CE, PE]`; SELL legs explicitly deferred to margin-service #47 | Manual: the short-premium Sell-PE (BTST)/Sell-CE (STBT) leg is not built; size/execute manually if traded. Automatable: true once #47 SPAN sizing is live. |
| Side split: close toward day HIGH ⇒ BTST/CE, toward day LOW ⇒ STBT/PE | §3.8 entry triggers; §6.8 entry_conditions | NONE | YAML uses `direction: both` + `scoring.threshold: 0.2`; the close-location→side rule has no engine gate. `ScalperConfluenceGate` has a VWAP-decisive side picker (`:149`) but is **not invoked** on the BTST pre-close path. **v2 reinforcement:** the side is not resolved bidirectionally at all — `direction: both` collapses to LONG/BUY everywhere: live emit hard-codes `side = "BUY"` unless `direction == SHORT` (`SignalEngine.java:591-592`), and the backtest/golden runner emits LONG unless `direction == SHORT` (`TickwiseGoldenRunner.java:269,284-286,297-299`). `OptionsPremiumReplay` then buys ATM CE for long / PE for short (`OptionsPremiumReplay.java:41-42,132,143`), so STBT (the PE/bearish carry) is **never executed in replay** — the strategy is effectively CE-long-only. The YAML header's "side resolved LIVE at the seam off the close location" is aspirational, not coded | Manual: only carry BTST if the day closed at/near its HIGH (STBT at/near its LOW), AND manually take the PE/STBT side — the engine will only ever signal the CE/BTST (BUY) side. Automatable: true (a close-vs-day-high/low gate + a `both`→side resolver). |
| OI-quadrant confirm — BTST SC=Q3/LB=Q1, STBT SB=Q2/LU=Q4 (mapped from close vs OI day-high/low) | §3.8 entry OI-quadrant; §6.8 entry_conditions | NONE | `ScalperGates.oiQuadrant` (`ScalperGates.java:121`) exists but checks the generic futures bull/bear quadrant, not the close-vs-OI BTST mapping, and is not reached on the BTST path | Manual: map the close + OI position to the correct quadrant per §3.8 before carrying. Automatable: partial (the literal SC/LB/SB/LU close-vs-OI-extreme mapping is not coded; generic quadrant data exists). |
| 3:15pm Futures-OI direction = bullish (BTST) / bearish (STBT) | §3.8 setup 5; entry 3:15 checks | NONE | the `OiQuadrant` enum (`SHORT_COVERING`/`SHORT_BUILDUP` constants `OiQuadrant.java:14,16`; `.bullish()`/`.bearish()` at `:28,33`, used by `HeroZeroGate`) / `futures_oi` dot (`ConnectTheDotsScorer.java:80`) exist in the scorer but the BTST path never calls the gate | Manual: confirm 3:15pm Futures OI direction matches the carry side. Automatable: true (data exists in `ConnectingDotsService` `futOi` factor). |
| 3:15pm Option-OI direction via Trending OI + Sentiment | §3.8 setup 6; entry 3:15 | NONE | `trending_cross` + `sentiment` dots exist in `ConnectTheDotsScorer.java:83-88`; not invoked for BTST | Manual: confirm Trending OI + Sentiment graph agree with the side at 3:15. Automatable: true. |
| 3:20pm view matches OI-Pulse / OIP AI direction | §3.8 setup 9; entry 3:20 | NONE | No OIP-AI-direction input is wired into any scalper gate | Manual: confirm your view matches the OI-Pulse AI read at 3:20. Automatable: uncertain (no OIP-AI-direction feed in repo). |
| Observe short covering (BTST) / short build-up (STBT) between 2:30–3:00pm around support/resistance | §3.8 setup 4, entry; filters time-of-day | NONE | **v2 MISSED row.** No gate observes a 2:30–3:00pm SC/SB window; the BTST clock fires a single evaluation at `pre_close_at: "15:20"` (`SignalEngine.java:511,518`) and the entry gate is only `volume > 0`. The `ScalperGates.oiQuadrant` SC/SB classification is in the bypassed confluence gate | Manual: watch for short covering (BTST) / short build-up (STBT) around the OI S/R between 2:30 and 3:00pm before deciding the carry. Automatable: partial (SC/SB classification exists in `ScalperGates`; the 2:30–3:00 window is not coded). |
| "320 Strategy" carry = a 3:20pm probability signal with a deliberately WIDE overnight SL (S21 Day 12) | §3.8 S21 update (b); §6.8 edge_cases | NONE | **v2 MISSED row.** The doc's S21-Day-12 carry mechanism is a probability signal entered at 3:20pm with a *wide* gap-tolerant SL ("buy at the indicated price and exit when the SL is hit"). The YAML codes a fixed 50%-premium SL (`exit_rules stop_loss premium_pct: 50`) — a defined stop, not the doc's "wide overnight" stop — and there is no probability-signal input | Manual: the carry's deliberately wide overnight SL and the "320" probability read are not modelled; size the overnight stop for gap risk yourself. Automatable: partial (SL value is a knob; the probability signal has no feed). |
| Global cues positive (BTST) / negative (STBT) at 3:15 — DOW/Dow30, Dollar index, Asian, Oil | §3.8 setup 7, filters; §6.8 filters | MANUAL_COVERED (partial) | `ScalperManualChecks.CHECKS` key `global_cues_ok` (`ScalperManualChecks.java:51-55`, doc_ref 4.7) covers DOW futures + Asian + crude + USD; `vix`/`dow` factors exist in `ConnectingDotsService` but not gated on BTST | Manual checklist item exists (`global_cues_ok`) but is generic, not the 3:15pm BTST stamp; Dollar index is not a coded factor. Automatable: partial (DOW live via `GlobalQuoteSource`; Dollar/Asia/Oil feeds absent). |
| India VIX confirms direction (VIX down = BTST, up = STBT; BTST when VIX closes at day low + market at day high) | §3.8 filters VIX; §6.8 filters | MANUAL_COVERED (partial) | `ScalperManualChecks` key `vix_normal` (`:46-50`, doc_ref 4.5) is a *spike* check, NOT the directional/close-at-low rule; `ScalperGates.vix` directional gate exists but is unreached on BTST | Manual: confirm VIX direction (and the "VIX at day low + market at day high" BTST case). Automatable: true (VIX candle factor exists). |
| RSI not overbought >75 (BTST); over-sold watch (STBT); examples BTST>60 / STBT<40 | §3.8 setup 8, filters; §6.8 indicators | PARTIAL | RSI(14) declared in YAML; `ScalperGates.rsiBand` (CE 60–80 / PE 20–40, `ScalperGates.java:76`) exists but is only enforced inside the bypassed gate. On the BTST path RSI is only whatever the YAML `entry_rules.gate` checks — and it checks only `volume > 0` | Manual: confirm daily RSI not >75 (BTST) / not over-sold (STBT). Automatable: true (RSI gate exists; wire it to the BTST path). |
| Daily-RSI hard limit: never carry FRESH STOCK positions if daily RSI > 75 | §3.8 risk; §6.8 risk_management | NONE | no daily-RSI>75 hard-block gate; index variants only (stock universe deferred) | Manual: never carry a fresh stock position with daily RSI > 75. Automatable: true for the daily-RSI value; stock universe is deferred (#3). |
| Volume high & bullish/bearish in last 30 minutes | §3.8 setup 10; filters | PARTIAL | YAML gate `volume > 0` (trivial) — not the §0B `ScalperGates.volume` floor (NIFTY 125k / index 50k, `ScalperGates.java:64`), which is in the bypassed gate | Manual: confirm strong directional volume into the close. Automatable: true (volume floor gate exists). |
| Advance/decline must match (mirrors morning-trade adv>32/dec>32) | §6.9 (Morning Trade) — NOT §3.8; cross-strategy import | NONE | `ScalperGates.breadth` (`:128`, >32) exists but unreached on BTST. **v2 doc-§ correction:** §3.8/§6.8 carry NO breadth rule; the adv>32/dec>32 threshold originates in Morning-Trade §6.9 (doc line 2535 "Nifty adv/dec must match (adv>32=CE, dec>32=PE)"). v1 cited "§3.8 (breadth implied)" — the inference is reasonable but the doc-§ pointer was wrong; the >32 number was correctly placed on the code, never invented onto §3.8 | Manual: confirm breadth agrees with the carry side. Automatable: true. |
| Avoid Friday (weekend gap risk) | §3.8 risk "avoid Friday"; §6.8 risk_management | NONE | no day-of-week gate anywhere; `preCloseClock` cron fires MON-FRI (`SignalEngine.java:510`) incl. Friday | Manual: do not open a BTST/STBT carry on a Friday. Automatable: true (trivial day-of-week skip). |
| Keep off if an event is scheduled after 3:30pm / on event days data takes a back-seat | §3.8 filters, edge cases; §6.8 filters | MANUAL_COVERED | `ScalperManualChecks` key `news_clear` (`:26-30`, doc_ref 2.13) | Manual checklist item present. Automatable: false (event-calendar judgement). |
| No improper BTST near expiry against the trend; don't BTST after a parabolic close | §3.8 risk, S22 note; §6.8 edge_cases | MANUAL_COVERED (partial) | `ScalperManualChecks` key `not_parabolic` (`:36-40`, doc_ref 3.1) covers the parabolic case; no expiry-vs-trend block | Manual: skip BTST near expiry against trend and after a parabolic up-close. Automatable: partial. |
| STBT stock short-sell penalty in monthly expiry (delivery/square-off) | §3.8 risk; §6.8 risk_management | NONE | stock universe deferred (#3); not modelled | Manual: STBT in stocks risks a delivery penalty in monthly expiry — index-only here. Automatable: n/a (stock universe deferred). |
| Next-day continuation read: BTST confirmed if prev-day high NOT tested; STBT if prev-day low IS tested; trail winners next day | §3.8 exit, edge cases; §6.8 exit/edge | NONE | exit is a flat `time_stop max_holding_days: 1`; no prev-day-level read or next-day trailing | Manual: next morning, confirm the prev-day level read and trail winners; the engine just time-exits after 1 day. Automatable: partial (prev-day level read codable; trailing not modelled). |
| Morning re-confirm before continuing the hold: keep the carry only if yesterday's 3:20pm view + the morning-trade read + premarket + global cues all match (else manage out) | §3.8 exit "Confirmation before continuing the hold"; §6.8 stop_loss ("Manage out if next-day premarket / global cues / morning-trade read do not align with prior 3:20pm view") | NONE | **v3 MISSED row.** §3.8 Exit Rules line 933 makes the next-morning hold conditional on a 4-way re-alignment (prior 3:20 view + morning read + premarket + global cues); §6.8 `stop_loss` repeats it. The engine just `time_stop max_holding_days: 1` exits — no next-morning re-confirm gate, and none of these inputs (premarket / global cues / prior-3:20 view) is wired into any scalper gate or `ScalperManualChecks` for the carry path | Manual: next morning, only continue the hold if yesterday's 3:20pm read, the morning-trade view, premarket and global cues all agree; otherwise manage out. Automatable: partial (the global-cue/premarket inputs are not coded for the carry; the re-alignment is a discretionary read). |
| Stock BTST/STBT 8/9-day-low (or 15-day-low) break candidate | §3.8 setup 11, S22; §6.8 entry bearish | NONE | stock universe deferred (#3, Market Movers); YAML header lines 52–53 explicitly defer it | Manual: the stock variant (n-day-low break + Futures OI at day-high/price at day-low) is not built. Automatable: false until the equity universe exists. |
| Strike guidance: Put-as-support / Call-as-resistance read off OI build-up | §3.8 setup 3 | NONE | not coded | Manual: identify max-OI S/R before the carry. Automatable: true (chain data exists). |

**Not automated (gaps):**
- The whole §3.8 confluence is bypassed for BTST: the `style: btst` pre-close path emits with `decision = null` (`SignalEngine.java:567`), so `ScalperConfluenceGate`/`ConnectTheDotsScorer` (and therefore OI-quadrant, Futures/Option-OI direction, sentiment, VIX-direction, breadth, basis, IV, the VWAP-decisive **side** picker, and the **StrikePicker** delta/premium leg selection) never run. Live BTST = chart `EntryEvaluator` over `volume > 0` + `direction: both` + `scoring.threshold: 0.2` only.
- Side resolution (close-toward-high ⇒ CE / close-toward-low ⇒ PE) is **not** automated — `direction: both` with a near-trivial gate; the trader must choose the side.
- The 3:15pm Futures-OI and Option-OI (Trending OI + Sentiment) direction checks and the 3:20pm OI-Pulse alignment are not gated.
- "Avoid Friday" weekend-gap rail: no day-of-week block; `preCloseClock` fires Friday too.
- Daily-RSI>75 hard limit, the strong-volume-into-close floor, advance/decline match, and the directional/close-at-low VIX rule are not enforced on the BTST path (the gates exist but are unreachable for `style: btst`).
- Strike/delta/premium leg selection (ATM+/-3, delta 0.6–0.7, premium band) is bypassed at the 15:20 clock — the trader picks the option manually.
- Short-premium SELL legs (BTST Sell-PE / STBT Sell-CE), the prev-day-level next-day continuation read + winner-trailing, OIP-AI direction, Dollar/Asia/Oil global-cue feeds, and the stock 8/9/15-day-low variant are all unbuilt.
- `ScalperManualChecks` covers only generic news / VIX-spike / parabolic / regime / global-cues / clean-setup / level items — none of them are the BTST-specific 3:15/3:20 OI-Pulse, close-location, OI-quadrant, avoid-Friday, or daily-RSI>75 rules.

## v2 review notes

Independent second-pass review. v1 was **high quality**: every cited file:line was spot-checked and
verified — `SignalEngine.java:510/518/522` (the BTST clock + once/day guard), `:567-569` (`emitEntry(...,
null)` → the confluence/StrikePicker bypass that is v1's whole thesis), `ScalperConfluenceGate.java:149`
(VWAP side picker), `ScalperGates.java:64/76/121/128` (volume/RSI/oiQuadrant/breadth), `ScalperConfig.java:82-83/93-98`
(delta/premium bands, SENSEX 300–800), and the four `ScalperManualChecks` keys all match. The three YAMLs
were read in full; the two SENSEX variants differ only by id/name and `oi_confluence_gate.index` (which is
`enabled: false` everywhere, so inert). **No false-coverage and no invented figures** were found in v1.

Changes made:
- **INACCURATE (doc-§ correction, the assigned known flag):** the *Advance/decline must match* row cited
  the breadth rule to "§3.8 (breadth implied)". §3.8 and §6.8 carry **no** breadth rule; the adv>32/dec>32
  threshold lives in **Morning-Trade §6.9** (doc line 2535). Corrected the Doc-§ cell; status stays NONE
  and the >32 figure remains correctly attributed to the code, never invented onto §3.8.
- **INACCURATE (evidence strengthened, status unchanged):** the *Side split* row's evidence was correct
  but understated. `direction: both` does not produce a bidirectional signal — it collapses to LONG/BUY on
  **every** path: live emit hard-codes `side="BUY"` unless `direction==SHORT` (`SignalEngine.java:591-592`),
  and the golden/backtest runner emits LONG unless `direction==SHORT` (`TickwiseGoldenRunner.java:269,284-286,297-299`),
  after which `OptionsPremiumReplay` buys ATM CE for long / PE for short (`OptionsPremiumReplay.java:41-42,132,143`).
  Net: STBT (the PE/bearish carry) is **never executed in replay** and the live signal is always BUY — the
  strategy is effectively CE-long-only. Added this proof to the row and to the gap-summary side-resolution bullet.
- **MISSED (2 new rows, both NONE):** (1) the §3.8-setup-4 *2:30–3:00pm observe short-covering/short-build-up*
  window — no gate observes it; the carry fires a single 15:20 evaluation. (2) the S21-Day-12 *"320 Strategy"*
  carry mechanism — a 3:20pm probability signal with a deliberately **wide overnight SL**; the YAML codes a
  fixed 50%-premium stop (a defined stop, not the doc's wide gap-tolerant one) and there is no probability-signal feed.

All other v1 rows and gap bullets are CONFIRMED accurate as written.

## v3 review notes

Third-pass citation-validation. I re-opened EVERY cited file:line, yaml key and doc-§ against the three
YAMLs, the strategy-signal scalper sources, `TickwiseGoldenRunner`, `OptionsPremiumReplay`,
`ConnectingDotsService`, and the consolidated doc §3.8 (lines 874–967) / §6.8 (line 2506) / §6.9 (line 2535).

**Citations validated — all clean (no drift found):**
- `SignalEngine.java:510` (`@Scheduled` cron `preCloseClock`, MON-FRI), `:518` (`pre_close_at` match), `:522`
  (once/day `preCloseDone` guard), `:567-569` (`emitEntry(..., null)` — the confluence/StrikePicker bypass),
  `:591-592` (`side = SHORT ? "SELL" : "BUY"`). All exact.
- `ScalperGates.java:64` (volume floor NIFTY 125k/index 50k), `:76` (`rsiBand` CE 60-80/PE 20-40), `:121`
  (`oiQuadrant` — generic futures bull/bear, NOT close-vs-OI), `:128` (`breadth` >32). All exact.
- `ScalperConfluenceGate.java:149` (VWAP-decisive side picker). Exact.
- `ScalperConfig.java:82-83` (DELTA_LO/HI 0.6/0.7), `:93-98` (premium map; SENSEX 300–800). Exact.
- `ScalperManualChecks.java` keys: `news_clear` `:26-30`/2.13, `not_parabolic` `:36-40`/3.1, `vix_normal`
  `:46-50`/4.5, `global_cues_ok` `:51-55`/4.7. All exact (key, line range AND doc_ref).
- `TickwiseGoldenRunner.java:269` ("SHORT":"LONG"), `:284-286`, `:297-299` (LONG unless SHORT). Exact.
- `OptionsPremiumReplay.java:41-42` (javadoc "long buys ATM CE, short ATM PE"), `:132,143` (`"SHORT".equals(
  open.direction())` → isShort). Exact — confirms STBT/PE is never executed in replay since every signal emits LONG.
- `ConnectTheDotsScorer.java:83-88` (`trending_cross`/`sentiment` dots; `futures_oi` is at :80). Present.
- `ConnectingDotsService.java`: `futOiFactor` (:249), `dowFactor` (:316), `vixFactor` (:263) — the `futOi`/`dow`/`vix`
  factors the rows reference all exist. Present.
- All YAML keys re-confirmed in all three scalp-btst-stbt-*.yaml (style: btst, pre_close_at 15:20, strikes
  atm_window width 3, stop_loss premium_pct 50, time_stop max_holding_days 1, direction: both, threshold 0.2,
  oi_confluence_gate.enabled: false). The two SENSEX variants also carry `strike_reference: BFO/SENSEX-FUT-CONT`
  (a 2b-E2b addition the v2 note did not mention) and differ from each other only by id/name + `oi_confluence_gate.index`.
- All doc-§ pointers (§3.8 setup 1–12 / entry / exit / risk / filters / edge cases; §6.8 fields; the §6.9 line-2535
  adv>32/dec>32 quote) re-read and confirmed verbatim, including the S21-update (b) "320 Strategy"/wide-SL and
  S22-note (d) parabolic-close citations.

**Changes made (3):**
- **Citation tightened (row "3:15pm Futures-OI direction"):** the `SHORT_COVERING`/`SHORT_BUILDUP` constants were
  cited to `OiQuadrant.java:10` (the `public enum` line); the named constants are at `:14,16` and `.bullish()`/
  `.bearish()` at `:28,33`. Corrected the line refs and added the `futures_oi` dot's exact site (`ConnectTheDotsScorer.java:80`).
  Status unchanged (NONE).
- **MISSED (1 new row, NONE):** §3.8 Exit Rules line 933 ("Confirmation before continuing the hold: if yesterday's
  3:20pm view, the morning trade read, premarket, and global cues all match...") + §6.8 `stop_loss` ("Manage out if
  next-day premarket / global cues / morning-trade read do not align with prior 3:20pm view") is a discrete
  next-morning re-alignment gate not represented by any prior row (row 42 covers only the prev-day-level continuation
  read + trailing). The engine just `time_stop`-exits after 1 day; the premarket/global-cue/prior-3:20 inputs are not
  wired into any carry-path gate or `ScalperManualChecks`. Added as a NONE row.
- No status overturns: every FULL/PARTIAL/NONE/MANUAL_COVERED verdict survived citation validation unchanged.

Convergence: stable. Beyond the one missed §3.8-exit re-confirm rule, no genuinely-unrepresented doc rule remains;
the "best setups" ranking (§3.8 line 910) is a soft preference subsumed by the OI-quadrant row, not a discrete gate.
