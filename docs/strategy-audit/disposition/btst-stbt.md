# BTST / STBT (Siva #8) — gap disposition

Every non-FULL row from `docs/strategy-audit/btst-stbt.md` is assigned exactly one disposition.
Source section has **25 non-FULL rows** (PARTIAL/NONE/MANUAL_COVERED); all 25 are accounted for below.

**Dimension-level note.** The headline finding is structural: `style: btst` emits with `decision = null`
(`SignalEngine.java:567`), so the whole `ScalperConfluenceGate`/`ConnectTheDotsScorer` seam — and the
StrikePicker — is **bypassed** on the BTST path. Many gates already *exist* (`ScalperGates.*`,
`ConnectTheDotsScorer` dots) but are unreachable for `style: btst`. The single highest-leverage
automation here is therefore not 25 new gates but one work-package: **route the BTST pre-close path
through the confluence gate + StrikePicker** (theme `btst-route-through-gate`). Once routed, ~9 of the
rows below collapse to already-built behaviour. The remaining rows are genuinely new feeds, manual reads,
or by-design soft/derived-history artifacts.

| Gap (rule) | Doc § | Audit status | Disposition | Work-package / note |
|---|---|---|---|---|
| Strikes within ATM +/- 3 (StrikePicker bypassed at 15:20) | §3.8 strike; §6.8 instruments | PARTIAL | AUTOMATE_PKG | `btst-route-through-gate` — StrikePicker `atm_window width:3` already coded; runs once `style: btst` routes through the gate (`decision != null`). |
| Delta 0.6–0.7 for buys (StrikePicker bypassed) | §3.8 strike; §6.8 instruments | PARTIAL | AUTOMATE_PKG | `btst-route-through-gate` — `DELTA_LO/HI` band exists (`ScalperConfig.java:82-83`); rides the same routing fix. |
| Premium band (Nifty 100–250 / SENSEX 300–800; StrikePicker bypassed) | §3.8 strike guidance | PARTIAL | AUTOMATE_PKG | `btst-route-through-gate` — premium map exists (`ScalperConfig.java:93-98`); rides the same routing fix. |
| Executable leg: BTST Buy CE / STBT Buy PE; short Sell-PE/Sell-CE deferred to SPAN | §3.8/§6.8 instruments | PARTIAL | AUTOMATE_PKG | `short-premium-span-legs` — long buy leg seeded; SELL legs blocked on margin-service #47 SPAN sizing (own theme; see span-margin-appliance memory). |
| Side split: close toward HIGH ⇒ BTST/CE, toward LOW ⇒ STBT/PE (`direction: both` collapses to BUY-only; STBT never executed in replay) | §3.8 entry; §6.8 entry_conditions | NONE | AUTOMATE_PKG | `btst-side-resolver` — needs a close-vs-day-high/low gate + a `both`→side resolver in the emit + golden/replay path (`SignalEngine.java:591-592`, `TickwiseGoldenRunner.java:269`, `OptionsPremiumReplay.java:41-42`). Distinct from routing: even if routed, `both`→BUY collapse must be fixed. |
| OI-quadrant confirm — SC=Q3/LB=Q1 (BTST), SB=Q2/LU=Q4 (STBT), mapped from close vs OI day-high/low | §3.8 entry; §6.8 entry_conditions | NONE | AUTOMATE_PKG | `btst-close-vs-oi-quadrant` — generic `oiQuadrant` exists but is futures bull/bear, NOT the literal close-vs-OI-extreme BTST mapping; that mapping is unbuilt. (Partly muted on derived history.) |
| 3:15pm Futures-OI direction = bullish (BTST)/bearish (STBT) | §3.8 setup 5; entry 3:15 | NONE | AUTOMATE_PKG | `btst-route-through-gate` — `futOi` factor exists (`ConnectingDotsService.java:249`), `futures_oi` dot (`ConnectTheDotsScorer.java:80`); reachable once routed. Muted on derived history (forward-paper discriminator). |
| 3:15pm Option-OI direction via Trending OI + Sentiment | §3.8 setup 6; entry 3:15 | NONE | AUTOMATE_PKG | `btst-route-through-gate` — `trending_cross` + `sentiment` dots exist (`ConnectTheDotsScorer.java:83-88`); reachable once routed. |
| 3:20pm view matches OI-Pulse / OIP AI direction | §3.8 setup 9; entry 3:20 | NONE | KEEP_MANUAL_NEW | No OIP-AI-direction feed in repo (audit: automatable uncertain). Manual-check candidate beyond FU1; not automatable without an external OIP-AI feed. |
| Observe short covering (BTST)/short build-up (STBT) 2:30–3:00pm around S/R | §3.8 setup 4; filters | NONE | AUTOMATE_PKG | `btst-intraday-oi-window` — SC/SB classification exists in `ScalperGates`; the 2:30–3:00pm observation window is uncoded (single 15:20 eval today). New windowed read. |
| "320 Strategy" carry = 3:20pm probability signal with deliberately WIDE overnight SL | §3.8 S21 update (b); §6.8 edge_cases | NONE | KEEP_MANUAL_NEW | No probability-signal feed; the wide gap-tolerant overnight SL is a discretionary sizing read (YAML codes a fixed 50% stop). Manual-check / trader-discretion candidate; the SL value alone is a knob but the "320" probability read has no source. |
| Global cues positive (BTST)/negative (STBT) at 3:15 — DOW/Dollar/Asian/Oil | §3.8 setup 7; §6.8 filters | MANUAL_COVERED (partial) | COVERED_EXISTING | Carried by shipped `global_cues_ok` (`ScalperManualChecks.java:51-55`, doc_ref 4.7). Generic, not the 3:15pm BTST stamp; Dollar/Asia/Oil feeds absent → see `global-cues-feed` package for the automation extension. |
| India VIX confirms direction (VIX down=BTST/up=STBT; VIX at day-low + market at day-high) | §3.8 filters; §6.8 filters | MANUAL_COVERED (partial) | COVERED_FU1 | FU1 adds `vix_regime_bands` (item 9, doc_ref 4.14.1) covering the absolute-band/VIX-vs-price read beyond the existing `vix_normal` spike check. Directional VIX *automation* (a gate) is out of FU2 scope → tracked as `directional-vix-gate`; the manual read is FU1-covered. |
| RSI not overbought >75 (BTST)/over-sold (STBT); examples >60 / <40 | §3.8 setup 8; §6.8 indicators | PARTIAL | AUTOMATE_PKG | `btst-route-through-gate` — `rsiBand` gate exists (`ScalperGates.java:76`) but unreachable on BTST; wires up once routed. |
| Daily-RSI hard limit: never carry FRESH STOCK with daily RSI > 75 | §3.8 risk; §6.8 risk_management | NONE | AUTOMATE_PKG | `daily-rsi-hard-block` — daily-RSI>75 block is codable for the index variants now; the STOCK scope is deferred (#3 equity universe). Theme covers the daily-RSI guard; stock applicability waits on the universe. |
| Volume high & bullish/bearish in last 30 minutes | §3.8 setup 10; filters | PARTIAL | AUTOMATE_PKG | `btst-route-through-gate` — `ScalperGates.volume` floor (NIFTY 125k / index 50k) exists but unreachable (YAML gate is trivial `volume > 0`); wires up once routed. |
| Advance/decline must match (adv>32/dec>32, imported from Morning-Trade §6.9) | §6.9 (cross-strategy import) | NONE | COVERED_FU2 | FU2 promotes the breadth dot to a hard `breadth-gate` (`ScalperGates.breadth` >32). Reachable on BTST only once `btst-route-through-gate` lands (FU2 gates ride the confluence path); note the routing dependency. |
| Avoid Friday (weekend gap risk) | §3.8 risk; §6.8 risk_management | NONE | AUTOMATE_PKG | `avoid-friday-skip` — trivial day-of-week skip on `preCloseClock` (`SignalEngine.java:510` fires Friday too); no gate exists. Standalone, does not need the routing fix. |
| Keep off if an event scheduled after 3:30pm / event-day data takes a back-seat | §3.8 filters, edge cases; §6.8 filters | MANUAL_COVERED | COVERED_EXISTING | Carried by shipped `news_clear` (`ScalperManualChecks.java:26-30`, doc_ref 2.13). Event-calendar judgement — not automatable. |
| No improper BTST near expiry against trend; don't BTST after a parabolic close | §3.8 risk, S22; §6.8 edge_cases | MANUAL_COVERED (partial) | COVERED_EXISTING | Parabolic case carried by shipped `not_parabolic` (`ScalperManualChecks.java:36-40`, doc_ref 3.1). The expiry-vs-trend half is uncovered → see KEEP_MANUAL_NEW note: a future `expiry-vs-trend` manual check (not in FU1). |
| STBT stock short-sell penalty in monthly expiry (delivery/square-off) | §3.8 risk; §6.8 risk_management | NONE | ACCEPT_BY_DESIGN | Stock universe deferred (#3); index-only here. wontfix until the equity universe exists — n/a to the index variants. |
| Next-day continuation read: BTST if prev-day high NOT tested / STBT if prev-day low IS tested; trail winners | §3.8 exit, edge cases; §6.8 exit/edge | NONE | AUTOMATE_PKG | `trade-management-targets-trailing` — prev-day-level read is codable; next-day trailing is unbuilt (engine flat `time_stop max_holding_days: 1`). New exit-management package. |
| Morning re-confirm: keep the carry only if prior 3:20 view + morning-trade read + premarket + global cues all match | §3.8 exit (line 933); §6.8 stop_loss | NONE | KEEP_MANUAL_NEW | The premarket / prior-3:20 / morning-read inputs are uncoded and the re-alignment is a discretionary 4-way read; a future manual-check candidate (not in FU1). Partial automation possible only if a premarket/global-cue feed is built (`global-cues-feed`). |
| Stock BTST/STBT 8/9-day-low (or 15-day-low) break candidate | §3.8 setup 11, S22; §6.8 entry bearish | NONE | ACCEPT_BY_DESIGN | Stock universe deferred (#3, Market Movers); YAML explicitly defers. wontfix until the equity universe exists — not automatable in the index-only build. |
| Strike guidance: Put-as-support / Call-as-resistance off OI build-up | §3.8 setup 3 | NONE | AUTOMATE_PKG | `oi-support-resistance` — max-OI S/R read off the chain is codable (chain data exists); not modelled today. New package (chain-derived S/R levels). |

## Disposition counts

- COVERED_EXISTING: 3
- COVERED_FU1: 1
- COVERED_FU2: 1
- AUTOMATE_PKG: 15
- KEEP_MANUAL_NEW: 3
- ACCEPT_BY_DESIGN: 2
- UNCERTAIN_OWNER: 0

Total = 25 dispositions, one per non-FULL source row (3 + 1 + 1 + 15 + 3 + 2 + 0). Row-for-row
mapping is 1:1 with the 25 non-FULL rows of the source section.

## AUTOMATE_PKG themes (for the synthesizer)

- `btst-route-through-gate` (7 rows): the load-bearing fix — route `style: btst` through the
  confluence gate + StrikePicker so the already-built gates (strike/delta/premium, RSI, volume,
  futures-OI/option-OI/sentiment dots) become reachable on the BTST path.
- `btst-side-resolver` (1): close-location→side + `both`→side resolution across emit + golden/replay
  (so STBT/PE actually executes).
- `btst-close-vs-oi-quadrant` (1): the literal SC/LB/SB/LU close-vs-OI-extreme mapping (generic quadrant exists).
- `btst-intraday-oi-window` (1): the 2:30–3:00pm SC/SB observation window.
- `short-premium-span-legs` (1): BTST Sell-PE / STBT Sell-CE legs — gated on margin-service #47 SPAN.
- `daily-rsi-hard-block` (1): daily-RSI>75 fresh-carry block (index now; stock deferred).
- `avoid-friday-skip` (1): day-of-week skip on the pre-close clock.
- `trade-management-targets-trailing` (1): prev-day-level continuation read + next-day winner-trailing.
- `oi-support-resistance` (1): max-OI Put-support/Call-resistance levels off the chain.
- `directional-vix-gate` (0 rows here — cross-referenced from the VIX row, whose manual read is FU1-covered).
- `global-cues-feed` (0 rows here — cross-referenced; the manual reads are COVERED_EXISTING / KEEP_MANUAL_NEW,
  the Dollar/Asia/Oil feed automation is the shared package).
